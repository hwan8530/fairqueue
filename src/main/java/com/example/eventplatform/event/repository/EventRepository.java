package com.example.eventplatform.event.repository;

import com.example.eventplatform.event.entity.Event;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface EventRepository extends JpaRepository<Event, Long> {

  List<Event> findByStatus(String status);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<Event> findByIdWithLock(Long id);

}
