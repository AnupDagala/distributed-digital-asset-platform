package dev.assetplatform.security;

import dev.assetplatform.domain.Asset;
import dev.assetplatform.exception.ApiException;
import java.util.UUID;

public final class Ownership {
  private Ownership() {}

  public static Asset require(Asset asset, UUID owner) {
    if (!asset.getOwnerId().equals(owner) || asset.getDeletedAt() != null)
      throw ApiException.notFound();
    return asset;
  }
}
