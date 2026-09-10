package dev.assetplatform.service;

import dev.assetplatform.config.PlatformProperties;
import dev.assetplatform.dto.AssetDtos;
import dev.assetplatform.exception.ApiException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class StatusStreamService {
  private static final Logger LOG = LoggerFactory.getLogger(StatusStreamService.class);
  private final AssetQueryService queries;
  private final Semaphore capacity;
  private final ConcurrentMap<UUID, Connection> connections = new ConcurrentHashMap<>();

  private static class Connection {
    final UUID id = UUID.randomUUID(), owner, asset;
    final SseEmitter emitter;
    final Instant deadline;
    final AtomicBoolean closed = new AtomicBoolean();
    long cursor;
    Instant heartbeat = Instant.EPOCH;

    Connection(UUID owner, UUID asset, long cursor, Instant deadline, SseEmitter emitter) {
      this.owner = owner;
      this.asset = asset;
      this.cursor = cursor;
      this.deadline = deadline;
      this.emitter = emitter;
    }
  }

  public StatusStreamService(AssetQueryService queries, PlatformProperties props) {
    this.queries = queries;
    capacity = new Semaphore(props.sseMaxConnections());
  }

  public SseEmitter open(UUID owner, UUID asset, long cursor, Instant tokenExpires) {
    if (cursor < 0)
      throw ApiException.invalid("INVALID_CURSOR", "Last-Event-ID must be non-negative");
    queries.status(owner, asset);
    if (!capacity.tryAcquire())
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS, "STREAM_CAPACITY", "Stream capacity reached; retry later");
    Instant deadline = Instant.now().plusSeconds(60);
    if (tokenExpires.isBefore(deadline)) deadline = tokenExpires;
    SseEmitter emitter =
        new SseEmitter(Math.max(1, java.time.Duration.between(Instant.now(), deadline).toMillis()));
    Connection connection = new Connection(owner, asset, cursor, deadline, emitter);
    emitter.onCompletion(() -> close(connection));
    emitter.onTimeout(
        () -> {
          close(connection);
          emitter.complete();
        });
    emitter.onError(ex -> close(connection));
    connections.put(connection.id, connection);
    return emitter;
  }

  @Scheduled(fixedDelayString = "${platform.sse-poll-ms:1000}")
  public void poll() {
    for (Connection connection : connections.values()) send(connection);
  }

  private void send(Connection connection) {
    if (connection.closed.get()) return;
    try {
      if (!Instant.now().isBefore(connection.deadline)) {
        close(connection);
        connection.emitter.complete();
        return;
      }
      List<AssetDtos.Event> events =
          queries.events(connection.owner, connection.asset, connection.cursor);
      for (AssetDtos.Event event : events) {
        connection.emitter.send(
            SseEmitter.event().id(Long.toString(event.id())).name("status").data(event));
        connection.cursor = event.id();
        if (event.status().terminal()) {
          close(connection);
          connection.emitter.complete();
          return;
        }
      }
      if (events.isEmpty()
          && queries.status(connection.owner, connection.asset).status().terminal()) {
        close(connection);
        connection.emitter.complete();
        return;
      }
      if (connection.heartbeat.plusSeconds(15).isBefore(Instant.now())) {
        connection.emitter.send(SseEmitter.event().comment("keepalive"));
        connection.heartbeat = Instant.now();
      }
    } catch (IOException | IllegalStateException ex) {
      LOG.debug("status_stream_disconnected type={}", ex.getClass().getSimpleName());
      close(connection);
      connection.emitter.complete();
    } catch (RuntimeException ex) {
      LOG.warn("status_stream_failed type={}", ex.getClass().getSimpleName());
      try {
        connection.emitter.send(
            SseEmitter.event().name("error").data(Map.of("code", "STREAM_UNAVAILABLE")));
      } catch (IOException | IllegalStateException disconnected) {
        LOG.debug("status_stream_error_delivery_failed");
      }
      close(connection);
      connection.emitter.complete();
    }
  }

  private void close(Connection connection) {
    if (connection.closed.compareAndSet(false, true)) {
      connections.remove(connection.id);
      capacity.release();
    }
  }

  @PreDestroy
  public void shutdown() {
    for (Connection connection : connections.values()) {
      close(connection);
      connection.emitter.complete();
    }
  }
}
