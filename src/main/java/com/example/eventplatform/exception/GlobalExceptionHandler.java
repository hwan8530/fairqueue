package com.example.eventplatform.exception;

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  @ExceptionHandler(GlobalCustomException.class)
  protected ResponseEntity<ExceptionDTO> HandleGlobalCustomException(
      GlobalCustomException exception) {
    GlobalExceptions e = exception.getGlobalExceptions();
    return ResponseEntity.status(e.getStatusCode()).body(
        new ExceptionDTO(e.getErrorCode(), e.getDescription(), null)); // traceID는 어디서 어떻게 처리할지 고민
  }

  /*
   * IllegalArgumentException 전용 핸들러
   * 클라이언트로 Internal Error 전송하고 error log 발생
   */
  @ExceptionHandler(IllegalArgumentException.class)
  protected ResponseEntity<ExceptionDTO> HandleIllegalArgumentException(
      IllegalArgumentException exception) {
    log.error(Arrays.toString(exception.getStackTrace()));
    return ResponseEntity.status(GlobalExceptions.INTERNAL_ERROR.getStatusCode()).body(
        new ExceptionDTO(GlobalExceptions.INTERNAL_ERROR.getErrorCode(),
            GlobalExceptions.INTERNAL_ERROR.getDescription(), null));

  }
}
