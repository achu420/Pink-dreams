-- V015: Admin-editable image provider/model settings (Task 14)
-- Secrets (API keys) remain environment-only; never stored here.

CREATE TABLE IF NOT EXISTS image_provider_settings (
    id           UUID PRIMARY KEY,
    provider     VARCHAR(100),
    model_id     VARCHAR(255),
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    notes        TEXT,
    updated_at   TIMESTAMP NOT NULL,
    updated_by   VARCHAR(255)
);

-- Singleton row id (same convention as ai_settings)
-- Application upserts on first Admin save; empty table = fall through to env.
