package com.example.eventplatform.event.entity;

import java.util.Arrays;

public enum EventStatus {
  SCHEDULED("SCHEDULED"),
  OPEN("OPEN"),
  CLOSED("CLOSED"),
  SOLD_OUT("SOLD_OUT");
  private String status;

  EventStatus(String status) {
    this.status = status;
  }

  public String getStatus() {
    return status;
  }

  public static EventStatus fromStringStatus(String status) {
    return Arrays.stream(EventStatus.values())
        .filter(eventStatus -> eventStatus.getStatus().equals(status))
        .findFirst().orElse(null);
  }
}
