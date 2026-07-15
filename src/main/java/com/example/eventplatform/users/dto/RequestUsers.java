package com.example.eventplatform.users.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

public class RequestUsers {

  @Getter
  @NoArgsConstructor
  public static class RequestSignUp {

    private String username;
    private String email;
    private String password;

    public RequestSignUp(String username, String email, String password) {
      this.username = username;
      this.email = email;
      this.password = password;
    }
  }

  @Getter
  @NoArgsConstructor
  public static class RequestLogIn {

    private String username;
    private String password;

    public RequestLogIn(String username, String password) {
      this.username = username;
      this.password = password;
    }
  }

}
