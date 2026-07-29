package com.example.eventplatform.event.dto;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

public class ResponseEvent {

  // @Getter가 없으면 Jackson 기본 가시성 규칙상 private 필드를 볼 수 없어 항상 빈 "{}"로
  // 직렬화된다 - 이 파일의 DTO 3종이 전부 이 버그를 갖고 있었다
  // (docs/refactoring-and-abstraction-review.md 참고).
  @Getter
  @Setter
  @NoArgsConstructor
  public static class ResponseCreateEvent {

    private long eventId;
    private String status;
  }

  @Getter
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

  @Getter
  @Setter
  @NoArgsConstructor
  public static class ResponseEventStock {

    private long eventId;
    private long remainingStock;
    private boolean soldOut;
  }
}
