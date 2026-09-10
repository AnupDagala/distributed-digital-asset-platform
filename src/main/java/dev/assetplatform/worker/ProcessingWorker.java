package dev.assetplatform.worker;

import dev.assetplatform.messaging.*;
import io.micrometer.core.instrument.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ProcessingWorker {
  private static final Logger LOG = LoggerFactory.getLogger(ProcessingWorker.class);
  private final EventCodec codec;
  private final ProcessingCoordinator coordinator;
  private final MeterRegistry metrics;

  public ProcessingWorker(
      EventCodec codec, ProcessingCoordinator coordinator, MeterRegistry metrics) {
    this.codec = codec;
    this.coordinator = coordinator;
    this.metrics = metrics;
  }

  @KafkaListener(topics = "${platform.processing-topic}")
  public void consume(ConsumerRecord<String, String> record) {
    ProcessingRequest event = codec.decode(record.value());
    if (!event.assetId().toString().equals(record.key())) throw new InvalidEventException();
    Timer.Sample duration = Timer.start(metrics);
    try {
      coordinator.handle(event);
      LOG.info(
          "processing_delivery_handled eventId={} assetId={}", event.eventId(), event.assetId());
    } finally {
      duration.stop(metrics.timer("asset.processing.duration"));
    }
  }
}
