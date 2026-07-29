package com.example.eventplatform.messagebroker;

import com.example.eventplatform.database.JobRedisKey;
import com.example.eventplatform.job.service.JobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobExpireListener implements MessageListener {

  private final JobService jobService; // 비즈니스 로직을 처리할 서비스

  @Override
  public void onMessage(Message message, byte[] pattern) {
    // 만료된 Redis Key 값 ex) "job:schedule:123"
    String expiredKey = message.toString();

    log.info("Redis Job Expired 이벤트 수신: {}", expiredKey);
    // key 별 동작 분기 처리 (접두사는 JobRedisKey 에서만 정의 - 문자열 리터럴로 직접 비교/파싱하지 않는다)
    if (JobRedisKey.SCHEDULE.matches(expiredKey)) {
      jobService.enQueue(JobRedisKey.SCHEDULE.extractJobId(expiredKey));
    } else if (JobRedisKey.QUEUE.matches(expiredKey)) {
      jobService.start(JobRedisKey.QUEUE.extractJobId(expiredKey));
    }
  }
}