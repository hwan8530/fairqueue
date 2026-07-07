package com.example.eventplatform.job.service;

import com.example.eventplatform.database.RedisHandler;
import com.example.eventplatform.event.entity.Event;
import com.example.eventplatform.event.service.EventService;
import com.example.eventplatform.exception.GlobalCustomException;
import com.example.eventplatform.exception.GlobalExceptions;
import com.example.eventplatform.job.entity.Job;
import com.example.eventplatform.job.entity.JobType;
import com.example.eventplatform.job.repository.JobRepository;
import com.example.eventplatform.messagebroker.KafkaProducer;
import com.example.eventplatform.reservation.entity.Reservation;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class JobService {

  private final JobRepository jobRepository;
  private final ObjectMapper objectMapper;
  private final RedisHandler redisHandler;
  private final KafkaProducer producer;
  private final EventService eventService;

  @Transactional
  public void makeSchedule(String topic, Reservation reservation) {
    if (jobRepository.existsByIdempotency_key(reservation.getIdempotency_key())) {
      return;
    }
    Map<String, Object> payload = objectMapper.convertValue(reservation,
        new TypeReference<Map<String, Object>>() {
        });
    Job job = Job.builder().type(JobType.fromStringtoJobType(topic.toUpperCase()))
        .payload(payload).idempotency_key(
            reservation.getIdempotency_key()).build();
    jobRepository.save(job);
    String key = "job:schedule:" + job.getId();
    redisHandler.makeJobWithTtl(key, job.getNext_run_at());
  }

  // 반환타입은 아직 미정이라 void
  @Transactional
  public void confirmReservation(Reservation reservation) {
    Job job = jobRepository.findByIdempotency_key(reservation.getIdempotency_key())
        .orElseThrow(() -> new GlobalCustomException(
            GlobalExceptions.INTERNAL_ERROR));
  }

  @Transactional
  public void enQueue(long jobId) {
    Job job = jobRepository.findById(jobId)
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR));
    String key = "job:queue:" + job.getId();
    redisHandler.makeJobWithTtl(key, job.enQueueJob());
  }

  @Transactional
  public void start(long jobId) {
    // QUEUE 에서 next_run_at 까지 도달한 경우
    Job job = jobRepository.findById(jobId)
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR));

    // 실행 -> 가장 아래에 위치한 Event 차감 처리 시도 -> 실패시 재시도
    job.start();
    Long eventId = extractEventId(job.getPayload());
    if (eventId == null) {
      // eventId를 추출하지 못했다면 Fail 처리
      job.fail("can't extract eventId from payload");
      log.error("can't extract eventId from payload:{}", job.getPayload());
    }
    if (eventService.decreaseRemainingStock(eventId) == 0) {
      // 재고 부족 retry
      String key = "job:queue:" + job.getId();
      redisHandler.makeJobWithTtl(key, job.retry());
    } else {
      job.succeed();
    }
  }

  public Long extractEventId(Map<String, Object> payload) {
    Object rawData = payload.get("event");
    if (rawData != null) {
      Event event = objectMapper.convertValue(rawData, Event.class);
      return event.getId();
    }
    return null;
  }

}
