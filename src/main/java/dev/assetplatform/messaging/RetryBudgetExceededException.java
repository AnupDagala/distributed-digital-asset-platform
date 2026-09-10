package dev.assetplatform.messaging;

/** The database budget also bounds attempts across consumer restarts. */
public class RetryBudgetExceededException extends RuntimeException {
  public RetryBudgetExceededException() {
    super("Persisted processing retry budget exhausted");
  }
}
