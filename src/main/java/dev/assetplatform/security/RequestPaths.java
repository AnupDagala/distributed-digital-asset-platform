package dev.assetplatform.security;

import jakarta.servlet.http.HttpServletRequest;

/** Use the container's decoded servlet path, matching MVC routing, not raw encoded request URI. */
final class RequestPaths {
  private RequestPaths() {}

  static String applicationPath(HttpServletRequest request) {
    if (request.getPathInfo() != null) return request.getPathInfo();
    if (!request.getServletPath().isEmpty()) return request.getServletPath();
    // MockMvc and the servlet-root mapping can expose an empty servlet path.
    return org.springframework.web.util.UriUtils.decode(
        request.getRequestURI().substring(request.getContextPath().length()),
        java.nio.charset.StandardCharsets.UTF_8);
  }
}
