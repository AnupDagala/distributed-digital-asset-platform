package dev.assetplatform.integration;

import static org.mockito.Mockito.*;

import dev.assetplatform.TestProperties;
import dev.assetplatform.security.RateLimiter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@EmbeddedKafka(
    partitions = 3,
    adminTimeout = 120,
    topics = {"assets.processing.v1", "assets.processing.v1.dlt"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers",
    brokerProperties = {"log.cleaner.enable=false"})
@TestPropertySource(properties = "management.health.redis.enabled=false")
@DirtiesContext
class LocalPlatformIT extends PlatformContract {
  @MockitoBean RateLimiter limiter;

  @BeforeEach
  void allowTraffic() {
    when(limiter.allow(anyString(), anyBoolean())).thenReturn(true);
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
    registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USER"));
    registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
    registry.add("spring.data.redis.password", () -> "");
    registry.add("platform.jwt-secret", TestProperties::secret);
    registry.add("platform.storage-root", () -> "target/local-it-objects-" + UUID.randomUUID());
  }
}
