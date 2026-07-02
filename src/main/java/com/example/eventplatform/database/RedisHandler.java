package com.example.eventplatform.database;

import com.example.eventplatform.event.entity.QueueStruct;
import com.example.eventplatform.exception.GlobalCustomException;
import com.example.eventplatform.exception.GlobalExceptions;
import com.example.eventplatform.security.JwtUtil;
import java.time.Duration;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.scheduling.annotation.Async;

@RequiredArgsConstructor
public class RedisHandler {

  private final RedisTemplate<String, Object> redisTemplate;
  private final JwtUtil jwtUtil;

  /*
   * SET에 접근하여 연산 수행
   */
  public SetOperations<String, Object> setOperation() {
    return this.redisTemplate.opsForSet();
  }

  @Async("redisAsyncExecutor")
  public void putSet(String key, long value) {
    redisTemplate.opsForSet().add(key, value);
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

  public QueueStruct enQueueWaiting(long eventId, String username) {
    ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
    ValueOperations<String, Object> zValueOps = redisTemplate.opsForValue();

    if (zSetOps.rank(EventRedisKey.WAITING.generateKeyNoParam(eventId), username) == null) {
      // WAITING QUEUE 에 없으므로 추가
      zSetOps.add(EventRedisKey.WAITING.generateKeyNoParam(eventId), username,
          System.currentTimeMillis());
      // 순번 기억용 K-V 추가
      zValueOps.set(EventRedisKey.WAITING_IDENTIFY.generateKey(eventId, username),
          zSetOps.rank(EventRedisKey.WAITING.generateKeyNoParam(eventId), username) + 1);
    }
    long identifier = (long) Optional.ofNullable(
            zValueOps.get(EventRedisKey.WAITING_IDENTIFY.generateKey(eventId, username)))
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR));
    return new QueueStruct(identifier,
        zSetOps.rank(EventRedisKey.WAITING.generateKeyNoParam(eventId), username), null, 0);
  }

  @Async("redisAsyncExecutor")
  public CompletableFuture<QueueStruct> queueStatus(long eventId, String username) {
    ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
    ValueOperations<String, Object> zValueOps = redisTemplate.opsForValue();

    Long rank = zSetOps.rank(EventRedisKey.WAITING.generateKeyNoParam(eventId), username);
    // 1. WAITING or ALLOWED 에 있는지 확인
    if (rank == null) {
      // ALLOWED 에서 확인
      if (zSetOps.rank(EventRedisKey.ALLOWED.generateKeyNoParam(eventId), username) == null) {
        throw new GlobalCustomException(GlobalExceptions.QUEUE_ENTRY_NOT_FOUND);
      }
      rank = 0L;
    }

    // 2. IDENTIFY get
    long identifier = (long) Optional.ofNullable(
            zValueOps.get(EventRedisKey.WAITING_IDENTIFY.generateKey(eventId, username)))
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.QUEUE_ENTRY_NOT_FOUND));

    // 3. ENTRY_TOKEN 확인
    String entryToken = (String) zValueOps.get(
        EventRedisKey.ENTRY_TOKEN.generateKey(eventId, username));
    Long remainingTime = redisTemplate.getExpire(
        EventRedisKey.ENTRY_TOKEN.generateKey(eventId, username), TimeUnit.SECONDS);
    return CompletableFuture.completedFuture(
        new QueueStruct(identifier, rank, entryToken, remainingTime));
  }

  /*
   * 대기열(ZSET)에서 allowCount 만큼 pop 하고 해당 인원들의 entryToken을 생성하는 메소드
   * @Params
   * - key : redis Key ex)waiting:{eventID}
   * - allowCount : 초당 허용 건수
   * 부모 메소드에서 Async 어노테이션 선언
   */
  public void popQueueAndGenerateEntryToken(long eventId, int allowCount, long ttl) {
    ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
    ValueOperations<String, Object> zValueOps = redisTemplate.opsForValue();
    Set<ZSetOperations.TypedTuple<Object>> popedQueue = zSetOps.popMin(
        EventRedisKey.WAITING.generateKeyNoParam(eventId), allowCount);
    for (TypedTuple<?> o : popedQueue) {
      String username = Objects.requireNonNull(o.getValue()).toString();
      zSetOps.add(EventRedisKey.ALLOWED.generateKeyNoParam(eventId), username,
          System.currentTimeMillis() + ttl * 1000); // ttl 은 초단위니까 밀리초 단위로 맞춰주기 위한 연산
      zValueOps.set(EventRedisKey.ENTRY_TOKEN.generateKey(eventId, username),
          jwtUtil.makeEntryToken(username, new Date(System.currentTimeMillis()), ttl),
          Duration.ofSeconds(ttl)); // entry_token:{eventId}:username TTL: 30seconds
      zValueOps.getAndExpire(EventRedisKey.WAITING_IDENTIFY.generateKey(eventId, username),
          Duration.ofSeconds(ttl));
    }
  }

  /*
  일정주기로 ALLOWED 삭제
  WAITING_IDENTITY는 ALLOWED 에 넣을 때 TTL 설정하도록 했으니 여기선 ALLOWED만 삭제
   */
  public void removeAllowed(long eventId) {
    ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
    zSetOps.removeRangeByScore(EventRedisKey.ALLOWED.generateKeyNoParam(eventId),
        Double.NEGATIVE_INFINITY, System.currentTimeMillis());
  }

  public boolean entryKeyAvailable(long eventId, String username, String entryToken) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    Object redisAction = valueOps.get(EventRedisKey.ENTRY_TOKEN.generateKey(eventId, username));
    if (redisAction == null || !redisAction.toString().equals(entryToken)) {
      return false;
    }
    return true;
  }
}
