package com.example.eventplatform.reservation.repository;

import com.example.eventplatform.reservation.entity.Reservation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

  @Query("select r from Reservation r where r.idempotency_key = :idempotencyKey")
  Optional<Reservation> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

  @Query("select r from Reservation r join fetch r.user u where u.username = :username")
  List<Reservation> findByUsername(@Param("username") String username);
}
