package com.example.eventplatform.event.entity;

import java.util.Arrays;

public enum EventType {
  COUPON("COUPON"),
  TICKET("TICKET");

  private String type;

  EventType(String type) {
    this.type = type;
  }

  String getType() {
    return type;
  }

  public static EventType fromStringType(String type) {
    return Arrays.stream(EventType.values()).filter(eventType -> eventType.getType().equals(type))
        .findFirst().orElse(null);
  }
}
