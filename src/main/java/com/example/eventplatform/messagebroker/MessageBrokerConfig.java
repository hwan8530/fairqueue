package com.example.eventplatform.messagebroker;

import com.example.eventplatform.reservation.entity.Reservation;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/*
 * KEY_(DE)SERIALIZER_CLASS_CONFIG는 반드시 org.apache.kafka.common.serialization.Serializer/
 * Deserializer를 구현한 클래스여야 한다. 예전엔 이름이 같은 tools.jackson(Jackson 직렬화용
 * 클래스, Kafka Serializer가 아님)이 잘못 임포트돼 있어서 KafkaProducer/Consumer 생성 자체가
 * "class ... is not an instance of org.apache.kafka.common.serialization.Serializer"로
 * 실패하고 있었다 - 예약 생성 시 Kafka 발행이 항상 실패했고(500), 그래서 CONFIRM_RESERVATION
 * Job도 만들어진 적이 없었다 (docs/refactoring-and-abstraction-review.md 참고).
 */
@Configuration
public class MessageBrokerConfig {

  @Bean
  public ProducerFactory<String, Object> producerFactory(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    Map<String, Object> config = new HashMap<>();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
    return new DefaultKafkaProducerFactory<>(config);
  }

  @Bean
  public KafkaTemplate<String, Object> kafkaTemplate(
      ProducerFactory<String, Object> producerFactory) {
    return new KafkaTemplate<>(producerFactory);
  }

  /*
   * VALUE_DEFAULT_TYPE/TRUSTED_PACKAGES를 안 주면 JacksonJsonDeserializer가 타입 정보를
   * 신뢰할 수 없어(또는 헤더가 기대와 달라) 역직렬화를 String으로 폴백해버리고, 그 결과를
   * 리스너의 Reservation 파라미터에 바인딩하는 단계에서
   * "Cannot convert from [String] to [Reservation]"으로 항상 실패했다 - CONFIRM_RESERVATION
   * Job이 한 번도 만들어진 적이 없었던 이유 중 하나다. 이 토픽은 Reservation만 실어 나르므로
   * 타입을 명시적으로 고정한다 (docs/refactoring-and-abstraction-review.md 참고).
   *
   * ENABLE_AUTO_COMMIT/AUTO_OFFSET_RESET도 이 Map에 직접 넣는다 - 이 팩토리는
   * spring.kafka.consumer.* 프로퍼티를 전혀 읽지 않는 완전히 커스텀한 빈이라서,
   * application.yaml에 그 프로퍼티들을 적어놔도 조용히 무시되고 있었다.
   */
  @Bean
  public ConsumerFactory<String, Object> consumerFactory(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class);
    config.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, Reservation.class.getName());
    config.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.example.eventplatform.*");
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    return new DefaultKafkaConsumerFactory<>(config);
  }

  /*
   * @KafkaListener는 기본적으로 "kafkaListenerContainerFactory"라는 이름의 빈을 찾는데,
   * 이 클래스가 그 빈을 정의하지 않으면 Spring Boot가 spring.kafka.consumer.* 프로퍼티만으로
   * 자기 것을 자동구성해서 대신 쓴다 - 그 자동구성판은 value-deserializer가 기본값
   * StringDeserializer라서, 위에서 공들여 설정한 consumerFactory(JacksonJsonDeserializer)가
   * 실제로는 전혀 쓰이지 않고 있었다. 실제 로그로 확인(value.deserializer =
   * org.apache.kafka.common.serialization.StringDeserializer)한 뒤 이 빈을 명시적으로
   * 만들어 연결했다. ack-mode도 프로퍼티에 맡기지 않고 여기서 직접 고정한다.
   */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
      ConsumerFactory<String, Object> consumerFactory) {
    ConcurrentKafkaListenerContainerFactory<String, Object> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE);
    return factory;
  }

  @Bean
  public RedisMessageListenerContainer redisMessageListenerContainer(
      RedisConnectionFactory connectionFactory,
      JobExpireListener jobExpirationListener // 리스너 빈 주입
  ) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);

    // Redis의 내부 만료 이벤트 토픽 양식: __keyevent@*__:expired
    // 모든 데이터베이스(*)에서 발생한 만료(expired) 이벤트를 감지하겠다는 의미입니다.
    container.addMessageListener(jobExpirationListener, new PatternTopic("__keyevent@*__:expired"));

    return container;
  }
}
