package com.example.eventplatform.event.service;

import com.example.eventplatform.event.dto.RequestEvent.RequestCreateEvent;
import com.example.eventplatform.event.dto.ResponseEvent.ResponseCreateEvent;
import com.example.eventplatform.event.entity.Event;
import com.example.eventplatform.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventService {

  private final EventRepository eventRepository;

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

    return new ResponseCreateEvent(event.getId(), event.getStatus().name());
  }


}
