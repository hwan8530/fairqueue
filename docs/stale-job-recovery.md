# 워커 장애 복구(stale lock reclaim) — 무엇이 문제였고 어떻게 고쳤는가

## 배경

`docs/job-trigger-reliability.md`에서 Job 실행 트리거를 DB 폴러 + 원자 클레임으로 고치면서
"아직 남아 있는 갭"으로 명시적으로 남겨뒀던 항목이다: `QUEUED → RUNNING` 클레임에는 원자성을
줬지만, **RUNNING으로 클레임한 워커가 처리 도중 죽어버리는 경우**를 회수하는 경로는 없었다.
명세서 FR-E8("워커가 처리 도중 비정상 종료해도, 해당 Job은 잠금 만료 후 다른 워커가 재처리할 수
있어야 하며 결과는 중복되지 않아야 한다")와 NFR-4, 테스트 시나리오 T5가 요구하는 부분이다.

## 개념: 왜 "RUNNING에서 멈춘 Job"이 저절로 회복되지 않는가

Job 큐에서 한 워커가 작업을 "가져가는" 순간(`QUEUED → RUNNING` 클레임)부터 그 작업이
"끝났다고 보고하는" 순간(`SUCCEEDED`/`FAILED`/재시도로 `QUEUED` 복귀) 사이에는 반드시 시간
간극이 있다. 이 간극에서 워커 프로세스가 죽으면(OOM kill, 배포 중 강제 종료, 서버 장애 등) 그
워커는 다시는 "끝났다"는 보고를 하지 못한다. DB에는 `status = RUNNING`, `locked_by = <죽은
워커 ID>`만 남고, 아무도 이 Job을 다음 상태로 옮겨주지 않는다 — **락을 쥔 소유자가 사라졌는데
락 자체는 영원히 남아있는 상태**다.

분산 시스템에서 이 문제를 푸는 표준 패턴은 두 가지다.

1. **하트비트(lease) 갱신**: 워커가 작업을 처리하는 동안 주기적으로 `locked_at`을 갱신한다.
   감독자는 "마지막 하트비트로부터 오래 지난" Job만 회수한다. 정확도가 높지만 구현이 더 복잡하다
   (워커 쪽에 갱신 로직이 필요).
2. **고정 타임아웃**: 하트비트 없이, 클레임된 시각(`locked_at`)으로부터 일정 시간이 지나면
   무조건 죽은 것으로 간주하고 회수한다. 구현은 단순하지만, 그 시간 안에 끝나지 않는 정상
   작업까지 "오탐"으로 회수될 수 있다.

명세서 10.5도 "`locked_at`이 일정 시간(예: 60초) 초과한 Job을 stale로 간주"라고 고정 타임아웃
방식을 예시로 제시하고 있고, 이 프로젝트의 Job(주로 CONFIRM_RESERVATION, 결제 확정 모사)은
처리 시간이 매우 짧아 60초 안에 끝나는 게 정상이므로, 2번(고정 타임아웃)으로도 오탐 위험이
낮다. 그래서 이번 수정도 고정 타임아웃 방식을 선택했다.

## 문제였던 부분

`jobs` 테이블에는 명세서대로 `locked_by`/`locked_at` 컬럼이 있었지만, `docs/session-notes.md`에
이미 기록돼 있듯이 **어느 코드에서도 쓰이지 않는 죽은 컬럼**이었다. `docs/job-trigger-reliability.md`
작업에서 `claimForRunning`으로 이 컬럼들을 처음 실제로 채우기 시작했지만, 그 반대 방향
(`RUNNING`에서 멈춘 걸 되돌리는 경로)은 만들지 않은 상태였다. 이 상태로는:

- 워커가 `dispatch(job)` 도중(예: `confirmReservation()` 실행 중) 죽으면, 해당 Job은 `RUNNING`에
  영원히 멈춘다.
- 그 Job과 연결된 `Reservation`도 `PENDING`에서 영원히 벗어나지 못한다 — 사용자는 예약이
  확정됐는지 영원히 알 수 없다.
- 재고는 예약 생성 시점에 이미 차감돼 있으므로, 이 Job이 멈춘 채로 쌓이면 실제로는 아무에게도
  발급되지 않은 재고가 영구히 묶여서 "품절이 아닌데 품절처럼" 보이는 상황이 만들어진다.

## 수정 내용

### `JobRepository.java` — stale 조회 + 원자 회수(reclaim)

```java
// RUNNING 상태로 locked_at 이 staleBefore 이전인 Job 조회
@Query("select j from Job j where j.status = ...RUNNING and j.locked_at <= :staleBefore")
List<Job> findStaleRunningJobs(@Param("staleBefore") LocalDateTime staleBefore);

// RUNNING -> QUEUED 원자 회수. staleBefore 조건까지 UPDATE의 WHERE절에 그대로 넣어서
// 조회와 갱신 사이의 TOCTOU를 막는다 (여러 인스턴스가 같은 stale Job을 동시에 회수하려 해도
// 정확히 한쪽만 성공).
@Modifying
@Query("update Job j set j.status = ...QUEUED, j.next_run_at = :now, j.updated_at = :now, "
    + "j.locked_by = null, j.locked_at = null "
    + "where j.id = :id and j.status = ...RUNNING and j.locked_at <= :staleBefore")
int reclaimStale(@Param("id") Long id, @Param("staleBefore") LocalDateTime staleBefore,
    @Param("now") LocalDateTime now);
```

`findStaleRunningJobs`로 후보를 조회한 뒤 `reclaimStale`로 회수하는 2단계 구조인데, 이 둘
사이에는 시간 간극이 있다(TOCTOU). 그런데 `reclaimStale`의 `WHERE`절에 `status = RUNNING and
locked_at <= :staleBefore` 조건을 그대로 다시 넣어뒀기 때문에, 조회 시점과 갱신 시점 사이에
그 Job이 이미 다른 인스턴스에 의해 회수됐거나, 클레임한 워커가 정상적으로 끝냈다면(`SUCCEEDED`/
`FAILED`로 바뀜) 이 UPDATE는 0행에 영향을 주고 조용히 실패한다 — 안전하다.

### `JobService.java` — 회수 실행 + 폴러 3단계로 모듈화

기존 `pollDueJobs()`는 SCHEDULED/QUEUED 두 갈래만 처리했는데, 여기에 stale RUNNING 회수까지
추가하면서 한 메서드에 세 가지 책임이 섞이지 않도록 각 단계를 private 메서드로 분리했다.

```java
@Scheduled(fixedDelay = 1000)
public void pollDueJobs() {
  LocalDateTime now = LocalDateTime.now();
  enqueueDueScheduledJobs(now);   // SCHEDULED -> QUEUED 유실 복구
  startDueQueuedJobs(now);        // QUEUED -> RUNNING 유실 복구
  reclaimStaleRunningJobs(now);   // 죽은 워커의 RUNNING Job 회수
}

private void reclaimStaleRunningJobs(LocalDateTime now) {
  LocalDateTime staleBefore = now.minusSeconds(staleLockSeconds);
  for (Job job : jobRepository.findStaleRunningJobs(staleBefore)) {
    self.reclaimStaleJob(job.getId(), staleBefore);
  }
}

@Transactional
public void reclaimStaleJob(long jobId, LocalDateTime staleBefore) {
  int reclaimed = jobRepository.reclaimStale(jobId, staleBefore, LocalDateTime.now());
  if (reclaimed == 1) {
    log.warn("Job {} 가 RUNNING 상태로 {}초 이상 멈춰 있어 QUEUED로 회수함 (워커 장애로 추정)",
        jobId, staleLockSeconds);
  }
}
```

`staleLockSeconds`는 하드코딩하지 않고 `@Value("${job.stale-lock-seconds:60}")`로 뺐다.
명세서 부록의 `JOB_STALE_LOCK_SECONDS` 환경 변수와 이름을 맞춰 `application.yaml`에
`${JOB_STALE_LOCK_SECONDS:60}`으로 등록했다 — 기본값 60초, 환경 변수로 덮어쓸 수 있다.

### `attempts` 카운터와의 관계

`JobService.start()`는 클레임에 성공하면 `job.start()`를 호출해 `attempts`를 먼저 증가시킨
**뒤에** 실제 처리(`dispatch`)를 시작한다. 즉 워커가 죽었다면 그 시도의 `attempts`는 이미
증가된 상태다. `reclaimStale`은 `attempts`를 건드리지 않는다 — 다음에 다시 클레임돼 `start()`가
호출될 때 한 번 더 증가하며, 반복해서 죽는(혹은 반복 실패하는) Job은 결국 `max_attempts`를
넘겨 자연히 `FAILED`로 빠진다. 별도의 특수 처리 없이 기존 재시도 카운팅 로직에 자연스럽게
합류하도록 설계했다.

## 왜 회수 후 Redis 트리거 키를 다시 등록하지 않았는가 (의도적 트레이드오프)

`enQueue()`는 성공 시 `job:queue:{id}` Redis 키를 다시 세팅해 만료 알림(빠른 경로)도 함께
켜준다. 반면 `reclaimStale`은 회수 후 이 키를 다시 세팅하지 않는다 — 회수된 Job은 다음 poll
tick(최대 1초 후)에 `findDueJobs(QUEUED, now)`가 다시 찾아내 `start()`를 시도한다. 워커 장애
복구는 애초에 "몇 초 내로 반드시 재개"가 필요한 지연 민감 경로가 아니라 드물게 발생하는 예외
상황이므로, 코드를 더 복잡하게 만들면서까지 빠른 경로를 얹을 필요가 없다고 판단했다.

## 검증

- `./gradlew compileJava compileTestJava` — 빌드 성공.
- `./gradlew test --tests AuthTest` — Testcontainers 기반 통합 테스트로 전체 컨텍스트 기동과
  로그인 흐름이 정상 동작함을 확인했다(새 스케줄러/쿼리가 빈 등록을 깨지 않음).
- **워커가 실제로 RUNNING 도중 죽었을 때 이 회수 로직이 동작하는지를 검증하는 전용 테스트(T5)는
  아직 작성하지 않았다.** 지금 확인한 건 "빌드가 되고 앱이 뜬다"는 것뿐이다. 다음 단계로
  Testcontainers 환경에서 Job을 `RUNNING`으로 만들어두고 `locked_at`을 과거로 조작한 뒤
  `pollDueJobs()`(또는 `reclaimStaleJob()`)를 직접 호출해 `QUEUED`로 돌아오는지, 그리고
  재클레임 후 정확히 한 번만 부수효과가 나는지를 검증하는 테스트를 작성해야 한다.

## 아직 남아 있는 갭

- 고정 타임아웃 방식이므로, `staleLockSeconds`(기본 60초)보다 오래 걸리는 정상 처리가 있다면
  오탐 회수가 발생할 수 있다. 다만 `claimForRunning`이 재클레임 시점에 다시 CAS로 막아주므로
  "두 워커가 동시에 실행"까지는 가지 않는다 — 원래 워커가 뒤늦게 결과를 커밋하려 하면 이미
  상태가 바뀌어 있어 그 갱신은 실패하거나 무시된다(`confirmReservation()`의 "이미 PENDING이
  아니면 멱등하게 no-op" 로직이 이 경우도 방어한다). 다만 이 부분은 명시적인 동시성 테스트로
  아직 확인하지 못했다.
- DLQ(최대 재시도 초과 시 `DEAD` 전이 + 격리)는 이번 작업 범위 밖이다. `session-notes.md`
  우선순위 3번에 이미 있는 항목이므로 이어서 진행하는 것을 권장한다.
