# Job 실행 트리거 신뢰성 수정 — 무엇이 문제였고 어떻게 고쳤는가

## 배경

PR 리뷰 관점에서 프로젝트 아키텍처를 다시 보다가, `docs/stock-locking-strategies.md`에서 다루는
"재고 차감 락 전략 3종 비교"보다 훨씬 근본적인 문제를 발견했다. Job 큐 엔진(명세서 10절, 평가
배점 25%)의 "실행이 되긴 하는가, 정확히 한 번만 실행되는가"라는 전제 자체가 깨져 있었다. 이
문서는 그 문제의 정확한 원인과 수정 내용을 기록하고, 앞으로 이런 트리거를 설계할 때 Redis
Pub/Sub과 Kafka 중 무엇을 선택해야 하는지 원칙을 남긴다.

## 무엇이 문제였는가 (as-was)

### 1. Job 실행을 깨우는 경로가 Redis 키 만료 알림 하나뿐이었다

```java
// MessageBrokerConfig.java (수정 전)
container.addMessageListener(jobExpirationListener, new PatternTopic("__keyevent@*__:expired"));
```

`SCHEDULED → QUEUED`(`JobService.enQueue`)와 `QUEUED → RUNNING`(`JobService.start`) 전이는
전부 `JobExpireListener.onMessage()`가 Redis 키 만료 이벤트를 수신했을 때만 호출됐다. 저장소
전체에서 `jobs` 테이블을 직접 스캔하는 스케줄러는 하나도 없었다 — `EventService`의
`activateEvent()`/`moveQueueToAllow()`만 `@Scheduled`였고, Job 테이블용 폴러는 존재하지
않았다.

Redis keyspace notification은 **at-most-once, 미영속 pub/sub**이다. Redis 공식 문서도
"애플리케이션이 다운돼 있는 동안 발생한 이벤트는 재전달되지 않는다"고 명시한다. 즉 앱이 재시작
직후이거나, 리스너 컨테이너가 아주 짧게라도 끊긴 순간에 만료가 발생하면, 그 이벤트는 그냥
사라진다. 이 경우 해당 Job은 `jobs` 테이블에 `SCHEDULED`나 `QUEUED`로 영원히 멈춰 있고, 아무도
다시 깨우지 않는다. 명세서 10.3이 애초에 "폴러(poller)가 `next_run_at <= now`인 Job을
주기적으로 집어 실행"하라고 명시한 이유가 이것이다 — Redis TTL은 빠른 트리거일 뿐이고, DB 폴링이
진실을 보장하는 안전망이어야 하는데, 안전망이 없었다.

### 2. Job 실행에 원자적 클레임이 없어 다중 실행에 무방비했다

`JobService.start()`는 `findById` 후 상태를 바꾸는 평범한 트랜잭션 메서드였고,
`UPDATE ... WHERE status = 'QUEUED'` 같은 조건부 원자 갱신이 없었다. 스키마의
`locked_by`/`locked_at` 컬럼도 아무 코드에서 쓰이지 않았다.

Redis Pub/Sub은 **구독자 전원에게 메시지를 브로드캐스트**한다(컨슈머 그룹처럼 한 명에게만
전달하는 로드밸런싱 개념이 없다). 앱 인스턴스를 2대 이상 띄우면 두 인스턴스 모두 같은
`__keyevent@*__:expired` 패턴을 구독하므로, 만료 이벤트 하나에 **두 인스턴스가 동시에
`jobService.start(jobId)`를 호출**한다. 클레임 로직이 없었으니 이건 이론적 위험이 아니라
스케일아웃하는 순간 그대로 재현되는 버그였고, FR-E7("여러 워커여도 한 Job은 한 워커만 실행")을
정면으로 위반했다.

### 3. (수정하며 추가로 발견) `@EnableScheduling`이 프로젝트 어디에도 없었다

폴러를 추가하려고 보니 더 근본적인 문제가 있었다. Spring Boot는 `@EnableScheduling`을 명시적으로
켜주지 않으면 `@Scheduled` 애노테이션이 있어도 아무 것도 실행하지 않는다. 저장소 전체를 검색해도
`@EnableScheduling`이 없었다. 즉 기존의 `EventService.activateEvent()`(이벤트 SCHEDULED→OPEN
전이)와 `moveQueueToAllow()`(대기열에서 입장 토큰 발급)도 **실제로는 한 번도 실행된 적이
없었다**. 이번 수정에서 이걸 켜지 않았다면 새로 만든 폴러도 똑같이 죽은 코드가 될 뻔했다.

