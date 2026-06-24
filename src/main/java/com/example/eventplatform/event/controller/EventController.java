package com.example.eventplatform.event.controller;

import com.example.eventplatform.event.dto.ResponseEvent.ResponseEventDetail;
import com.example.eventplatform.event.dto.ResponseEvent.ResponseEventStock;
import com.example.eventplatform.event.dto.ResponseQueue.ResponseQueueStatus;
import com.example.eventplatform.event.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

  private final EventService eventService;

  @GetMapping("/{eventId}")
  public ResponseEntity<ResponseEventDetail> getEvent(@PathVariable long eventId) {
    return ResponseEntity.status(HttpStatus.OK).body(eventService.getEvent(eventId));
  }

  @GetMapping("/{eventId}/stock")
  public ResponseEntity<ResponseEventStock> getEventStock(@PathVariable long eventId) {
    return ResponseEntity.status(HttpStatus.OK).body(eventService.getEventStock(eventId));
  }

  @PostMapping("/{eventId}/queue")
  public ResponseEntity<ResponseQueueStatus> enQueueEvent(@PathVariable long eventId) {
    return ResponseEntity.status(HttpStatus.OK).body(eventService.enQueueWaiting(eventId));
  }


}
