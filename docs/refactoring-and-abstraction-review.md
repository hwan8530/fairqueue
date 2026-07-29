# 리팩토링·추상화·아키텍처 검토 — 적용 결과

## 배경

이 문서는 원래 "고칠 만한 부분이 있는지 검토"만 담은 리뷰 문서였다. 이후 "락 관련 사항(A3)만
빼고 지금 다 고치자"는 결정에 따라 실제로 구현까지 진행했고, 이 문서도 그 결과를 반영해 다시
정리했다. 각 항목에 적용 여부와 최종 코드를 남긴다.

수정을 진행하다가 **이 문서에는 원래 없던, 훨씬 심각한 버그 2건**을 추가로 발견해서 같이
고쳤다. 아래 "구현 중 발견한 추가 버그" 절에 별도로 기록했다 — 결과적으로 이번 작업에서 가장
중요한 부분은 애초에 계획했던 리팩토링이 아니라 이 두 버그였다.

이미 별도 문서로 다룬 주제는 이 문서에서 다시 다루지 않는다:

- 재고 차감 락 전략 3종 비교 → `docs/stock-locking-strategies.md`
- Job 실행 트리거 신뢰성(Redis 알림 + DB 폴러) → `docs/job-trigger-reliability.md`
- 워커 장애 복구(stale RUNNING 회수) → `docs/stale-job-recovery.md`
- `@Async` 블로킹 안티패턴 → `docs/async-blocking-fix.md`

## 구현 중 발견한 추가 버그 (가장 중요한 부분)

### 1. `JwtUtil.getAuthentication()` — 유효한 토큰도 전부 거부되고 있었다

`UsernamePasswordAuthenticationToken`은 생성자가 두 개다. `(principal, credentials)` 2-인자
생성자는 "아직 인증 전"이라는 의미로 `authenticated=false`, `authorities=[]`를 강제로 세팅한다
(AuthenticationManager가 이후 검증해서 3-인자 생성자로 다시 만들어주는 걸 전제로 한 설계).
기존 코드는 이 2-인자 생성자를 그대로 썼다:

```java
// 수정 전
return new UsernamePasswordAuthenticationToken(nameFromAccessToken, token);
```

서명 검증(`parseSignedClaims`)이 이미 인증을 완료한 시점인데도 "인증 전" 토큰을 만들어
`SecurityContextHolder`에 그대로 꽂아 넣고 있었던 것이다. 직접 확인해보니(단위 테스트로
재현) `isAuthenticated() == false`, `authorities == []`였다. 이러면 `.authenticated()`/
`hasRole()` 판정이 전부 실패한다 — **유효한 JWT를 들고 있어도 대기열/예약/관리자 API 전부
401·403을 받았을 것**이다. 로그인 자체는 성공(200)했기 때문에, 로그인만 검증하는 기존
`AuthTest`로는 이 버그가 드러나지 않았다.

수정: role을 JWT claim에 실어서 발급하고, `getAuthentication()`에서 authorities를 채운
3-인자 생성자를 쓰도록 바꿨다.

```java
// JwtUtil.java (수정 후)
public String makeAccessToken(String username, Role role) {
  return Jwts.builder()...
      .claim("username", username).claim("role", role.name()).signWith(TOKEN_SECRET).compact();
}

public Authentication getAuthentication(String token) {
  Claims claims = parseClaims(token);
  String username = claims.get("username", String.class);
  String role = claims.get("role", String.class);
  List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
  return new UsernamePasswordAuthenticationToken(username, token, authorities);
}
```

검증: `JwtUtilTest`(신규)가 발급된 토큰의 `isAuthenticated()==true`, `ROLE_USER`/`ROLE_ADMIN`
권한이 정확한지 회귀 테스트로 고정했다.

### 2. Redis `StringRedisSerializer` + non-String 값 → `ClassCastException` 3곳

