package dev.assetplatform.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.http.*;
import org.springframework.stereotype.Component;

@Component
public class ErrorWriter {
  private final ObjectMapper mapper;

  public ErrorWriter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public void write(
      HttpServletRequest request,
      HttpServletResponse response,
      HttpStatus status,
      String code,
      String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), ApiError.of(status, code, message, request));
  }
}
