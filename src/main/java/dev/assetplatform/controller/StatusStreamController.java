package dev.assetplatform.controller;

import dev.assetplatform.service.StatusStreamService;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class StatusStreamController {
  private final StatusStreamService streams;

  public StatusStreamController(StatusStreamService streams) {
    this.streams = streams;
  }

  @GetMapping(value = "/api/v1/assets/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> events(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader(name = "Last-Event-ID", defaultValue = "0") long cursor) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Accel-Buffering", "no")
        .body(streams.open(UUID.fromString(jwt.getSubject()), id, cursor, jwt.getExpiresAt()));
  }
}
