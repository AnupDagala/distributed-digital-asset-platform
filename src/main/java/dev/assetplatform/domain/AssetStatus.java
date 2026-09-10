package dev.assetplatform.domain;

public enum AssetStatus {
  UPLOADING,
  QUEUED,
  PROCESSING,
  COMPLETED,
  FAILED,
  REJECTED,
  DUPLICATE;

  public boolean terminal() {
    return this == COMPLETED || this == FAILED || this == REJECTED || this == DUPLICATE;
  }
}
