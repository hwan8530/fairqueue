package com.example.eventplatform.users.repository;

import com.example.eventplatform.users.entity.Users;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsersRepository extends JpaRepository<Users, Long> {

  Optional<Users> findByUsername(String username);

  Optional<Users> findByEmail(String email);
}
