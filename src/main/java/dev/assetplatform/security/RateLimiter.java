package dev.assetplatform.security;

import dev.assetplatform.config.PlatformProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {
  private static final DefaultRedisScript<Long> COUNT =
      new DefaultRedisScript<>(
          "local n = redis.call('INCR', KEYS[1]); if n == 1 then redis.call('PEXPIRE', KEYS[1],"
              + " ARGV[1]); end; return n",
          Long.class);
  private final StringRedisTemplate redis;
  private final PlatformProperties props;

  public RateLimiter(StringRedisTemplate redis, PlatformProperties props) {
    this.redis = redis;
    this.props = props;
  }

  public boolean allow(String identity, boolean authentication) {
    String key = "asset-rate:" + (authentication ? "auth:" : "api:") + identity;
    Long count = redis.execute(COUNT, List.of(key), Long.toString(props.rateWindow().toMillis()));
    if (count == null) throw new IllegalStateException("Rate counter unavailable");
    return count <= (authentication ? props.authRequestsPerMinute() : props.requestsPerMinute());
  }

  public long retryAfterSeconds() {
    return Math.max(1, (props.rateWindow().toMillis() + 999) / 1000);
  }
}
