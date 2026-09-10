package dev.assetplatform.worker;

import dev.assetplatform.domain.*;
import dev.assetplatform.messaging.*;
import dev.assetplatform.repository.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

@Service
public class ProcessingTransactions {
  public static final int MAX_ATTEMPTS = 4;
  private final AssetRepository assets;
  private final JobRepository jobs;
  private final MetadataRepository metadata;
  private final EventRepository events;
  private final AssetInspector inspector;
  private final JdbcTemplate jdbc;
  private final MeterRegistry metrics;

  public ProcessingTransactions(
      AssetRepository assets,
      JobRepository jobs,
      MetadataRepository metadata,
      EventRepository events,
      AssetInspector inspector,
      JdbcTemplate jdbc,
      MeterRegistry metrics) {
    this.assets = assets;
    this.jobs = jobs;
    this.metadata = metadata;
    this.events = events;
    this.inspector = inspector;
    this.jdbc = jdbc;
    this.metrics = metrics;
  }

  @Transactional(timeout = 30, propagation = Propagation.REQUIRES_NEW)
  public boolean start(ProcessingRequest event) {
    Asset asset = assets.lockById(event.assetId()).orElseThrow(InvalidEventException::new);
    AssetProcessingJob job = verifiedJob(event);
    if (asset.getDeletedAt() != null || asset.getStatus().terminal()) return false;
    if (job.getAttempts() >= MAX_ATTEMPTS) throw new RetryBudgetExceededException();
    asset.start();
    job.beginAttempt();
    events.save(new ProcessingEvent(asset, "PROCESSING_STARTED"));
    return true;
  }

  @Transactional(timeout = 120, propagation = Propagation.REQUIRES_NEW)
  public void process(ProcessingRequest event) {
    Asset asset = assets.lockById(event.assetId()).orElseThrow(InvalidEventException::new);
    AssetProcessingJob job = verifiedJob(event);
    if (asset.getDeletedAt() != null || asset.getStatus().terminal()) return;
    try {
      AssetMetadata extracted = inspector.inspect(asset);
      jdbc.update(
          "insert into content_fingerprints(owner_id, sha256, asset_id) values (?, ?, ?) on"
              + " conflict (owner_id, sha256) do nothing",
          asset.getOwnerId(),
          asset.getSha256(),
          asset.getId());
      UUID canonical =
          jdbc.queryForObject(
              "select asset_id from content_fingerprints where owner_id = ? and sha256 = ?",
              UUID.class,
              asset.getOwnerId(),
              asset.getSha256());
      boolean duplicate = !asset.getId().equals(canonical);
      metadata.save(extracted);
      finish(
          asset,
          job,
          duplicate ? AssetStatus.DUPLICATE : AssetStatus.COMPLETED,
          duplicate ? canonical : null,
          duplicate ? "DUPLICATE_CONTENT" : "PROCESSED");
    } catch (RejectedAssetException ex) {
      finish(asset, job, AssetStatus.REJECTED, null, ex.code());
    } catch (IOException ex) {
      throw new UncheckedIOException("Asset storage read failed", ex);
    }
  }

  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public boolean pending(ProcessingRequest event) {
    Asset asset = assets.findById(event.assetId()).orElseThrow(InvalidEventException::new);
    verifiedJob(event);
    return asset.getDeletedAt() == null && !asset.getStatus().terminal();
  }

  @Transactional(timeout = 30, propagation = Propagation.REQUIRES_NEW)
  public void fail(ProcessingRequest event) {
    Asset asset = assets.lockById(event.assetId()).orElseThrow(InvalidEventException::new);
    AssetProcessingJob job = verifiedJob(event);
    if (asset.getDeletedAt() == null && !asset.getStatus().terminal())
      finish(asset, job, AssetStatus.FAILED, null, "RETRIES_EXHAUSTED");
  }

  private AssetProcessingJob verifiedJob(ProcessingRequest event) {
    AssetProcessingJob job =
        jobs.findByAssetId(event.assetId()).orElseThrow(InvalidEventException::new);
    if (!job.getId().equals(event.jobId()) || !job.getRequestEventId().equals(event.eventId()))
      throw new InvalidEventException();
    return job;
  }

  private void finish(
      Asset asset, AssetProcessingJob job, AssetStatus result, UUID canonical, String code) {
    asset.finish(result, canonical);
    job.finish(result == AssetStatus.FAILED || result == AssetStatus.REJECTED ? code : null);
    events.save(new ProcessingEvent(asset, code));
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            metrics.counter("asset.processing", "status", result.name()).increment();
          }
        });
  }
}
