# 재고 차감 동시성 전략: 비관적 락 / 낙관적 락 / 원자 연산

## 왜 이 문서가 필요한가

`FairQueue_요구사항_명세서.pdf` 9절(C3)은 DB 비관적 락 / DB 낙관적 락 / Redis 원자 연산 세 가지
재고 차감 전략을 **모두 구현하고 동일 부하로 TPS·p99·오버셀링 여부를 비교**하도록 요구한다
(평가 배점 25%). `docs/session-notes.md`의 "다음에 할 일" 2번 항목도 동일한 작업을 최우선 과제
중 하나로 남겨 두었다. 이 문서는 코드를 직접 읽어 확인한 **현재 구현 상태**를 정리하고, 세 전략의
원리·트레이드오프를 비교한 뒤, 명세서 요구사항을 충족하기 위한 구체적인 개선점을 도출한다.

## 1. 현재 구현 상태 (as-is)

결론부터 말하면 지금 코드는 세 전략 중 **어느 하나만 순수하게 쓰고 있지 않다**. 비관적 락과 Redis
원자 연산을 동시에 쓰면서 그 둘을 굳이 같이 쓸 이유가 사라지는 조합이 되어 있고, 낙관적 락은
스키마상 자리만 있고 실제로는 동작하지 않는다.

### 1.1 비관적 락 — 사용 중이지만 락 보유 범위가 과도하게 넓다

`EventRepository`에 `SELECT ... FOR UPDATE`를 발행하는 메서드가 정의되어 있다.

```java
// EventRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select e from Event e where e.id = :id")
Optional<Event> findByIdWithLock(@Param("id") Long id);
```

이 메서드는 `ReservationService.makeReservation()`(예약 생성)과 `deleteReservation()`(예약
취소) 두 곳에서 호출된다. 문제는 락을 잡는 시점과 놓는 시점이다. `makeReservation()`은
메서드 전체가 `@Transactional`이고, 락을 획득한 **이후에** 1인 한도 조회, Redis 원자 차감, 예약
INSERT, **Kafka 메시지 발행**까지 전부 같은 트랜잭션 안에서 순차 실행된다. 즉 이벤트 row 락이
Kafka 프로듀서 호출(네트워크 I/O)이 끝날 때까지 유지된다. 같은 이벤트에 대한 동시 요청은 이
전체 구간 동안 완전히 직렬화되므로, 비관적 락을 쓰는 순간 사실상 이 이벤트의 예약 처리량은
"락을 쥔 트랜잭션 하나가 끝나는 속도"로 상한이 걸린다.

### 1.2 Redis 원자 연산 — 이미 구현되어 있지만 비관적 락과 이중으로 겹쳐 있다

`RedisHandler.decrementEventStock()`은 Lua 스크립트(`decrementEventStock.lua`)로 조회-비교-차감을
원자적으로 수행한다.

```lua
-- decrementEventStock.lua
local currentStock = redis.call('GET', stockKey)
if not currentStock then return -1 end        -- 존재하지 않는 키
if tonumber(currentStock) < quantity then return 0 end  -- SOLD_OUT
redis.call('DECRBY', stockKey, quantity)
return 1                                        -- 성공
```

`makeReservation()`의 실제 흐름은 다음과 같다 (`ReservationService.java:71-89`):

1. `eventRepository.findByIdWithLock(eventId)` — **DB row 락 획득** (요청이 여기서부터 직렬화)
2. 1인 한도 카운트 조회
3. `redisHandler.decrementEventStock(eventId, 1)` — **Redis 원자 차감** (SOLD_OUT 여부 판정)
4. `event.decreaseRemainingStock()` — 락으로 보호된 엔티티의 `remaining_stock`을 메모리에서 감소
   (dirty checking으로 커밋 시 UPDATE)

DB 락이 이미 해당 이벤트에 대한 모든 동시 요청을 한 줄로 세우기 때문에, 그 안에서 다시 Redis에
원자 연산을 요청하는 것은 **정합성 관점에서 불필요한 이중 방어**다. 비관적 락 하나만으로도
오버셀링은 막을 수 있고, Redis 원자 연산 하나만으로도 막을 수 있는데, 지금은 두 메커니즘의
지연 시간(DB 락 대기 + Redis 왕복)을 모두 지불하면서 어느 쪽의 장점(락의 단순함, Redis의 처리량)도
온전히 얻지 못하는 구조다.

