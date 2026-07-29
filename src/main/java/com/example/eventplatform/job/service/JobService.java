package com.example.eventplatform.job.service;

import com.example.eventplatform.database.JobRedisKey;
import com.example.eventplatform.database.RedisHandler;
import com.example.eventplatform.event.entity.EventType;
import com.example.eventplatform.exception.GlobalCustomException;
import com.example.eventplatform.exception.GlobalExceptions;
import com.example.eventplatform.job.entity.Job;
import com.example.eventplatform.job.entity.JobStatus;
import com.example.eventplatform.job.entity.JobType;
import com.example.eventplatform.job.repository.JobRepository;
import com.example.eventplatform.reservation.entity.Reservation;
import com.example.eventplatform.reservation.entity.ReservationStatus;
import com.example.eventplatform.reservation.repository.ReservationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class JobService {

  // Job의 locked_by에 기록할 이 JVM(워커/인스턴스)의 식별자. 인스턴스당 1회만 생성하면 충분하다.
  private static final String WORKER_ID = UUID.randomUUID().toString();

  private final JobRepository jobRepository;
  private final ObjectMapper objectMapper;
  private final RedisHandler redisHandler;
  private final ReservationRepository reservationRepository;

  // RUNNING 상태로 이 시간(초) 이상 멈춰 있으면 워커가 죽은 것으로 간주하고 회수한다.
  // 명세서 부록의 JOB_STALE_LOCK_SECONDS 환경변수와 동일한 의미.
  @Value("${job.stale-lock-seconds:60}")
  private long staleLockSeconds;

  /*
   * enQueue/start를 self.xxx()로 호출하기 위한 자기 자신에 대한 프록시 참조.
   * 같은 클래스 내부에서 this.enQueue()처럼 직접 호출하면 스프링 AOP 프록시를 거치지 않아
   * @Transactional이 적용되지 않는다(자기 호출 시 프록시 우회 문제). pollDueJobs()가 여러 Job을
   * 한 번에 처리할 때 Job 하나당 독립된 트랜잭션을 갖게 하려고 프록시를 거치는 self를 둔다.
   */
  @Lazy
  @Autowired
  private JobService self;

  @Transactional
  public void makeSchedule(String topic, Reservation reservation) {
    if (jobRepository.existsByIdempotency_key(reservation.getIdempotency_key())) {
      return;
    }
    Map<String, Object> payload = objectMapper.convertValue(reservation,
        new TypeReference<Map<String, Object>>() {
        });
    Job job = Job.builder().type(JobType.fromTopic(topic))
        .payload(payload).idempotency_key(
            reservation.getIdempotency_key()).build();
    jobRepository.save(job);
    String key = JobRedisKey.SCHEDULE.generateKeyNoParam(job.getId());
    redisHandler.makeJobWithTtl(key, job.getNext_run_at());
  }

  /*
   * SCHEDULED -> QUEUED 전이. Redis 만료 알림(JobExpireListener)과 폴러(pollDueJobs) 양쪽에서
   * 호출될 수 있으므로, 원자 클레임(claimForQueue)으로 정확히 한쪽만 전이를 성공시킨다.
   * 이미 다른 경로에서 처리된 경우(영향 행 수 0)는 정상적인 경쟁 상황이므로 조용히 무시한다.
   */
  @Transactional
  public void enQueue(long jobId) {
    LocalDateTime nextRunAt = LocalDateTime.now().plusSeconds(1);
    int claimed = jobRepository.claimForQueue(jobId, nextRunAt, LocalDateTime.now());
    if (claimed == 0) {
      log.debug("Job {} SCHEDULED->QUEUED 클레임 실패 (이미 다른 경로에서 처리됨)", jobId);
      return;
    }
    String key = JobRedisKey.QUEUE.generateKeyNoParam(jobId);
    redisHandler.makeJobWithTtl(key, nextRunAt);
  }

  /*
   * QUEUED -> RUNNING 전이 및 실행. Redis 만료 알림과 폴러 양쪽에서, 그리고 인스턴스를 여러 대
   * 띄운 경우 각 인스턴스에서 동시에 호출될 수 있다. claimForRunning(원자 클레임)이 정확히
   * 하나의 호출만 성공시키므로 동일 Job이 두 번 실행되는 일이 없다 (FR-E7/FR-E8).
   */
  @Transactional
  public void start(long jobId) {
    int claimed = jobRepository.claimForRunning(jobId, WORKER_ID, LocalDateTime.now());
    if (claimed == 0) {
      log.debug("Job {} QUEUED->RUNNING 클레임 실패 (다른 워커/트리거가 이미 처리 중)", jobId);
      return;
    }

    Job job = jobRepository.findById(jobId)
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR));

    job.start();
    if (job.getStatus() == JobStatus.FAILED) {
      // max_attempts 초과로 이미 FAILED 처리됨 - 더 이상 진행하지 않는다
      return;
    }

    try {
      dispatch(job);
      job.succeed();
    } catch (Exception e) {
      log.error("Job {} 처리 실패 (attempt {}): {}", job.getId(), job.getAttempts(),
          e.getMessage());
      String key = JobRedisKey.QUEUE.generateKeyNoParam(job.getId());
      redisHandler.makeJobWithTtl(key, job.retry());
    }
  }

  /*
   * 1초마다 도는 폴러 진입점. 세 가지 안전망을 순서대로 수행한다:
   *   1) enqueueDueScheduledJobs - SCHEDULED -> QUEUED 유실 복구
   *   2) startDueQueuedJobs      - QUEUED -> RUNNING 유실 복구
   *   3) reclaimStaleRunningJobs - RUNNING에서 멈춘 채 죽은 워커의 Job 회수
   * 세 단계 모두 Redis 알림 경로와 동일한 전이 메서드/원자 클레임을 재사용하므로, 이 폴러가
   * 켜져 있든 꺼져 있든 최종 동작은 같고 "얼마나 빨리 회수되는가"만 달라진다.
   *
   * propagation = NOT_SUPPORTED로 이 메서드 자체는 트랜잭션을 열지 않는다. 클래스 레벨
   * @Transactional(readOnly = true)를 그대로 물려받게 두면(메서드에 별도 애노테이션이 없어서
   * 상속됨), self.enQueue()/self.start()/self.reclaimStaleJob() 호출이 REQUIRED 전파로 이
   * 읽기전용 트랜잭션에 합류해버려 내부의 UPDATE(claimForQueue/claimForRunning/reclaimStale)가
   * 전부 "cannot execute UPDATE in a read-only transaction"으로 실패한다 - 통합 테스트로
   * Job이 실제 있는 상태에서 폴러를 처음 돌려보고서야 드러난 버그다
   * (docs/refactoring-and-abstraction-review.md 참고). 이 메서드가 트랜잭션 없이 실행되면
   * self.xxx() 각각이 자기 소유의 새 (쓰기 가능한) 트랜잭션을 연다.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @Scheduled(fixedDelay = 1000)
  public void pollDueJobs() {
    LocalDateTime now = LocalDateTime.now();
    enqueueDueScheduledJobs(now);
    startDueQueuedJobs(now);
    reclaimStaleRunningJobs(now);
  }

  /*
   * Redis 키 만료 알림(job:schedule:{id})이 유실됐을 때를 대비한 안전망. next_run_at이 지났는데
   * 아직 SCHEDULED에 머물러 있는 Job을 다시 집어 enQueue()를 호출한다. self를 거치는 이유는
   * Job마다 독립된 트랜잭션으로 실행해 하나의 실패가 다른 Job 처리에 번지지 않게 하기 위함이다.
   */
  private void enqueueDueScheduledJobs(LocalDateTime now) {
    for (Job job : jobRepository.findDueJobs(JobStatus.SCHEDULED, now)) {
      self.enQueue(job.getId());
    }
  }

  /*
   * Redis 키 만료 알림(job:queue:{id})이 유실됐을 때를 대비한 안전망. next_run_at이 지났는데
   * 아직 QUEUED에 머물러 있는 Job을 다시 집어 start()를 호출한다.
   */
  private void startDueQueuedJobs(LocalDateTime now) {
    for (Job job : jobRepository.findDueJobs(JobStatus.QUEUED, now)) {
      self.start(job.getId());
    }
  }

  /*
   * 워커 장애 복구(FR-E8). RUNNING으로 클레임된 뒤 locked_at 기준 staleLockSeconds 이상
   * 아무 진행이 없는 Job은, 그 Job을 처리하던 워커가 죽었다고 간주하고 QUEUED로 되돌려
   * 이후 poll tick에서 (다른 워커든 같은 워커든) 다시 클레임해 재처리할 수 있게 한다.
   */
  private void reclaimStaleRunningJobs(LocalDateTime now) {
    LocalDateTime staleBefore = now.minusSeconds(staleLockSeconds);
    for (Job job : jobRepository.findStaleRunningJobs(staleBefore)) {
      self.reclaimStaleJob(job.getId(), staleBefore);
    }
  }

  /*
   * RUNNING -> QUEUED 회수를 실제로 수행하는 단일 Job 단위 트랜잭션. reclaimStale의 WHERE절이
   * status=RUNNING and locked_at<=staleBefore 조건까지 원자적으로 검사하므로, 여러 인스턴스가
   * 동시에 같은 stale Job을 발견해 이 메서드를 호출해도 정확히 하나만 실제로 회수한다.
   * attempts는 최초 클레임 시점(job.start())에 이미 1 증가했으므로 여기서는 건드리지 않는다 -
   * 재클레임 후 다시 실행되다 실패를 반복하면 자연히 max_attempts를 넘겨 FAILED로 빠진다.
   */
  @Transactional
  public void reclaimStaleJob(long jobId, LocalDateTime staleBefore) {
    int reclaimed = jobRepository.reclaimStale(jobId, staleBefore, LocalDateTime.now());
    if (reclaimed == 1) {
      log.warn("Job {} 가 RUNNING 상태로 {}초 이상 멈춰 있어 QUEUED로 회수함 (워커 장애로 추정)",
          jobId, staleLockSeconds);
    }
  }

  private void dispatch(Job job) {
    if (job.getType() == JobType.CONFIRM_RESERVATION) {
      confirmReservation(job);
    } else {
      throw new IllegalStateException("Unsupported job type: " + job.getType());
    }
  }

  private void confirmReservation(Job job) {
    Reservation reservation = reservationRepository.findByIdempotencyKey(job.getIdempotency_key())
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR));

    if (reservation.getStatus() != ReservationStatus.PENDING) {
      // 이미 CONFIRMED/CANCELLED/EXPIRED 등 종료 상태로 처리됨 -> 멱등 처리 (부수효과 없이 성공 처리)
      return;
    }
    reservation.confirm(generateIssuedCode(reservation.getEvent().getType()));
  }

  private String generateIssuedCode(EventType type) {
    String prefix = type == EventType.COUPON ? "CPN" : "TKT";
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    return prefix + "-" + suffix;
  }

}
