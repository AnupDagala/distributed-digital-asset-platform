package dev.assetplatform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processing_events")
public class ProcessingEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private UUID assetId;

  @Enumerated(EnumType.STRING)
  private AssetStatus status;

  private String code;
  private Instant createdAt;

  protected ProcessingEvent() {}

  public ProcessingEvent(Asset asset, String code) {
    assetId = asset.getId();
    status = asset.getStatus();
    this.code = code;
    createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public AssetStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
