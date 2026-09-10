package dev.assetplatform.controller;

import dev.assetplatform.domain.AssetStatus;
import dev.assetplatform.dto.AssetDtos;
import dev.assetplatform.service.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {
  private final AssetUploadService uploads;
  private final AssetQueryService queries;

  public AssetController(AssetUploadService uploads, AssetQueryService queries) {
    this.uploads = uploads;
    this.queries = queries;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<AssetDtos.Created> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestPart("file") MultipartFile file) {
    AssetRegistration.Result result = uploads.upload(owner(jwt), idempotencyKey(key), file);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/assets/" + result.response().id()))
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .body(result.response());
  }

  @GetMapping
  public AssetDtos.Page list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort,
      @RequestParam(required = false) AssetStatus status,
      @RequestParam(required = false) String mediaType) {
    return queries.list(owner(jwt), page, size, sort, status, mediaType);
  }

  @GetMapping("/{id}")
  public AssetDtos.View get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return queries.get(owner(jwt), id);
  }

  @GetMapping("/{id}/status")
  public AssetDtos.Status status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return queries.status(owner(jwt), id);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    queries.delete(owner(jwt), id);
  }

  private UUID idempotencyKey(String key) {
    if (key == null) return null;
    if (!key.matches(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
      throw dev.assetplatform.exception.ApiException.invalid(
          "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be a UUID");
    }
    return UUID.fromString(key);
  }

  private UUID owner(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
