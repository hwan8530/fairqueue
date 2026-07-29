package com.example.eventplatform.reservation.controller;

import com.example.eventplatform.reservation.dto.ResponseReservation;
import com.example.eventplatform.reservation.dto.ResponseReservation.ReservationDTO;
import com.example.eventplatform.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReservationController {

  private final ReservationService reservationService;

  // 예약 생성. 서비스 메서드가 동기로 결과를 바로 반환하므로 그대로 상태 코드/본문에 매핑한다.
  @PostMapping("/api/events/{eventId}/reservations")
  public ResponseEntity<?> postReservation(@PathVariable long eventId,
      @RequestHeader("X-Entry-Token") String entryToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    ResponseReservation<ReservationDTO> response = reservationService.makeReservation(
        eventId, entryToken, idempotencyKey);
    return ResponseEntity.status(response.getStatus()).body(response.getData());
  }

  @GetMapping("/api/reservations/{reservationId}")
  public ResponseEntity<?> getReservation(@PathVariable long reservationId) {
    return ResponseEntity.ok(reservationService.getReservation(reservationId));
  }

  @GetMapping("/api/me/reservations")
  public ResponseEntity<?> getMyReservations() {
    return ResponseEntity.ok(reservationService.getMyReservations());
  }

  @DeleteMapping("/api/reservations/{reservationId}")
  public ResponseEntity<?> deleteReservation(@PathVariable long reservationId) {
    return ResponseEntity.ok(reservationService.deleteReservation(reservationId));
  }

}
