package dev.assetplatform.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.assetplatform.exception.ErrorWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitFilterTest {
  private final RateLimiter limiter = mock(RateLimiter.class);
  private final RateLimitFilter filter =
      new RateLimitFilter(
          limiter,
          new ErrorWriter(JsonMapper.builder().findAndAddModules().build()),
          new SimpleMeterRegistry());

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void encodedLoginPathCannotBypassAuthQuota() throws Exception {
    var req = new MockHttpServletRequest("POST", "/platform/api/v1/auth/%6cogin");
    req.setContextPath("/platform");
    req.setServletPath("/api/v1/auth/login");
    var res = new MockHttpServletResponse();
    filter.doFilter(req, res, new MockFilterChain());
    verify(limiter).allow(req.getRemoteAddr(), true);
    assertThat(res.getStatus()).isEqualTo(429);
  }

  @Test
  void contextPathDoesNotBypassAuthQuota() throws Exception {
    var req = new MockHttpServletRequest("POST", "/platform/api/v1/auth/login");
    req.setContextPath("/platform");
    var res = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(req, res, chain);
    verify(limiter).allow(req.getRemoteAddr(), true);
    assertThat(res.getStatus()).isEqualTo(429);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void contextPathDoesNotBypassSignedUserQuota() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("owner", null, List.of()));
    var req = new MockHttpServletRequest("GET", "/platform/api/v1/assets");
    req.setContextPath("/platform");
    var res = new MockHttpServletResponse();
    filter.doFilter(req, res, new MockFilterChain());
    verify(limiter).allow("owner", false);
    assertThat(res.getStatus()).isEqualTo(429);
  }

  @Test
  void deniesExceededQuotaWithRetryHeader() throws Exception {
    when(limiter.retryAfterSeconds()).thenReturn(60L);
    var req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    var res = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(req, res, chain);
    assertThat(res.getStatus()).isEqualTo(429);
    assertThat(res.getHeader("Retry-After")).isEqualTo("60");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void redisOutageFailsClosed() throws Exception {
    when(limiter.allow(anyString(), anyBoolean())).thenThrow(new IllegalStateException());
    var res = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest("POST", "/api/v1/auth/register"), res, new MockFilterChain());
    assertThat(res.getStatus()).isEqualTo(503);
  }

  @Test
  void usesSignedIdentityInsteadOfClientOwnerOrForwardedIp() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("owner", null, List.of()));
    when(limiter.allow("owner", false)).thenReturn(true);
    var req = new MockHttpServletRequest("GET", "/api/v1/assets");
    req.addHeader("X-Forwarded-For", "spoof");
    var chain = new MockFilterChain();
    filter.doFilter(req, new MockHttpServletResponse(), chain);
    verify(limiter).allow("owner", false);
    assertThat(chain.getRequest()).isNotNull();
  }
}
