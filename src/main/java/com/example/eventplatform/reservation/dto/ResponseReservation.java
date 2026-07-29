package com.example.eventplatform.reservation.dto;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ResponseReservation<T> {

  int status;
  T data;

  // status/data를 매번 new + setter 3줄로 채우는 보일러플레이트를 줄이기 위한 정적 팩토리.
  public static <T> ResponseReservation<T> of(int status, T data) {
    ResponseReservation<T> response = new ResponseReservation<>();
    response.setStatus(status);
    response.setData(data);
    return response;
  }

  @Setter
  @Getter
  @NoArgsConstructor
  public static class ReservationDTO {

    private long reservationId;
    private String status;
    private String issuedCode;
    private LocalDateTime createdAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime expiresAt;
  }

  @Setter
  @Getter
  @NoArgsConstructor
  public static class DeleteReservationDTO {

    private long reservationId;
    private String status;
  }
}
