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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Setter
public class Reservation {

  // IDENTITY 생성 전략의 id에 @NotNull을 붙이면 Hibernate의 pre-insert Bean Validation이
  // "아직 DB가 채번하지 않아 null인 게 정상"인 시점에 걸려서 모든 INSERT가
  // ConstraintViolationException으로 실패한다 - 예약 생성 자체가 항상 500이었다
  // (docs/refactoring-and-abstraction-review.md 참고). Event/Job/Users 엔티티는 이 실수가 없다.
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
  @Nullable
  private LocalDateTime confirmed_at;

  @Builder
  public Reservation(Event event, Users user, String idempotency_key) {
    this.event = event;
    this.user = user;
    this.idempotency_key = idempotency_key;
    this.status = ReservationStatus.PENDING;
    this.created_at = LocalDateTime.now();
    this.expires_at = this.created_at.plusMinutes(5);
  }

  public void confirm(String issuedCode) {
    this.status = ReservationStatus.CONFIRMED;
    this.issued_code = issuedCode;
    this.confirmed_at = LocalDateTime.now();
  }

  public void cancel() {
    this.status = ReservationStatus.CANCELLED;
  }
}
