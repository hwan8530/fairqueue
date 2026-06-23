package com.example.eventplatform.exception;

import lombok.Getter;

@Getter
public enum GlobalExceptions {
  SOLD_OUT("E1001", 409, "SOLD_OUT - 재고소진"),
  ALREADY_RESERVED("E1002", 409, "ALREADY_RESERVED - 1인 한도 초과"),
  EVENT_NOT_OPEN("E1003", 409, "EVENT_NOT_OPEN - 오픈 전/마감됨"),
  INVALID_ENTRY_TOKEN("E1004", 403, "INVALID_ENTRY_TOKEN - 입장 토큰 없음/만료"),
  QUEUE_NOT_ADMITTED("E1005", 403, "QUEUE_NOT_ADMITTED - 아직 입장 불가"),
  QUEUE_ENTRY_NOT_FOUND("E1006", 404, "QUEUE_ENTRY_NOT_FOUND - 대기열에 없음"),
  RESERVATION_NOT_FOUND("E2001", 404, "RESERVATION_NOT_FOUND"),
  RESERVATION_NOT_CANCELLABLE("E2002", 409, "RESERVATION_NOT_CANCELLABLE - 취소 불가 상태"),
  VALIDATION_FAILED("E4001", 400, "VALIDATION_FAILED"),
  DUPLICATE_USER("E4002", 409, "DUPLICATE_USER"),
  AUTH_FAILED("E4003", 401, "AUTH_FAILED"),
  TOKEN_INVALID_OR_EXPIRED("E4004", 401, "TOKEN_INVALID_OR_EXPIRED"),
  FORBIDDEN("E4005", 403, "FORBIDDEN - 권한 없음"),
  INTERNAL_ERROR("E5001", 500, "INTERNAL_ERROR");


  private final String errorCode;
  private final int statusCode;
  private final String description;

  GlobalExceptions(String errorCode, int statusCode, String description) {
    this.errorCode = errorCode;
    this.statusCode = statusCode;
    this.description = description;
  }
}
