package com.example.eventplatform.database;

import lombok.Getter;

@Getter
public enum EventRedisKey {
  WAITING("waiting:"), // 각 EVENT 별 대기열 - ZSET
  ENTRY_TOKEN("entry_token:"), // 각 EVENT 별 ENTRY_TOKEN 인원 - VALUE / TTL : 30s
  ACTIVE_EVENTS("active:events"), // 현재 OPEN 상태인 EVENT - SET
  WAITING_IDENTIFY("waiting:identify:"),
  REMAINING_STOCK("remaining_stock:"),
  ALLOWED("allowed:");

  private String prefix;

  private EventRedisKey(String prefix) {
    this.prefix = prefix;
  }

  public String generateKeyNoParam(long eventId) {
    return prefix + eventId;
  }

  public String generateKey(long eventId, String key) {
    return prefix + eventId + ":" + key;
  }

  public String generateKeyWithParams(long eventId, String key, String value) {
    return prefix + eventId + ":" + key + ":" + value;
  }
}
