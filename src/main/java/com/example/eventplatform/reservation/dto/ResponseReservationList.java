package com.example.eventplatform.reservation.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class ResponseReservationList {

  List<item> items;

  @Getter
  @Setter
  @AllArgsConstructor
  public static class item {

    long reservationId;
    long eventId;
    String status;
  }
}
