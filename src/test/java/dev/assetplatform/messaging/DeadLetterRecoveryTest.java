package dev.assetplatform.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.assetplatform.TestProperties;
import dev.assetplatform.worker.ProcessingCoordinator;
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

  private final ProcessingCoordinator coordinator = mock(ProcessingCoordinator.class);
  private final ObjectMapper mapper = new ObjectMapper();

  private DeadLetterRecovery recovery() {
    return new DeadLetterRecovery(
        kafka,
        TestProperties.at(Path.of("target")),
        new EventCodec(mapper),
        coordinator,
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
    verifyNoInteractions(coordinator);
  }

  @Test
  void malformedMessageIsQuarantinedWithoutTouchingJobs() {
    when(kafka.send(anyString(), anyInt(), any(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    recovery().recover(new ConsumerRecord<>("in", 0, 1, "x", "{"), new InvalidEventException());
    verifyNoInteractions(coordinator);
  }

  @Test
  void recoveryWaitsForPublishThenMarksCorrelatedJobFailed() throws Exception {
    var event = new ProcessingRequest(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    when(kafka.send(anyString(), anyInt(), any(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(coordinator.recover(eq(event), any()))
        .thenAnswer(
            call -> {
              call.getArgument(1, Runnable.class).run();
              return true;
            });
    recovery()
        .recover(
            new ConsumerRecord<>(
                "in", 0, 1, event.assetId().toString(), mapper.writeValueAsString(event)),
            new RuntimeException());
    verify(coordinator).recover(eq(event), any());
    verify(kafka).send(anyString(), anyInt(), any(), anyString());
  }

  @Test
  void busyDeliveryCannotFailOrQuarantineItsActivePeer() {
    assertThatThrownBy(
            () ->
                recovery()
                    .recover(
                        new ConsumerRecord<>("in", 0, 1, "x", "{"),
                        new RuntimeException(new ProcessingBusyException())))
        .isInstanceOf(ProcessingBusyException.class);
    verifyNoInteractions(coordinator, kafka);
  }

  @Test
  void completedDeliveryDoesNotPublishMisleadingDeadLetter() throws Exception {
    var event = new ProcessingRequest(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    recovery()
        .recover(
            new ConsumerRecord<>(
                "in", 0, 1, event.assetId().toString(), mapper.writeValueAsString(event)),
            new RuntimeException());
    verify(coordinator).recover(eq(event), any());
    verifyNoInteractions(kafka);
  }
}
