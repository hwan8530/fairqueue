package com.example.eventplatform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.eventplatform.users.dto.RequestUsers.RequestLogIn;
import com.example.eventplatform.users.dto.RequestUsers.RequestSignUp;
import com.example.eventplatform.users.dto.ResponseUsers.ResponseLogIn;
import com.example.eventplatform.users.entity.Role;
import com.example.eventplatform.users.entity.Users;
import com.example.eventplatform.users.repository.UsersRepository;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/*
 * docs/refactoring-and-abstraction-review.md의 B3(SecurityConfig 라우트 드리프트)에서
 * "정적으로만 추론했고 실제 동작은 통합 테스트로 확인해야 한다"고 남겨뒀던 부분을 검증한다.
 * 동시에 JwtUtil.getAuthentication() 버그(authenticated=false, authorities=[])가 실제로
 * 고쳐졌는지도 이 테스트가 없으면 확인할 방법이 없었다 - 로그인(AuthTest)만으로는 발급된
 * 토큰이 실제로 보호된 API를 통과하는지 알 수 없기 때문이다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class SecurityConfigTest extends IntegrationTestSupport {

  @Autowired
  private UsersRepository usersRepository;

  @BeforeEach
  void cleanUp() {
    usersRepository.deleteAll();
  }

  @Test
  @DisplayName("Authorization 헤더 없이 보호된 엔드포인트를 호출하면 401")
  void unauthenticatedRequestIsRejected() {
    HttpStatusCode status = syncClient().get().uri("/api/me/reservations")
        .exchange((req, res) -> res.getStatusCode());

    assertEquals(401, status.value());
  }

  @Test
  @DisplayName("유효한 JWT면 /api/me/reservations 인가 게이트를 통과한다 (예전엔 URL 패턴이 안 맞아 " +
      "authenticated() 규칙 자체가 안 걸렸고, JwtUtil 버그로 걸렸어도 항상 거부됐었다)")
  void authenticatedRequestPassesTheGate() {
    String token = signUpAndLogin("user" + System.nanoTime());

    HttpStatusCode status = syncClient().get().uri("/api/me/reservations")
        .header("Authorization", "Bearer " + token)
        .exchange((req, res) -> res.getStatusCode());

    assertEquals(200, status.value());
  }

  @Test
  @DisplayName("일반 USER는 관리자 API 호출 시 403")
  void nonAdminCannotCallAdminApi() {
    String token = signUpAndLogin("user" + System.nanoTime());

    HttpStatusCode status = syncClient().post().uri("/api/admin/events")
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .body(sampleEventRequest())
        .exchange((req, res) -> res.getStatusCode());

    assertEquals(403, status.value());
  }

  @Test
  @DisplayName("ADMIN 역할은 관리자 API를 호출할 수 있다")
  void adminCanCallAdminApi() {
    String username = "admin" + System.nanoTime();
    signUp(username);
    Users user = usersRepository.findByUsername(username).orElseThrow();
    user.setRole(Role.ADMIN);
    usersRepository.save(user);
    String token = login(username);

    HttpStatusCode status = syncClient().post().uri("/api/admin/events")
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .body(sampleEventRequest())
        .exchange((req, res) -> res.getStatusCode());

    assertEquals(201, status.value());
  }

  private Map<String, Object> sampleEventRequest() {
    return Map.of(
        "name", "테스트 이벤트",
        "type", "COUPON",
        "totalStock", 10,
        "perUserLimit", 1,
        "openAt", LocalDateTime.now().minusMinutes(1).toString(),
        "closeAt", LocalDateTime.now().plusHours(1).toString());
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

  private String signUpAndLogin(String username) {
    signUp(username);
    return login(username);
  }
}
