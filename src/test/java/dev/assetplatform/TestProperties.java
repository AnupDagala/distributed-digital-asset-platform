package dev.assetplatform;

import dev.assetplatform.config.PlatformProperties;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

public final class TestProperties {
  private TestProperties() {}

  public static String secret() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  public static PlatformProperties at(Path root) {
    return new PlatformProperties(
        root,
        20971520,
        secret(),
        "digital-asset-platform",
        Duration.ofMinutes(15),
        120,
        10,
        Duration.ofMinutes(1),
        "assets.processing.v1",
        "assets.processing.v1.dlt",
        3,
        100,
        40000000);
  }
}
