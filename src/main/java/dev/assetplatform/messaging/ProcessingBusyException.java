package dev.assetplatform.messaging;

/** Another delivery owns this asset; contention must not consume its processing budget. */
public class ProcessingBusyException extends RuntimeException {
  public ProcessingBusyException() {
    super("Asset processing is already in progress");
  }
}
