package com.example.eventplatform.users.controller;

import com.example.eventplatform.users.dto.RequestUsers.RequestLogIn;
import com.example.eventplatform.users.dto.RequestUsers.RequestSignUp;
import com.example.eventplatform.users.dto.ResponseUsers.ResponseLogIn;
import com.example.eventplatform.users.dto.ResponseUsers.ResponseSignUp;
import com.example.eventplatform.users.service.UsersService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/")
@Slf4j
public class UsersController {

  private final UsersService usersService;

  @PostMapping("signup")
  public ResponseEntity<ResponseSignUp> signUp(@RequestBody RequestSignUp request) {
    return ResponseEntity.status(201).body(usersService.signUp(request));
  }

  @PostMapping("login")
  public ResponseEntity<ResponseLogIn> logIn(@RequestBody RequestLogIn request) {
    return ResponseEntity.status(200).body(usersService.logIn(request));
  }

}
