package dev.assetplatform.worker;

import static dev.assetplatform.storage.LocalObjectStorage.sha256;

import dev.assetplatform.messaging.ProcessingBusyException;
import dev.assetplatform.messaging.ProcessingRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds a transaction-scoped lock across independently committed attempt and result transactions.
 */
@Service
public class ProcessingCoordinator {
  private final JdbcTemplate jdbc;
  private final ProcessingTransactions transactions;

  public ProcessingCoordinator(JdbcTemplate jdbc, ProcessingTransactions transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  @Transactional(timeout = 180)
  public void handle(ProcessingRequest event) {
    lock(event);
    if (transactions.start(event)) transactions.process(event);
  }

  @Transactional(timeout = 60)
  public boolean recover(ProcessingRequest event, Runnable publish) {
    lock(event);
    if (!transactions.pending(event)) return false;
    publish.run();
    transactions.fail(event);
    return true;
  }

  private void lock(ProcessingRequest event) {
    long key =
        ByteBuffer.wrap(
                sha256().digest(("processing:" + event.assetId()).getBytes(StandardCharsets.UTF_8)))
            .getLong();
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, key))) {
      throw new ProcessingBusyException();
    }
  }
}
