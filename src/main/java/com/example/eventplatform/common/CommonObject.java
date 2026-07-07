package com.example.eventplatform.common;

import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

public class CommonObject {

  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}
