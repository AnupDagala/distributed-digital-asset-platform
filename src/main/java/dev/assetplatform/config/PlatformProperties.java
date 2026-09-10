package dev.assetplatform.config;

import jakarta.validation.constraints.*;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("platform")
public record PlatformProperties(
    @NotNull Path storageRoot,
    @Min(1) @Max(104857600) long maxFileBytes,
    @NotBlank String jwtSecret,
    @NotBlank String jwtIssuer,
    @NotNull Duration tokenTtl,
    @Min(1) int requestsPerMinute,
    @Min(1) int authRequestsPerMinute,
    @NotNull Duration rateWindow,
    @NotBlank String processingTopic,
    @NotBlank String deadLetterTopic,
    @Min(1) int kafkaPartitions,
    @Min(1) int sseMaxConnections,
    @Min(1) long maxImagePixels) {
  public PlatformProperties {
    if (rateWindow != null
        && (rateWindow.isZero()
            || rateWindow.isNegative()
            || rateWindow.compareTo(Duration.ofHours(1)) > 0)) {
      throw new IllegalArgumentException("Rate window must be positive and at most one hour");
    }
  }
}
