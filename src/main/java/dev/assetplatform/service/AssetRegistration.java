package dev.assetplatform.service;

import static dev.assetplatform.storage.LocalObjectStorage.sha256;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.assetplatform.domain.*;
import dev.assetplatform.dto.AssetDtos.Created;
import dev.assetplatform.exception.ApiException;
import dev.assetplatform.messaging.ProcessingRequest;
import dev.assetplatform.repository.*;
import dev.assetplatform.storage.ObjectStorage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import org.slf4j.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

@Service
public class AssetRegistration {
  public record Result(Created response, boolean replayed) {}

  private static final Logger LOG = LoggerFactory.getLogger(AssetRegistration.class);
  private final JdbcTemplate jdbc;
  private final AssetRepository assets;
  private final JobRepository jobs;
  private final EventRepository events;
  private final ObjectMapper mapper;
  private final ObjectStorage storage;

  public AssetRegistration(
      JdbcTemplate jdbc,
      AssetRepository assets,
      JobRepository jobs,
      EventRepository events,
      ObjectMapper mapper,
      ObjectStorage storage) {
    this.jdbc = jdbc;
    this.assets = assets;
    this.jobs = jobs;
    this.events = events;
    this.mapper = mapper;
    this.storage = storage;
  }

  @Transactional(timeout = 30)
  public Result register(
      UUID owner, UUID key, String name, String mediaType, ObjectStorage.StoredObject object) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            // Unknown commit outcome keeps bytes: removing them could corrupt a committed asset.
            if (status == STATUS_ROLLED_BACK) cleanup(object.key());
          }
        });
    String fingerprint =
        HexFormat.of()
            .formatHex(
                sha256()
                    .digest(
                        (name + "\0" + mediaType + "\0" + object.size() + "\0" + object.sha256())
                            .getBytes(StandardCharsets.UTF_8)));
    if (key != null) {
      long lock =
          ByteBuffer.wrap(sha256().digest((owner + ":" + key).getBytes(StandardCharsets.UTF_8)))
              .getLong();
      jdbc.queryForList("select pg_advisory_xact_lock(?)", lock);
      List<Map<String, Object>> existing =
          jdbc.queryForList(
              "select request_fingerprint, asset_id from idempotency_records where owner_id = ? and"
                  + " idempotency_key = ?",
              owner,
              key);
      if (!existing.isEmpty()) {
        if (!fingerprint.equals(existing.getFirst().get("request_fingerprint"))) {
          throw new ApiException(
              HttpStatus.CONFLICT,
              "IDEMPOTENCY_CONFLICT",
              "Idempotency key was already used for different input");
        }
        UUID id = (UUID) existing.getFirst().get("asset_id");
        return new Result(Created.from(assets.findById(id).orElseThrow()), true);
      }
    }
    Asset asset =
        assets.saveAndFlush(
            new Asset(
                UUID.randomUUID(),
                owner,
                name,
                mediaType,
                object.size(),
                object.sha256(),
                object.key()));
    UUID eventId = UUID.randomUUID();
    AssetProcessingJob job = jobs.saveAndFlush(new AssetProcessingJob(asset.getId(), eventId));
    ProcessingRequest request = new ProcessingRequest(1, eventId, asset.getId(), job.getId());
    String payload;
    try {
      payload = mapper.writeValueAsString(request);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Cannot serialize internal event", ex);
    }
    jdbc.update(
        "insert into outbox_events(id, asset_id, event_type, payload, created_at, available_at)"
            + " values (?, ?, 'PROCESS_ASSET', ?, now(), now())",
        eventId,
        asset.getId(),
        payload);
    events.save(new ProcessingEvent(asset, "ACCEPTED"));
    if (key != null)
      jdbc.update(
          "insert into idempotency_records(owner_id, idempotency_key, request_fingerprint,"
              + " asset_id, created_at) values (?, ?, ?, ?, ?)",
          owner,
          key,
          fingerprint,
          asset.getId(),
          Timestamp.from(asset.getCreatedAt()));
    return new Result(Created.from(asset), false);
  }

  public void cleanup(String key) {
    try {
      storage.delete(key);
    } catch (IOException ex) {
      LOG.warn("object_cleanup_failed storageKey={} type={}", key, ex.getClass().getSimpleName());
    }
  }
}