## 무엇을 고쳤는가

### `DatabaseConfig.java` — 스케줄링 활성화

```java
@Configuration
@EnableAsync
@EnableScheduling   // 추가
public class DatabaseConfig { ... }
```

### `JobRepository.java` — 원자 클레임(compare-and-swap) 2종 + 폴러용 조회

```java
@Query("select j from Job j where j.status = :status and j.next_run_at <= :now")
List<Job> findDueJobs(@Param("status") JobStatus status, @Param("now") LocalDateTime now);

@Modifying
@Query("update Job j set j.status = com.example.eventplatform.job.entity.JobStatus.QUEUED, "
    + "j.next_run_at = :nextRunAt, j.updated_at = :now "
    + "where j.id = :id and j.status = com.example.eventplatform.job.entity.JobStatus.SCHEDULED")
int claimForQueue(@Param("id") Long id, @Param("nextRunAt") LocalDateTime nextRunAt,
    @Param("now") LocalDateTime now);

@Modifying
@Query("update Job j set j.status = com.example.eventplatform.job.entity.JobStatus.RUNNING, "
    + "j.locked_by = :workerId, j.locked_at = :now, j.updated_at = :now "
    + "where j.id = :id and j.status = com.example.eventplatform.job.entity.JobStatus.QUEUED")
int claimForRunning(@Param("id") Long id, @Param("workerId") String workerId,
    @Param("now") LocalDateTime now);
```

두 쿼리 모두 `WHERE ... AND status = <기대하는 이전 상태>` 조건을 걸고, **영향받은 행 수(0 또는
1)로 클레임 성공 여부를 판별**한다(명세서 10.4의 "DB 원자 클레임" 옵션 그대로). 두 호출자(Redis
알림, 폴러, 혹은 서로 다른 인스턴스)가 동시에 같은 Job에 대해 이 쿼리를 날려도 DB가 행 잠금으로
직렬화해주므로 정확히 하나만 `1`을 받고 나머지는 `0`을 받는다.

### `JobService.java` — 클레임 적용 + 폴러 추가

```java
@Transactional
public void enQueue(long jobId) {
  LocalDateTime nextRunAt = LocalDateTime.now().plusSeconds(1);
  int claimed = jobRepository.claimForQueue(jobId, nextRunAt, LocalDateTime.now());
  if (claimed == 0) {
    log.debug("Job {} SCHEDULED->QUEUED 클레임 실패 (이미 다른 경로에서 처리됨)", jobId);
    return; // 정상적인 경쟁 상황 - 다른 트리거가 이미 처리함
  }
  String key = JobRedisKey.QUEUE.generateKeyNoParam(jobId);
  redisHandler.makeJobWithTtl(key, nextRunAt);
}

@Transactional
public void start(long jobId) {
  int claimed = jobRepository.claimForRunning(jobId, WORKER_ID, LocalDateTime.now());
  if (claimed == 0) {
    log.debug("Job {} QUEUED->RUNNING 클레임 실패 (다른 워커/트리거가 이미 처리 중)", jobId);
    return;
  }
  Job job = jobRepository.findById(jobId).orElseThrow(...);
  job.start();   // attempts 증가 등 기존 로직 그대로
  ...
}

@Scheduled(fixedDelay = 1000)
public void pollDueJobs() {
  LocalDateTime now = LocalDateTime.now();
  for (Job job : jobRepository.findDueJobs(JobStatus.SCHEDULED, now)) {
    self.enQueue(job.getId());
  }
  for (Job job : jobRepository.findDueJobs(JobStatus.QUEUED, now)) {
    self.start(job.getId());
  }
}
```

`pollDueJobs()`는 1초마다 `next_run_at`이 지났는데 아직 전이되지 못한 Job을 다시 집어
**Redis 알림과 완전히 동일한 메서드**(`enQueue`/`start`)를 호출한다. 별도의 실행 경로를
새로 만들지 않고 기존 경로를 재사용했기 때문에, Job 실행 로직이 "Redis 트리거용"과
"폴러 트리거용"으로 갈라지는 일이 없다. 이제 이 두 메서드는 원자 클레임으로 보호되므로 Redis
알림과 폴러가 같은 Job을 동시에 집어도 정확히 한쪽만 실제로 전이/실행한다.

