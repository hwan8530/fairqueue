package com.example.eventplatform.security;

import com.example.eventplatform.exception.GlobalCustomException;
import com.example.eventplatform.exception.GlobalExceptions;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
public class JwtEntryPoint implements AuthenticationEntryPoint {

  private final HandlerExceptionResolver resolver;

  public JwtEntryPoint(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  public void commence(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException authException) throws IOException, ServletException {
    JwtException jwtException = (JwtException) request.getAttribute("jwtException");
    if (jwtException != null) {
      resolver.resolveException(request, response, null,
          new GlobalCustomException(GlobalExceptions.TOKEN_INVALID_OR_EXPIRED));
    } else {
      resolver.resolveException(request, response, null,
          new GlobalCustomException(GlobalExceptions.AUTH_FAILED));
    }
  }
}
