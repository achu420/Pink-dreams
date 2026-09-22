-- V010: generated_candidates (image pipeline asset metadata)
-- Previously created only via Exposed SchemaUtils.createMissingTablesAndColumns.
-- This migration documents the table for Flyway / hand-applied Postgres.
-- Note: V009 is turn_attributions (Claude); do not reuse that version number.

CREATE TABLE IF NOT EXISTS generated_candidates (
    id              UUID PRIMARY KEY,
    image_job_id    UUID NOT NULL REFERENCES image_jobs(id),
    storage_key     VARCHAR(1024) NOT NULL,
    content_type    VARCHAR(100) NOT NULL,
    file_size       BIGINT NOT NULL,
    width_px        INTEGER,
    height_px       INTEGER,
    checksum        VARCHAR(256) NOT NULL,
    candidate_index INTEGER NOT NULL,
    created_at      TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_generated_candidates_job
    ON generated_candidates(image_job_id);

-- Image generation observability events (workload=IMAGE, separate from llm_exchanges)
CREATE TABLE IF NOT EXISTS image_generation_events (
    id                  UUID PRIMARY KEY,
    image_job_id        UUID NOT NULL REFERENCES image_jobs(id),
    turn_request_id     UUID,
    conversation_id     UUID,
    persona_id          UUID,
    persona_visual_version_id UUID,
    provider            VARCHAR(64),
    model               VARCHAR(255),
    attempt             INTEGER NOT NULL DEFAULT 0,
    outcome             VARCHAR(32) NOT NULL,
    error_class         VARCHAR(64),
    error_message       TEXT,
    queue_latency_ms    BIGINT,
    generation_latency_ms BIGINT,
    download_latency_ms BIGINT,
    persistence_latency_ms BIGINT,
    total_latency_ms    BIGINT,
    asset_count         INTEGER,
    created_at          TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_image_generation_events_job
    ON image_generation_events(image_job_id);
CREATE INDEX IF NOT EXISTS idx_image_generation_events_created
    ON image_generation_events(created_at);
