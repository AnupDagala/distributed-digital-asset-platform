package dev.assetplatform.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> domain(ApiException ex, HttpServletRequest request) {
    return response(ex.status(), ex.code(), ex.getMessage(), request);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class
  })
  ResponseEntity<ApiError> invalid(Exception ex, HttpServletRequest request) {
    if (org.springframework.core.NestedExceptionUtils.getMostSpecificCause(ex)
        instanceof dev.assetplatform.security.BodyLimitFilter.BodyTooLargeException)
      return response(
          HttpStatus.PAYLOAD_TOO_LARGE, "BODY_TOO_LARGE", "JSON body exceeds 16 KiB", request);
    return response(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "Request is malformed or violates validation rules",
        request);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ApiError> oversized(Exception ex, HttpServletRequest request) {
    return response(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "FILE_TOO_LARGE",
        "Request exceeds the configured size limit",
        request);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ApiError> conflict(Exception ex, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT, "CONFLICT", "Request conflicts with existing data", request);
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiError> database(Exception ex, HttpServletRequest request) {
    LOG.warn("database_request_failed type={}", ex.getClass().getSimpleName());
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "SERVICE_UNAVAILABLE",
        "Service temporarily unavailable",
        request);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiError> missing(Exception ex, HttpServletRequest request) {
    return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", request);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiError> method(Exception ex, HttpServletRequest request) {
    return response(
        HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not supported", request);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiError> media(Exception ex, HttpServletRequest request) {
    return response(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "UNSUPPORTED_MEDIA_TYPE",
        "Media type not supported",
        request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
    LOG.error("request_failed type={}", ex.getClass().getSimpleName());
    return response(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "Request could not be completed",
        request);
  }

  private ResponseEntity<ApiError> response(
      HttpStatus status, String code, String message, HttpServletRequest request) {
    return ResponseEntity.status(status).body(ApiError.of(status, code, message, request));
  }
}
