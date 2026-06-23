package com.example.eventplatform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

  private final JwtUtil jwtUtil;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String token = extractTokenFromHeader(request.getHeader("Authorization"));
    if (token != null) {
      Authentication authentication = jwtUtil.getAuthentication(token);
      if (authentication != null) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } else {
        request.setAttribute("jwtException", "Invalid token");
      }
    }
    filterChain.doFilter(request, response);
  }

  private String extractTokenFromHeader(String header) {
    if (header == null || !header.startsWith("Bearer ")) {
      return null;
    }
    return header.split(" ")[1];
  }
}