한 가지 주의한 지점: `pollDueJobs()` 내부에서 `this.enQueue(...)`처럼 직접 호출하면 스프링 AOP
프록시를 거치지 않아(자기 호출 시 프록시 우회) `@Transactional`이 적용되지 않고, `@Modifying`
쿼리는 활성 트랜잭션이 없으면 예외를 던진다. 그래서 `@Lazy @Autowired`로 자기 자신의 프록시
참조(`self`)를 주입받아 `self.enQueue(...)`로 호출하도록 했다 — 이러면 프록시를 거쳐 각 Job마다
독립된 트랜잭션이 생기고, Job 하나 처리 실패가 같은 틱에서 처리 중인 다른 Job에 영향을 주지
않는다.

### `Job.java` — 더 이상 안 쓰는 `enQueueJob()` 제거

상태 전이 로직을 `JobRepository`의 원자 쿼리로 옮기면서, 엔티티의 in-memory 상태 변경에
의존하던 `enQueueJob()` 메서드는 호출부가 없어져 죽은 코드가 됐다. 그대로 삭제했다.

## 검증

- `./gradlew compileJava compileTestJava` — 빌드 성공.
- `./gradlew test --tests AuthTest` — Testcontainers(Postgres/Redis/Kafka)로 전체 Spring
  컨텍스트를 띄우는 기존 통합 테스트가 통과했다. `@EnableScheduling` 추가, `JobRepository`의 새
  `@Modifying` 쿼리, `JobService`의 `@Lazy self` 필드가 빈 등록/기동 단계에서 문제를 일으키지
  않음을 확인했다.
- `EventplatformApplicationTests.contextLoads()`는 이번 변경과 무관하게 원래도 실패한다
  (Testcontainers를 쓰지 않는 테스트라 datasource 자체가 없어서 나는 실패 — `Failed to
  determine a suitable driver class`). 이번 작업 범위 밖이라 손대지 않았다.
- **폴러가 실제로 유실된 알림을 회수하는지, 멀티 인스턴스에서 중복 실행이 안 되는지를 검증하는
  전용 테스트는 아직 작성하지 않았다.** (예: Redis 리스너를 일부러 끈 상태에서 Job이 폴러만으로
  완료되는지, 두 `JobService` 인스턴스가 같은 `jobId`에 대해 `start()`를 동시에 호출했을 때
  하나만 부수효과를 내는지 등.) `docs/stock-locking-strategies.md`에서 제안한 동시성 테스트
  작업과 함께 다음 단계로 작성하는 것을 권장한다.

## 아직 남아 있는 갭 (이번 수정 범위 밖)

- **워커가 `RUNNING` 상태에서 죽는 경우의 stale lock 회수**는 여전히 미구현이다. 지금
  `claimForRunning`은 `QUEUED → RUNNING`만 처리하므로, 어떤 워커가 클레임에 성공한 뒤 처리
  도중 그대로 죽으면 그 Job은 `RUNNING`에 멈춘 채 폴러도 다시 집어가지 않는다(폴러는 `SCHEDULED`
  /`QUEUED`만 조회한다). `locked_at`이 일정 시간(예: 60초)을 넘긴 `RUNNING` Job을 다시
  `QUEUED`로 되돌리는 별도의 stale-lock 회수 로직이 필요하다 — 이미 `docs/session-notes.md`
  우선순위 3번("Job DEAD 전이 + DLQ")에 이 항목이 있으므로 그 작업과 함께 처리하는 것을
  권장한다.
- `redisAsyncExecutor`(무제한 virtual thread executor) 위에서 `@Async`로 실행되는 예약 생성
  경로의 스레딩 문제는 별개 이슈로 이번에 손대지 않았다.

## Redis Pub/Sub과 Kafka, 언제 어느 쪽을 쓸 것인가

이번 사고의 근본 원인은 "메시지 브로커를 잘못 골랐다"가 아니라 **"Redis Pub/Sub의 전달 보장
수준을 몰각한 채, 그 수준이 필요 없는 자리인 것처럼 썼다"**는 데 있다. 이 프로젝트에는 이미
Kafka도 있고(`confirm_reservation` 토픽 → `Worker.java`의 `@KafkaListener(groupId =
"reservation-worker")`), 그 경로는 컨슈머 그룹 덕분에 원래부터 안전한 패턴이었다. 반면 Job
실행 트리거는 똑같이 "정확히 한 번 실행돼야 하는 부수효과"를 다루면서 Redis Pub/Sub을 그대로
가져다 썼다. 이게 문제의 핵심이다.

