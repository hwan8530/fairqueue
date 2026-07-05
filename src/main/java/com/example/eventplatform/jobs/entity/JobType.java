package com.example.eventplatform.jobs.entity;

public enum JobType {
  CONFIRMED_RESERVATION("CONFIRMED_RESERVATION"),
  EXPIRED_RESERVATION("EXPIRED_RESERVATION"),
  SEND_NOTIFICATION("SEND_NOTIFICATION");
  private final String detail;

  private JobType(String detail) {
    this.detail = detail;
  }

  public String getDetail() {
    return detail;
  }
}
