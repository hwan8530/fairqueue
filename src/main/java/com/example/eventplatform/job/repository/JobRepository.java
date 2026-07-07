package com.example.eventplatform.job.repository;

import com.example.eventplatform.job.entity.Job;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, Long> {

  Optional<Job> findByIdempotency_key(String idempotency_key);

  boolean existsByIdempotency_key(String idempotency_key);

}