### 두 기술의 전달 보장 차이

| | Redis Pub/Sub (keyspace notification 포함) | Kafka |
|---|---|---|
| 영속성 | 없음. 메시지는 저장되지 않고 그 순간 구독 중인 클라이언트에게만 전달 | 있음. 토픽에 로그로 저장, retention 기간 내 재구독/재생 가능 |
| 전달 보장 | at-most-once, 구독이 끊겨 있으면 그냥 유실 | 컨슈머가 오프셋을 커밋하기 전까지 재전달 가능 (at-least-once 구성 가능) |
| 다중 구독자 동작 | 브로드캐스트 — 구독자 전원이 같은 메시지를 받음 | 컨슈머 그룹 내에서는 파티션당 정확히 한 컨슈머만 받음 (로드밸런싱) |
| 순서 보장 | 채널 단위로 약함 | 파티션 내에서는 강하게 보장 |
| 지연/오버헤드 | 매우 낮음 (이미 쓰는 Redis 재사용) | 상대적으로 높음 (별도 브로커 운영, 배치/네트워크 오버헤드) |
| 재처리/감사 | 불가능(메시지가 안 남음) | 가능(오프셋을 되돌려 재생) |

### 선택 기준

- **유실돼도 시스템 정확성에 영향이 없는, "있으면 좋은" 신호**라면 Redis Pub/Sub이 적합하다.
  예: 캐시 무효화 알림, 실시간 대시보드 갱신 힌트, presence(접속 여부) 브로드캐스트. 이런
  용도는 메시지 하나를 놓쳐도 다음 이벤트나 다음 폴링에서 자연히 만회되거나, 애초에 "지금
  당장 정확할 필요"가 없다.
- **정확히 한 번은 반드시 처리돼야 하는 부수효과(결제 확정, 재고 반영, 알림 발송 같은 Job
  실행)**라면 Kafka 컨슈머 그룹처럼 "파티션당 단일 소비 + 오프셋 커밋"을 보장하는 수단을 쓰거나,
  최소한 DB 원자 클레임(`UPDATE ... WHERE status = ?`)으로 직접 그 보장을 만들어야 한다. 이번
  수정에서 Job 실행에 `claimForQueue`/`claimForRunning`을 추가한 이유가 이것이다.
- **"여러 워커/인스턴스 중 정확히 하나만 이 작업을 처리해야 한다"**는 요구가 있다면 Redis
  Pub/Sub은 원천적으로 부적합하다(브로드캐스트이므로 전원이 처리하려 든다). Kafka 컨슈머
  그룹, 혹은 DB/Redis 분산 락(Redisson `RLock`, `SET NX PX`)으로 명시적인 상호 배제를
  걸어야 한다.
- **트리거를 놓쳤을 때 재생(replay)하거나 감사 로그로 남겨야 한다**면 Kafka가 적합하다. Redis
  Pub/Sub은 지나간 메시지를 복구할 방법이 없다.
- **진실의 원천(source of truth)이 이미 DB에 있고, Redis/Kafka는 "언제 그걸 다시 보러 가야
  하는지"를 알려주는 알람 역할일 뿐이라면**, Redis TTL/Pub-Sub을 "빠른 트리거"로 쓰되 반드시
  DB를 직접 스캔하는 폴러를 안전망으로 병행해야 한다. 이번에 적용한 패턴이 정확히 이것이다 —
  Redis 알림이 사라져도 시스템의 정확성은 깨지지 않고, 최악의 경우 폴링 주기(1초)만큼 지연될
  뿐이다. `docs/redis-key-convention.md`에도 이미 이 원칙이 암묵적으로 깔려 있다: "실제
  상태와 진행 이력은 항상 PostgreSQL이 진실 소스이고, Redis 키는 알람 역할만 한다."

한 줄로 요약하면: **Redis Pub/Sub은 "유실돼도 괜찮은 최선-노력 신호"에만 쓰고, 부수효과가 있는
작업의 실행 트리거로 쓸 때는 반드시 DB 폴링 같은 영속적인 안전망을 동반한다. 여러 워커 중 하나만
처리해야 하는 작업은 애초에 Kafka 컨슈머 그룹 같은, "정확히 한 번 전달"을 구조적으로 보장하는
수단을 쓴다.**
