package com.example.eventplatform.reservation.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class ResponseReservationList {

  List<Item> items;

  @Getter
  @Setter
  @AllArgsConstructor
  public static class Item {

    long reservationId;
    long eventId;
    String status;
  }
}
