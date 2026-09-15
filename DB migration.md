-- Pink Dreams — Initial schema
-- Implements production-architecture.md (frozen) + pink-dreams-implementation-spec.md section 6-7.
-- Assumes a pre-existing `users(id UUID PRIMARY KEY, ...)` table owned by the
-- partner's auth/account system — this migration does not create it, only
-- references it via FK. If it does not yet exist in the target database,
-- create a minimal stand-in before running this migration in dev:
--   CREATE TABLE IF NOT EXISTS users (id UUID PRIMARY KEY DEFAULT gen_random_uuid());

CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- gen_random_uuid()

-- =========================================================================
-- 1. Conversation engines (universal rules, versioned, at most one active)
-- =========================================================================

CREATE TABLE conversation_engines (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version         INTEGER NOT NULL UNIQUE,
    content         TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'draft'
                        CHECK (status IN ('draft', 'published', 'archived')),
    is_active       BOOLEAN NOT NULL DEFAULT false,
    changelog_note  TEXT,
    created_by      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_active_implies_published CHECK (NOT is_active OR status = 'published')
);

-- At most one active engine (DB-guaranteed). Exactly-one-in-production is a
-- deploy/health check, not something a partial unique index can express —
-- zero active rows is a valid transient state (bootstrap, or just archived
-- the old one before activating the new one in the same transaction).
CREATE UNIQUE INDEX one_active_engine ON conversation_engines (is_active) WHERE is_active;

-- =========================================================================
-- 2. Persona identity (image-identity anchor, referenced by personas)
-- =========================================================================
-- Minimal placeholder per ai-architecture.md's persona_identity concept.
-- Extend with actual image-identity fields when the image pipeline lands;
-- not fleshed out further here since it's out of scope for this migration.

