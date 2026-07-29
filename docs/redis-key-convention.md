# Redis 키 네이밍 규칙

## 왜 이 문서가 필요한가

`job:queue:{jobId}` 키를 쓰는 코드와 `job:queued:{jobId}` 를 읽는 코드가 서로 다른 문자열
리터럴로 각자 하드코딩되어 있어서, 실제로는 절대 매칭되지 않는 채로 방치된 적이 있었다
(`JobService.enQueue` 는 `"job:queue:"` 로 키를 만드는데 `JobExpireListener` 는
`"job:queued:"` 로 시작하는지 검사하고 있었음). 컴파일러가 잡아주지 못하는 종류의 버그라서,
앞으로는 **키 문자열을 손으로 두 번 이상 타이핑하지 않는 것**을 원칙으로 한다.

## 규칙

1. **접두사(prefix)는 도메인별 `*RedisKey` enum에만 정의한다.**
   - 이벤트/대기열 관련: `EventRedisKey`
   - Job 관련: `JobRedisKey`
   - 새로운 키가 필요하면 문자열을 코드 여기저기 흩어놓지 말고, 해당 enum에 상수를 추가한다.
2. **접두사 형식**: `{domain}:{purpose}:` 형태로 항상 `:` 로 끝난다. (예: `job:schedule:`, `waiting:`)
3. **키 조립/해석은 반드시 enum의 메서드로만 한다.** 문자열을 직접 이어붙이거나(`+`),
   직접 `startsWith`/`replace` 하지 않는다.
   - 생성: `generateKeyNoParam(id)` / `generateKey(id, sub)` / `generateKeyWithParams(id, sub, sub2)`
   - 판별: `matches(rawKey)`
   - 역파싱(만료 이벤트 등에서 원본 키로부터 id 복원): `extractJobId(rawKey)` 등
   - 이렇게 하면 접두사를 바꿔야 할 때 enum 한 곳만 고치면 되고, 쓰는 쪽/읽는 쪽이 다시 어긋날 수 없다.

## 현재 키 목록

### `EventRedisKey` (도메인: 이벤트/대기열)

| 상수 | 접두사 | 최종 키 예시 | 자료구조 | 용도 / TTL |
|---|---|---|---|---|
| `WAITING` | `waiting:` | `waiting:100` | ZSET | 이벤트별 대기열. score=진입 시각, member=username. TTL 없음(입장 시 pop) |
| `WAITING_IDENTIFY` | `waiting:identify:` | `waiting:identify:100:alice` | STRING | 유저의 최초 진입 순번 기억용. ALLOWED 진입 시 TTL 부여 |
| `ALLOWED` | `allowed:` | `allowed:100` | ZSET | 입장 허용된 유저 집합. score=만료 시각(ms) |
| `ENTRY_TOKEN` | `entry_token:` | `entry_token:100:alice` | STRING | 발급 요청 자격 토큰. TTL 30초 |
| `ACTIVE_EVENTS` | `active:events` | `active:events` | SET | 현재 OPEN 상태인 이벤트 id 집합 |
| `REMAINING_STOCK` | `remaining_stock:` | `remaining_stock:100` | STRING(counter) | 원자적 재고 차감/복구용 카운터 |

### `JobRedisKey` (도메인: Job 상태 전이 트리거)

| 상수 | 접두사 | 최종 키 예시 | 자료구조 | 용도 / TTL |
|---|---|---|---|---|
| `SCHEDULE` | `job:schedule:` | `job:schedule:5001` | STRING(빈값) | `SCHEDULED → QUEUED` 전이 트리거. TTL = `next_run_at` 까지, 만료 시 `JobExpireListener` 가 `JobService.enQueue()` 호출 |
| `QUEUE` | `job:queue:` | `job:queue:5001` | STRING(빈값) | `QUEUED → RUNNING` 전이 트리거(초기 실행 및 재시도 모두 이 키 사용). TTL = `next_run_at` 까지, 만료 시 `JobService.start()` 호출 |

두 키 모두 값 자체는 의미가 없고(빈 문자열), Redis Keyspace Notification(`notify-keyspace-events Ex`)의
만료 이벤트만 트리거로 활용한다. 실제 상태(SCHEDULED/QUEUED/RUNNING/...)와 진행 이력은 항상
PostgreSQL의 `jobs` 테이블이 진실 소스(source of truth)이고, Redis 키는 "언제 다음 단계로
넘어갈지"를 알려주는 알람 역할만 한다.

## 새 키를 추가할 때 체크리스트

- [ ] 해당 도메인의 `*RedisKey` enum에 상수를 추가했는가?
- [ ] 이 문서의 표에 한 줄 추가했는가?
- [ ] 키를 만드는 곳과 읽는(파싱/매칭하는) 곳이 전부 그 enum의 메서드를 통하는가? (문자열 리터럴 중복 없음)