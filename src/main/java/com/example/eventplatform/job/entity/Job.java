package com.example.eventplatform.job.entity;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@NoArgsConstructor
@Getter
public class Job {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @NotNull
  @Column(columnDefinition = "VARCHAR(50)")
  @Enumerated(EnumType.STRING)
  private JobType type;
  @NotNull
  @Column(columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> payload;
  @NotNull
  @Column(columnDefinition = "VARCHAR(20)")
  @Enumerated(EnumType.STRING)
  private JobStatus status;
  @NotNull
  private int attempts = 0;
  @NotNull
  private int max_attempts = 5;
  @Nullable
  private LocalDateTime next_run_at;
  @NotNull
  @Column(unique = true, columnDefinition = "VARCHAR(100)")
  private String idempotency_key;
  @Nullable
  private String last_error;
  @Nullable
  @Column(columnDefinition = "VARCHAR(100)")
  private String locked_by;
  @Nullable
  private LocalDateTime locked_at;
  @NotNull
  private LocalDateTime created_at;
  @NotNull
  private LocalDateTime updated_at;

  // CONFIRM_RESERVATION
  @Builder
  public Job(JobType type, Map<String, Object> payload, String idempotency_key) {
    this.type = type;
    this.payload = payload;
    this.status = JobStatus.SCHEDULED;
    this.idempotency_key = idempotency_key;
    this.attempts = 0;
    this.max_attempts = 5;
    LocalDateTime now = LocalDateTime.now();
    this.created_at = now;
    this.updated_at = now;
    this.next_run_at = now.plusMinutes(3);
  }

  public LocalDateTime enQueueJob() {
    this.status = JobStatus.QUEUED;
    this.updated_at = LocalDateTime.now();
    this.next_run_at = LocalDateTime.now().plusMinutes(2);
    return next_run_at;
  }

  public int remainingAttempts() {
    return max_attempts - (attempts + 1);
  }

  public void start() {
    this.attempts = this.attempts + 1;
    if (this.attempts > max_attempts) {
      this.status = JobStatus.FAILED;
      this.last_error = "Exceed max attempts";
    } else {
      this.status = JobStatus.RUNNING;
    }
    this.updated_at = LocalDateTime.now();
  }

  public void fail(String error) {
    this.status = JobStatus.FAILED;
    this.last_error = error;
    this.updated_at = LocalDateTime.now();
  }

  public void succeed() {
    this.status = JobStatus.SUCCEEDED;
    this.updated_at = LocalDateTime.now();
    this.next_run_at = null;
  }

  public LocalDateTime retry() {
    // delay = min ( base * 2^(attempt-1), cap ) + jitter
    long delay = (long) (Math.min(2 * Math.pow(2, attempts - 1), 60) + (int) (Math.random() * 2));
    this.status = JobStatus.QUEUED;
    this.updated_at = LocalDateTime.now();
    this.next_run_at = LocalDateTime.now().plusSeconds(delay);
    return next_run_at;
  }

}
