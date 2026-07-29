package com.example.eventplatform.job.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.eventplatform.messagebroker.ReservationConfirmationListener;
import org.junit.jupiter.api.Test;

/*
 * @KafkaListener(topics = ...)는 애노테이션 속성이라 컴파일타임 상수만 허용되어
 * JobType.CONFIRM_RESERVATION.getTopic()을 ReservationConfirmationListener에서 직접 참조할
 * 수 없다. 그래서 ReservationConfirmationListener.CONFIRM_RESERVATION_TOPIC이라는 별도
 * 리터럴이 남아있는데, 이 테스트가 그 값과 JobType의 토픽 이름이 항상 같은지를 강제해서 두 값이
 * 조용히 어긋나는(드리프트) 걸 막는다.
 */
class JobTypeTopicConsistencyTest {

  @Test
  void 리스너_토픽_상수는_JobType의_토픽과_같아야_한다() {
    assertThat(ReservationConfirmationListener.CONFIRM_RESERVATION_TOPIC)
        .isEqualTo(JobType.CONFIRM_RESERVATION.getTopic());
  }

  @Test
  void fromTopic은_토픽_문자열로_JobType을_정확히_복원한다() {
    assertThat(JobType.fromTopic(JobType.CONFIRM_RESERVATION.getTopic()))
        .isEqualTo(JobType.CONFIRM_RESERVATION);
  }
}
