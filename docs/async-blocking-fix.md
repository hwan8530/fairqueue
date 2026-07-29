# `@Async` 블로킹 안티패턴 — 무엇이 문제였고 어떻게 고쳤는가

## 배경

지난 아키텍처 리뷰에서 예약 생성 경로(`ReservationService.makeReservation`)가 `@Async` +
`@Transactional`로 선언돼 있는데, 정작 호출부인 컨트롤러가 그 결과를 즉시 `.get()`으로
블로킹하고 있다는 걸 발견했다. 이번에 고치면서 같은 파일 밖에서 **완전히 동일한 패턴**이
대기열 상태 조회 경로(`EventService.queueStatus` ← `RedisHandler.queueStatus`)에도 그대로
있는 걸 확인해서 같이 고쳤다.

## 개념: `@Async` + 즉시 `.get()`은 왜 안티패턴인가

스프링의 `@Async`는 AOP 프록시를 통해 메서드 호출을 가로채서, 실제 실행을 별도 스레드
(지정한 `Executor`, 이 프로젝트에서는 `redisAsyncExecutor`)에 위임하고 즉시
`CompletableFuture`를 반환한다. 이게 의미가 있으려면 **호출자가 그 `Future`를 받아서 다른
일을 하다가 나중에(또는 아예 신경 쓰지 않고) 결과를 확인**해야 한다.

그런데 호출자가 `future.get()`을 그 자리에서 바로 부르면 어떻게 될까:

1. 호출 스레드(예: Tomcat/서블릿 처리 스레드)는 `.get()`에서 그대로 멈춰 결과를 기다린다.
2. 실제 작업은 다른 스레드(`redisAsyncExecutor`)에서 수행된다.
3. 클라이언트 입장에서 응답 시간은 "동기로 직접 실행했을 때"와 정확히 같거나 스레드 전환
   오버헤드만큼 더 길다.

즉 **아무 것도 비동기가 아니고, 스레드를 하나 더 빌려 쓰는 비용만 추가된 동기 호출**이 된다.
명세서가 말하는 "202 Accepted — 비동기 처리"의 "비동기"는 "이 HTTP 요청을 처리하는 스레드를
쪼갠다"는 뜻이 아니라 **"결제 확정 같은 무거운 후속 처리를 기다리지 않고, 가벼운 PENDING
생성만 하고 바로 응답한다"**는 뜻이다. 이 프로젝트에서 그 "비동기 후속 처리"는 이미 Job
큐 엔진(Kafka 발행 → Job 생성 → Worker가 나중에 확정)이 담당하고 있으므로, `makeReservation`
자체를 스레드 분리할 이유가 처음부터 없었다.

## 문제였던 부분

### 1. 예약 생성 (`ReservationController` → `ReservationService.makeReservation`)

```java
// ReservationController.java (수정 전)
CompletableFuture<ResponseReservation<reservationDTO>> response =
    reservationService.makeReservation(eventId, entryToken, idempotencyKey);
return ResponseEntity.status(response.get().getStatus()).body(response.get().getData());
```
```java
// ReservationService.java (수정 전)
@Transactional
@Async
public CompletableFuture<ResponseReservation<reservationDTO>> makeReservation(...) { ... }
```

이 경로가 특히 위험했던 이유는 단순히 "이득 없는 오버헤드"에 그치지 않기 때문이다.
`makeReservation`은 이벤트 하나당 **비관적 락(`findByIdWithLock`)으로 직렬화되는 핫스팟**이다
(자세한 내용은 `docs/stock-locking-strategies.md`). `redisAsyncExecutor`는
`Executors.newVirtualThreadPerTaskExecutor()`(무제한 virtual thread)라서, 대기열에서 한꺼번에
입장 토큰을 받은 사용자들이 몰리면 무제한으로 만들어진 가상 스레드들이 전부 같은 이벤트 row
락을 기다리며 **유한한 HikariCP 커넥션**을 점유한 채 쌓일 수 있었다 — 커넥션 풀 고갈로 이
이벤트와 무관한 다른 API까지 영향을 받을 수 있는 구조였다.

### 2. 대기열 상태 조회 (`EventController` → `EventService.queueStatus` → `RedisHandler.queueStatus`)

```java
// EventService.java (수정 전)
public ResponseQueueStatus queueStatus(long eventId)
    throws ExecutionException, InterruptedException {
  ...
  QueueStruct queueStruct = redisHandler.queueStatus(eventId, ...).get();
  ...
}
```
```java
// RedisHandler.java (수정 전)
@Async("redisAsyncExecutor")
public CompletableFuture<QueueStruct> queueStatus(long eventId, String username) { ... }
```

