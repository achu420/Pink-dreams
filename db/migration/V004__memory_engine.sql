-- =========================================================================
-- V004: Phase D — Memory Engine Foundation & Batch Memory Maintenance
-- =========================================================================
--
-- IMPORTANT — runtime schema note (see V001/V002/V003 for the full
-- explanation): this project's actual runtime schema is created and evolved
-- entirely by Exposed's SchemaUtils.createMissingTablesAndColumns() against
-- the Table objects in DatabaseFactory.kt, not by executing files in this
-- directory. This file exists only to keep the documented schema history
-- consistent.
--
-- New table: memory_engines — an independently-versioned prompt/config for
-- memory maintenance, structurally identical to conversation_engines (single
-- global active row). "At most one active version" is enforced at the
-- application level (MemoryEngineRepository.activate()), the same way
-- conversation_engines' own invariant is enforced in code, not a DB
-- constraint — see that table's note for precedent.

CREATE TABLE IF NOT EXISTS memory_engines (
    id             UUID PRIMARY KEY,
    version        INTEGER NOT NULL,
    content        TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'draft',
    is_active      BOOLEAN NOT NULL DEFAULT false,
    changelog_note TEXT,
    created_by     TEXT,
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_memory_engines_version UNIQUE (version)
);

-- memory_facts: extended for Phase D. All additive, all nullable/defaulted —
-- existing rows are unaffected, existing readers/writers are unaffected.
ALTER TABLE memory_facts
    ADD COLUMN IF NOT EXISTS owner         TEXT NOT NULL DEFAULT 'USER',
    ADD COLUMN IF NOT EXISTS supersedes_id UUID,
    ADD COLUMN IF NOT EXISTS updated_at    TIMESTAMP;

-- conversations: independent batch cursor for Memory Engine maintenance,
-- distinct from continuity_summary_covered_count (the chat-context "last 10"
-- window and the Memory Engine's "next unprocessed batch" are different
-- concepts and must never share a cursor).
ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS memory_engine_processed_count INTEGER NOT NULL DEFAULT 0;
