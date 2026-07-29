# 세션 노트 (2026-07-29)

다른 기기(맥북 등)에서 이 저장소를 pull 받아 이어서 작업할 때 맥락을 빠르게 따라잡기 위한 문서.
이 세션에서는 `FairQueue_요구사항_명세서.pdf`(저장소 루트)를 기준으로 현재 구현 상태를 리뷰하고,
가장 시급한 버그(우선순위 1) 몇 가지를 고쳤다.

## 이번 세션에서 고친 것

### 1. Redis 키 네이밍 규칙 정리 (`docs/redis-key-convention.md` 참고)

`JobService`가 `"job:queue:" + id` 로 키를 만드는데 `JobExpireListener`는 `"job:queued:"`
(오타)로 매칭을 검사하고 있어서, 지연 실행되어야 할 Job이 영원히 트리거되지 않는 버그가 있었다.
문자열 리터럴을 여기저기서 손으로 다시 타이핑하다 생긴 문제라서, `JobRedisKey` enum(기존에
`SCHEDULED("job:scheduled")` 하나만 있고 아무도 안 쓰는 죽은 파일 상태였음)을 제대로 채워서
`SCHEDULE`/`QUEUE` 두 상수와 `generateKeyNoParam()`/`matches()`/`extractJobId()` 메서드를
추가했다. 이제 키를 만드는 쪽(`JobService`)과 읽는 쪽(`JobExpireListener`)이 반드시 같은
enum 메서드를 거치므로 이런 종류의 드리프트가 구조적으로 불가능하다.

**규칙**: 앞으로 Redis 키는 절대 문자열 리터럴로 직접 조립/파싱하지 않는다. 반드시
`EventRedisKey` / `JobRedisKey` enum의 메서드를 통해서만 만들고 읽는다. 자세한 표는
`docs/redis-key-convention.md` 참고.

### 2. Enum 보일러플레이트 5종 단순화

`EventStatus`, `EventType`, `JobStatus`, `JobType`, `ReservationStatus` 모두 문자열 필드가
상수 이름과 항상 동일한데도 `getStatus()`/`fromStringStatus()` 등을 직접 구현해서 쓰고
있었다. Java enum의 `.name()`/`.valueOf()`로 대체하고 각 enum에서 보일러플레이트를 제거했다.
호출부도 `event.getStatus().getStatus().equals(...)` 같은 문자열 비교 대신
`event.getStatus() == EventStatus.OPEN` 처럼 enum을 직접 비교하도록 정리했다.

이 과정에서 `JobType`의 `CONFIRMED_RESERVATION`/`EXPIRED_RESERVATION`을 스펙에 맞게
`CONFIRM_RESERVATION`/`EXPIRE_RESERVATION`으로 정정했다 (Kafka 토픽명
`"confirm_reservation"`을 대문자화한 값과 기존 enum 이름이 실제로는 안 맞아서
`JobType.fromStringtoJobType(...)`이 항상 `null`을 반환 → Job 생성 자체가 막혀 있었음).

### 3. 예약 확정(CONFIRM_RESERVATION) 로직 구현

`JobService.confirmReservation()`이 Job만 조회하고 아무 것도 하지 않는 빈 메서드였다.
`JobService.start()`에 JobType 기반 디스패치를 추가하고, 실제 확정 로직
(`Reservation.confirm(issuedCode)` — CONFIRMED 전이, `issued_code` 발급, `confirmed_at`
기록)을 구현했다. 이미 종료 상태인 예약에 대해서는 멱등하게 no-op 처리한다.

이 흐름을 막고 있던 연쇄 버그들도 같이 발견/수정:

- `Reservation` 생성자가 `status`를 설정하지 않아 항상 `null`이었음 → `PENDING` 기본값 추가
- `Event` 생성자가 `remaining_stock`/`create_at`을 초기화하지 않아 신규 이벤트가 재고 0으로
  시작했음(즉시 SOLD_OUT) → 생성자에서 초기화하도록 수정
- **`RedisHandler.createEventStock()`이 구현은 되어 있었지만 어디서도 호출되지 않아** Redis
  재고 카운터가 아예 생성된 적이 없었음 → 이 상태로는 예약 요청이 항상 500 에러였음.
  `EventService.createEvent()`에서 호출하도록 연결
- `Event.status`를 결정하는 if/else 체인에 `open_at == now` 정확히 일치할 때 상태가 null로
  남는 구멍이 있어서 `open_at.isAfter(now)` 기준으로 재작성
- Job의 `next_run_at`이 타입과 무관하게 무조건 +3분/+2분으로 하드코딩되어 있어 스펙상
  "즉시 처리"인 CONFIRM_RESERVATION도 실제로는 5분 뒤에야 실행됐음 → +1초(사실상 즉시)로 변경
