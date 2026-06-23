package com.example.eventplatform.security;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;

@Configuration
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
            auth -> auth.requestMatchers("/api/auth/signup", "/api/auth/login", "/api/events/*",
                    "/api/events/**/stock", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .requestMatchers("/api/events/**/queue", "/api/events/**/queue/status",
                    "/api/events/**/reservations", "/api/reservation/*", "/api/me/reservation")
                .authenticated()
                .requestMatchers("/api/admin/events", "/api/admin/jobs", "/api/admin/jobs/*",
                    "/api/admin/jobs/**/retry", "/api/admin/dlq", "/api/admin/dlq/**/requeue")
                .hasRole("ADMIN"))
        .exceptionHandling(handle -> handle.authenticationEntryPoint(entryPoint))
        .addFilterBefore(new JwtFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

}
