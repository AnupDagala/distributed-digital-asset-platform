CREATE TABLE app_users (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE assets (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES app_users(id),
    original_file_name varchar(180) NOT NULL,
    media_type varchar(50) NOT NULL CHECK (media_type IN ('image/jpeg','image/png','application/pdf')),
    file_size bigint NOT NULL CHECK (file_size > 0),
    sha256 varchar(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    storage_key varchar(80) NOT NULL UNIQUE,
    status varchar(20) NOT NULL CHECK (status IN ('UPLOADING','QUEUED','PROCESSING','COMPLETED','FAILED','REJECTED','DUPLICATE')),
    duplicate_of uuid REFERENCES assets(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX assets_owner_created_idx ON assets(owner_id, created_at DESC, id) WHERE deleted_at IS NULL;
CREATE INDEX assets_owner_status_created_idx ON assets(owner_id, status, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX assets_owner_media_created_idx ON assets(owner_id, media_type, created_at DESC) WHERE deleted_at IS NULL;

CREATE TABLE asset_processing_jobs (
    id uuid PRIMARY KEY,
    asset_id uuid NOT NULL UNIQUE REFERENCES assets(id),
    request_event_id uuid NOT NULL UNIQUE,
    attempts integer NOT NULL DEFAULT 0,
    finished_at timestamptz,
    failure_code varchar(60)
);
CREATE TABLE asset_metadata (
    asset_id uuid PRIMARY KEY REFERENCES assets(id),
    width integer,
    height integer,
    page_count integer
);
CREATE TABLE content_fingerprints (
    owner_id uuid NOT NULL REFERENCES app_users(id),
    sha256 varchar(64) NOT NULL,
    asset_id uuid NOT NULL UNIQUE REFERENCES assets(id),
    PRIMARY KEY (owner_id, sha256)
);
CREATE TABLE idempotency_records (
    owner_id uuid NOT NULL REFERENCES app_users(id),
    idempotency_key uuid NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    asset_id uuid NOT NULL REFERENCES assets(id),
    created_at timestamptz NOT NULL,
    PRIMARY KEY(owner_id, idempotency_key)
);
CREATE TABLE processing_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id uuid NOT NULL REFERENCES assets(id),
    status varchar(20) NOT NULL,
    code varchar(60) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX processing_events_asset_cursor_idx ON processing_events(asset_id, id);
CREATE TABLE outbox_events (
    id uuid PRIMARY KEY,
    asset_id uuid NOT NULL REFERENCES assets(id),
    event_type varchar(30) NOT NULL CHECK (event_type IN ('PROCESS_ASSET','DELETE_OBJECT')),
    payload text NOT NULL,
    created_at timestamptz NOT NULL,
    available_at timestamptz NOT NULL,
    published_at timestamptz,
    attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX outbox_pending_idx ON outbox_events(available_at, created_at) WHERE published_at IS NULL;
