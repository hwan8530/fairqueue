package com.example.eventplatform.reservation.service;

import static com.example.eventplatform.common.CommonFunction.currentUsername;

import com.example.eventplatform.database.RedisHandler;
import com.example.eventplatform.event.entity.Event;
import com.example.eventplatform.event.entity.EventStatus;
import com.example.eventplatform.event.repository.EventRepository;
import com.example.eventplatform.exception.GlobalCustomException;
import com.example.eventplatform.exception.GlobalExceptions;
import com.example.eventplatform.job.entity.JobType;
import com.example.eventplatform.messagebroker.KafkaProducer;
import com.example.eventplatform.reservation.dto.ResponseReservation;
import com.example.eventplatform.reservation.dto.ResponseReservation.DeleteReservationDTO;
import com.example.eventplatform.reservation.dto.ResponseReservation.ReservationDTO;
import com.example.eventplatform.reservation.dto.ResponseReservationList;
import com.example.eventplatform.reservation.dto.ResponseReservationList.Item;
import com.example.eventplatform.reservation.entity.Reservation;
import com.example.eventplatform.reservation.entity.ReservationStatus;
import com.example.eventplatform.reservation.mapper.ReservationMapper;
import com.example.eventplatform.reservation.repository.ReservationRepository;
import com.example.eventplatform.users.entity.Users;
import com.example.eventplatform.users.repository.UsersRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
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

  /*
   * 예약(발급) 생성. 명세서상 "202 Accepted로 즉시 응답 후 확정은 비동기 Job이 처리"라는 의미는
   * "이 메서드 자체가 스레드를 나눠 실행돼야 한다"는 뜻이 아니라 "PENDING 예약만 만들고 결제 확정
   * 같은 무거운 후속 처리는 Job 큐에 맡긴다"는 뜻이다. 이 메서드가 하는 일(엔트리 토큰 검증, DB
   * 조회 1~2회, Redis 원자 차감 1회, INSERT 1건, Kafka 발행 1건)은 그 자체로 빠르고 짧으므로
   * 굳이 별도 스레드로 넘길 이유가 없다 - 동기 @Transactional로 처리하고 바로 반환한다.
   *
   * (이전에는 @Async + CompletableFuture로 선언돼 있었지만, 호출부(컨트롤러)가 즉시 .get()으로
   * 블로킹해서 실제로는 비동기 이점이 전혀 없이 스레드 전환 비용만 추가되고 있었다. 게다가 이
   * 메서드는 이벤트 하나당 비관적 락으로 직렬화되는 핫스팟이라, 무제한으로 스레드를 만들어내는
   * 비동기 실행기 위에서 돌리면 유한한 DB 커넥션 풀이 그 스레드 수만큼 대기 상태로 쌓일 위험이
   * 있었다. 자세한 배경은 docs/async-blocking-fix.md 참고.)
   */
  @Transactional
  public ResponseReservation<ReservationDTO> makeReservation(
      long eventId,
      String entryToken,
      String idempotencyKey) {
    String username = currentUsername();

    if (!redisHandler.entryKeyAvailable(eventId, username, entryToken)) {
      throw new GlobalCustomException(GlobalExceptions.INVALID_ENTRY_TOKEN);
    }

    Optional<Reservation> optional = reservationRepository.findByIdempotencyKey(idempotencyKey);
    if (optional.isPresent()) { // idempotencyKey 중복 -> 새로 만들지 않고 기존 결과 그대로 반환 (멱등)
      ReservationDTO dto = reservationMapper.toResponse(optional.get());
      return ResponseReservation.of(200, dto);
    }

    // eventId는 URL 경로에서 그대로 온 사용자 입력이므로, 없으면 클라이언트 귀책(404)이다.
    Event event = eventRepository.findByIdWithLock(eventId)
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.EVENT_NOT_FOUND));

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
    // 토픽 이름은 JobType이 유일한 소스 - ReservationConfirmationListener의 @KafkaListener,
    // JobService.makeSchedule의 JobType.fromTopic()과 반드시 일치해야 하며 여기서 문자열을
    // 다시 손으로 타이핑하지 않는다.
    kafkaProducer.sendMessage(JobType.CONFIRM_RESERVATION.getTopic(), reservation);

    ReservationDTO dto = reservationMapper.toResponse(reservation);
    return ResponseReservation.of(202, dto);
  }

  public ReservationDTO getReservation(long reservationId) {
    Reservation reservation = getOwnedReservation(reservationId, currentUsername());
    return reservationMapper.toResponse(reservation);
  }

  /*
   * reservationId로 예약을 조회하고, 요청자 본인 소유가 맞는지까지 검증한다.
   * getReservation/deleteReservation 양쪽에서 동일하게 필요한 "조회 + 소유권 확인"을 한 곳에
   * 모아서, 소유권 규칙이 바뀌더라도(예: ADMIN 우회 허용) 한 곳만 고치면 되게 한다.
   */
  private Reservation getOwnedReservation(long reservationId, String username) {
    Reservation reservation = reservationRepository.findById(reservationId)
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.RESERVATION_NOT_FOUND));
    if (!reservation.getUser().getUsername().equals(username)) {
      throw new GlobalCustomException(GlobalExceptions.FORBIDDEN);
    }
    return reservation;
  }

  /*
  PENDING 상태의 예약을 취소(CANCELLED)하고 EVENT의 재고를 복구하는 메소드.
  재고 복구는 DB(비관적 락으로 보호)와 Redis(원자 카운터) 양쪽에 대칭으로 반영한다.
   */
  @Transactional
  public DeleteReservationDTO deleteReservation(long reservationId) {
    Reservation reservation = getOwnedReservation(reservationId, currentUsername());

    if (reservation.getStatus() != ReservationStatus.PENDING) {
      throw new GlobalCustomException(GlobalExceptions.RESERVATION_NOT_CANCELLABLE);
    }

    // reservation.getEvent()는 사용자 입력이 아니라 DB FK이므로, 없으면 사용자 입력 오류가 아니라
    // 참조 무결성이 깨진 것 - EVENT_NOT_FOUND(404)가 아니라 INTERNAL_ERROR(500)가 맞다.
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
    List<Reservation> reservationList = reservationRepository.findByUsername(currentUsername());
    ResponseReservationList dto = new ResponseReservationList(new ArrayList<>());
    for (Reservation r : reservationList) {
      dto.getItems().add(new Item(r.getId(), r.getEvent().getId(), r.getStatus().name()));
    }
    return dto;
  }
}
