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
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@NoArgsConstructor
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
  @Column(columnDefinition = "DEFAULT 0")
  private int attempts;
  @NotNull
  @Column(columnDefinition = "DEFAULT 5")
  private int max_attempts;
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
  }
}
