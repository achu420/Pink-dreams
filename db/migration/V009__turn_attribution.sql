-- V009 — Task 24: Complete Pipeline Attribution.
--
-- ONE new table, one row per conversational turn. Purely ADDITIVE: no existing
-- table, column, constraint, index or row meaning is altered, so rolling this
-- migration forward cannot change any existing behavior or query result.
--
-- NOTE ON EXECUTION: files under db/migration are DOCUMENTARY in this project.
-- The runtime schema is governed by DatabaseFactory's Table objects plus
-- SchemaUtils.createMissingTablesAndColumns(), which creates this table
-- automatically. This file is the human-readable statement of the change.
--
-- WHY A NEW TABLE (Task 24 Part 17 requires this justification):
--   * llm_exchanges is per-CALL — a turn has 2-5 rows. Turn-level facts such
--     as the persona version would be duplicated onto every row, and async
--     side-channel rows would carry on-path generation config they never used.
--   * messages.metadata is an unstructured text blob that exists only when
--     PERSIST succeeded; a failed generation must still carry attribution.
-- Nothing recorded elsewhere is duplicated: per-call model/provider/latency/
-- tokens/outcome/raw bodies stay in llm_exchanges (joined on turn_request_id),
-- stage timings stay in messages.metadata, and memory TEXT is never copied —
-- only memory IDs and counts.

CREATE TABLE IF NOT EXISTS turn_attributions (
    turn_request_id                   UUID PRIMARY KEY,
    conversation_id                   UUID NOT NULL,
    user_id                           UUID NOT NULL,
    is_test_chat                      BOOLEAN NOT NULL DEFAULT FALSE,

    -- Persona (Part 3): the authoritative runtime persona + active core version.
    persona_id                        UUID,
    persona_version_id                UUID,
    persona_version                   INTEGER,

    -- Conversation Engine (Part 4): the active engine row actually assembled in.
    conversation_engine_id            UUID,
    conversation_engine_version       INTEGER,

    -- Intent Engine + the configuration ACTUALLY used for this request (Part 5).
    -- Stored per turn, never re-derived at inspection time, so a later admin
    -- configuration change cannot rewrite history.
    intent_engine_id                  UUID,
    intent_engine_version             INTEGER,
    intent_model                      TEXT,
    intent_model_source               VARCHAR(32),
    intent_json_mode                  BOOLEAN,
    intent_json_mode_source           VARCHAR(32),
    intent_max_output_tokens          INTEGER,
    intent_max_output_tokens_source   VARCHAR(32),

    -- Intent result (Part 6): the structured outcome the current implementation
    -- genuinely produces. No new classifier, no extra LLM call.
    intent_outcome                    VARCHAR(48),
    intent_result_skill_key           VARCHAR(128),

    -- Skill (Part 7).
    selected_skill_key                VARCHAR(128),
    skill_context_injected            BOOLEAN,

    -- Memory (Part 8): identifiers and counts only — never memory text.
    memory_ids_used                   TEXT,
    memory_count_used                 INTEGER,
    memory_candidate_count            INTEGER,
    memory_selection_source           VARCHAR(48),

    -- User profile (Part 9): no profile versioning exists; updated_at is the
    -- only stable revision marker the schema actually has.
    user_profile_present              BOOLEAN,
    user_profile_updated_at           VARCHAR(64),

    -- Generation configuration (Part 10) with the resolution source of each value.
    generation_model                  TEXT,
    generation_model_source           VARCHAR(32),
    generation_temperature            DOUBLE PRECISION,
    generation_temperature_source     VARCHAR(32),
    generation_max_output_tokens      INTEGER,
    generation_max_output_tokens_source VARCHAR(32),
    generation_reasoning              BOOLEAN,
    generation_json_mode              BOOLEAN,
    generation_provider_sort          VARCHAR(64),
    generation_provider_sort_source   VARCHAR(32),

    -- Regeneration (Part 11). Per-ATTEMPT model/provider/latency/tokens/
    -- finishReason are not duplicated here — they are the llm_exchanges rows
    -- for workload='primary_generation' on this turn, in creation order.
    regeneration_occurred             BOOLEAN NOT NULL DEFAULT FALSE,
    regeneration_count                INTEGER NOT NULL DEFAULT 0,

    -- Input / final response references (Part 12). References only; message
    -- CONTENT is not duplicated out of `messages`.
    client_message_id                 UUID,
    assistant_message_id              UUID,

    outcome                           VARCHAR(64) NOT NULL,
    failed_stage                      VARCHAR(48),
    created_at                        TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_turn_attributions_conversation ON turn_attributions (conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_turn_attributions_created_at ON turn_attributions (created_at DESC);
