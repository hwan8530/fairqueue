package com.example.eventplatform.reservation.entity;

import com.example.eventplatform.event.entity.Event;
import com.example.eventplatform.users.entity.Users;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@NoArgsConstructor
@Setter
public class Reservation {

  @NotNull
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "event_id")
  @NotNull
  private Event event;
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id")
  @NotNull
  private Users user;
  @NotNull
  @Enumerated(EnumType.STRING)
  private ReservationStatus status;
  @NotNull
  @Column(columnDefinition = "VARCHAR(100)")
  private String idempotency_key;
  @Nullable
  private String issued_code; // 확정시 발급
  @Nullable
  private LocalDateTime expires_at;
  @NotNull
  private LocalDateTime created_at;
  @NotNull
  private LocalDateTime confirmed_at;

  @Builder
  public Reservation(Event event, Users user, String idempotency_key) {
    this.event = event;
    this.user = user;
    this.idempotency_key = idempotency_key;
    this.created_at = LocalDateTime.now();
  }
}
