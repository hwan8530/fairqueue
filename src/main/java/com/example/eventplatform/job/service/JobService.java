package com.example.eventplatform.job.service;

import com.example.eventplatform.job.entity.Job;
import com.example.eventplatform.job.entity.JobType;
import com.example.eventplatform.job.repository.JobRepository;
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

  @Transactional
  public void confirm(String topic, Reservation reservation) {
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
  }

}