Redis 커맨드 몇 번을 순서대로 실행하는 짧은 동기 호출인데도 똑같이 `@Async`로 선언돼 있었고,
호출부가 똑같이 즉시 `.get()`으로 블로킹하고 있었다. FR-C4(대기열 상태 폴링)는 클라이언트가
자주(예: 1~2초 간격) 호출하는 엔드포인트라서, 요청마다 불필요한 스레드 전환이 반복해서 쌓인다.

## 수정 내용

두 곳 모두 같은 방식으로 고쳤다: **`@Async`를 제거하고, `CompletableFuture`로 감싸지 않고
결과를 직접 반환한다.**

```java
// ReservationService.java (수정 후)
@Transactional
public ResponseReservation<reservationDTO> makeReservation(...) {
  ...
  return responseReservation; // CompletableFuture.completedFuture(...) 대신 직접 반환
}
```
```java
// ReservationController.java (수정 후)
ResponseReservation<reservationDTO> response = reservationService.makeReservation(
    eventId, entryToken, idempotencyKey);
return ResponseEntity.status(response.getStatus()).body(response.getData());
```
```java
// RedisHandler.java (수정 후)
public QueueStruct queueStatus(long eventId, String username) {
  ...
  return new QueueStruct(identifier, rank, entryToken, remainingTime);
}
```
```java
// EventService.java (수정 후)
public ResponseQueueStatus queueStatus(long eventId) {
  ...
  QueueStruct queueStruct = redisHandler.queueStatus(eventId, ...); // .get() 제거
  ...
}
```

부수적으로 `throws ExecutionException, InterruptedException` 선언과 관련 import
(`CompletableFuture`, `ExecutionException`)도 컨트롤러/서비스에서 함께 제거했다 — 더 이상
그런 예외가 날 수 있는 호출이 아니므로 시그니처에 남아 있으면 오해의 소지가 있었다.

## 검증

- `./gradlew compileJava compileTestJava` — 빌드 성공.
- `./gradlew test --tests AuthTest` — 로그인 흐름을 포함한 전체 Spring 컨텍스트가 정상
  기동함을 확인했다. 다만 이 테스트는 예약 생성이나 대기열 조회 API를 직접 호출하지 않으므로,
  응답 값 자체가 수정 전후로 동일한지(회귀 여부)는 별도 테스트가 필요하다 — 로직은 그대로
  두고 스레딩 방식만 걷어냈으므로 동작이 달라질 이유는 없지만, 이번 검증만으로 "동작이
  똑같음을 증명했다"고 말할 수는 없다.
- 부하 상황에서 실제로 스레드/커넥션 풀 사용량이 줄었는지는 부하 테스트(k6/Gatling)로 확인해야
  하며, 아직 하지 않았다. `docs/stock-locking-strategies.md`에서 제안한 부하 테스트 작업과
  같이 진행하는 것을 권장한다.

## 그럼 `@Async`는 언제 써야 하는가

이번 수정에서 `@Async`를 전부 걷어낸 건 아니다. 저장소에는 여전히 정당한 `@Async` 사용이
남아 있다:

- `RedisHandler.putSet(...)` — `EventService.activateEvent()`에서 호출되는데, 호출자가
  결과를 기다리지 않고 그냥 "적어두기만 하면 되는" fire-and-forget 호출이다.
- `EventService.activateEvent()` / `moveQueueToAllow()` — `@Scheduled` 진입점 자체가
  `@Async`다. 스케줄러 트리거가 작업 완료를 기다리지 않고, 다음 틱이 밀리지 않도록 별도
  실행기로 넘기는 용도이므로 정당하다.

판별 기준은 단순하다: **① 호출자가 결과를 그 자리에서 기다리는가? 기다린다면 `@Async`를 쓸
이유가 없다(동기 호출과 다를 게 없으면서 오버헤드만 는다).** **② 정말 스레드를 나눠 써야 할
만큼 무거운 작업이거나, 호출자를 막지 않고 흘려보내야 하는 신호인가?** 이 프로젝트는 이미
virtual thread를 켜둔 상태라(`spring.thread.virtual.enabled: true`), 블로킹 I/O가 플랫폼
스레드를 점유하지 않는다 — 전통적인 스레드 풀 모델에서 `@Async`로 스레드를 아껴 써야 했던
이유 자체가 이미 상당 부분 사라져 있다. 앞으로 새로운 `@Async`를 추가하려면 "호출자가 정말
결과를 기다리지 않는가"를 먼저 확인해야 한다.
