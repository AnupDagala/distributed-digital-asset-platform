-- Extend the published schema; never change the checksum of V1 on an existing database.
ALTER TABLE assets ADD CONSTRAINT assets_duplicate_shape
    CHECK ((status = 'DUPLICATE') = (duplicate_of IS NOT NULL)
           AND (duplicate_of IS NULL OR duplicate_of <> id));
ALTER TABLE asset_processing_jobs ADD CONSTRAINT jobs_attempt_budget
    CHECK (attempts BETWEEN 0 AND 4);
ALTER TABLE outbox_events ADD CONSTRAINT outbox_attempts_nonnegative
    CHECK (attempts >= 0);
ALTER TABLE asset_metadata ADD CONSTRAINT metadata_shape
    CHECK ((width IS NOT NULL AND height IS NOT NULL AND width > 0 AND height > 0 AND page_count IS NULL)
        OR (width IS NULL AND height IS NULL AND page_count IS NOT NULL AND page_count > 0));
ALTER TABLE processing_events ADD CONSTRAINT processing_event_status
    CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','FAILED','REJECTED','DUPLICATE'));
