package com.example.eventplatform.users.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

public class ResponseUsers {

  @Getter
  @NoArgsConstructor
  public static class ResponseSignUp {

    private Long userId;
    private String username;

    public ResponseSignUp(Long userId, String username) {
      this.userId = userId;
      this.username = username;
    }
  }

  @Setter
  public static class ResponseLogIn {

    private String accessToken;
    private String tokenType; // Bearer 고정
    private long expiresIn;

    public ResponseLogIn(String accessToken, long expiresIn) {
      this.accessToken = accessToken;
      this.tokenType = "Bearer";
      this.expiresIn = expiresIn;
    }
  }
}
