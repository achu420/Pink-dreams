-- V006 — widen the memory_facts CHECK constraints to match the taxonomy the
-- application actually writes.
--
-- WHY THIS EXISTS
-- The original hand-written schema constrained fact_type, source and status to
-- the values that existed at the time. The Memory Engine phase extended the
-- taxonomy in application code (MemoryService.FACT_TYPES / SOURCES, and the
-- superseded/removed statuses used for supersession) but the database
-- constraints were never widened to match.
--
-- The gap was invisible to the test suite: tests run on H2 via
-- SchemaUtils.createMissingTablesAndColumns(), which creates no CHECK
-- constraints at all. On real PostgreSQL every Memory Engine write failed with
--   new row for relation "memory_facts" violates check constraint
--   "memory_facts_source_check"
-- and every PERSONA-owned memory would have failed the same way on fact_type.
--
-- NOTE ON EXECUTION: files under db/migration are DOCUMENTARY in this project —
-- the runtime schema is governed entirely by DatabaseFactory's Table objects
-- plus SchemaUtils. SchemaUtils does not manage CHECK constraints, so these
-- statements must be applied to each real database by hand (they were applied
-- to the local pinkdreams database during baseline verification).
-- MemoryFactsTaxonomyConstraintTest guards this file against future drift.

ALTER TABLE memory_facts DROP CONSTRAINT memory_facts_source_check;
ALTER TABLE memory_facts ADD CONSTRAINT memory_facts_source_check
    CHECK (source IN ('llm_extracted', 'manual', 'memory_engine'));

ALTER TABLE memory_facts DROP CONSTRAINT memory_facts_fact_type_check;
ALTER TABLE memory_facts ADD CONSTRAINT memory_facts_fact_type_check
    CHECK (fact_type IN (
        -- USER-owned taxonomy (unchanged, pre-existing)
        'past_event', 'future_event', 'mindset', 'weakness', 'aspiration',
        'desire', 'habit', 'want', 'interest',
        -- PERSONA-owned taxonomy (Memory Engine phase)
        'relationship', 'commitment', 'promise', 'interaction_context'
    ));

ALTER TABLE memory_facts DROP CONSTRAINT memory_facts_status_check;
ALTER TABLE memory_facts ADD CONSTRAINT memory_facts_status_check
    CHECK (status IN ('open', 'resolved', 'superseded', 'removed'));
