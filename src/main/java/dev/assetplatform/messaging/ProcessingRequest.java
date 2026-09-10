package dev.assetplatform.messaging;

import java.util.UUID;

public record ProcessingRequest(int schemaVersion, UUID eventId, UUID assetId, UUID jobId) {
  public boolean valid() {
    return schemaVersion == 1 && eventId != null && assetId != null && jobId != null;
  }
}
