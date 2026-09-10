package dev.assetplatform.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "Asset not found");
  }

  public static ApiException invalid(String code, String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, code, message);
  }
}
