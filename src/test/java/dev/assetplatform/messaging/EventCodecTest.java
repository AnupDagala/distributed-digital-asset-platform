package dev.assetplatform.messaging;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventCodecTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final EventCodec codec = new EventCodec(mapper);

  @ParameterizedTest
  @ValueSource(strings = {"{", "null", "{}", "[]", "{\"schemaVersion\":2}"})
  void rejectsMalformedOrUnknownEvents(String json) {
    assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(InvalidEventException.class);
  }

  @Test
  void decodesVersionedEvent() throws Exception {
    var event = new ProcessingRequest(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    assertThat(codec.decode(mapper.writeValueAsString(event))).isEqualTo(event);
  }

  @Test
  void boundsMessageSize() {
    assertThatThrownBy(() -> codec.decode("x".repeat(4097)))
        .isInstanceOf(InvalidEventException.class);
  }
}
