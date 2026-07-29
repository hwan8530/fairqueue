package com.example.eventplatform.database;

import lombok.Getter;

/**
 * Job 상태 전이를 트리거하는 Redis 키 규칙. 상세 규칙/전체 키 목록은 docs/redis-key-convention.md 참고.
 * 반드시 이 enum의 메서드를 통해서만 키를 생성/판별/파싱한다 (문자열 리터럴 직접 조립 금지).
 */
@Getter
public enum JobRedisKey {
  SCHEDULE("job:schedule:"), // SCHEDULED -> QUEUED 전이 트리거. TTL = next_run_at 까지, value 없음
  QUEUE("job:queue:");       // QUEUED -> RUNNING 전이 트리거. TTL = next_run_at 까지, value 없음

  private final String prefix;

  JobRedisKey(String prefix) {
    this.prefix = prefix;
  }

  public String generateKeyNoParam(long jobId) {
    return prefix + jobId;
  }

  public boolean matches(String rawKey) {
    return rawKey.startsWith(prefix);
  }

  public long extractJobId(String rawKey) {
    return Long.parseLong(rawKey.substring(prefix.length()));
  }
}