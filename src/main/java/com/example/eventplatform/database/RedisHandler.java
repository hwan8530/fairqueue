package com.example.eventplatform.database;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.types.Expiration;

@RequiredArgsConstructor
public class RedisHandler {

  private final RedisTemplate<String, Object> redisTemplate;

  /*
   * SET에 접근하여 연산 수행
   */
  public SetOperations<String, Object> setOperation() {
    return this.redisTemplate.opsForSet();
  }

  /*
   * Value에 접근하여 데이터를 추가하고 TTL을 설정
   */
  public void setStringWithTtl(String key, String value, long timeout, TimeUnit unit) {
    Expiration expiration = Expiration.from(timeout, unit);
    redisTemplate.opsForValue().set(key, value, expiration);
  }

  public String getString(String key) {
    return (String) redisTemplate.opsForValue().get(key);
  }
}
