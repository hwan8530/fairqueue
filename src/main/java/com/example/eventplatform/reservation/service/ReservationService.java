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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(readOnly = true)
@Service
public class ReservationService {

  private static final List<ReservationStatus> ACTIVE_STATUSES =
      List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

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
    if (optional.isPresent()) { // idempotencyKey 중복 -> 새로 만들지 않고 기존 결과 그대로 반환 (멱등)
      Reservation reservation = optional.get();
      reservationDTO dto = reservationMapper.toResponse(reservation);
      ResponseReservation<reservationDTO> responseReservation = new ResponseReservation<>();
      responseReservation.setStatus(200);
      responseReservation.setData(dto);
      return CompletableFuture.completedFuture(responseReservation);
    }

    Event event = eventRepository.findByIdWithLock(eventId)
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR));

    if (event.getStatus() != EventStatus.OPEN) {
      throw new GlobalCustomException(GlobalExceptions.EVENT_NOT_OPEN);
    }

    // 1인 한도 체크 - 재고를 건드리기 전에 먼저 확인해야 실패 시 롤백할 부수효과가 없다
    long activeCount = reservationRepository.countByEvent_IdAndUser_UsernameAndStatusIn(eventId,
        username, ACTIVE_STATUSES);
    if (activeCount >= event.getPer_user_limit()) {
      throw new GlobalCustomException(GlobalExceptions.ALREADY_RESERVED);
    }

    // 재고 원자 차감 (Redis Lua 스크립트, 성공 시에만 진행) - 1건 예약이므로 항상 1개만 차감
    if (redisHandler.decrementEventStock(eventId, 1) == 0) {
      throw new GlobalCustomException(GlobalExceptions.SOLD_OUT);
    }
    event.decreaseRemainingStock();

    Users user = usersRepository.findByUsername(username)
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR));
    Reservation reservation = Reservation.builder().event(event)
        .user(user).idempotency_key(idempotencyKey).build();
    reservationRepository.save(reservation);
    kafkaProducer.sendMessage("confirm_reservation", reservation); // kafka를 통한 메세지 발행

    reservationDTO dto = reservationMapper.toResponse(reservation);
    ResponseReservation<reservationDTO> responseReservation = new ResponseReservation<>();
    responseReservation.setStatus(202);
    responseReservation.setData(dto);
    return CompletableFuture.completedFuture(responseReservation);
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
  PENDING 상태의 예약을 취소(CANCELLED)하고 EVENT의 재고를 복구하는 메소드.
  재고 복구는 DB(비관적 락으로 보호)와 Redis(원자 카운터) 양쪽에 대칭으로 반영한다.
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

    if (reservation.getStatus() != ReservationStatus.PENDING) {
      throw new GlobalCustomException(GlobalExceptions.RESERVATION_NOT_CANCELLABLE);
    }

    Event event = eventRepository.findByIdWithLock(reservation.getEvent().getId())
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR)); // 비관적 락 획득
    if (event.getRemaining_stock() < event.getTotal_stock()) {
      event.increaseRemainingStock();
      redisHandler.incrementEventStock(event.getId(), 1);
    }
    reservation.cancel();

    return reservationMapper.toDeleteResponse(reservation);
  }

  public ResponseReservationList getMyReservations() {
    Authentication authentication = extractAuthentication();
    String username = (String) authentication.getPrincipal();
    List<Reservation> reservationList = reservationRepository.findByUsername(username);
    ResponseReservationList dto = new ResponseReservationList(new ArrayList<>());
    for (Reservation r : reservationList) {
      dto.getItems().add(new item(r.getId(), r.getEvent().getId(), r.getStatus().name()));
    }
    return dto;
  }
}
