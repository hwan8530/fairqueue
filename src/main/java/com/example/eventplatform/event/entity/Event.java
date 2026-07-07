package com.example.eventplatform.event.entity;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Setter
@Getter
public class Event {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @NotNull
  @Column(columnDefinition = "VARCHAR(200)")
  private String name;
  @NotNull
  @Column(columnDefinition = "VARCHAR(20)")
  @Enumerated(EnumType.STRING)
  private EventType type;
  @NotNull
  @Min(0)
  private int total_stock;
  @NotNull
  @Min(0)
  private int remaining_stock;
  @NotNull
  private int per_user_limit;
  @NotNull
  @Column(columnDefinition = "VARCHAR(20)")
  @Enumerated(EnumType.STRING)
  private EventStatus status;
  @NotNull
  private LocalDateTime open_at;
  @Nullable
  private LocalDateTime close_at;
  @NotNull
  private long version;
  @NotNull
  private LocalDateTime create_at;

  @Builder
  public Event(String name, String type, int total_stock, int per_user_limit, LocalDateTime open_at,
      LocalDateTime close_at) {
    this.name = name;
    this.type = EventType.fromStringType(type);
    this.total_stock = total_stock;
    this.per_user_limit = per_user_limit;
    this.open_at = open_at;
    this.close_at = close_at;
    if (this.open_at.isBefore(LocalDateTime.now())) {
      this.status = EventStatus.OPEN;
    } else if (this.open_at.isAfter(LocalDateTime.now())) {
      this.status = EventStatus.SCHEDULED;
    } else if (this.close_at != null && this.close_at.isBefore(LocalDateTime.now())) {
      this.status = EventStatus.CLOSED;
    }
  }

  public long decreaseRemainingStock() {
    if (this.remaining_stock >= 1) {
      this.remaining_stock--;
      return 1;
    } else {
      return 0;
    }
  }
}
