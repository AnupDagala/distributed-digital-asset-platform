package dev.assetplatform.observability;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader("X-Request-ID");
    String id =
        supplied != null && supplied.matches("[A-Za-z0-9_-]{1,64}")
            ? supplied
            : UUID.randomUUID().toString();
    request.setAttribute("requestId", id);
    response.setHeader("X-Request-ID", id);
    try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", id)) {
      chain.doFilter(request, response);
    }
  }
}
