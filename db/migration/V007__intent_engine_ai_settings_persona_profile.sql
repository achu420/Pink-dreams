-- V007 — Phase ADMIN-2: Intent Engine versioning, AI runtime settings, and
-- persona PROFILE metadata.
--
-- NOTE ON EXECUTION: files under db/migration are DOCUMENTARY in this project.
-- The runtime schema is governed by DatabaseFactory's Table objects plus
-- SchemaUtils.createMissingTablesAndColumns(), which DOES create missing
-- tables and add missing nullable/defaulted columns — so everything below is
-- applied automatically on startup. The CHECK constraints are the exception:
-- SchemaUtils does not manage them, so apply those by hand on databases that
-- carry the hand-written constraint style from V001 (see V006 for why this
-- distinction matters — stale CHECK constraints silently reject valid writes).

-- ---------------------------------------------------------------------------
-- Intent Engine: the versioned prompt that governs how interaction intent is
-- identified. Structurally identical to memory_engines / conversation_engines.
-- It deliberately does NOT store the skill catalogue: the active candidate
-- keys are supplied by the runtime from the active skills.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS intent_engines (
    id              UUID PRIMARY KEY,
    version         INTEGER NOT NULL UNIQUE,
    content         TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'draft',
    is_active       BOOLEAN NOT NULL DEFAULT FALSE,
    changelog_note  TEXT,
    created_by      TEXT,
    created_at      TIMESTAMP NOT NULL,
    CONSTRAINT intent_engines_status_check
        CHECK (status IN ('draft', 'published', 'archived'))
);

-- At most one active version, enforced by the database rather than only in
-- code — the same guarantee conversation_engines relies on.
CREATE UNIQUE INDEX IF NOT EXISTS intent_engines_single_active
    ON intent_engines ((TRUE)) WHERE is_active;

-- ---------------------------------------------------------------------------
-- AI runtime settings: a SINGLE row (id is fixed by the application).
-- Every column is nullable on purpose — NULL means "not configured here",
-- which falls through to the environment variable and then the application
-- default. An empty table therefore reproduces pre-ADMIN-2 behavior exactly.
-- Not versioned: these are live operational knobs, not reviewed prompt
-- content; auditability comes from updated_at / updated_by.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_settings (
    id                UUID PRIMARY KEY,
    model             TEXT,
    temperature       DOUBLE PRECISION,
    max_output_tokens INTEGER,
    updated_at        TIMESTAMP NOT NULL,
    updated_by        TEXT,
    CONSTRAINT ai_settings_temperature_check
        CHECK (temperature IS NULL OR (temperature >= 0 AND temperature <= 2)),
    CONSTRAINT ai_settings_max_output_tokens_check
        CHECK (max_output_tokens IS NULL OR (max_output_tokens >= 1 AND max_output_tokens <= 32000))
);

-- ---------------------------------------------------------------------------
-- Persona PROFILE metadata — descriptive information about the persona,
-- deliberately separate from the Persona Core (which is versioned and defines
-- who the character is and how she behaves). gender / orientation /
-- apparent_age already exist on personas and are NOT duplicated here.
--
-- tags is a JSON array of strings in one column rather than a join table: it
-- is a small, display-oriented, wholly-replaced list, and a linked table would
-- add a repository and a lifecycle for no query we actually make.
-- ---------------------------------------------------------------------------
ALTER TABLE personas ADD COLUMN IF NOT EXISTS bio        TEXT;
ALTER TABLE personas ADD COLUMN IF NOT EXISTS city       VARCHAR(255);
ALTER TABLE personas ADD COLUMN IF NOT EXISTS occupation VARCHAR(255);
ALTER TABLE personas ADD COLUMN IF NOT EXISTS interests  TEXT;
ALTER TABLE personas ADD COLUMN IF NOT EXISTS tags       TEXT NOT NULL DEFAULT '[]';
