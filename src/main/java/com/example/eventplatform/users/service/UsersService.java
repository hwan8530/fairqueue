package com.example.eventplatform.users.service;

import com.example.eventplatform.database.RedisHandler;
import com.example.eventplatform.exception.GlobalCustomException;
import com.example.eventplatform.exception.GlobalExceptions;
import com.example.eventplatform.security.JwtUtil;
import com.example.eventplatform.users.dto.RequestUsers.RequestLogIn;
import com.example.eventplatform.users.dto.RequestUsers.RequestSignUp;
import com.example.eventplatform.users.dto.ResponseUsers.ResponseLogIn;
import com.example.eventplatform.users.dto.ResponseUsers.ResponseSignUp;
import com.example.eventplatform.users.entity.Users;
import com.example.eventplatform.users.repository.UsersRepository;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UsersService {

  private final UsersRepository usersRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtUtil jwtUtil;
  private final RedisHandler redisHandler;

  @Transactional
  public ResponseSignUp signUp(RequestSignUp request) {
    if (usersRepository.findByUsername(request.getUsername()).isPresent()
        || usersRepository.findByEmail(
        request.getEmail()).isPresent()) {
      throw new GlobalCustomException(GlobalExceptions.DUPLICATE_USER);
    }
    Users user = new Users();
    user.create(request.getUsername(), request.getEmail(), passwordEncoder.encode(
        request.getPassword()));
    usersRepository.save(user);

    return new ResponseSignUp(user.getId(), user.getUsername());
  }

  public ResponseLogIn logIn(RequestLogIn request) {
    Users user = usersRepository.findByUsername(request.getUsername())
        .orElseThrow(() -> new GlobalCustomException(GlobalExceptions.AUTH_FAILED));

    if (passwordEncoder.matches(request.getPassword(), user.getPassword_hash())) {
      throw new GlobalCustomException(GlobalExceptions.AUTH_FAILED);
    }
    // refresh token redis 저장
    String redisKey = user.getUsername() + ":refresh_token";
    redisHandler.setStringWithTtl(redisKey, jwtUtil.makeRefreshToken(user.getUsername()),
        jwtUtil.refreshTokenExpiresIn(), TimeUnit.SECONDS);

    return new ResponseLogIn(jwtUtil.makeAccessToken(user.getUsername()),
        jwtUtil.accessTokenExpiresIn());


  }
}
