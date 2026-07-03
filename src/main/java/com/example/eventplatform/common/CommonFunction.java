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
}
