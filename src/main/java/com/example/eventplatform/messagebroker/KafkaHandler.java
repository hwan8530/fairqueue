package com.example.eventplatform.messagebroker;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KafkaHandler {

  private final KafkaTemplate<String, Object> kafkaTemplate; // producer
}
