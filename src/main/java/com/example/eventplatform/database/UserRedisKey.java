package com.example.eventplatform.database;

import lombok.Getter;

/**
 * 사용자 관련 Redis 키 규칙. docs/redis-key-convention.md 참고.
 * 반드시 이 enum의 메서드를 통해서만 키를 생성한다 (문자열 리터럴 직접 조립 금지).
 */
@Getter
public enum UserRedisKey {
  REFRESH_TOKEN("refresh_token:"); // username별 refresh token 저장 - STRING / TTL: jwt.refresh_token.valid_time

  private final String prefix;

  UserRedisKey(String prefix) {
    this.prefix = prefix;
  }

  public String generateKey(String username) {
    return prefix + username;
  }
}
