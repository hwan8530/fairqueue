package com.example.eventplatform.admin.controller;

import com.example.eventplatform.event.dto.RequestEvent.RequestCreateEvent;
import com.example.eventplatform.event.dto.ResponseEvent.ResponseCreateEvent;
import com.example.eventplatform.event.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 관리자 전용 컨트롤러. 인가 규칙(ADMIN)을 SecurityConfig의 URL 목록이 아니라 여기 라우트
// 선언과 같은 자리에 두어, 새 관리자 API를 추가할 때 SecurityConfig를 잊고 안 고치는 실수를
// 구조적으로 막는다 (docs/refactoring-and-abstraction-review.md B3/C1 참고).
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

  private final EventService eventService;

  @PostMapping("/events")
  public ResponseEntity<ResponseCreateEvent> createEvent(@RequestBody RequestCreateEvent request) {
    return ResponseEntity.status(201).body(eventService.createEvent(request));
  }

}
