package dev.assetplatform.messaging;

import dev.assetplatform.config.PlatformProperties;
import dev.assetplatform.worker.ProcessingCoordinator;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterRecovery {
  private static final Logger LOG = LoggerFactory.getLogger(DeadLetterRecovery.class);
  private final KafkaTemplate<String, String> kafka;
  private final PlatformProperties props;
  private final EventCodec codec;
  private final ProcessingCoordinator coordinator;
  private final MeterRegistry metrics;

  public DeadLetterRecovery(
      KafkaTemplate<String, String> kafka,
      PlatformProperties props,
      EventCodec codec,
      ProcessingCoordinator coordinator,
      MeterRegistry metrics) {
    this.kafka = kafka;
    this.props = props;
    this.codec = codec;
    this.coordinator = coordinator;
    this.metrics = metrics;
  }

  public void recover(ConsumerRecord<?, ?> record, Exception failure) {
    // A busy delivery has not attempted processing. Do not quarantine or fail its active peer.
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ProcessingBusyException) throw new ProcessingBusyException();
    }
    String payload = (String) record.value();
    String key = (String) record.key();
    boolean invalid = false;
    try {
      ProcessingRequest event = codec.decode(payload);
      if (!event.assetId().toString().equals(key)) throw new InvalidEventException();
      if (!coordinator.recover(event, () -> publish(record, key, payload))) return;
    } catch (InvalidEventException ex) {
      publish(record, key, payload);
      invalid = true;
    }
    metrics
        .counter("asset.deadletter", "kind", invalid ? "invalid" : "processing_failed")
        .increment();
    LOG.warn(
        "processing_deadletter topic={} partition={} offset={} type={}",
        record.topic(),
        record.partition(),
        record.offset(),
        failure.getClass().getSimpleName());
  }

  private void publish(ConsumerRecord<?, ?> record, String key, String payload) {
    try {
      kafka
          .send(props.deadLetterTopic(), record.partition(), key, payload)
          .get(20, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Recovery interrupted", ex);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
      throw new IllegalStateException("Dead-letter publish failed", ex);
    }
  }
}
