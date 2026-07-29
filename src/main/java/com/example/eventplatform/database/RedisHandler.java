package com.example.eventplatform.database;

import com.example.eventplatform.event.dto.QueueStruct;
import com.example.eventplatform.exception.GlobalCustomException;
import com.example.eventplatform.exception.GlobalExceptions;
import com.example.eventplatform.security.JwtUtil;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@Slf4j
public class RedisHandler {

  private final RedisTemplate<String, Object> redisTemplate;
  private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
  private final JwtUtil jwtUtil;
  private final RedisScript<Long> decrementRedisScript;

  /*
   * SET에 접근하여 연산 수행
   */
  public SetOperations<String, Object> setOperation() {
    return this.redisTemplate.opsForSet();
  }

  // value 직렬화기가 StringRedisSerializer이므로(비-String을 넘기면 ClassCastException) 문자열로 저장한다.
  @Async("redisAsyncExecutor")
  public void putSet(String key, long value) {
    redisTemplate.opsForSet().add(key, String.valueOf(value));
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
      // add 직후 rank를 다시 읽는 사이, moveQueueToAllow 스케줄러가 그새 popMin으로 이 유저를
      // 먼저 admit(pop)해버리면 rank가 null이 될 수 있다 - 실제로 통합 테스트에서 재현된
      // 레이스다 (docs/refactoring-and-abstraction-review.md 참고). null이면 이미 입장
      // 처리된 것이므로 순번을 0으로 취급한다.
      Long rankAfterAdd = zSetOps.rank(EventRedisKey.WAITING.generateKeyNoParam(eventId),
          username);
      long nextIdentifier = (rankAfterAdd != null ? rankAfterAdd : 0L) + 1;
      // 순번 기억용 K-V 추가 (value 직렬화기가 StringRedisSerializer라 문자열로 저장해야 한다)
      zValueOps.set(EventRedisKey.WAITING_IDENTIFY.generateKey(eventId, username),
          String.valueOf(nextIdentifier));
    }
    long identifier = Long.parseLong((String) Optional.ofNullable(
            zValueOps.get(EventRedisKey.WAITING_IDENTIFY.generateKey(eventId, username)))
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR)));
    return new QueueStruct(identifier,
        zSetOps.rank(EventRedisKey.WAITING.generateKeyNoParam(eventId), username), null, 0);
  }

  /*
   * 대기열 상태 조회. Redis 커맨드 몇 번만 순서대로 실행하는 짧은 동기 호출이라 별도 스레드로
   * 넘길 이유가 없다 (예전에는 @Async였는데, 호출부인 EventService.queueStatus()가 즉시
   * .get()으로 블로킹해서 실제 이득 없이 스레드 전환 비용만 내고 있었다. 자세한 배경은
   * docs/async-blocking-fix.md 참고).
   */
  public QueueStruct queueStatus(long eventId, String username) {
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
    long identifier = Long.parseLong((String) Optional.ofNullable(
            zValueOps.get(EventRedisKey.WAITING_IDENTIFY.generateKey(eventId, username)))
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.QUEUE_ENTRY_NOT_FOUND)));

    // 3. ENTRY_TOKEN 확인
    String entryToken = (String) zValueOps.get(
        EventRedisKey.ENTRY_TOKEN.generateKey(eventId, username));
    Long remainingTime = redisTemplate.getExpire(
        EventRedisKey.ENTRY_TOKEN.generateKey(eventId, username), TimeUnit.SECONDS);
    return new QueueStruct(identifier, rank, entryToken, remainingTime);
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
      // ENTRY_TOKEN을 먼저 저장한 뒤에 ALLOWED에 추가한다. 순서가 반대였을 때는(예전 코드)
      // "ALLOWED 등록 -> ENTRY_TOKEN 저장" 사이의 짧은 순간에 큐 상태 조회가 끼어들면
      // admitted=true인데 entryToken=null인 상태를 실제로 관찰할 수 있었다 - 통합 테스트로
      // 재현해서 발견했다 (docs/refactoring-and-abstraction-review.md 참고). 클라이언트가
      // admitted=true를 봤을 때 entryToken도 항상 같이 준비돼 있도록 순서를 바꿨다.
      zValueOps.set(EventRedisKey.ENTRY_TOKEN.generateKey(eventId, username),
          jwtUtil.makeEntryToken(username, new Date(System.currentTimeMillis()), ttl),
          Duration.ofSeconds(ttl)); // entry_token:{eventId}:username TTL: 30seconds
      zSetOps.add(EventRedisKey.ALLOWED.generateKeyNoParam(eventId), username,
          System.currentTimeMillis() + ttl * 1000); // ttl 은 초단위니까 밀리초 단위로 맞춰주기 위한 연산
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
    return redisAction != null && redisAction.toString().equals(entryToken);
  }

  /*
   * redisTemplate의 value 직렬화기가 StringRedisSerializer라서 String이 아닌 값을 넘기면
   * (예전에는 long을 그대로 넘겨서) ClassCastException(Long -> String)이 난다. decrementEventStock의
   * Lua 스크립트가 tonumber(GET stockKey)로 읽으므로, 여기서도 반드시 문자열로 저장해야 한다.
   */
  public void createEventStock(long eventId, long stock) {
    redisTemplate.opsForValue()
        .set(EventRedisKey.REMAINING_STOCK.generateKeyNoParam(eventId), String.valueOf(stock));
  }

  /*
  return : 0 - SOLD_OUT
           1 - Success
   */
  public Long decrementEventStock(long eventId, long stock) {
    String key = EventRedisKey.REMAINING_STOCK.generateKeyNoParam(eventId);
    Long result = redisTemplate.execute(decrementRedisScript, Collections.singletonList(key),
        String.valueOf(stock));

    if (result == null || result == -1) {
      log.debug("Event remaining is null. eventId:{}", eventId);
      throw new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR);
    }
    return result;
  }

  /*
  취소/만료/실패로 인한 재고 복구용. decrementEventStock과 대칭되는 연산.
   */
  public void incrementEventStock(long eventId, long amount) {
    redisTemplate.opsForValue()
        .increment(EventRedisKey.REMAINING_STOCK.generateKeyNoParam(eventId), amount);
  }

  public void makeJobWithTtl(String key, LocalDateTime next_run_at) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    valueOps.set(key, "", Duration.between(LocalDateTime.now(), next_run_at));
  }
}
