package dev.assetplatform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "assets")
public class Asset {
  @Id private UUID id;
  private UUID ownerId;
  private String originalFileName;
  private String mediaType;
  private long fileSize;
  private String sha256;
  private String storageKey;

  @Enumerated(EnumType.STRING)
  private AssetStatus status;

  private UUID duplicateOf;
  private Instant createdAt;
  private Instant updatedAt;
  private Instant deletedAt;
  @Version private long version;

  protected Asset() {}

  public Asset(
      UUID id, UUID ownerId, String name, String mediaType, long size, String sha256, String key) {
    this.id = id;
    this.ownerId = ownerId;
    this.originalFileName = name;
    this.mediaType = mediaType;
    this.fileSize = size;
    this.sha256 = sha256;
    this.storageKey = key;
    this.status = AssetStatus.QUEUED;
    this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    this.updatedAt = createdAt;
  }

  public void start() {
    if (status != AssetStatus.QUEUED && status != AssetStatus.PROCESSING) {
      throw new IllegalStateException("Cannot start a terminal asset");
    }
    status = AssetStatus.PROCESSING;
    updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  public void finish(AssetStatus result, UUID canonicalId) {
    if (!result.terminal() || status.terminal())
      throw new IllegalStateException("Invalid final transition");
    if ((result == AssetStatus.DUPLICATE) != (canonicalId != null)) {
      throw new IllegalArgumentException("Duplicate requires a canonical asset");
    }
    status = result;
    duplicateOf = canonicalId;
    updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  public void delete() {
    deletedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    updatedAt = deletedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getOriginalFileName() {
    return originalFileName;
  }

  public String getMediaType() {
    return mediaType;
  }

  public long getFileSize() {
    return fileSize;
  }

  public String getSha256() {
    return sha256;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public AssetStatus getStatus() {
    return status;
  }

  public UUID getDuplicateOf() {
    return duplicateOf;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public long getVersion() {
    return version;
  }
}
