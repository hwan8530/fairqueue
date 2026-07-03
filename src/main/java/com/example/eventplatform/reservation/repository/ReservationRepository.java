package com.example.eventplatform.reservation.repository;

import com.example.eventplatform.reservation.entity.Reservation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

  Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

  List<Reservation> findByUsername(String username);
}
