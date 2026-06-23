package com.example.eventplatform.users.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

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
  @Column(columnDefinition = "VARCHAR(20)")
  @ColumnDefault("'USER'")
  private Role role;
  @NotNull
  private LocalDateTime create_at;
}
