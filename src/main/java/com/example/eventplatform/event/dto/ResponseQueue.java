package com.example.eventplatform.event.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

public class ResponseQueue {

  // @Getter가 없으면 Jackson이 private 필드를 못 봐서 항상 빈 "{}"로 직렬화된다
  // (docs/refactoring-and-abstraction-review.md 참고).
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ResponseQueueStatus {

    private long eventId;
    private long rank; // 부여받은 번호
    private long ahead; // redis.rank 값
    private boolean admitted;
    private String entryToken;
    private long entryTokenTtlSec;
  }

}