`RedisTemplate<String, Object>`의 value 직렬화기가 `StringRedisSerializer`로 설정돼 있는데
(`DatabaseConfig`), 여러 곳에서 `Long`/`long` 값을 문자열로 감싸지 않고 그대로
`.set()`/`.add()`에 넘기고 있었다. `StringRedisSerializer.serialize()`는 값을 무조건
`(String)`으로 캐스팅하므로, 이런 호출은 전부 `ClassCastException: Long cannot be cast to
String`으로 즉시 죽는다.

- `RedisHandler.createEventStock()` — **이벤트 생성(`POST /api/admin/events`) 때마다 죽고
  있었다.** `EventService.createEvent()`가 이 메서드를 호출하는 순간 예외가 나서, 실제로는
  이벤트 생성 API가 한 번도 끝까지 성공한 적이 없었을 것으로 보인다. 이번에 처음으로 이
  경로를 실제 HTTP로 호출하는 테스트(`SecurityConfigTest`)를 작성하면서 드러났다.
- `RedisHandler.enQueueWaiting()`의 대기 순번(`WAITING_IDENTIFY`) 저장 — **대기열 진입
  (`POST /api/events/{id}/queue`)이 호출될 때마다 죽고 있었다.** FR-C(대기열)의 핵심 경로다.
- `RedisHandler.putSet()` — `EventService.activateEvent()`가 OPEN으로 전환된 이벤트 id를
  기록하려 할 때마다 죽고 있었다(다만 `@Async`라 예외가 로그에만 남고 조용히 삼켜졌을 것이다).

세 곳 모두 값을 `String.valueOf(...)`로 감싸도록 고치고, 대응하는 읽기 쪽(`Long.parseLong(...)`
캐스팅)도 맞춰서 고쳤다. `decrementEventStock`의 Lua 스크립트가 `GET` 값을 `tonumber()`로
읽는 것과 동일한 표현(문자열로 저장된 숫자)으로 통일한 것이기도 하다.

### 3. `SecurityConfig`에 `/error`가 `permitAll`이 아니어서 403이 401로 둔갑

B3/C1 작업(아래) 검증 중 발견. `@PreAuthorize`가 정확히 403을 판정해도, 서블릿 컨테이너가
그 에러 응답을 클라이언트에 보내기 전에 내부적으로 `/error`로 forward한다. 이 forward된
요청도 `SecurityConfig`의 필터 체인을 다시 타는데, `/error`가 `permitAll()`에 없어서
`anyRequest().authenticated()`에 걸려 (인증 컨텍스트가 없는 상태로) 401로 다시 막히고, 그
401이 원래 응답을 덮어써 버렸다. `/error`를 `permitAll()`에 추가해 해결했다. Spring Security
디버그 로그(`org.springframework.security: TRACE`)로 실제 필터 체인 흐름을 추적해서 원인을
특정했다.

## A. 추상화 (적용함)

### A1. `CommonFunction.currentUsername()` 추가 — 적용함

```java
public static String currentUsername() {
  return (String) extractAuthentication().getPrincipal();
}
```

`ReservationService`(4곳), `EventService`(2곳)에 반복되던 `extractAuthentication()` +
캐스팅 2줄을 이 한 줄로 교체했다.

### A2. "예약 조회 + 소유권 검증" 통합 — 적용함

```java
private Reservation getOwnedReservation(long reservationId, String username) {
  Reservation reservation = reservationRepository.findById(reservationId)
      .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.RESERVATION_NOT_FOUND));
  if (!reservation.getUser().getUsername().equals(username)) {
    throw new GlobalCustomException(GlobalExceptions.FORBIDDEN);
  }
  return reservation;
}
```

`getReservation`/`deleteReservation` 양쪽에서 이 메서드 하나만 호출하도록 정리했다.

### A3. Event 비관적 락 조회 헬퍼 — 보류 (락 전략 작업과 함께 진행)

