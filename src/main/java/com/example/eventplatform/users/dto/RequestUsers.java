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
  }

  @Getter
  @NoArgsConstructor
  public static class RequestLogIn {

    private String username;
    private String password;
  }

}
