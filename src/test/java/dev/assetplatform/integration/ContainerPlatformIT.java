package dev.assetplatform.integration;

import static org.assertj.core.api.Assertions.*;

import dev.assetplatform.TestProperties;
import dev.assetplatform.security.RateLimiter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext
class ContainerPlatformIT extends PlatformContract {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-alpine");

  @Container static KafkaContainer broker = new KafkaContainer("apache/kafka-native:3.9.2");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.4.5-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.kafka.bootstrap-servers", broker::getBootstrapServers);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    registry.add("spring.data.redis.password", () -> "");
    registry.add("platform.jwt-secret", TestProperties::secret);
    registry.add("platform.storage-root", () -> "target/container-it-objects-" + UUID.randomUUID());
  }

  @Autowired org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

  @Test
  void redisWindowExpiresAndAtomicCountersEnforceLimit() {
    var config = org.mockito.Mockito.mock(dev.assetplatform.config.PlatformProperties.class);
    org.mockito.Mockito.when(config.rateWindow()).thenReturn(java.time.Duration.ofSeconds(2));
    org.mockito.Mockito.when(config.requestsPerMinute()).thenReturn(5);
    RateLimiter limiter = new RateLimiter(redisTemplate, config);
    String identity = UUID.randomUUID().toString();
    java.util.concurrent.atomic.AtomicInteger allowed =
        new java.util.concurrent.atomic.AtomicInteger();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(10)) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 20; i++)
        futures.add(
            pool.submit(
                () -> {
                  if (limiter.allow(identity, false)) allowed.incrementAndGet();
                }));
      for (var future : futures) {
        try {
          future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ex) {
          throw new AssertionError(ex);
        }
      }
    }
    assertThat(allowed.get()).isEqualTo(5);
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .until(() -> limiter.allow(identity, false));
  }
}