CREATE TABLE persona_identity (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =========================================================================
-- 3. Personas (metadata only — never persona-specific code branches)
-- =========================================================================

CREATE TABLE personas (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug                    TEXT NOT NULL UNIQUE,
    display_name            TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'draft'
                                CHECK (status IN ('draft', 'active', 'retired')),
    gender                  TEXT NOT NULL,
    orientation             TEXT NOT NULL,
    apparent_age            INTEGER NOT NULL CHECK (apparent_age >= 25),
    language_profile        JSONB NOT NULL DEFAULT '{}',
    active_core_version_id  UUID,  -- FK added after persona_core_versions exists (circular ref)
    persona_identity_id     UUID REFERENCES persona_identity(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =========================================================================
-- 4. Persona core versions (identity/voice content, versioned, never deleted)
-- =========================================================================

CREATE TABLE persona_core_versions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Intentionally no ON DELETE CASCADE: a persona going away (retired)
    -- must never take its version history down with it. Old versions are
    -- what make a three-week-old transcript reproducible.
    persona_id      UUID NOT NULL REFERENCES personas(id),
    version         INTEGER NOT NULL,
    content         TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'draft'
                        CHECK (status IN ('draft', 'published', 'archived')),
    changelog_note  TEXT,
    author          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (persona_id, version)
);

ALTER TABLE personas
    ADD CONSTRAINT fk_active_core_version
    FOREIGN KEY (active_core_version_id) REFERENCES persona_core_versions(id);

-- Cross-table invariant (personas.active_core_version_id must point at a
-- 'published' persona_core_versions row) cannot be expressed as a CHECK —
-- Postgres CHECK constraints don't span tables. Enforced via trigger:

CREATE OR REPLACE FUNCTION chk_active_core_version_is_published()
RETURNS TRIGGER AS $$
DECLARE
    v_status TEXT;
BEGIN
    IF NEW.active_core_version_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT status INTO v_status
    FROM persona_core_versions
    WHERE id = NEW.active_core_version_id;

    IF v_status IS DISTINCT FROM 'published' THEN
        RAISE EXCEPTION 'persona %: active_core_version_id % is not a published version (status=%)',
            NEW.id, NEW.active_core_version_id, v_status;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_active_core_version_is_published
    BEFORE INSERT OR UPDATE OF active_core_version_id ON personas
    FOR EACH ROW
    EXECUTE FUNCTION chk_active_core_version_is_published();

-- =========================================================================
-- 5. User profile (global identity — never persona-scoped)
-- =========================================================================

CREATE TABLE user_profiles (
    user_id             UUID PRIMARY KEY REFERENCES users(id),
    display_name        TEXT,
    preferred_language  TEXT,
    communication_style TEXT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =========================================================================
-- 6. Memory facts (per user, per persona — relationship-scoped, never global)
--    Memory v1.2: typed + LLM-scored + two-tier (hot/cold). See
--    production-architecture.md section 3 for the full rationale.
-- =========================================================================

CREATE TABLE memory_facts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id),
    persona_id          UUID NOT NULL REFERENCES personas(id),
    fact                TEXT NOT NULL,
    fact_type           TEXT NOT NULL CHECK (fact_type IN (
                            'past_event', 'future_event', 'mindset', 'weakness',
                            'aspiration', 'desire', 'habit', 'want', 'interest'
                        )),
    -- Model's own estimate of how much this fact should shape future
    -- replies. Drives per-turn selection (high first) independently of tier.
    criticality         TEXT NOT NULL DEFAULT 'medium'
                            CHECK (criticality IN ('low', 'medium', 'high')),
    -- criticality is TEXT for readability, but 'high' > 'medium' > 'low' is
    -- NOT alphabetical ('medium' > 'low' > 'high' alphabetically) — a plain
    -- `ORDER BY criticality DESC` would silently sort backwards. This
    -- generated column is the single source of truth for priority ordering;
    -- every selection/eviction query must sort by criticality_rank, never
    -- by the raw criticality column.
    criticality_rank    SMALLINT GENERATED ALWAYS AS (
                            CASE criticality
                                WHEN 'high'   THEN 3
                                WHEN 'medium' THEN 2
                                WHEN 'low'    THEN 1
                            END
                        ) STORED,
    -- hot = in the active working pool (max 20 per user/persona, enforced
    -- in the application layer, not a DB constraint — eviction is a
    -- read-modify-write, not something a CHECK can express); cold = evicted.
    -- "Evicted" means removed from the hot pool for capacity reasons only —
    -- it does NOT mean permanently deleted. Cold rows persist for future
    -- consolidation/review and are removed only if/when a user-data
    -- retention or deletion policy (still an open product decision, see
    -- the implementation spec's history/retention section) says to purge
    -- them. This table does not itself define that policy.
    tier                TEXT NOT NULL DEFAULT 'hot' CHECK (tier IN ('hot', 'cold')),
    status              TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved')),
    source              TEXT NOT NULL DEFAULT 'llm_extracted'
                            CHECK (source IN ('llm_extracted', 'manual')),
    learned_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Set only when the fact is determined to be actually conversationally
    -- relevant again (the user brought it up, or extraction flagged it as
    -- referenced) — NOT merely because ContextAssembler injected it into a
    -- prompt. Updating it on every injection would let a frequently-selected
    -- fact perpetually refresh its own recency and never lose priority,
    -- defeating the point of ranking by recency at all. See MemoryService
    -- in the implementation spec for exactly where this gets written.
    last_referenced_at  TIMESTAMPTZ,
    evicted_at          TIMESTAMPTZ  -- set when tier flips hot -> cold
);

-- Scoping (user_id, persona_id) must be enforced at the repository layer on
-- every query too — this index just makes the correct query path fast.
CREATE INDEX ix_memory_facts_user_persona ON memory_facts (user_id, persona_id);

-- Per-turn selection AND eviction both read this path: hot facts for this
-- user/persona, ranked by resolution status, then criticality_rank, then
-- recency. Partial index keeps it cheap since cold facts (the larger,
-- ever-growing archive) are never touched on this path.
--   Selection order (highest priority to keep/send first):
--     status = 'open' before 'resolved', criticality_rank DESC,
--     last_referenced_at DESC NULLS LAST, learned_at DESC
--   Eviction picks from the OPPOSITE end of that same ordering:
--     resolved before open, criticality_rank ASC, oldest last_referenced_at,
--     oldest learned_at — i.e. a resolved, low-criticality, stale fact is
--     evicted before an active, high-criticality, recent one.
CREATE INDEX ix_memory_facts_hot_selection
    ON memory_facts (user_id, persona_id, status, criticality_rank, last_referenced_at, learned_at)
    WHERE tier = 'hot';

-- Guardrail note (not a DB constraint — enforced by never wiring it up):
-- fact_type IN ('weakness','desire') and criticality are for persona
-- personalization only. Matching, entitlement, and monetization code paths
-- must never query this table. See production-architecture.md section 3.

-- =========================================================================
-- 7. Entitlements (per user, owned by the partner's billing/onboarding flow)
-- =========================================================================

CREATE TABLE entitlements (
    user_id                 UUID PRIMARY KEY REFERENCES users(id),
    state                   TEXT NOT NULL
                                CHECK (state IN ('trial', 'free', 'extended', 'paid', 'lapsed')),
    trial_started_at        TIMESTAMPTZ,
    state_changed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    extended_days_remaining INTEGER NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =========================================================================
-- 8. Conversations (per user/persona pair, medium-lived)
-- =========================================================================

CREATE TABLE conversations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id),
    persona_id      UUID NOT NULL REFERENCES personas(id),
    state           TEXT NOT NULL DEFAULT 'active'
                        CHECK (state IN ('active', 'idle', 'archived')),
    last_message_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_conversations_user ON conversations (user_id, persona_id);

-- =========================================================================
-- 9. Messages (exact generation provenance on every assistant reply)
-- =========================================================================

CREATE TABLE messages (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id          UUID NOT NULL REFERENCES conversations(id),
    role                     TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content                  TEXT NOT NULL,
    engine_version_id        UUID REFERENCES conversation_engines(id),
    persona_core_version_id  UUID REFERENCES persona_core_versions(id),
    client_message_id        UUID,
    request_id               UUID,
    metadata                 JSONB NOT NULL DEFAULT '{}',
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Only assistant replies carry a version; every other role must not.
    CONSTRAINT chk_version_matches_role CHECK (
        (role = 'assistant' AND engine_version_id IS NOT NULL AND persona_core_version_id IS NOT NULL)
        OR (role <> 'assistant' AND engine_version_id IS NULL AND persona_core_version_id IS NULL)
    ),

    -- Only user messages originate from a client and carry a client_message_id.
    CONSTRAINT chk_client_message_id_role CHECK (
        client_message_id IS NULL OR role = 'user'
    )
);

CREATE INDEX ix_messages_conversation_time ON messages (conversation_id, created_at);

-- Nullable client_message_id + partial unique index: NULL rows (assistant,
-- system) coexist freely; only real client sends get deduplicated.
CREATE UNIQUE INDEX uq_client_message_per_conversation
    ON messages (conversation_id, client_message_id)
    WHERE client_message_id IS NOT NULL;

-- =========================================================================
-- 10. Chat request executions (idempotency ledger — implementation-spec
--     section 7; the primary key IS the concurrency lock, no distributed
--     locking needed at this scale)
-- =========================================================================

CREATE TABLE chat_request_executions (
    conversation_id       UUID NOT NULL REFERENCES conversations(id),
    client_message_id     UUID NOT NULL,
    request_id            UUID NOT NULL,
    status                TEXT NOT NULL DEFAULT 'processing'
                              CHECK (status IN ('processing', 'completed', 'failed')),
    assistant_message_id  UUID REFERENCES messages(id),
    error_code            TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at          TIMESTAMPTZ,
    PRIMARY KEY (conversation_id, client_message_id)
);

-- =========================================================================
-- Notes for anyone writing V002 and beyond
-- =========================================================================
-- - Never edit a table's meaning in place once real data depends on it —
--   add a new migration (V002__..., V003__...), same discipline as the
--   persona/engine version model this schema itself enforces.
-- - `users` is intentionally not created here; it belongs to whichever
--   migration set owns auth. This file only ever references it by FK.
-- - No seed data in this migration. Seeding an initial conversation_engines
--   row and at least one persona is a separate, explicit V00N seed migration
--   or an admin-API bootstrap step — not silently baked into schema setup.