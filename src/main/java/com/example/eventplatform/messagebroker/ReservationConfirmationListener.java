package com.example.eventplatform.messagebroker;

import com.example.eventplatform.exception.GlobalCustomException;
import com.example.eventplatform.exception.GlobalExceptions;
import com.example.eventplatform.job.service.JobService;
import com.example.eventplatform.reservation.entity.Reservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/*
 * 예전 이름은 job.worker.Worker였는데, 실제로는 Job을 "실행"하지 않고 예약 확정 요청 Kafka
 * 메시지를 받아 Job 행을 만들기만 한다 - 이름/패키지가 "job.worker"라서 여기가 Job 실행부인
 * 것처럼 오해하기 쉬웠다. Job을 실제로 실행하는 코드는 JobExpireListener(Redis 알림 수신)와
 * JobService의 폴러/클레임 메서드들이다. 이 클래스는 메시지 브로커(Kafka)를 구독해 Job을
 * 큐에 등록하는 "수신부" 역할이라는 걸 이름과 위치로 드러내기 위해 messagebroker 패키지로
 * 옮기고 이름을 바꿨다 (docs/refactoring-and-abstraction-review.md C3 참고).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationConfirmationListener {

  /*
   * @KafkaListener는 애노테이션 속성이라 컴파일타임 상수만 허용되어 JobType.CONFIRM_RESERVATION
   * .getTopic()을 직접 쓸 수 없다. 이 상수는 그 값과 반드시 같아야 하고,
   * JobTypeTopicConsistencyTest가 그 일치를 테스트로 강제한다 (job/entity/JobType.java 참고).
   */
  public static final String CONFIRM_RESERVATION_TOPIC = "confirm_reservation";

  private final JobService jobService;

  // Kafka에서 예약 확정 요청 메시지를 받아 CONFIRM_RESERVATION Job을 등록한다.
  // 실제 확정 처리(결제 확정, issued_code 발급)는 이 시점이 아니라 Job이 나중에 실행될 때 일어난다.
  @KafkaListener(topics = CONFIRM_RESERVATION_TOPIC, groupId = "reservation-worker")
  public void onConfirmReservationRequested(@Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
      Reservation reservation, Acknowledgment acknowledgment) {
    try {
      jobService.makeSchedule(topic, reservation);
      acknowledgment.acknowledge();
    } catch (Exception e) {
      // 원인을 지운 채 INTERNAL_ERROR만 던지면 컨슈머 재시도 로그에서 실제 원인을 알 수 없다
      // (디버깅하다가 이 문제로 실제 원인을 찾는 데 시간이 걸렸다).
      log.error("CONFIRM_RESERVATION Job 등록 실패 (idempotencyKey={})",
          reservation.getIdempotency_key(), e);
      throw new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR);
    }
  }

}
