package com.example.eventplatform.messagebroker;

import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KafkaProducer {

  private final KafkaTemplate<String, Object> kafkaTemplate; // producer

  public CompletableFuture<?> sendMessageWithResult(String topic, Object message) {
    return kafkaTemplate.send(topic, message);
  }

  public void sendMessage(String topic, Object message) {
    kafkaTemplate.send(topic, message);
  }
}