원래 문서에 적었던 대로, 락 전략 3종 비교 작업(`docs/stock-locking-strategies.md`)을 시작할
때 같이 정리하는 게 낫다고 판단해 이번에는 손대지 않았다.

### A4. `ResponseReservation.of()` 정적 팩토리 — 적용함

```java
public static <T> ResponseReservation<T> of(int status, T data) {
  ResponseReservation<T> response = new ResponseReservation<>();
  response.setStatus(status);
  response.setData(data);
  return response;
}
```

## B. 문자열 드리프트 (적용함)

### B1. `UsersService`의 refresh token Redis 키 — 적용함

`UserRedisKey` enum을 새로 추가하고(`docs/redis-key-convention.md`에도 표를 추가), 문자열
직접 조립을 제거했다.

```java
public enum UserRedisKey {
  REFRESH_TOKEN("refresh_token:");
  public String generateKey(String username) { return prefix + username; }
}
```
```java
// UsersService.logIn()
String redisKey = UserRedisKey.REFRESH_TOKEN.generateKey(user.getUsername());
```

### B2. Kafka 토픽 이름 중앙화 — 적용함

`JobType` enum이 각 타입의 토픽 이름을 직접 들고 있게 하고(`getTopic()`), 파싱은
`JobType.valueOf(topic.toUpperCase())` 대신 `JobType.fromTopic(topic)`으로 바꿔 "문자열이
대문자로 바꾸면 enum 이름과 우연히 일치해야 한다"는 암묵적 가정을 없앴다.

`@KafkaListener(topics = ...)`는 애노테이션이라 컴파일타임 상수만 허용되므로 이 enum을 직접
참조할 수 없다는 한계는 여전히 남는다 — 대신
`ReservationConfirmationListener.CONFIRM_RESERVATION_TOPIC` 상수와 `JobType`의 값이 항상
같은지를 `JobTypeTopicConsistencyTest`가 테스트로 강제하도록 안전망을 뒀다. 이 리스너 자체는
C3에서 `job.worker.Worker` → `messagebroker.ReservationConfirmationListener`로 이름과
위치를 함께 바꿨다(아래 참고).

### B3. `SecurityConfig` 라우트 드리프트 — 적용함 (C1과 함께)

아래 C1 참고.

## C. 아키텍처 변경 (적용함)

### C1. URL 패턴 인가 → `permitAll` 최소 목록 + `anyRequest().authenticated()` + `@PreAuthorize` — 적용함

```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  ...
  .authorizeHttpRequests(
      auth -> auth.requestMatchers("/api/auth/signup", "/api/auth/login", "/api/events/*",
              "/api/events/*/stock", "/v3/api-docs/**", "/swagger-ui/**", "/error")
          .permitAll()
          .anyRequest().authenticated())
```

```java
// AdminController.java
@RestController
@RequestMapping("/api/admin/")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController { ... }
```

`/api/reservation/*`(단수), `/api/me/reservation`(단수) 같은 실제 라우트와 어긋난 패턴 목록을
완전히 제거했다. 이제 "인증이 필요 없는 예외"만 손으로 유지보수하면 되고(새 보호 라우트를
추가해도 `SecurityConfig`를 잊고 안 고쳐서 뚫리는 경로가 생길 수 없음), ADMIN 권한은
`AdminController` 위의 `@PreAuthorize`가 라우트 선언과 같은 자리에서 관리한다.

검증: `SecurityConfigTest`(신규, Testcontainers 통합 테스트)로 다음 4가지를 실제 HTTP 호출로
확인했다.
- 인증 헤더 없이 보호된 엔드포인트 호출 → 401
- 유효한 JWT로 보호된 엔드포인트 호출 → 인가 게이트 통과(200)
- 일반 USER가 관리자 API 호출 → 403
- ADMIN 역할이 관리자 API 호출 → 성공(201)

