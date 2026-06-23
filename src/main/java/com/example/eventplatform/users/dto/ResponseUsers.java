package com.example.eventplatform.users.dto;

import lombok.AllArgsConstructor;
import lombok.Setter;

public class ResponseUsers {

  @Setter
  @AllArgsConstructor
  public static class ResponseSignUp {

    private Long userId;
    private String username;
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
