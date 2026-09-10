package dev.assetplatform.security;

import static org.assertj.core.api.Assertions.*;

import dev.assetplatform.domain.Asset;
import dev.assetplatform.exception.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnershipTest {
  @Test
  void deniesForeignAndDeletedAssetsWithSameNotFoundCode() {
    UUID owner = UUID.randomUUID();
    Asset asset =
        new Asset(
            UUID.randomUUID(),
            owner,
            "file.pdf",
            "application/pdf",
            1,
            "a".repeat(64),
            UUID.randomUUID().toString());
    assertThat(Ownership.require(asset, owner)).isSameAs(asset);
    assertThatThrownBy(() -> Ownership.require(asset, UUID.randomUUID()))
        .isInstanceOf(ApiException.class)
        .hasMessage("Asset not found");
    asset.delete();
    assertThatThrownBy(() -> Ownership.require(asset, owner))
        .isInstanceOf(ApiException.class)
        .hasMessage("Asset not found");
  }
}
