package dev.assetplatform.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class EventCodec {
  private final ObjectMapper mapper;

  public EventCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public ProcessingRequest decode(String payload) {
    if (payload == null || payload.length() > 4096) throw new InvalidEventException();
    try {
      ProcessingRequest event = mapper.readValue(payload, ProcessingRequest.class);
      if (event == null || !event.valid()) throw new InvalidEventException();
      return event;
    } catch (JsonProcessingException ex) {
      throw new InvalidEventException();
    }
  }
}
