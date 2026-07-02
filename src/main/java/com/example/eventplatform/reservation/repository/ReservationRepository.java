package com.example.eventplatform.reservation.repository;

import com.example.eventplatform.reservation.entity.Reservation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Long, Reservation> {

  Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

}
