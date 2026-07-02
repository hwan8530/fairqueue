package com.example.eventplatform.reservation.entity;

import java.util.Arrays;

public enum ReservationStatus {
  PENDING("PENDING"),
  CONFIRMED("CONFIRMED"),
  EXPIRED("EXPIRED"),
  CANCELED("CANCELED"),
  FAILED("FAILED");

  private String status;

  ReservationStatus(String status) {
    this.status = status;
  }

  String getStatus() {
    return status;
  }

  public static ReservationStatus fromStringStatus(String status) {
    return Arrays.stream(ReservationStatus.values())
        .filter(reservationStatus -> reservationStatus.getStatus().equals(status)).findFirst()
        .orElse(null);
  }
}
