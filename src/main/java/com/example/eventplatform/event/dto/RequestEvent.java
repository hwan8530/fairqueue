package com.example.eventplatform.event.dto;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class RequestEvent {

  @Getter
  @NoArgsConstructor
  public static class RequestCreateEvent {

    private String name;
    private String type;
    private int totalStock;
    private int perUserLimit;
    private LocalDateTime openAt;
    private LocalDateTime closeAt;
  }
}
