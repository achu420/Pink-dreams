-- V008 — Phase ADMIN-3: Test/Production execution mode and configuration
-- snapshot, carried directly on `conversations` (Test Chat is a conversation
-- with an immutable, fixed configuration; it is not a separate entity).
--
-- NOTE ON EXECUTION: files under db/migration are DOCUMENTARY in this project.
-- The runtime schema is governed by DatabaseFactory's Table objects plus
-- SchemaUtils.createMissingTablesAndColumns(), which creates the columns
-- below automatically (all new columns are either DEFAULTed or nullable, so
-- this is a pure addition — no existing row's meaning changes).

ALTER TABLE conversations ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(20) NOT NULL DEFAULT 'PRODUCTION';

-- Snapshot columns are meaningful ONLY when execution_mode = 'TEST'. A
-- PRODUCTION conversation (every existing row, and every row the normal
-- /v1/conversations API creates) always resolves the currently active
-- version of everything, exactly as before this phase.
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS snapshot_conversation_engine_version INTEGER;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS snapshot_persona_core_version INTEGER;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS snapshot_intent_engine_version INTEGER;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS snapshot_memory_engine_version INTEGER;
-- JSON object: skill key -> version, e.g. {"friendship":2,"flirting":4}. Only
-- the named keys are candidates for this conversation's Intent Engine.
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS snapshot_skill_versions_json TEXT;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS snapshot_model TEXT;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS snapshot_temperature DOUBLE PRECISION;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS snapshot_max_output_tokens INTEGER;
-- A real, hidden Personas row created for this one TEST conversation's memory
-- alone. Required because memory_facts.persona_id carries a REAL foreign key
-- to personas(id) on PostgreSQL (H2/SchemaUtils enforces no such key, which is
-- exactly why this was found only during live PostgreSQL verification, not by
-- the automated suite) — a merely-derived UUID is rejected on insert. See
-- MemoryScopeResolver.TestMemoryScope and TestChatService.create().
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS snapshot_memory_scope_persona_id UUID;

ALTER TABLE conversations ADD CONSTRAINT conversations_execution_mode_check
    CHECK (execution_mode IN ('PRODUCTION', 'TEST'));
