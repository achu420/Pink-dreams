-- =========================================================================
-- V005: Baseline AI Configuration — Memory Engine operational configuration
-- =========================================================================
--
-- IMPORTANT — runtime schema note (see V001-V004): this project's actual
-- runtime schema is created and evolved entirely by Exposed's
-- SchemaUtils.createMissingTablesAndColumns() against the Table objects in
-- DatabaseFactory.kt, not by executing files in this directory. This file
-- exists only to keep the documented schema history consistent.
--
-- Operational configuration is attached to the VERSIONED Memory Engine rather
-- than stored as constants or embedded in free-form prompt text, so that
-- activating a Memory Engine version activates its batch/target settings
-- atomically with its instructions.
--
-- relevant_memory_target is a TARGET the engine aims for, deliberately NOT a
-- hard cap: there is no CHECK constraint and no trigger enforcing it, because
-- the engine must be free to return fewer (or occasionally more) memories when
-- that is genuinely more useful.

ALTER TABLE memory_engines
    ADD COLUMN IF NOT EXISTS batch_size             INTEGER NOT NULL DEFAULT 10,
    ADD COLUMN IF NOT EXISTS relevant_memory_target INTEGER NOT NULL DEFAULT 20;
