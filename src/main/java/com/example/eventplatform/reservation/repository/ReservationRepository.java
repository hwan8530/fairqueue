package com.example.eventplatform.reservation.repository;

import com.example.eventplatform.reservation.entity.Reservation;
import com.example.eventplatform.reservation.entity.ReservationStatus;
import java.util.Collection;
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

  // per_user_limit 체크용: 특정 이벤트에서 이 유저가 보유한 활성(PENDING/CONFIRMED) 예약 수
  // "_" 로 중첩 프로퍼티 경로(event.id, user.username)를 명시해 파싱 모호성을 없앤다.
  long countByEvent_IdAndUser_UsernameAndStatusIn(long eventId, String username,
      Collection<ReservationStatus> statuses);
}
