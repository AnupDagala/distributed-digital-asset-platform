package dev.assetplatform.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssetTest {
  private Asset asset() {
    return new Asset(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "x.png",
        "image/png",
        12,
        "a".repeat(64),
        UUID.randomUUID().toString());
  }

  @Test
  void terminalAssetsCannotRestart() {
    Asset asset = asset();
    asset.start();
    asset.finish(AssetStatus.COMPLETED, null);
    assertThatThrownBy(asset::start).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void duplicateMustReferenceCanonicalAsset() {
    assertThatThrownBy(() -> asset().finish(AssetStatus.DUPLICATE, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void interruptedStartMayBeRetried() {
    Asset asset = asset();
    asset.start();
    asset.start();
    assertThat(asset.getStatus()).isEqualTo(AssetStatus.PROCESSING);
  }

  @Test
  void tombstoneRetainsIdentity() {
    Asset asset = asset();
    UUID id = asset.getId();
    asset.delete();
    assertThat(asset.getDeletedAt()).isNotNull();
    assertThat(asset.getId()).isEqualTo(id);
  }
}
