package com.example.eventplatform.job.service;

import com.example.eventplatform.database.JobRedisKey;
import com.example.eventplatform.database.RedisHandler;
import com.example.eventplatform.event.entity.EventType;
import com.example.eventplatform.exception.GlobalCustomException;
import com.example.eventplatform.exception.GlobalExceptions;
import com.example.eventplatform.job.entity.Job;
import com.example.eventplatform.job.entity.JobStatus;
import com.example.eventplatform.job.entity.JobType;
import com.example.eventplatform.job.repository.JobRepository;
import com.example.eventplatform.reservation.entity.Reservation;
import com.example.eventplatform.reservation.entity.ReservationStatus;
import com.example.eventplatform.reservation.repository.ReservationRepository;
import java.util.Map;
import java.util.UUID;
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
  private final ReservationRepository reservationRepository;

  @Transactional
  public void makeSchedule(String topic, Reservation reservation) {
    if (jobRepository.existsByIdempotency_key(reservation.getIdempotency_key())) {
      return;
    }
    Map<String, Object> payload = objectMapper.convertValue(reservation,
        new TypeReference<Map<String, Object>>() {
        });
    Job job = Job.builder().type(JobType.valueOf(topic.toUpperCase()))
        .payload(payload).idempotency_key(
            reservation.getIdempotency_key()).build();
    jobRepository.save(job);
    String key = JobRedisKey.SCHEDULE.generateKeyNoParam(job.getId());
    redisHandler.makeJobWithTtl(key, job.getNext_run_at());
  }

  @Transactional
  public void enQueue(long jobId) {
    Job job = jobRepository.findById(jobId)
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR));
    String key = JobRedisKey.QUEUE.generateKeyNoParam(job.getId());
    redisHandler.makeJobWithTtl(key, job.enQueueJob());
  }

  @Transactional
  public void start(long jobId) {
    // QUEUE 에서 next_run_at 까지 도달한 경우
    Job job = jobRepository.findById(jobId)
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR));

    job.start();
    if (job.getStatus() == JobStatus.FAILED) {
      // max_attempts 초과로 이미 FAILED 처리됨 - 더 이상 진행하지 않는다
      return;
    }

    try {
      dispatch(job);
      job.succeed();
    } catch (Exception e) {
      log.error("Job {} 처리 실패 (attempt {}): {}", job.getId(), job.getAttempts(),
          e.getMessage());
      String key = JobRedisKey.QUEUE.generateKeyNoParam(job.getId());
      redisHandler.makeJobWithTtl(key, job.retry());
    }
  }

  private void dispatch(Job job) {
    if (job.getType() == JobType.CONFIRM_RESERVATION) {
      confirmReservation(job);
    } else {
      throw new IllegalStateException("Unsupported job type: " + job.getType());
    }
  }

  private void confirmReservation(Job job) {
    Reservation reservation = reservationRepository.findByIdempotencyKey(job.getIdempotency_key())
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR));

    if (reservation.getStatus() != ReservationStatus.PENDING) {
      // 이미 CONFIRMED/CANCELLED/EXPIRED 등 종료 상태로 처리됨 -> 멱등 처리 (부수효과 없이 성공 처리)
      return;
    }
    reservation.confirm(generateIssuedCode(reservation.getEvent().getType()));
  }

  private String generateIssuedCode(EventType type) {
    String prefix = type == EventType.COUPON ? "CPN" : "TKT";
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    return prefix + "-" + suffix;
  }

}
