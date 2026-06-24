package com.example.eventplatform.event.service;

import com.example.eventplatform.database.RedisHandler;
import com.example.eventplatform.event.dto.RequestEvent.RequestCreateEvent;
import com.example.eventplatform.event.dto.ResponseEvent.ResponseCreateEvent;
import com.example.eventplatform.event.dto.ResponseEvent.ResponseEventDetail;
import com.example.eventplatform.event.dto.ResponseEvent.ResponseEventStock;
import com.example.eventplatform.event.dto.ResponseQueue.ResponseQueueStatus;
import com.example.eventplatform.event.entity.Event;
import com.example.eventplatform.event.entity.QueueStruct;
import com.example.eventplatform.event.mapper.EventMapper;
import com.example.eventplatform.event.repository.EventRepository;
import com.example.eventplatform.exception.GlobalCustomException;
import com.example.eventplatform.exception.GlobalExceptions;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
  public ResponseQueueStatus enQueueWaiting(long eventId)
      throws ExecutionException, InterruptedException {
    Authentication authentication = extractAuthentication();
    findEvent(eventId); // event 존재 여부만 확인
    String redisKey = "waiting:" + eventId;
    QueueStruct queueStruct = redisHandler.enQueue(redisKey, (String) authentication.getPrincipal())
        .get();
    return new ResponseQueueStatus(eventId, queueStruct.getIdentifier(), queueStruct.getRank(),
        queueStruct.getRank() == 0, queueStruct.getEntryToken());
  }

  public ResponseQueueStatus queueStatus(long eventId)
      throws ExecutionException, InterruptedException {
    Authentication authentication = extractAuthentication();
    findEvent(eventId); // event 존재 여부만 확인
    String redisKey = "waiting:" + eventId;
    QueueStruct queueStruct = redisHandler.queueStatus(redisKey,
            (String) authentication.getPrincipal())
        .get();
    return new ResponseQueueStatus(eventId, queueStruct.getIdentifier(), queueStruct.getRank(),
        queueStruct.getRank() == 0, queueStruct.getEntryToken());
  }

  private Event findEvent(long eventId) {
    return eventRepository.findById(eventId)
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.INTERNAL_ERROR));
  }

  private Authentication extractAuthentication() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new GlobalCustomException(GlobalExceptions.AUTH_FAILED);
    }
    return authentication;
  }
}
