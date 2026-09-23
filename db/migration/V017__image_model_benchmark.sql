-- V017: Multi-model image benchmark (Task 30)
-- Orchestration/evaluation only. Reuses image_jobs / generated_candidates.

CREATE TABLE IF NOT EXISTS benchmark_run (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    notes           TEXT,
    created_by      VARCHAR(255),
    created_at      TIMESTAMP NOT NULL,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP
);

CREATE TABLE IF NOT EXISTS benchmark_model (
    id                      UUID PRIMARY KEY,
    benchmark_run_id        UUID NOT NULL REFERENCES benchmark_run(id),
    slot_key                VARCHAR(100) NOT NULL,
    model_id                VARCHAR(255),
    provider                VARCHAR(100) NOT NULL DEFAULT 'openrouter',
    display_name            VARCHAR(255) NOT NULL,
    capability_snapshot     TEXT,
    pricing_snapshot        TEXT,
    status                  VARCHAR(50) NOT NULL,
    sort_order              INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS ix_benchmark_model_run
    ON benchmark_model (benchmark_run_id, sort_order);

CREATE TABLE IF NOT EXISTS benchmark_prompt (
    id                  UUID PRIMARY KEY,
    benchmark_run_id    UUID NOT NULL REFERENCES benchmark_run(id),
    prompt_id           VARCHAR(50) NOT NULL,
    prompt_text         TEXT NOT NULL,
    category            VARCHAR(100) NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_benchmark_prompt_run
    ON benchmark_prompt (benchmark_run_id, prompt_id);

CREATE TABLE IF NOT EXISTS benchmark_execution (
    id                          UUID PRIMARY KEY,
    benchmark_run_id            UUID NOT NULL REFERENCES benchmark_run(id),
    persona_id                  UUID NOT NULL,
    visual_identity_version_id  UUID NOT NULL,
    prompt_id                   VARCHAR(50) NOT NULL,
    model_id                    VARCHAR(255),
    slot_key                    VARCHAR(100) NOT NULL,
    references_available        TEXT,
    references_sent             TEXT,
    references_omitted          TEXT,
    reference_omit_reason       TEXT,
    requested_candidates        INTEGER,
    actual_candidates           INTEGER,
    requested_resolution        VARCHAR(50),
    actual_resolution           VARCHAR(50),
    resolution_deviation        TEXT,
    job_id                      UUID,
    provider_request_id         VARCHAR(255),
    status                      VARCHAR(50) NOT NULL DEFAULT 'PLANNED',
    provider_failure_type       VARCHAR(80),
    provider_failure_message    TEXT,
    actual_cost                 NUMERIC(18,8),
    currency                    VARCHAR(20),
    created_at                  TIMESTAMP NOT NULL,
    completed_at                TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_benchmark_execution_run
    ON benchmark_execution (benchmark_run_id, persona_id, prompt_id, slot_key);

CREATE TABLE IF NOT EXISTS benchmark_evaluation (
    id                  UUID PRIMARY KEY,
    execution_id        UUID NOT NULL REFERENCES benchmark_execution(id),
    candidate_id        UUID,
    identity_rating     INTEGER,
    identity_remarks    TEXT,
    realism_rating      INTEGER,
    realism_remarks     TEXT,
    admin_decision      VARCHAR(80),
    admin_remarks       TEXT,
    evaluated_at        TIMESTAMP NOT NULL,
    evaluated_by        VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS ix_benchmark_evaluation_execution
    ON benchmark_evaluation (execution_id);
