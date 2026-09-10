package dev.assetplatform.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;

public record ApiError(
    Instant timestamp,
    int status,
    String error,
    String code,
    String message,
    String path,
    String requestId) {
  public static ApiError of(
      HttpStatus status, String code, String message, HttpServletRequest request) {
    return new ApiError(
        Instant.now(),
        status.value(),
        status.getReasonPhrase(),
        code,
        message,
        request.getRequestURI(),
        (String) request.getAttribute("requestId"));
  }
}
