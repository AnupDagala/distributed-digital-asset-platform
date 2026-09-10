package dev.assetplatform.dto;

import dev.assetplatform.domain.*;
import java.time.Instant;
import java.util.*;

public final class AssetDtos {
  private AssetDtos() {}

  public record Created(UUID id, AssetStatus status, Instant createdAt) {
    public static Created from(Asset asset) {
      return new Created(asset.getId(), AssetStatus.QUEUED, asset.getCreatedAt());
    }
  }

  public record Metadata(Integer width, Integer height, Integer pageCount) {
    public static Metadata from(AssetMetadata metadata) {
      return new Metadata(metadata.getWidth(), metadata.getHeight(), metadata.getPageCount());
    }
  }

  public record View(
      UUID id,
      String originalFileName,
      String mediaType,
      long fileSize,
      String sha256,
      AssetStatus status,
      UUID duplicateOf,
      Instant createdAt,
      Instant updatedAt,
      long version,
      Metadata metadata) {
    public static View from(Asset asset, Metadata metadata) {
      return new View(
          asset.getId(),
          asset.getOriginalFileName(),
          asset.getMediaType(),
          asset.getFileSize(),
          asset.getSha256(),
          asset.getStatus(),
          asset.getDuplicateOf(),
          asset.getCreatedAt(),
          asset.getUpdatedAt(),
          asset.getVersion(),
          metadata);
    }
  }

  public record Status(UUID id, AssetStatus status, Instant updatedAt, long version) {}

  public record Page(List<View> content, int page, int size, long totalElements, int totalPages) {}

  public record Event(long id, AssetStatus status, String code, Instant createdAt) {
    public static Event from(ProcessingEvent event) {
      return new Event(event.getId(), event.getStatus(), event.getCode(), event.getCreatedAt());
    }
  }
}
