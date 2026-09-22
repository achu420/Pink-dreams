-- V013: Image model evaluation runs (Task 12)
-- Reuses image_jobs / generated_candidates; does not change production IMAGE_MODEL.

CREATE TABLE IF NOT EXISTS image_evaluations (
    id                          UUID PRIMARY KEY,
    persona_id                  UUID NOT NULL,
    visual_version_id           UUID NOT NULL,
    seed_prompt                 TEXT NOT NULL,
    candidate_count             INTEGER NOT NULL DEFAULT 4,
    status                      VARCHAR(50) NOT NULL DEFAULT 'RUNNING',
    production_model_snapshot   VARCHAR(255),
    created_by                  VARCHAR(255),
    created_at                  TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_image_evaluations_persona
    ON image_evaluations (persona_id, created_at DESC);

CREATE TABLE IF NOT EXISTS image_evaluation_models (
    id                          UUID PRIMARY KEY,
    evaluation_id               UUID NOT NULL REFERENCES image_evaluations(id),
    model_id                    VARCHAR(255) NOT NULL,
    display_name                VARCHAR(255),
    provider                    VARCHAR(100) NOT NULL DEFAULT 'openrouter',
    image_job_id                UUID,
    status                      VARCHAR(50) NOT NULL DEFAULT 'QUEUED',
    sort_order                  INTEGER NOT NULL DEFAULT 0,
    notes                       TEXT,
    identity_consistency        INTEGER,
    scene_adherence             INTEGER,
    pose_adherence              INTEGER,
    wardrobe_adherence          INTEGER,
    image_quality               INTEGER,
    naturalness                 INTEGER,
    artifact_quality            INTEGER,
    provider_restriction_notes  TEXT,
    created_at                  TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_image_evaluation_models_eval
    ON image_evaluation_models (evaluation_id, sort_order);
