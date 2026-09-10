package dev.assetplatform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset_processing_jobs")
public class AssetProcessingJob {
  @Id private UUID id;
  private UUID assetId;
  private UUID requestEventId;
  private int attempts;
  private Instant finishedAt;
  private String failureCode;

  protected AssetProcessingJob() {}

  public AssetProcessingJob(UUID assetId, UUID eventId) {
    id = UUID.randomUUID();
    this.assetId = assetId;
    requestEventId = eventId;
  }

  public void beginAttempt() {
    attempts++;
  }

  public void finish(String code) {
    finishedAt = Instant.now();
    failureCode = code;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAssetId() {
    return assetId;
  }

  public UUID getRequestEventId() {
    return requestEventId;
  }

  public int getAttempts() {
    return attempts;
  }
}
