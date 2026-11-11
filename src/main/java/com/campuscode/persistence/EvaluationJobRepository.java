package com.campuscode.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EvaluationJobRepository extends JpaRepository<EvaluationJobEntity, UUID> {
    Optional<EvaluationJobEntity> findByIngestionId(UUID ingestionId);
}
