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

  @Setter
  @Getter
  @NoArgsConstructor
  public static class reservationDTO {

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
  public static class deleteReservationDTO {

    private long reservationId;
    private String status;
  }
}
