package com.example.eventplatform.database;

public enum JobRedisKey {
  SCHEDULED("job:scheduled");
  private String key;

  JobRedisKey(String key) {
    this.key = key;
  }

  public String getKey() {
    return key;
  }
}
