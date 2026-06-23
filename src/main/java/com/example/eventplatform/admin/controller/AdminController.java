package com.example.eventplatform.admin.controller;

import com.example.eventplatform.event.dto.RequestEvent.RequestCreateEvent;
import com.example.eventplatform.event.dto.ResponseEvent.ResponseCreateEvent;
import com.example.eventplatform.event.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/")
public class AdminController {

  private final EventService eventService;

  @PostMapping("/events")
  public ResponseEntity<ResponseCreateEvent> createEvent(@RequestBody RequestCreateEvent request) {
    return ResponseEntity.status(201).body(eventService.createEvent(request));
  }

}
