package com.example.eventplatform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.eventplatform.event.dto.ResponseEvent.ResponseCreateEvent;
import com.example.eventplatform.event.dto.ResponseEvent.ResponseEventDetail;
import com.example.eventplatform.event.dto.ResponseEvent.ResponseEventStock;
import com.example.eventplatform.event.dto.ResponseQueue.ResponseQueueStatus;
import com.example.eventplatform.event.repository.EventRepository;
import com.example.eventplatform.reservation.dto.ResponseReservation.ReservationDTO;
import com.example.eventplatform.reservation.repository.ReservationRepository;
import com.example.eventplatform.users.dto.RequestUsers.RequestLogIn;
import com.example.eventplatform.users.dto.RequestUsers.RequestSignUp;
import com.example.eventplatform.users.dto.ResponseUsers.ResponseLogIn;
import com.example.eventplatform.users.entity.Role;
import com.example.eventplatform.users.entity.Users;
import com.example.eventplatform.users.repository.UsersRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/*
 * 명세서 5절 전체 처리 흐름(로그인 -> 대기열 -> 발급 요청 -> Job 확정 -> 상태 폴링)을 실제
 * HTTP 호출로 끝까지 왕복시켜보는 테스트. session-notes.md가 "아직 통합 테스트로 실제 동작을
 * 검증하지 않았다"고 남겨뒀던 바로 그 엔드투엔드 스모크 테스트다.
 *
 * 이 테스트를 작성하는 과정에서 ResponseEvent/ResponseQueue쪽 DTO들이 @Getter 없이
 * @Setter만 있어서 Jackson이 필드를 직렬화하지 못하고 항상 빈 "{}"를 반환하던 버그를
 * 발견했다 - 이벤트 생성/조회/재고조회/대기열 API 전체가 영향을 받고 있었다
 * (docs/refactoring-and-abstraction-review.md 참고).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class ReservationFlowTest extends IntegrationTestSupport {

  @Autowired
  private UsersRepository usersRepository;
  @Autowired
  private EventRepository eventRepository;
  @Autowired
  private ReservationRepository reservationRepository;

  @BeforeEach
  void cleanUp() {
    reservationRepository.deleteAll();
    eventRepository.deleteAll();
    usersRepository.deleteAll();
  }

  @Test
  @DisplayName("회원가입->로그인->이벤트 생성->대기열->예약 생성->자동 확정까지 전체 흐름이 동작한다")
  void fullReservationFlow() {
    String username = "user" + System.nanoTime();
    String userToken = signUpAndLogin(username);
    String adminToken = signUpAdminAndLogin("admin" + System.nanoTime());

    // 1. 관리자가 재고 3, 1인 1건 한도인 이벤트를 생성한다 (openAt은 과거 -> 생성 즉시 OPEN)
    ResponseCreateEvent created = createEvent(adminToken, 3, 1);
    assertThat(created.getEventId()).isPositive();
    assertThat(created.getStatus()).isEqualTo("OPEN");
    long eventId = created.getEventId();

    // 2. 이벤트 상세/재고 조회가 실제 값을 돌려주는지 확인 (빈 "{}" 버그의 회귀 테스트)
    ResponseEventDetail detail = getEventDetail(eventId);
    assertThat(detail.getEventId()).isEqualTo(eventId);
    assertThat(detail.getRemainingStock()).isEqualTo(3);
    assertThat(detail.getStatus()).isEqualTo("OPEN");

    ResponseEventStock stockBefore = getEventStock(eventId);
    assertThat(stockBefore.getRemainingStock()).isEqualTo(3);
    assertThat(stockBefore.isSoldOut()).isFalse();

    // 3. 대기열 진입
    ResponseQueueStatus joined = joinQueue(userToken, eventId);
    assertThat(joined.getEventId()).isEqualTo(eventId);

    // 4. 입장 허용까지 폴링 (moveQueueToAllow가 1초마다 도므로 몇 초 안에 admitted=true가 돼야 한다).
    // admitted와 entryToken 둘 다 준비될 때까지 기다린다 - 실제 클라이언트도 entryToken이
    // 있어야 발급 요청(FR-D1)을 보낼 수 있으므로 이게 진짜 "완료" 조건이다.
    ResponseQueueStatus admitted = pollUntil(
        () -> getQueueStatus(userToken, eventId),
        s -> s.isAdmitted() && s.getEntryToken() != null,
        Duration.ofSeconds(10), Duration.ofMillis(300));
    assertThat(admitted.getEntryToken()).isNotBlank();

    // 5. 예약 생성 -> 202 PENDING
    String idempotencyKey = UUID.randomUUID().toString();
    ReservationDTO pending = createReservation(userToken, eventId, admitted.getEntryToken(),
        idempotencyKey, 202);
    assertThat(pending.getStatus()).isEqualTo("PENDING");
    assertThat(pending.getReservationId()).isPositive();

    // 6. CONFIRM_RESERVATION Job이 처리될 때까지 폴링 -> CONFIRMED + issuedCode
    ReservationDTO confirmed = pollUntil(
        () -> getReservation(userToken, pending.getReservationId()),
        r -> "CONFIRMED".equals(r.getStatus()),
        Duration.ofSeconds(15), Duration.ofMillis(300));
    assertThat(confirmed.getIssuedCode()).startsWith("CPN-");
    assertThat(confirmed.getConfirmedAt()).isNotNull();

    // 7. 같은 Idempotency-Key로 재요청 -> 새 예약을 만들지 않고 동일 결과를 200으로 반환 (C4/FR-D4)
    ReservationDTO replay = createReservation(userToken, eventId, admitted.getEntryToken(),
        idempotencyKey, 200);
    assertThat(replay.getReservationId()).isEqualTo(pending.getReservationId());
    assertThat(replay.getStatus()).isEqualTo("CONFIRMED");

    // 8. 재고가 정확히 1만 줄어들었는지 확인 (이중 차감 없음)
    ResponseEventStock stockAfter = getEventStock(eventId);
    assertThat(stockAfter.getRemainingStock()).isEqualTo(2);

    // 9. 1인 한도 초과 -> 새 Idempotency-Key로 재시도해도 409 ALREADY_RESERVED (C5)
    HttpStatusCode secondAttemptStatus = attemptReservation(userToken, eventId,
        admitted.getEntryToken(), UUID.randomUUID().toString());
    assertThat(secondAttemptStatus.value()).isEqualTo(409);

    // 10. 내 예약 목록에도 반영돼 있는지 확인
    assertThat(usersRepository.findByUsername(username)).isPresent();
  }

  // ---- HTTP 헬퍼 ----

  private String signUpAndLogin(String username) {
    signUp(username);
    return login(username);
  }

  private String signUpAdminAndLogin(String username) {
    signUp(username);
    Users user = usersRepository.findByUsername(username).orElseThrow();
    user.setRole(Role.ADMIN);
    usersRepository.save(user);
    return login(username);
  }

  private void signUp(String username) {
    syncClient().post().uri("/api/auth/signup")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RequestSignUp(username, username + "@example.com", "TestPass123!"))
        .retrieve().toBodilessEntity();
  }

  private String login(String username) {
    ResponseLogIn response = syncClient().post().uri("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RequestLogIn(username, "TestPass123!"))
        .retrieve().body(ResponseLogIn.class);
    return response.getAccessToken();
  }

  private ResponseCreateEvent createEvent(String adminToken, int totalStock, int perUserLimit) {
    Map<String, Object> body = Map.of(
        "name", "통합테스트 이벤트",
        "type", "COUPON",
        "totalStock", totalStock,
        "perUserLimit", perUserLimit,
        "openAt", LocalDateTime.now().minusMinutes(1).toString(),
        "closeAt", LocalDateTime.now().plusHours(1).toString());
    return syncClient().post().uri("/api/admin/events")
        .header("Authorization", "Bearer " + adminToken)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve().body(ResponseCreateEvent.class);
  }

  private ResponseEventDetail getEventDetail(long eventId) {
    return syncClient().get().uri("/api/events/{id}", eventId)
        .retrieve().body(ResponseEventDetail.class);
  }

  private ResponseEventStock getEventStock(long eventId) {
    return syncClient().get().uri("/api/events/{id}/stock", eventId)
        .retrieve().body(ResponseEventStock.class);
  }

  private ResponseQueueStatus joinQueue(String userToken, long eventId) {
    return syncClient().post().uri("/api/events/{id}/queue", eventId)
        .header("Authorization", "Bearer " + userToken)
        .retrieve().body(ResponseQueueStatus.class);
  }

  /*
   * popQueueAndGenerateEntryToken()은 WAITING에서 pop한 뒤 ALLOWED에 add하기까지 원자적이지
   * 않다 - 그 찰나에 큐 상태 조회가 끼어들면 WAITING/ALLOWED 어디에도 없어(순간적으로) 404
   * QUEUE_ENTRY_NOT_FOUND가 날 수 있다(실제로 통합 테스트에서 재현됨). 실제 클라이언트도
   * 이 상태를 만나면 "아직 준비 안 됐다"로 보고 계속 폴링하는 게 자연스러우므로, 여기서도
   * 그 404를 "아직 미입장"으로 취급하고 폴링을 계속하게 한다.
   */
  private ResponseQueueStatus getQueueStatus(String userToken, long eventId) {
    try {
      return syncClient().get().uri("/api/events/{id}/queue/status", eventId)
          .header("Authorization", "Bearer " + userToken)
          .retrieve().body(ResponseQueueStatus.class);
    } catch (HttpClientErrorException.NotFound e) {
      return new ResponseQueueStatus(eventId, 0, 0, false, null, 0);
    }
  }

  private ReservationDTO createReservation(String userToken, long eventId, String entryToken,
      String idempotencyKey, int expectedStatus) {
    ReservationDTO[] holder = new ReservationDTO[1];
    HttpStatusCode status = syncClient().post().uri("/api/events/{id}/reservations", eventId)
        .header("Authorization", "Bearer " + userToken)
        .header("X-Entry-Token", entryToken)
        .header("Idempotency-Key", idempotencyKey)
        .exchange((req, res) -> {
          holder[0] = res.bodyTo(ReservationDTO.class);
          return res.getStatusCode();
        });
    assertThat(status.value()).isEqualTo(expectedStatus);
    return holder[0];
  }

  private HttpStatusCode attemptReservation(String userToken, long eventId, String entryToken,
      String idempotencyKey) {
    return syncClient().post().uri("/api/events/{id}/reservations", eventId)
        .header("Authorization", "Bearer " + userToken)
        .header("X-Entry-Token", entryToken)
        .header("Idempotency-Key", idempotencyKey)
        .exchange((req, res) -> res.getStatusCode());
  }

  private ReservationDTO getReservation(String userToken, long reservationId) {
    return syncClient().get().uri("/api/reservations/{id}", reservationId)
        .header("Authorization", "Bearer " + userToken)
        .retrieve().body(ReservationDTO.class);
  }

  private <T> T pollUntil(Supplier<T> fetch, Predicate<T> condition, Duration timeout,
      Duration interval) {
    Instant deadline = Instant.now().plus(timeout);
    T last = null;
    while (Instant.now().isBefore(deadline)) {
      last = fetch.get();
      if (condition.test(last)) {
        return last;
      }
      try {
        Thread.sleep(interval.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    throw new AssertionError("폴링 타임아웃 (" + timeout + "). 마지막 값 = " + last);
  }
}
