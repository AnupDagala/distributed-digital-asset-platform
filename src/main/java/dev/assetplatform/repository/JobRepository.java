package dev.assetplatform.repository;

import dev.assetplatform.domain.AssetProcessingJob;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<AssetProcessingJob, UUID> {
  Optional<AssetProcessingJob> findByAssetId(UUID assetId);
}