### 1.3 낙관적 락 — 필드만 있고 죽어 있다

`events` 테이블에는 명세서대로 `version BIGINT`가 있고 `Event` 엔티티에도 대응 필드가 있다.

```java
// Event.java
@NotNull
private long version;
```

하지만 JPA가 낙관적 락으로 인식하려면 `@jakarta.persistence.Version` 애노테이션이 필요한데
이 필드에는 붙어 있지 않다. 저장소 전체를 `@Version`/`OptimisticLock`으로 검색해도 한 건도
나오지 않는다. 즉 이 `version` 컬럼은 매 UPDATE마다 증가하지도, `WHERE version = ?` 조건에
쓰이지도 않는 **완전한 죽은 필드**이고, 낙관적 락 전략은 코드베이스에 아예 존재하지 않는다.

### 1.4 DB ↔ Redis 이중 쓰기 비정합성 (전략과 별개로 존재하는 구조적 문제)

세 전략과는 별도로, 재고를 "DB의 `remaining_stock`"과 "Redis의 `remaining_stock:{eventId}`
카운터" 두 곳에 나눠 들고 있다는 점 자체가 위험을 만든다.

- `EventService.createEvent()`: DB INSERT 후 `redisHandler.createEventStock()`을 호출하는데,
  이는 조건 없는 `SET`이다(`SETNX` 아님). DB 커밋이 성공하고 Redis `SET` 호출 시점에 장애가
  나면 두 저장소가 처음부터 어긋난 채로 시작한다.
- `makeReservation()`: **Redis 차감이 DB 트랜잭션보다 먼저, 트랜잭션 경계 밖에서** 일어난다.
  Redis 차감 성공 이후 `reservationRepository.save()`나 커밋이 실패하면, Redis 카운터는 이미
  줄었는데 DB에는 예약도 재고 감소도 반영되지 않는 상태가 남는다 — 재고가 "새는" 방향의 비정합.
- `deleteReservation()`: 반대로 DB 증가와 Redis 증가가 한 트랜잭션 안에서 같이 호출되지만,
  Redis `incrementEventStock()`은 이 트랜잭션이 롤백돼도 되돌아가지 않는다(Redis는 JPA 트랜잭션에
  참여하지 않음).
- `incrementEventStock()` 자체도 무조건 `INCRBY`라서, 같은 취소 요청이 중복 실행되면 (현재는
  방어 로직이 없어 보이지 않지만) 카운터가 실제보다 많이 복구될 수 있다.

### 1.5 만료 회수 미구현 — 재고가 영구히 묶이는 경로

`Reservation`은 `expires_at`을 갖고 생성되지만, 저장소 전체에서 `ReservationStatus.EXPIRED`로
전이시키는 코드나 만료 회수 스케줄러/Job이 존재하지 않는다. `JobService`에는
`CONFIRM_RESERVATION` 디스패치만 있고 `EXPIRE_RESERVATION`은 없다. 사용자가 결제(확정)도
취소도 하지 않고 방치한 `PENDING` 예약은 `remaining_stock`을 영구히 점유한 채 남는다 — 재고
차감 "전략"의 정합성과는 별개로, 재고가 실제로 얼마나 회전하는지에 큰 영향을 주는 문제이므로
같이 기록해 둔다. (이 자체는 C3보다 명세서 10.3/FR-E6 범위이며, `session-notes.md` 우선순위
목록에는 아직 명시적으로 올라와 있지 않다.)

## 2. 세 가지 전략의 원리와 트레이드오프

