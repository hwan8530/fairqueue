package com.example.eventplatform.jobs.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
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
}
