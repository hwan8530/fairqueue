package com.example.eventplatform.event.dto;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Setter;

public class ResponseEvent {

  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ResponseCreateEvent {

    private Long eventId;
    private String status;
  }
}
