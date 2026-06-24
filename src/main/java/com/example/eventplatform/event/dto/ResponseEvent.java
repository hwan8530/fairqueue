package com.example.eventplatform.event.dto;

import java.time.LocalDateTime;
import lombok.NoArgsConstructor;
import lombok.Setter;

public class ResponseEvent {

  @Setter
  @NoArgsConstructor
  public static class ResponseCreateEvent {

    private long eventId;
    private String status;
  }

  @Setter
  @NoArgsConstructor
  public static class ResponseEventDetail {

    private long eventId;
    private String name;
    private String type;
    private String status;
    private long totalStock;
    private long remainingStock;
    private LocalDateTime openAt;
    private LocalDateTime closeAt;
  }

  @Setter
  @NoArgsConstructor
  public static class ResponseEventStock {

    private long eventId;
    private long remainingStock;
    private boolean soldOut;
  }
}
