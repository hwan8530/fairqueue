package com.example.eventplatform.messagebroker;

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
    // key 별 동작 분기 처리
    if (expiredKey.startsWith("job:schedule:")) {
      // 키에서 Job ID만 추출합니다.
      String jobIdStr = expiredKey.replace("job:schedule:", "");
      Long jobId = Long.parseLong(jobIdStr);
      jobService.enQueue(jobId);
    } else if (expiredKey.startsWith("job:queued:")) {
      String jobIdStr = expiredKey.replace("job:queued:", "");
      Long jobId = Long.parseLong(jobIdStr);
      jobService.start(jobId);
    }
  }
}