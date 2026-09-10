package dev.assetplatform.repository;

import dev.assetplatform.domain.AssetMetadata;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetadataRepository extends JpaRepository<AssetMetadata, UUID> {}