이 테스트를 작성하는 과정에서 위 "구현 중 발견한 추가 버그" 2·3번이 드러났다 — 처음 세 번은
전부 401/403이 뒤섞여 실패했고, 그때마다 원인이 이 문서의 범위 밖에 있는 별개의 버그였다.

### C2. `EVENT_NOT_FOUND` 에러 코드 추가 — 적용함

```java
EVENT_NOT_FOUND("E1007", 404, "EVENT_NOT_FOUND - 존재하지 않는 이벤트"),
```

URL 경로의 `eventId`(사용자 입력)로 이벤트를 찾는 두 곳(`EventService.findEvent`,
`ReservationService.makeReservation`)은 `INTERNAL_ERROR`(500) 대신 `EVENT_NOT_FOUND`(404)를
던지도록 바꿨다. 반대로 `ReservationService.deleteReservation`의 이벤트 조회는 사용자 입력이
아니라 예약의 FK를 따라가는 것이라 실패하면 참조 무결성이 깨진 것 — 이건 의도적으로
`INTERNAL_ERROR`로 남겨뒀고, 그 이유를 주석으로 남겼다.

### C3. `Worker` → `ReservationConfirmationListener` 개명 + 패키지 이동 — 적용함

`job.worker.Worker`(실제로는 Job을 실행하지 않고 Kafka 메시지를 받아 Job 행만 만듦)를
`messagebroker.ReservationConfirmationListener`로 옮기고, 메서드 이름도
`confirmReservation` → `onConfirmReservationRequested`로 바꿔 "여기서 확정 처리가 일어나는
게 아니라 확정 *요청*을 받아 Job을 등록할 뿐"이라는 걸 이름으로 드러냈다. 실제 Job 실행은
여전히 `JobExpireListener` + `JobService`의 폴러/클레임 메서드가 담당한다.

## D. DTO 내부 클래스 PascalCase 정리 — 적용함

`reservationDTO` → `ReservationDTO`, `deleteReservationDTO` → `DeleteReservationDTO`,
`ResponseReservationList.item` → `ResponseReservationList.Item`으로 변경하고
`ReservationMapper`/`ReservationController`/`ReservationService`의 참조를 모두 맞췄다.

## 검증

- `./gradlew compileJava compileTestJava` — 빌드 성공.
- `./gradlew test` — 10개 테스트 중 9개 통과. 유일한 실패(`EventplatformApplicationTests
  .contextLoads()`)는 이번 변경과 무관한 기존 결함이다(Testcontainers를 쓰지 않는 테스트라
  datasource가 아예 없어서 나는 실패 — `Failed to determine a suitable driver class`).
- 신규/확장된 테스트: `JwtUtilTest`(2), `SecurityConfigTest`(4), `JobTypeTopicConsistencyTest`(2).
  전부 Testcontainers 기반 실제 HTTP 호출 또는 실제 JWT 발급/검증으로 확인했다 — mock으로
  우회하지 않았다.

## 남은 것

- A3(Event 비관적 락 조회 헬퍼)는 `docs/stock-locking-strategies.md`의 락 전략 3종 비교
  작업과 함께 진행하는 게 낫다는 판단으로 보류했다.
- 이번에 고친 Redis 직렬화 버그가 세 곳이었는데, 같은 `RedisTemplate<String,Object>` +
  `StringRedisSerializer` 조합을 쓰는 코드가 앞으로 추가될 때 같은 실수가 재발할 수 있다.
  값을 저장하기 전에 항상 `String`으로 변환하는 걸 원칙으로 삼거나, 더 근본적으로는
  `GenericJackson2JsonRedisSerializer` 같은 범용 직렬화기로 바꿔서 이 클래스의 값 타입
  제약 자체를 없애는 것도 고려할 만하다(다만 그러면 기존에 저장된 값과의 호환성, Lua
  스크립트의 `tonumber(GET ...)` 가정을 다시 점검해야 한다).
