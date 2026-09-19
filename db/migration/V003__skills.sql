-- =========================================================================
-- V003: Phase B — Skill Foundation
-- =========================================================================
--
-- IMPORTANT — runtime schema note (see V001/V002 for the full explanation):
-- This project's actual runtime schema is created and evolved entirely by
-- Exposed's SchemaUtils.createMissingTablesAndColumns() against the Table
-- objects in DatabaseFactory.kt, not by executing files in this directory.
-- This file exists only to keep the documented schema history consistent.
--
-- Unlike conversation_engines (one single global active row), skills are
-- versioned PER KEY: distinct skill keys (e.g. "flirting", "friendship") can
-- each independently have their own active version at the same time.
-- Uniqueness is therefore (key, version), not version alone. "At most one
-- active version per key" is enforced at the application level in
-- SkillRepository.activate() (deactivate-then-activate), the same way
-- ConversationEngineRepository.activateEngine() enforces its own invariant —
-- see that table's note about "one_active_engine" for precedent.

CREATE TABLE IF NOT EXISTS skills (
    id             UUID PRIMARY KEY,
    key            TEXT NOT NULL,
    version        INTEGER NOT NULL,
    content        TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'draft',
    is_active      BOOLEAN NOT NULL DEFAULT false,
    changelog_note TEXT,
    author         TEXT,
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_skills_key_version UNIQUE (key, version)
);
