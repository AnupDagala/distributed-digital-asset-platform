package dev.assetplatform.security;

import dev.assetplatform.exception.ErrorWriter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.slf4j.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class RateLimitFilter extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);
  private final RateLimiter limiter;
  private final ErrorWriter errors;
  private final MeterRegistry metrics;

  public RateLimitFilter(RateLimiter limiter, ErrorWriter errors, MeterRegistry metrics) {
    this.limiter = limiter;
    this.errors = errors;
    this.metrics = metrics;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = RequestPaths.applicationPath(request);
    boolean auth = path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/register");
    Authentication principal = SecurityContextHolder.getContext().getAuthentication();
    if (!path.startsWith("/api/v1/")
        || (!auth && (principal == null || !principal.isAuthenticated()))) {
      chain.doFilter(request, response);
      return;
    }
    boolean allowed;
    try {
      allowed = limiter.allow(auth ? request.getRemoteAddr() : principal.getName(), auth);
    } catch (RuntimeException ex) {
      LOG.warn("rate_limiter_unavailable type={}", ex.getClass().getSimpleName());
      metrics.counter("asset.rate_limit", "result", "unavailable").increment();
      errors.write(
          request,
          response,
          HttpStatus.SERVICE_UNAVAILABLE,
          "RATE_LIMITER_UNAVAILABLE",
          "Service temporarily unavailable");
      return;
    }
    if (!allowed) {
      response.setHeader("Retry-After", Long.toString(limiter.retryAfterSeconds()));
      metrics.counter("asset.rate_limit", "result", "denied").increment();
      errors.write(
          request,
          response,
          HttpStatus.TOO_MANY_REQUESTS,
          "RATE_LIMITED",
          "Request limit exceeded");
      return;
    }
    chain.doFilter(request, response);
  }
}
