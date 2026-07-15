package com.example.eventplatform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.eventplatform.users.dto.RequestUsers.RequestLogIn;
import com.example.eventplatform.users.dto.RequestUsers.RequestSignUp;
import com.example.eventplatform.users.dto.ResponseUsers.ResponseLogIn;
import com.example.eventplatform.users.dto.ResponseUsers.ResponseSignUp;
import com.example.eventplatform.users.entity.Users;
import com.example.eventplatform.users.repository.UsersRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class AuthTest extends IntegrationTestSupport {

  @Autowired
  private UsersRepository usersRepository;
  
  @Autowired
  private PasswordEncoder passwordEncoder;
  
  @BeforeEach
  void cleanUp() {
    try {
      usersRepository.deleteAll();
      usersRepository.flush();
    } catch (Exception e) {
      System.err.println("Error during cleanup: " + e.getMessage());
    }
  }

  @Test
  @DisplayName("login test")
  void login() {
    // Create user directly via repository instead of HTTP
    long uniqueId = System.currentTimeMillis();
    Users user = new Users();
    user.setUsername("testuser" + uniqueId);
    user.setEmail("testuser" + uniqueId + "@example.com");
    user.setPassword_hash(passwordEncoder.encode("TestPass123!"));
    user.setCreate_at(LocalDateTime.now());
    usersRepository.save(user);

    // Now test login via REST
    RestClient restClient = syncClient();
    RequestLogIn requestLogIn = new RequestLogIn("testuser" + uniqueId, "TestPass123!");
    ResponseEntity<ResponseLogIn> responseLogin = restClient.post().uri("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(requestLogIn).retrieve().toEntity(ResponseLogIn.class);
    
    assertNotNull(responseLogin);
    assertNotNull(responseLogin.getBody());
    assertEquals(200, responseLogin.getStatusCode().value());
    assertEquals("Bearer", responseLogin.getBody().getTokenType());
    assertNotNull(responseLogin.getBody().getAccessToken());
    assertNotEquals(0, responseLogin.getBody().getExpiresIn());
  }

}
