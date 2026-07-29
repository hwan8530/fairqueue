package com.example.eventplatform.security;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;

/*
 * 라우트별 인가 규칙을 이 클래스에 URL 문자열 목록으로 손으로 유지보수하던 예전 방식은
 * 실제 컨트롤러 경로(예: 복수형 "/api/reservations")와 여기 적힌 패턴(예: 단수형
 * "/api/reservation/*")이 조용히 어긋나는 문제가 있었다(docs/refactoring-and-abstraction-review.md
 * B3 참고). 그래서 다음 두 가지로 구조를 바꿨다:
 *   1) permitAll()로 "인증이 필요 없는 예외"만 최소한으로 나열하고, 나머지 전부는
 *      anyRequest().authenticated()로 기본 거부(secure by default) - 새 컨트롤러 라우트가
 *      추가돼도 여기 손댈 필요가 없고, 깜빡 안 넣어서 뚫리는 경로가 생길 수 없다.
 *   2) ADMIN 전용 규칙은 URL 패턴이 아니라 @EnableMethodSecurity + 컨트롤러의
 *      @PreAuthorize로 옮겨서, 라우트 선언과 인가 규칙이 같은 자리(AdminController)에
 *      있게 했다.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtUtil jwtUtil;
  private final JwtEntryPoint entryPoint;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.cors(
            cors -> cors.configurationSource(request -> {
              CorsConfiguration config = new CorsConfiguration();
              config.setAllowCredentials(true);
              config.setAllowedOriginPatterns(List.of("*"));
              config.setAllowedMethods(List.of("GET", "POST", "DELETE", "PUT"));
              config.setAllowedHeaders(List.of("*"));
              return config;
            }))
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(
            // "/error"를 permitAll에 빼먹으면: 403/500 등 에러 응답을 서블릿 컨테이너가 내부적으로
            // "/error"로 다시 forward하는데, 그 forward된 요청도 이 필터 체인을 다시 타면서
            // (이번엔 인증 컨텍스트 없이) anyRequest().authenticated()에 걸려 401로 다시 막히고,
            // 그 401이 원래 응답(예: 403)을 덮어써 버린다 - @PreAuthorize가 정확히 403을
            // 판정했는데도 클라이언트는 401을 받는 원인이었다.
            auth -> auth.requestMatchers("/api/auth/signup", "/api/auth/login", "/api/events/*",
                    "/api/events/*/stock", "/v3/api-docs/**", "/swagger-ui/**", "/error")
                .permitAll()
                .anyRequest().authenticated())
        .exceptionHandling(handle -> handle.authenticationEntryPoint(entryPoint))
        .addFilterBefore(new JwtFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

}
