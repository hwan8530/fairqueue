package com.example.eventplatform.event.repository;

import com.example.eventplatform.event.entity.Event;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {

  List<Event> findByStatus(String status);

}
