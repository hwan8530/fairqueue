package com.example.eventplatform.job.entity;

import java.util.Arrays;

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

  public static JobType fromStringtoJobType(String type) {
    return Arrays.stream(JobType.values()).filter(jobType -> jobType.getDetail().equals(type))
        .findFirst().orElse(null);
  }
}
