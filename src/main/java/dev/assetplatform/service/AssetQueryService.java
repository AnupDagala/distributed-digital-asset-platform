package dev.assetplatform.service;

import dev.assetplatform.domain.*;
import dev.assetplatform.dto.AssetDtos;
import dev.assetplatform.exception.ApiException;
import dev.assetplatform.repository.*;
import dev.assetplatform.security.Ownership;
import dev.assetplatform.validation.FileValidator;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetQueryService {
  private final AssetRepository assets;
  private final MetadataRepository metadata;
  private final EventRepository events;
  private final JdbcTemplate jdbc;

  public AssetQueryService(
      AssetRepository assets,
      MetadataRepository metadata,
      EventRepository events,
      JdbcTemplate jdbc) {
    this.assets = assets;
    this.metadata = metadata;
    this.events = events;
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public AssetDtos.View get(UUID owner, UUID id) {
    Asset asset = owned(owner, id);
    return AssetDtos.View.from(
        asset, metadata.findById(id).map(AssetDtos.Metadata::from).orElse(null));
  }

  @Transactional(readOnly = true)
  public AssetDtos.Status status(UUID owner, UUID id) {
    Asset asset = owned(owner, id);
    return new AssetDtos.Status(id, asset.getStatus(), asset.getUpdatedAt(), asset.getVersion());
  }

  @Transactional(readOnly = true)
  public AssetDtos.Page list(
      UUID owner, int page, int size, String sort, AssetStatus status, String mediaType) {
    if (page < 0 || page > 10000 || size < 1 || size > 100)
      throw ApiException.invalid("INVALID_PAGE", "Page must be 0..10000 and size 1..100");
    String[] parts = sort.split(",", -1);
    if (parts.length != 2
        || !Set.of("createdAt", "updatedAt", "fileSize", "originalFileName").contains(parts[0])
        || !Set.of("asc", "desc").contains(parts[1])) {
      throw ApiException.invalid(
          "INVALID_SORT", "Sort must be an allowed field followed by asc or desc");
    }
    if (mediaType != null && !FileValidator.MEDIA_TYPES.contains(mediaType))
      throw ApiException.invalid("INVALID_MEDIA_TYPE", "Unsupported media-type filter");
    Specification<Asset> spec =
        (root, query, cb) ->
            cb.and(cb.equal(root.get("ownerId"), owner), cb.isNull(root.get("deletedAt")));
    if (status != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
    if (mediaType != null)
      spec = spec.and((root, query, cb) -> cb.equal(root.get("mediaType"), mediaType));
    Sort order = Sort.by(Sort.Direction.fromString(parts[1]), parts[0]).and(Sort.by("id"));
    Page<Asset> result = assets.findAll(spec, PageRequest.of(page, size, order));
    return new AssetDtos.Page(
        result.stream().map(a -> AssetDtos.View.from(a, null)).toList(),
        page,
        size,
        result.getTotalElements(),
        result.getTotalPages());
  }

  @Transactional(readOnly = true)
  public List<AssetDtos.Event> events(UUID owner, UUID id, long cursor) {
    owned(owner, id);
    return events
        .findByAssetIdAndIdGreaterThanOrderById(id, cursor, PageRequest.of(0, 100))
        .stream()
        .map(AssetDtos.Event::from)
        .toList();
  }

  @Transactional(timeout = 30)
  public void delete(UUID owner, UUID id) {
    Asset asset = Ownership.require(assets.lockById(id).orElseThrow(ApiException::notFound), owner);
    asset.delete();
    jdbc.update("delete from content_fingerprints where asset_id = ?", id);
    jdbc.update(
        "insert into outbox_events(id, asset_id, event_type, payload, created_at, available_at)"
            + " values (?, ?, 'DELETE_OBJECT', ?, now(), now())",
        UUID.randomUUID(),
        id,
        asset.getStorageKey());
  }

  private Asset owned(UUID owner, UUID id) {
    return assets
        .findByIdAndOwnerIdAndDeletedAtIsNull(id, owner)
        .orElseThrow(ApiException::notFound);
  }
}