- 안 쓰이던 죽은 Kafka 리스너(`Worker`의 `"QUEUED"` 토픽 구독 — 아무도 이 토픽에 publish하지
  않음) 제거

### 4. 재고 이중/오차감 버그 수정

- `decrementEventStock(eventId, per_user_limit)` → `decrementEventStock(eventId, 1)`
  (예약 1건인데 한도 개수만큼 차감하던 버그)
- `JobService.start()`에서 예약 생성 시 이미 차감한 재고를 Job 실행 시 또 차감하던
  이중 차감 로직 제거
- 1인 한도 체크가 이벤트 구분 없이 유저의 전체 예약 개수로 판단하던 버그 →
  `event_id + username` 스코프로 수정 (`ReservationRepository.countByEvent_IdAndUser_UsernameAndStatusIn`)
- 에러 코드 `DUPLICATE_USER`(E4002, 원래 회원가입 중복용) → `ALREADY_RESERVED`(E1002, 스펙에
  맞는 코드)로 정정
- 순서 버그 수정: 기존엔 재고를 먼저 차감한 뒤 1인 한도를 체크해서, 한도 초과로 실패해도
  Redis 차감분이 롤백되지 않고 새는 문제 → 한도 체크를 재고 차감보다 먼저 하도록 순서 변경
- `deleteReservation()`이 DB `remaining_stock`만 복구하고 Redis 카운터는 복구하지 않아
  취소가 쌓일수록 Redis 재고가 실제보다 적어지는 문제 → `RedisHandler.incrementEventStock()`
  추가해서 DB/Redis 양쪽 대칭 복구. 겸사겸사 하드 삭제 대신 `CANCELLED` 상태 전이로 변경
  (스펙의 상태 머신과 일치, 응답 DTO가 실제로는 반영 안 된 "CANCELLED"를 거짓으로 반환하던
  버그도 같이 해결)

## 검증 상태

- `./gradlew compileJava`, `./gradlew compileTestJava` 모두 BUILD SUCCESSFUL 확인.
- **아직 통합 테스트로 실제 동작(엔드투엔드)을 검증하지 않았다.** 기존 테스트는
  `AuthTest`(로그인 성공 1건) 뿐이고, 예약 생성 → Kafka → Job 생성 → Redis 만료 이벤트 →
  confirm 으로 이어지는 전체 왕복을 실제로 띄워서 확인한 적은 없다.

## 다음에 할 일 (우선순위순)

1. **통합 테스트 작성 (최우선)** — Testcontainers(Postgres/Redis/Kafka)로 아래를 검증:
   - 이벤트 생성 → 대기열 진입 → 입장 토큰 발급 → 예약 생성(202) → Job 생성 → Redis 만료
     이벤트 발생 → CONFIRMED 전이까지 실제로 동작하는지 (엔드투엔드 스모크 테스트)
   - 요구사항 명세서 13절의 T1(동시 1000 요청, 재고 100 → 정확히 100건),
     T2(멱등키 재요청), T3(1인 한도), T6/T7(만료 처리)부터 우선 작성
2. **재고 차감 3전략 비교 (C3, 배점 25%)** — 지금은 Redis 원자연산 1종만 실사용 중. DB
   비관적 락/낙관적 락 전략을 별도 구현하고 동일 부하로 TPS/p99/오버셀링 비교표를 README에
   추가해야 함. `Event.version` 필드에 `@Version` 애노테이션이 없어서 낙관적 락이 실제로는
   동작하지 않는 상태(죽은 필드)인 것도 같이 고쳐야 함.
3. **Job DEAD 전이 + DLQ** — 지금은 `max_attempts` 초과 시 `FAILED`로만 끝나고 `DEAD`/DLQ
   격리·재고복구가 없음. 워커 장애 시 stale lock reclaim(`locked_by`/`locked_at` 컬럼은
   있지만 아무도 안 씀)도 미구현.
4. **어드민 Job/DLQ 모니터링 API** — `SecurityConfig`에 권한 규칙은 미리 선언돼 있는데
   실제 컨트롤러가 없음 (`GET /api/admin/jobs`, `GET /api/admin/dlq` 등).
5. **관측성** — Actuator/Micrometer/Prometheus 의존성 자체가 없음. `compose.yaml`에도
   Prometheus/Grafana 서비스 없음.
6. **DB 제약 보강** — `reservations` 테이블에 `UNIQUE(event_id, idempotency_key)`,
   `UNIQUE(event_id, user_id) WHERE status IN (PENDING, CONFIRMED)` 같은 DB 레벨 제약이
   없음 (지금은 애플리케이션 레벨 체크에만 의존). `ddl-auto: update`라서 Flyway/Liquibase
   마이그레이션 도입도 같이 고려 필요.

## 참고

- `docs/redis-key-convention.md` — Redis 키 네이밍 규칙 전체 표
- 요구사항 명세서: 저장소 루트 `FairQueue_요구사항_명세서.pdf`
