package dev.assetplatform.messaging;

import dev.assetplatform.config.PlatformProperties;
import dev.assetplatform.worker.ProcessingTransactions;
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
  private final ProcessingTransactions transactions;
  private final MeterRegistry metrics;

  public DeadLetterRecovery(
      KafkaTemplate<String, String> kafka,
      PlatformProperties props,
      EventCodec codec,
      ProcessingTransactions transactions,
      MeterRegistry metrics) {
    this.kafka = kafka;
    this.props = props;
    this.codec = codec;
    this.transactions = transactions;
    this.metrics = metrics;
  }

  public void recover(ConsumerRecord<?, ?> record, Exception failure) {
    String payload = (String) record.value();
    String key = (String) record.key();
    try {
      kafka
          .send(props.deadLetterTopic(), record.partition(), key, payload)
          .get(20, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Recovery interrupted", ex);
    } catch (Exception ex) {
      throw new IllegalStateException("Dead-letter publish failed", ex);
    }
    boolean invalid = false;
    try {
      ProcessingRequest event = codec.decode(payload);
      if (!event.assetId().toString().equals(key)) throw new InvalidEventException();
      transactions.fail(event);
    } catch (InvalidEventException ex) {
      // Malformed or unrelated messages cannot be allowed to fail an arbitrary asset.
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
}
