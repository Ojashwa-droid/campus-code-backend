package com.campuscode.persistence;

import com.campuscode.api.IngestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IngestionJobRepository extends JpaRepository<IngestionJobEntity, UUID> {
    List<IngestionJobEntity> findByRepositoryUrlOrderByCreatedAtDesc(String repositoryUrl);
    List<IngestionJobEntity> findByStatusIn(List<IngestionStatus.State> statuses);
}
