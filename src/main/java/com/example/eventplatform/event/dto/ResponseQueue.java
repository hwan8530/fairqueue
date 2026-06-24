package com.example.eventplatform.event.dto;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Setter;

public class ResponseQueue {

  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ResponseQueueStatus {

    private long eventId;
    private long rank; // 부여받은 번호
    private long ahead; // redis.rank 값
    private boolean admitted;
    private String entryToken;
  }

}