| 항목 | 비관적 락 (`SELECT ... FOR UPDATE`) | 낙관적 락 (`@Version` + 조건부 UPDATE) | Redis 원자 연산 (Lua/`DECR`) |
|---|---|---|---|
| 동시성 제어 방식 | DB row에 배타 락을 걸어 다른 트랜잭션을 대기시킴 | 버전 불일치 시 실패 → 애플리케이션에서 재시도 | 단일 스레드 이벤트 루프에서 명령을 원자 실행 |
| 충돌이 드물 때 | 락 대기 오버헤드를 매번 지불(경쟁 없어도 락 획득/해제 비용 발생) | 거의 항상 1회 시도로 성공, 오버헤드 최소 | 항상 빠름(인메모리) |
| 충돌이 잦을 때(선착순 극단 상황) | 대기열이 그대로 요청 대기 시간이 되어 하나씩 처리 — 처리량이 DB 트랜잭션 속도로 수렴 | 재시도 폭증 → CPU/DB 커넥션 낭비, 재시도 상한 필요 | 여전히 단일 이벤트 루프라 초당 수만 건 처리 가능(가장 폭주에 강함) |
| DB 커넥션 점유 | 트랜잭션이 끝날 때까지 커넥션과 row 락을 계속 점유 → 커넥션 풀 고갈 위험 | 짧은 트랜잭션(조회 1회+조건부 UPDATE 1회)만 점유 | DB는 최종 반영 시에만 짧게 관여(비동기/배치 가능) |
| 정합성 보장 주체 | DB(트랜잭션 격리) | DB(버전 컬럼) | Redis(단일 인스턴스 원자성) — Redis가 SPOF가 될 수 있음 |
| 재고와 예약을 같은 트랜잭션에 묶기 | 자연스러움(같은 DB 트랜잭션) | 자연스러움(재시도 루프 안에서 같이 처리) | 어려움 — Redis 차감과 DB INSERT가 별도 시스템이라 보상 트랜잭션/Outbox 필요 |
| 구현 난이도 | 낮음(`@Lock` 애노테이션 하나) | 중간(재시도 루프, 예외 처리 필요) | 중간(Lua 스크립트, DB 동기화 설계 필요) |
| 이 프로젝트에서 어울리는 지점 | 낮은 동시성의 관리자 작업, 혹은 Job 클레임처럼 "짧고 확실하게 끝나는" 갱신 | 재시도 비용이 감당되는 중간 수준의 경쟁 | "1,000개에 10만 요청" 같은 명세서 목표(A) — 세 전략 중 이 시나리오에 가장 적합 |

세 전략은 상호 배타적이라기보다 **"DB만으로 막을지, Redis를 1차 방어선으로 두고 DB는 최종
반영만 할지"**의 축과 **"락으로 막을지, 버전 비교로 막을지"**의 축이 섞인 것이다. 명세서가
셋을 나란히 비교하라고 요구하는 이유도, 사이드 프로젝트 채점 관점에서 "트래픽 특성에 따라
어떤 선택이 합리적인지 설명할 수 있는가"를 보려는 것이지 셋을 다 동시에 프로덕션에 쓰라는
뜻이 아니다. 현재 코드처럼 비관적 락과 Redis를 겹쳐 쓰는 것은 이 요구사항이 의도한 "비교 대상"이
아니라, 두 전략의 단점만 합쳐진 상태에 가깝다.

## 3. 명세서 요구사항 대비 갭 요약

| 요구사항 | 현재 상태 |
|---|---|
| C3: 세 전략을 각각 구현하고 TPS/p99/오버셀링을 비교해 README에 기록 | 비관적 락 + Redis 하이브리드 1종만 존재. 낙관적 락 미구현. 비교 실험/표 없음 |
| `Event.version`을 낙관적 락에 사용 | `@Version` 미적용으로 죽은 필드 |
| T1 (재고 100, 동시 1,000 요청 → 정확히 100건) | 자동화 테스트 없음 (통합 테스트 자체가 `AuthTest` 1건뿐) |
| T10 (락 전략 3종 동일 부하 비교) | 테스트/부하 스크립트 없음 |
| C2 (재고 확인·차감의 원자성) | Redis 차감 자체는 원자적이나, Redis-DB 간 원자성은 없음(1.4 참고) |

## 4. 개선 제안 (우선순위순)

### 4.1 세 전략을 실제로 분리 구현하고 비교할 수 있게 만든다 (C3 직접 대응)

- 현재의 "비관적 락 + Redis" 혼합을 세 개의 독립된 구현으로 분리한다. 예: 전략 인터페이스
  (`StockDecrementStrategy`)를 두고 `PessimisticLockStrategy` / `OptimisticLockStrategy` /
  `RedisAtomicStrategy` 세 구현체를 만들어 설정(프로파일/파라미터)으로 전환 가능하게 한다.
  - **비관적 락 전략**: `findByIdWithLock()`만 사용, Redis 호출 제거. 락 보유 범위를 "조회 →
    조건부 감소 → flush"까지로 최소화하고, Kafka 발행은 트랜잭션 커밋 이후(또는 별도
    트랜잭션)로 옮긴다.
  - **낙관적 락 전략**: `Event.version`에 `@Version`을 붙이고, `remaining_stock`을 감소시킨 뒤
    저장하다가 `OptimisticLockException`이 나면 짧은 재시도 루프(예: 최대 N회, 실패 시
    `SOLD_OUT` 대신 일시 오류로 재시도 유도)로 처리한다.
  - **Redis 원자 연산 전략**: 현재의 Lua 스크립트를 그대로 1차 방어선으로 쓰되, DB
    `remaining_stock`은 트랜잭션 커밋 성공 여부와 무관하게 어긋나지 않도록 4.2의 동기화
    방식을 적용한다.
