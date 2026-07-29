package com.example.eventplatform.job.repository;

import com.example.eventplatform.job.entity.Job;
import com.example.eventplatform.job.entity.JobStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, Long> {

  @Query("select j from Job j where j.idempotency_key = :idempotency_key")
  Optional<Job> findByIdempotency_key(@Param("idempotency_key") String idempotency_key);

  // 반환 타입이 primitive boolean인데 "select j ..."는 매칭되는 행이 없으면 null을 돌려줘서
  // "Null return value ... does not match primitive return type" 예외가 났다 - makeSchedule()의
  // 첫 호출부터 항상 실패해서 CONFIRM_RESERVATION Job이 한 번도 만들어지지 못했다
  // (docs/refactoring-and-abstraction-review.md 참고). count(j) > 0으로 실제 boolean을 만든다.
  @Query("select count(j) > 0 from Job j where j.idempotency_key = :idempotency_key")
  boolean existsByIdempotency_key(@Param("idempotency_key") String idempotency_key);

  /*
   * Redis 만료 알림이 유실됐을 때를 대비한 폴러용 조회. next_run_at 이 지났는데 아직
   * 다음 상태로 전이되지 않은 Job을 찾는다.
   */
  @Query("select j from Job j where j.status = :status and j.next_run_at <= :now")
  List<Job> findDueJobs(@Param("status") JobStatus status, @Param("now") LocalDateTime now);

  /*
   * SCHEDULED -> QUEUED 원자 전이(compare-and-swap). Redis 만료 알림과 폴러가 동시에
   * 같은 Job을 큐잉하려 해도 영향 행 수(0 또는 1)로 정확히 한쪽만 성공했는지 판별할 수 있다.
   */
  @Modifying
  @Query("update Job j set j.status = com.example.eventplatform.job.entity.JobStatus.QUEUED, "
      + "j.next_run_at = :nextRunAt, j.updated_at = :now "
      + "where j.id = :id and j.status = com.example.eventplatform.job.entity.JobStatus.SCHEDULED")
  int claimForQueue(@Param("id") Long id, @Param("nextRunAt") LocalDateTime nextRunAt,
      @Param("now") LocalDateTime now);

  /*
   * QUEUED -> RUNNING 원자 클레임(compare-and-swap). 여러 워커/인스턴스가 동시에 같은
   * Job을 실행하려 해도 locked_by/locked_at을 정확히 한쪽만 획득한다 (FR-E7).
   */
  @Modifying
  @Query("update Job j set j.status = com.example.eventplatform.job.entity.JobStatus.RUNNING, "
      + "j.locked_by = :workerId, j.locked_at = :now, j.updated_at = :now "
      + "where j.id = :id and j.status = com.example.eventplatform.job.entity.JobStatus.QUEUED")
  int claimForRunning(@Param("id") Long id, @Param("workerId") String workerId,
      @Param("now") LocalDateTime now);

  /*
   * locked_at 이 staleBefore 이전인 RUNNING Job 조회. 워커가 클레임(RUNNING 전이) 후 처리
   * 도중 죽어서 succeed()/retry()/fail() 중 무엇도 호출되지 못한 채 멈춰버린 Job을 찾기 위함
   * (FR-E8 워커 장애 복구).
   */
  @Query("select j from Job j where j.status = com.example.eventplatform.job.entity.JobStatus.RUNNING "
      + "and j.locked_at <= :staleBefore")
  List<Job> findStaleRunningJobs(@Param("staleBefore") LocalDateTime staleBefore);

  /*
   * RUNNING -> QUEUED 원자 회수(reclaim). staleBefore 조건까지 UPDATE의 WHERE절에 그대로
   * 넣어서, 두 인스턴스가 같은 stale Job을 동시에 회수하려 해도(조회와 갱신 사이의 TOCTOU)
   * 정확히 한쪽만 성공한다. locked_by/locked_at은 회수 시 비워서 "현재 아무도 소유하지 않음"을
   * DB 상태만 보고도 알 수 있게 한다.
   */
  @Modifying
  @Query("update Job j set j.status = com.example.eventplatform.job.entity.JobStatus.QUEUED, "
      + "j.next_run_at = :now, j.updated_at = :now, j.locked_by = null, j.locked_at = null "
      + "where j.id = :id and j.status = com.example.eventplatform.job.entity.JobStatus.RUNNING "
      + "and j.locked_at <= :staleBefore")
  int reclaimStale(@Param("id") Long id, @Param("staleBefore") LocalDateTime staleBefore,
      @Param("now") LocalDateTime now);

}
