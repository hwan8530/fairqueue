package com.example.eventplatform.database;

import com.example.eventplatform.event.entity.QueueStruct;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.scheduling.annotation.Async;

@RequiredArgsConstructor
public class RedisHandler {

  private final RedisTemplate<String, Object> redisTemplate;
  private static final String ENTRY_TOKEN_KEY = "entry_token:";

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
    redisTemplate.opsForValue().set(key, value, Expiration.from(timeout, unit));
  }

  /*
   * Key에 해당하는 Value 리턴
   * String 형태임을 가정한 메소드
   */
  public String getString(String key) {
    return (String) redisTemplate.opsForValue().get(key);
  }

  /*
   * 대기열(Queue)에 삽입 메소드 (ZSET KEY SCORE VALUE)
   * @Params
   * - key : Queue 이름 (waiting:{eventId})
   * - value : 사용자 이름
   * @Note
   * - SCORE는 입력 당시의 타임스탬프를 넣도록 함
   * @Return
   * - 대기열 순번
   */
  @Async("redisAsyncExecutor")
  public CompletableFuture<QueueStruct> enQueue(String key, String value) {
    ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
    ValueOperations<String, Object> zValueOps = redisTemplate.opsForValue();
    // Queue 내에 존재하는지 확인
    String identicalKey = key + ":" + value; // waiting:{eventId}:username
    if (zSetOps.score(key, value) == null) {
      zSetOps.add(key, value, System.currentTimeMillis());
      zValueOps.set(identicalKey, zSetOps.rank(key, value) + 1);
    }
    return CompletableFuture.completedFuture(new QueueStruct((long) zValueOps.get(identicalKey),
        zSetOps.rank(key, value), null)); // rank starts 0
  }

  @Async("redisAsyncExecutor")
  public CompletableFuture<QueueStruct> queueStatus(String key, String value) {
    ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
    ValueOperations<String, Object> zValueOps = redisTemplate.opsForValue();
    String identicalKey = key + ":" + value; // waiting:{eventId}:username
    String entryKey = ENTRY_TOKEN_KEY + key.split(":")[1];
    String entryToken = getString(entryKey);
    return CompletableFuture.completedFuture(new QueueStruct((long) zValueOps.get(identicalKey),
        zSetOps.rank(key, value), entryToken));
  }
}
