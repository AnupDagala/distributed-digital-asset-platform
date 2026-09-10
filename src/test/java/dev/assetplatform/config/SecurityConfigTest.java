package dev.assetplatform.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.assetplatform.TestProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SecurityConfigTest {
  @Test
  void tokenTtlRejectsSubMinuteOverflowInsteadOfRoundingDown() {
    PlatformProperties props = mock(PlatformProperties.class);
    when(props.jwtSecret()).thenReturn(TestProperties.secret());
    var config = new SecurityConfig();
    for (Duration ttl :
        new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(1801)}) {
      when(props.tokenTtl()).thenReturn(ttl);
      assertThatThrownBy(() -> config.jwtKey(props)).isInstanceOf(IllegalArgumentException.class);
    }
    when(props.tokenTtl()).thenReturn(Duration.ofMinutes(30));
    assertThat(config.jwtKey(props).getEncoded()).hasSize(32);
  }

  @Test
  void redisWindowCannotTruncateToZeroMilliseconds() {
    assertThatThrownBy(
            () ->
                new PlatformProperties(
                    null,
                    1,
                    null,
                    null,
                    Duration.ofMinutes(1),
                    1,
                    1,
                    Duration.ofNanos(1),
                    null,
                    null,
                    1,
                    1,
                    1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