- k6/Gatling 등으로 동일한 부하 시나리오(T1, T10)를 세 전략에 각각 적용해 TPS/p99/오버셀링
  발생 여부를 측정하고, 결과를 README에 표로 남긴다. 이것이 배점 25%인 C3 항목의 직접적인
  산출물이다.

### 4.2 Redis-DB 간 쓰기를 하나의 트랜잭션 경계로 묶거나, 명시적으로 보상한다

- Redis 차감을 DB 트랜잭션보다 먼저 실행하는 지금 순서를 유지한다면, DB 커밋 실패 시 Redis를
  되돌리는 보상 로직(try/catch로 `incrementEventStock` 호출)을 반드시 추가한다.
- 장기적으로는 Outbox 패턴(명세서 16절 확장 과제에도 언급됨)을 적용해 "예약 생성 + 재고 감소
  기록"을 하나의 DB 트랜잭션으로 원자화하고, Redis 카운터 반영은 그 결과를 소비하는 별도
  프로세스가 담당하도록 분리하면 이중 쓰기 비정합 문제 자체가 사라진다.
- `EventService.createEvent()`의 `createEventStock()`은 `SET` 대신 `SETNX`(또는 존재 여부
  확인 후 조건부 생성)로 바꿔 같은 이벤트에 대해 실수로 두 번 초기화되는 경우를 방지한다.

### 4.3 비관적 락을 쓰는 경로의 락 보유 범위를 최소화한다

- 비관적 락 전략을 유지/구현하는 구간에서는, 락을 잡은 뒤 반드시 필요한 재고 조회·차감만
  수행하고 Kafka 발행·응답 조립 등 I/O는 락 밖(트랜잭션 밖 혹은 락 해제 이후)으로 이동한다.
  현재처럼 락 보유 트랜잭션 안에서 Kafka 프로듀서를 호출하면, Kafka 브로커 지연이 곧바로 해당
  이벤트의 전체 예약 처리량 저하로 직결된다.

### 4.4 낙관적 락을 살리거나, 죽은 필드를 정리한다

- `Event.version`에 `@Version`을 붙여 4.1의 낙관적 락 전략에서 실제로 사용하거나, 낙관적 락
  전략을 채택하지 않기로 결정했다면 이 필드와 관련 스키마 주석을 정리해 "구현되지 않은 기능"이
  코드에 남지 않도록 한다. 명세서가 세 전략 비교를 요구하는 이상 전자를 권장한다.

### 4.5 (관련 문제, 별도 우선순위) 만료 회수(EXPIRE_RESERVATION) 구현

- 세 가지 차감 전략과는 별개로, 지금은 `PENDING`으로 남은 예약이 만료돼도 재고가 회수되지
  않는다. 어떤 차감 전략을 쓰든 이 갭이 있으면 "장시간 부하 후 실제 가용 재고가 점점 줄어드는"
  현상으로 나타나므로, 부하 테스트(T9 등)에서 관찰되는 재고 수치를 정확히 해석하려면 이 기능도
  함께 필요하다. `session-notes.md`의 우선순위 목록에는 별도 항목(3번, Job DEAD/DLQ)으로
  이미 잡혀 있으니 그 작업과 함께 처리하는 것을 권장한다.

## 5. 다음 단계

1. `Event.version`에 `@Version` 적용 (가장 작은 변경, 낙관적 락 전략의 전제 조건).
2. 세 전략을 전략 패턴으로 분리하고 T1/T10 테스트(Testcontainers + 동시 요청)를 작성.
3. k6/Gatling 스크립트로 세 전략 각각 부하 실행 → TPS/p99/오버셀링 표를 README에 추가.
4. Redis-DB 보상 로직 또는 Outbox 적용으로 4.2의 비정합 갭 제거.
