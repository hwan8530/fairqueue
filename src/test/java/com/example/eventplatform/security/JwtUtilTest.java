package com.example.eventplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.eventplatform.users.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/*
 * JwtUtil.getAuthentication()이 실제로 "인증 완료" 상태(authenticated=true)와 올바른 ROLE_*
 * 권한을 만들어내는지 확인하는 회귀 테스트. UsernamePasswordAuthenticationToken의 2-인자
 * 생성자를 잘못 쓰면 authenticated=false, authorities=[]가 되어 유효한 토큰으로도 모든 보호된
 * API가 거부되는 버그가 있었다 (docs/refactoring-and-abstraction-review.md 참고).
 */
class JwtUtilTest {

  private final JwtUtil jwtUtil = new JwtUtil("600000", "7200000",
      "thisismysecretforjwt.needtoover32bytesmaybe.butthissample",
      "thisisentry_token_secretforjwt.needtoover32bytes.butthisisample");

  @Test
  void 발급된_토큰의_인증객체는_authenticated_true여야_한다() {
    String token = jwtUtil.makeAccessToken("alice", Role.USER);
    Authentication auth = jwtUtil.getAuthentication(token);

    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.getPrincipal()).isEqualTo("alice");
    assertThat(auth.getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_USER");
  }

  @Test
  void ADMIN_역할이면_ROLE_ADMIN_권한을_가져야_한다() {
    String token = jwtUtil.makeAccessToken("bob", Role.ADMIN);
    Authentication auth = jwtUtil.getAuthentication(token);

    assertThat(auth.getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_ADMIN");
  }
}
