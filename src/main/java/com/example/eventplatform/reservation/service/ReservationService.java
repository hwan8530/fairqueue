package com.example.eventplatform.reservation.service;

import static com.example.eventplatform.common.CommonFunction.extractAuthentication;

import com.example.eventplatform.database.RedisHandler;
import com.example.eventplatform.event.entity.Event;
import com.example.eventplatform.event.entity.EventStatus;
import com.example.eventplatform.event.repository.EventRepository;
import com.example.eventplatform.exception.GlobalCustomException;
import com.example.eventplatform.exception.GlobalExceptions;
import com.example.eventplatform.messagebroker.KafkaProducer;
import com.example.eventplatform.reservation.dto.ResponseReservation;
import com.example.eventplatform.reservation.dto.ResponseReservation.deleteReservationDTO;
import com.example.eventplatform.reservation.dto.ResponseReservation.reservationDTO;
import com.example.eventplatform.reservation.dto.ResponseReservationList;
import com.example.eventplatform.reservation.dto.ResponseReservationList.item;
import com.example.eventplatform.reservation.entity.Reservation;
import com.example.eventplatform.reservation.entity.ReservationStatus;
import com.example.eventplatform.reservation.mapper.ReservationMapper;
import com.example.eventplatform.reservation.repository.ReservationRepository;
import com.example.eventplatform.users.entity.Users;
import com.example.eventplatform.users.repository.UsersRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

  private final RedisHandler redisHandler;
  private final ReservationRepository reservationRepository;
  private final EventRepository eventRepository;
  private final UsersRepository usersRepository;
  private final ReservationMapper reservationMapper;
  private final KafkaProducer kafkaProducer;

  @Transactional
  @Async
  public CompletableFuture<ResponseReservation<reservationDTO>> makeReservation(
      long eventId,
      String entryToken,
      String idempotencyKey) {
    Authentication authentication = extractAuthentication();
    String username = (String) authentication.getPrincipal();

    if (!redisHandler.entryKeyAvailable(eventId, username, entryToken)) {
      throw new GlobalCustomException(GlobalExceptions.INVALID_ENTRY_TOKEN);
    }

    Optional<Reservation> optional = reservationRepository.findByIdempotencyKey(idempotencyKey);
    if (optional.isPresent()) { // idempotencyKey 중복
      Reservation reservation = optional.get();
      reservationDTO dto = reservationMapper.toResponse(reservation);
      ResponseReservation<reservationDTO> responseReservation = new ResponseReservation<>();
      responseReservation.setStatus(200);
      responseReservation.setData(dto);
      return CompletableFuture.completedFuture(responseReservation);
    } else { // 신규 생성
      Event event = eventRepository.findByIdWithLock(eventId)
          .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR));

      // event open 확인
      if (!event.getStatus().getStatus().equals(EventStatus.OPEN.getStatus())) {
        throw new GlobalCustomException(GlobalExceptions.EVENT_NOT_OPEN);
      }

      // 재고 원자 차감 (성공 시에만 진행)
      long result = redisHandler.decrementEventStock(eventId, event.getPer_user_limit());
      if (result == 1) {
        event.setRemaining_stock(event.getRemaining_stock() - event.getPer_user_limit());
        // 같은 사용자의 다른 예약이 있는지 확인
        List<Reservation> reservationList = reservationRepository.findByUsername(username);
        if (!reservationList.isEmpty() && reservationList.size() >= reservationList.getFirst()
            .getEvent().getPer_user_limit()) {
          throw new GlobalCustomException(GlobalExceptions.DUPLICATE_USER);
        }

        Optional<Event> eventOptional = eventRepository.findById(eventId);
        Optional<Users> usersOptional = usersRepository.findByUsername(username);
        if (eventOptional.isEmpty() || usersOptional.isEmpty()) {
          throw new GlobalCustomException(
              GlobalExceptions.INTERNAL_ERROR); // 명시된 에러가 없어서 Internal error
        }
        Reservation reservation = Reservation.builder().event(eventOptional.get())
            .user(usersOptional.get()).idempotency_key(idempotencyKey).build();
        reservationRepository.save(reservation);
        kafkaProducer.sendMessage("confirm_reservation", reservation); // kafka를 통한 메세지 발행
        reservationDTO dto = reservationMapper.toResponse(reservation);
        ResponseReservation<reservationDTO> responseReservation = new ResponseReservation<>();
        responseReservation.setStatus(202);
        responseReservation.setData(dto);
        return CompletableFuture.completedFuture(responseReservation);
      } else { // SOLD_OUT
        event.setRemaining_stock(0);
        throw new GlobalCustomException(GlobalExceptions.SOLD_OUT);
      }
    }
  }

  public reservationDTO getReservation(
      long reservationId) {
    Authentication authentication = extractAuthentication();
    String username = (String) authentication.getPrincipal();
    Optional<Reservation> optional = reservationRepository.findById(reservationId);
    if (optional.isEmpty()) {
      throw new GlobalCustomException(GlobalExceptions.RESERVATION_NOT_FOUND);
    }

    Reservation reservation = optional.get();
    if (!reservation.getUser().getUsername().equals(username)) {
      throw new GlobalCustomException(GlobalExceptions.FORBIDDEN);
    }
    return reservationMapper.toResponse(reservation);
  }

  /*
  PENDING 상태의 예약을 삭제하고 EVENT의 재고를 증가시켜주는 메소드 -> 동시성 문제 발생가능.
  동시성 문제 해결 방안 3가지 모두 구현해야함
  1. DB 비관적 락 (SELECT FOR UPDATE)
  2. DB 낙관적 락 (version column 사용해서 객체 내의 값을 변경 시킬 때 명시적으로 변경했다고 기록)
  3. REDIS 원자 연산 (DECR/Lua 스크립트로 재고 증감 후 DB 반영)
   */
  @Transactional
  public deleteReservationDTO deleteReservation(long reservationId) {
    Authentication authentication = extractAuthentication();
    String username = (String) authentication.getPrincipal();

    Optional<Reservation> optional = reservationRepository.findById(reservationId);
    if (optional.isEmpty()) {
      throw new GlobalCustomException(GlobalExceptions.RESERVATION_NOT_FOUND);
    }

    Reservation reservation = optional.get();
    if (!reservation.getUser().getUsername().equals(username)) {
      throw new GlobalCustomException(GlobalExceptions.FORBIDDEN);
    }

    if (!reservation.getStatus().getStatus().equals(ReservationStatus.PENDING.getStatus())) {
      throw new GlobalCustomException(GlobalExceptions.RESERVATION_NOT_CANCELLABLE);
    }

    deleteReservationDTO dto = reservationMapper.toDeleteResponse(reservation);
    Event event = eventRepository.findByIdWithLock(reservation.getEvent().getId())
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR)); // 비관적 락 획득
    if (event.getRemaining_stock() + 1 > event.getTotal_stock()) {
      event.setRemaining_stock(event.getRemaining_stock());
    } else {
      event.setRemaining_stock(event.getRemaining_stock() + 1);
    }
    reservationRepository.delete(reservation);
    return dto;
  }

  public ResponseReservationList getMyReservations() {
    Authentication authentication = extractAuthentication();
    String username = (String) authentication.getPrincipal();
    List<Reservation> reservationList = reservationRepository.findByUsername(username);
    ResponseReservationList dto = new ResponseReservationList(new ArrayList<>());
    for (Reservation r : reservationList) {
      dto.getItems().add(new item(r.getId(), r.getEvent().getId(), r.getStatus().getStatus()));
    }
    return dto;
  }
}
