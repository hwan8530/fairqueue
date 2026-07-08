package com.example.eventplatform.job.repository;

import com.example.eventplatform.job.entity.Job;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, Long> {

  @Query("select j from Job j where j.idempotency_key = :idempotency_key")
  Optional<Job> findByIdempotency_key(@Param("idempotency_key") String idempotency_key);

  @Query("select j from Job j where j.idempotency_key = :idempotency_key")
  boolean existsByIdempotency_key(@Param("idempotency_key") String idempotency_key);

}
