package dev.assetplatform.messaging;

import dev.assetplatform.config.PlatformProperties;
import dev.assetplatform.storage.ObjectStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(
    name = "platform.outbox-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxRelay {
  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
  private final JdbcTemplate jdbc;
  private final KafkaTemplate<String, String> kafka;
  private final ObjectStorage storage;
  private final PlatformProperties props;
  private final TransactionTemplate transaction;
  private final MeterRegistry metrics;

  public OutboxRelay(
      JdbcTemplate jdbc,
      KafkaTemplate<String, String> kafka,
      ObjectStorage storage,
      PlatformProperties props,
      org.springframework.transaction.PlatformTransactionManager manager,
      MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.kafka = kafka;
    this.storage = storage;
    this.props = props;
    this.metrics = metrics;
    transaction = new TransactionTemplate(manager);
    transaction.setTimeout(30);
  }

  @Scheduled(fixedDelayString = "${platform.outbox-delay-ms:500}")
  public void tick() {
    try {
      for (int i = 0;
          i < 16 && Boolean.TRUE.equals(transaction.execute(status -> publishOne()));
          i++) {
        /* bounded drain */
      }
    } catch (RuntimeException ex) {
      metrics.counter("asset.outbox.transaction.failures").increment();
      LOG.warn("outbox_transaction_failed type={}", ex.getClass().getSimpleName());
    }
  }

  private boolean publishOne() {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select id, asset_id, event_type, payload, attempts from outbox_events where"
                + " published_at is null and available_at <= now() order by available_at,"
                + " created_at limit 1 for update skip locked");
    if (rows.isEmpty()) return false;
    Map<String, Object> row = rows.getFirst();
    UUID id = (UUID) row.get("id");
    try {
      if (row.get("event_type").equals("DELETE_OBJECT"))
        storage.delete((String) row.get("payload"));
      else
        kafka
            .send(
                props.processingTopic(),
                row.get("asset_id").toString(),
                (String) row.get("payload"))
            .get(20, TimeUnit.SECONDS);
    } catch (Exception ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Outbox interrupted", ex);
      }
      int attempts = ((Number) row.get("attempts")).intValue();
      long delay = Math.min(60, 1L << Math.min(attempts, 6));
      jdbc.update(
          "update outbox_events set attempts = attempts + 1, available_at = ? where id = ?",
          Timestamp.from(Instant.now().plusSeconds(delay)),
          id);
      countAfterCommit("retry");
      LOG.warn(
          "outbox_publish_failed eventId={} attempt={} type={}",
          id,
          attempts + 1,
          ex.getClass().getSimpleName());
      return true;
    }
    jdbc.update(
        "update outbox_events set published_at = now(), attempts = attempts + 1 where id = ?", id);
    countAfterCommit("acknowledged");
    return true;
  }

  private void countAfterCommit(String result) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            metrics.counter("asset.outbox.publish", "result", result).increment();
          }
        });
  }
}
