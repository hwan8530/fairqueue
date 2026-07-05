package com.example.eventplatform.jobs.entity;

public enum JobStatus {
  SCHEDULED("SCHEDULED"),
  QUEUED("QUEUED"),
  RUNNING("RUNNING"),
  SUCCEEDED("SUCCEEDED"),
  FAILED("FAILED"),
  DEAD("DEAD");
  private final String detail;

  JobStatus(String detail) {
    this.detail = detail;
  }

  public String getDetail() {
    return detail;
  }
}
