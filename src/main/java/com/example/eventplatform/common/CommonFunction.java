package com.example.eventplatform.common;

import com.example.eventplatform.exception.GlobalCustomException;
import com.example.eventplatform.exception.GlobalExceptions;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class CommonFunction {

  public static Authentication extractAuthentication() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new GlobalCustomException(GlobalExceptions.AUTH_FAILED);
    }
    return authentication;
  }

  // 현재 요청의 로그인 사용자명. principal이 항상 username 문자열이라는 가정을 이 한 곳에만 둔다.
  public static String currentUsername() {
    return (String) extractAuthentication().getPrincipal();
  }
}
