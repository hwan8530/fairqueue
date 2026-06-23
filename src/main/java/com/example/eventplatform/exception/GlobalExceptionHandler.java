package com.example.eventplatform.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(GlobalCustomException.class)
  protected ResponseEntity<ExceptionDTO> HandleGlobalCustomException(
      GlobalCustomException exception) {
    GlobalExceptions e = exception.getGlobalExceptions();
    return ResponseEntity.status(e.getStatusCode()).body(
        new ExceptionDTO(e.getErrorCode(), e.getDescription(), null)); // traceID는 어디서 어떻게 처리할지 고민
  }
}
