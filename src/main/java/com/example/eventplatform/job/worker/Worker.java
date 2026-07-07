package com.example.eventplatform.job.worker;

import com.example.eventplatform.exception.GlobalCustomException;
import com.example.eventplatform.exception.GlobalExceptions;
import com.example.eventplatform.job.service.JobService;
import com.example.eventplatform.reservation.entity.Reservation;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Worker { // 기술적인 영역만 component에서 처리

  private final JobService jobService;

  @KafkaListener(topics = "confirm_reservation", groupId = "reservation-worker")
  // Ack를 받아서 처리해야한다
  // CONFIRM은 수신 즉시 job도 만들고 필요한 후행 처리도 바로 진행
  public void confirmReservation(@Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
      Reservation reservation, Acknowledgment acknowledgment) {
    try {
      jobService.makeSchedule(topic, reservation);
      acknowledgment.acknowledge();
    } catch (Exception e) {
      // Message 수신 실패 등에 대한 예외처리 필요
      // 임시로 Global Exception throw 하도록 설계
      throw new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR);
    }
  }

  @KafkaListener(topics = "QUEUED", groupId = "queued-worker")
  public void enQueueReservation(Long jobId, Acknowledgment acknowledgment) {
    try {
      jobService.enQueue(jobId);
      acknowledgment.acknowledge();
    } catch (Exception e) {
      throw new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR);
    }
  }

}
