package dev.assetplatform.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.assetplatform.TestProperties;
import dev.assetplatform.worker.ProcessingTransactions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class DeadLetterRecoveryTest {
  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

  private final ProcessingTransactions transactions = mock(ProcessingTransactions.class);
  private final ObjectMapper mapper = new ObjectMapper();

  private DeadLetterRecovery recovery() {
    return new DeadLetterRecovery(
        kafka,
        TestProperties.at(Path.of("target")),
        new EventCodec(mapper),
        transactions,
        new SimpleMeterRegistry());
  }

  @Test
  void failedDlqPublishDoesNotMarkJobFailedOrAllowRecovery() {
    when(kafka.send(anyString(), anyInt(), any(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
    assertThatThrownBy(
            () ->
                recovery()
                    .recover(new ConsumerRecord<>("in", 0, 1, "x", "{"), new RuntimeException()))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(transactions);
  }

  @Test
  void malformedMessageIsQuarantinedWithoutTouchingJobs() {
    when(kafka.send(anyString(), anyInt(), any(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    recovery().recover(new ConsumerRecord<>("in", 0, 1, "x", "{"), new InvalidEventException());
    verifyNoInteractions(transactions);
  }

  @Test
  void recoveryWaitsForPublishThenMarksCorrelatedJobFailed() throws Exception {
    var event = new ProcessingRequest(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    when(kafka.send(anyString(), anyInt(), any(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    recovery()
        .recover(
            new ConsumerRecord<>(
                "in", 0, 1, event.assetId().toString(), mapper.writeValueAsString(event)),
            new RuntimeException());
    var order = inOrder(kafka, transactions);
    order.verify(kafka).send(anyString(), anyInt(), any(), anyString());
    order.verify(transactions).fail(event);
  }
}
