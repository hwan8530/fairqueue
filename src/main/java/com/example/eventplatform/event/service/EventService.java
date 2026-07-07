package com.example.eventplatform.event.service;

import static com.example.eventplatform.common.CommonFunction.extractAuthentication;

import com.example.eventplatform.database.EventRedisKey;
import com.example.eventplatform.database.RedisHandler;
import com.example.eventplatform.event.dto.QueueStruct;
import com.example.eventplatform.event.dto.RequestEvent.RequestCreateEvent;
import com.example.eventplatform.event.dto.ResponseEvent.ResponseCreateEvent;
import com.example.eventplatform.event.dto.ResponseEvent.ResponseEventDetail;
import com.example.eventplatform.event.dto.ResponseEvent.ResponseEventStock;
import com.example.eventplatform.event.dto.ResponseQueue.ResponseQueueStatus;
import com.example.eventplatform.event.entity.Event;
import com.example.eventplatform.event.entity.EventStatus;
import com.example.eventplatform.event.mapper.EventMapper;
import com.example.eventplatform.event.repository.EventRepository;
import com.example.eventplatform.exception.GlobalCustomException;
import com.example.eventplatform.exception.GlobalExceptions;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventService {

  private final EventRepository eventRepository;
  private final EventMapper eventMapper;
  private final RedisHandler redisHandler;

  @Transactional
  public ResponseCreateEvent createEvent(RequestCreateEvent request) {
    Event event = Event.builder()
        .name(request.getName())
        .type(request.getType())
        .total_stock(request.getTotalStock())
        .per_user_limit(request.getPerUserLimit())
        .open_at(request.getOpenAt())
        .close_at(request.getCloseAt())
        .build();
    eventRepository.save(event);

    return eventMapper.eventToResponseCreateEvent(event);
  }

  public ResponseEventDetail getEvent(long eventId) {
    Event event = findEvent(eventId);
    return eventMapper.eventToResponseEventDetail(event);
  }

  public ResponseEventStock getEventStock(long eventId) {
    Event event = findEvent(eventId);
    return eventMapper.eventToResponseEventStock(event, event.getRemaining_stock() == 0);
  }

  @Transactional
  public ResponseQueueStatus enQueueWaiting(long eventId) {
    Authentication authentication = extractAuthentication();
    Event event = findEvent(eventId); // event 존재 여부 확인
    String eventStatus = event.getStatus().getStatus();
    if (!eventStatus.equals(EventStatus.SCHEDULED.getStatus())
        || !eventStatus.equals(EventStatus.OPEN.getStatus())) {
      throw new GlobalCustomException(GlobalExceptions.EVENT_NOT_OPEN);
    }

    QueueStruct queueStruct = redisHandler.enQueueWaiting(eventId,
        (String) authentication.getPrincipal());
    return new ResponseQueueStatus(eventId, queueStruct.getIdentifier(), queueStruct.getRank(),
        queueStruct.getRank() == 0, queueStruct.getEntryToken(), queueStruct.getEntryTokenTtlSec());
  }

  /*
  큐 상태 확인 메소드
  클라이언트가 이 메소드를 주기적으로 호출하면 큐 상태만 반환하는게 아닌 주기적인 입장도 여기서 처리해줘야함
  */
  public ResponseQueueStatus queueStatus(long eventId)
      throws ExecutionException, InterruptedException {
    Authentication authentication = extractAuthentication();
    findEvent(eventId); // event 존재 여부만 확인
    QueueStruct queueStruct = redisHandler.queueStatus(eventId,
            (String) authentication.getPrincipal())
        .get();
    return new ResponseQueueStatus(eventId, queueStruct.getIdentifier(), queueStruct.getRank(),
        queueStruct.getRank() == 0, queueStruct.getEntryToken(), queueStruct.getEntryTokenTtlSec());
  }


  /*
  1초마다 SCHEDULED 상태의 이벤트들을 확인하면서 상태를 변경할지 확인
   */
  @Async("redisAsyncExecutor")
  @Scheduled(fixedDelay = 1000)
  @Transactional
  public void activateEvent() {
    List<Event> eventList = eventRepository.findByStatus(EventStatus.SCHEDULED.getStatus());
    for (Event event : eventList) {
      if (event.getOpen_at().isBefore(LocalDateTime.now()) || event.getOpen_at()
          .isEqual(LocalDateTime.now())) {
        event.setStatus(EventStatus.OPEN);
        redisHandler.putSet(EventRedisKey.ACTIVE_EVENTS.getPrefix(), event.getId());
      }
    }
  }

  /*
  1초마다 OPEN 상태의 이벤트들을 확인하고 이벤트별 entry token 생성(TTL 30s) 및 ALLOWED, IDENTICAL 관리
  1. 초당 허용 건수만큼 waiting:{eventId} ZSET에서 삭제하고 allowed:{eventId} SET에 username을 member로 추가
  2. ALLOWED를 확인하고 score가 현재 시간보다 적다면 삭제
  3. 삭제하는 ALLOWED의 username으로 IDENTICAL에서 삭제
  entry_token:{eventId}:username 을 TTL 30초로 생성
   */
  @Async("redisAsyncExecutor")
  @Scheduled(fixedDelay = 1000)
  @Transactional
  public void moveQueueToAllow() {
    List<Event> eventList = eventRepository.findByStatus(EventStatus.OPEN.name());
    int maxAllowedCount = 5; // 초당 5명 허용
    long ttl = 30; // TTL 30초
    for (Event event : eventList) {
      redisHandler.popQueueAndGenerateEntryToken(event.getId(), maxAllowedCount, ttl);
      redisHandler.removeAllowed(event.getId());
    }
  }

  private Event findEvent(long eventId) {
    return eventRepository.findById(eventId)
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR));
  }

  @Transactional
  public long decreaseRemainingStock(long eventId) {
    Event event = eventRepository.findByIdWithLock(eventId)
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR));
    return event.decreaseRemainingStock();
  }
}
