package com.example.eventplatform.job.entity;

/*
 * 각 JobType이 어떤 Kafka 토픽에서 발행/구독되는지를 여기 한 곳에 묶어둔다. 예전에는
 * "confirm_reservation" 문자열이 발행부(ReservationService), 구독 애노테이션
 * (ReservationConfirmationListener), 파싱 로직(JobService.makeSchedule의
 * JobType.valueOf(topic.toUpperCase())) 세 곳에서 각자 손으로 일치시켜야 했다 - 이 프로젝트가
 * Redis 키 하나("job:queue:" vs "job:queued:")에서 이미 겪은 것과 똑같은 종류의 드리프트 위험이다.
 *
 * 다만 @KafkaListener(topics = ...)는 애노테이션이라 컴파일타임 상수만 허용되어 이 enum을
 * 직접 참조할 수 없다 - ReservationConfirmationListener.CONFIRM_RESERVATION_TOPIC 상수가
 * 그 자리에 남아있고, JobTypeTopicConsistencyTest가 두 값이 항상 같은지를 테스트로 강제한다.
 */
public enum JobType {
  CONFIRM_RESERVATION("confirm_reservation"),
  EXPIRE_RESERVATION("expire_reservation"),
  SEND_NOTIFICATION("send_notification");

  private final String topic;

  JobType(String topic) {
    this.topic = topic;
  }

  public String getTopic() {
    return topic;
  }

  public static JobType fromTopic(String topic) {
    for (JobType type : values()) {
      if (type.topic.equals(topic)) {
        return type;
      }
    }
    throw new IllegalArgumentException("지원하지 않는 Kafka 토픽: " + topic);
  }
}
