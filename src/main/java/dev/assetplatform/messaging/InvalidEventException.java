package dev.assetplatform.messaging;

public class InvalidEventException extends RuntimeException {
  public InvalidEventException() {
    super("Invalid processing event");
  }
}
