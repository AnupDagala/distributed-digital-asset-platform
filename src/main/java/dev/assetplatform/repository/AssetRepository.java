package dev.assetplatform.repository;

import dev.assetplatform.domain.Asset;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;

public interface AssetRepository
    extends JpaRepository<Asset, UUID>, JpaSpecificationExecutor<Asset> {
  Optional<Asset> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from Asset a where a.id = :id")
  Optional<Asset> lockById(UUID id);
}
