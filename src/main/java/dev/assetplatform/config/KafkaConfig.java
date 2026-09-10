package dev.assetplatform.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
public class KafkaConfig {
  @Bean
  org.springframework.kafka.listener.DefaultErrorHandler errorHandler(
      dev.assetplatform.messaging.DeadLetterRecovery recovery) {
    var handler =
        new org.springframework.kafka.listener.DefaultErrorHandler(
            recovery::recover, new org.springframework.util.backoff.FixedBackOff(1000L, 3L));
    handler.addNotRetryableExceptions(
        dev.assetplatform.messaging.InvalidEventException.class,
        dev.assetplatform.messaging.RetryBudgetExceededException.class);
    handler.setLogLevel(org.springframework.kafka.KafkaException.Level.DEBUG);
    return handler;
  }

  @Bean
  KafkaAdmin.NewTopics assetTopics(PlatformProperties props) {
    return new KafkaAdmin.NewTopics(
        new NewTopic(props.processingTopic(), props.kafkaPartitions(), (short) 1),
        new NewTopic(props.deadLetterTopic(), props.kafkaPartitions(), (short) 1));
  }
}
