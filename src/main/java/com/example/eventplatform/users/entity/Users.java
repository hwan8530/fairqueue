package com.example.eventplatform.users.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Users {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(unique = true, columnDefinition = "VARCHAR(50)")
  @NotNull
  private String username;
  @Column(unique = true, columnDefinition = "VARCHAR(255)")
  @NotNull
  private String email;
  @NotNull
  @Column(columnDefinition = "VARCHAR(255)")
  private String password_hash;
  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(columnDefinition = "VARCHAR(20)")
  private Role role = Role.USER;
  @NotNull
  private LocalDateTime create_at;
}
