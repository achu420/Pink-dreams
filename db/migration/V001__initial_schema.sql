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

CREATE UNIQUE INDEX one_active_engine ON conversation_engines (is_active) WHERE is_active;

-- =========================================================================
-- 2. Persona identity (image-identity anchor, referenced by personas)
-- =========================================================================

CREATE TABLE persona_identity (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    active_visual_version_id  UUID,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =========================================================================
-- 2.5. Persona visual versions (visual identity, immutable once published)
-- =========================================================================

CREATE TABLE persona_visual_versions (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    persona_identity_id   UUID NOT NULL REFERENCES persona_identity(id),
    version               INTEGER NOT NULL,
    physical_guide        JSONB NOT NULL DEFAULT '{}',
    style_constraints     JSONB NOT NULL DEFAULT '{}',
    status                TEXT NOT NULL DEFAULT 'draft'
                              CHECK (status IN ('draft', 'published', 'archived')),
    changelog_note        TEXT,
    author                TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (persona_identity_id, version)
);

ALTER TABLE persona_identity
    ADD CONSTRAINT fk_active_visual_version
    FOREIGN KEY (active_visual_version_id) REFERENCES persona_visual_versions(id);

CREATE OR REPLACE FUNCTION chk_active_visual_version_is_published()
RETURNS TRIGGER AS $$
DECLARE
    v_status TEXT;
BEGIN
    IF NEW.active_visual_version_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT status INTO v_status
    FROM persona_visual_versions
    WHERE id = NEW.active_visual_version_id;

    IF v_status IS DISTINCT FROM 'published' THEN
        RAISE EXCEPTION 'persona_identity %: active_visual_version_id % is not a published version (status=%)',
            NEW.id, NEW.active_visual_version_id, v_status;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_active_visual_version_is_published
    BEFORE INSERT OR UPDATE OF active_visual_version_id ON persona_identity
    FOR EACH ROW
    EXECUTE FUNCTION chk_active_visual_version_is_published();

CREATE OR REPLACE FUNCTION chk_visual_version_immutable()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status IN ('published', 'archived') THEN
        RAISE EXCEPTION 'persona_visual_version %: %s versions are immutable',
            OLD.id, OLD.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_visual_version_immutable
    BEFORE UPDATE OF physical_guide, style_constraints, status, changelog_note ON persona_visual_versions
    FOR EACH ROW
    EXECUTE FUNCTION chk_visual_version_immutable();

-- =========================================================================
-- 2.7. Persona visual wardrobe items (identity-level wardrobe catalog, immutable once locked)
-- =========================================================================

CREATE TABLE persona_visual_wardrobe_items (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    persona_visual_version_id UUID NOT NULL REFERENCES persona_visual_versions(id),
    category              TEXT NOT NULL,
    subcategory           TEXT NOT NULL,
    name                  TEXT NOT NULL,
    description           TEXT,
    color                 TEXT,
    material              TEXT,
    fit                   TEXT,
    pattern               TEXT,
    season_tags           TEXT[],
    style_tags            TEXT[],
    accessories           TEXT[],
    is_available          BOOLEAN NOT NULL DEFAULT true,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_wardrobe_items_version ON persona_visual_wardrobe_items (persona_visual_version_id);
CREATE INDEX ix_wardrobe_items_category ON persona_visual_wardrobe_items (persona_visual_version_id, category);

CREATE OR REPLACE FUNCTION chk_wardrobe_item_immutable()
RETURNS TRIGGER AS $$
DECLARE
    v_status TEXT;
BEGIN
    SELECT status INTO v_status
    FROM persona_visual_versions
    WHERE id = NEW.persona_visual_version_id;

    IF v_status IN ('published', 'archived') THEN
        RAISE EXCEPTION 'Wardrobe items for %s visual version % are immutable',
            v_status, NEW.persona_visual_version_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_wardrobe_item_immutable
    BEFORE INSERT OR UPDATE ON persona_visual_wardrobe_items
    FOR EACH ROW
    EXECUTE FUNCTION chk_wardrobe_item_immutable();

CREATE OR REPLACE FUNCTION chk_wardrobe_item_delete_immutable()
RETURNS TRIGGER AS $$
DECLARE
    v_status TEXT;
BEGIN
    SELECT status INTO v_status
    FROM persona_visual_versions
    WHERE id = OLD.persona_visual_version_id;

    IF v_status IN ('published', 'archived') THEN
        RAISE EXCEPTION 'Cannot delete wardrobe items from %s visual version %',
            v_status, OLD.persona_visual_version_id;
    END IF;

    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_wardrobe_item_delete_immutable
    BEFORE DELETE ON persona_visual_wardrobe_items
    FOR EACH ROW
    EXECUTE FUNCTION chk_wardrobe_item_delete_immutable();

-- =========================================================================
-- 2.8. Persona visual reference images (reference assets, immutable once locked)
-- =========================================================================

CREATE TABLE persona_visual_reference_images (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    persona_visual_version_id UUID NOT NULL REFERENCES persona_visual_versions(id),
    storage_key           TEXT NOT NULL UNIQUE,
    content_type          TEXT NOT NULL,
    file_size             BIGINT NOT NULL CHECK (file_size > 0),
    width                 INTEGER,
    height                INTEGER,
    checksum              TEXT,
    role                  TEXT NOT NULL DEFAULT 'GENERAL_IDENTITY'
                              CHECK (role IN ('FACE', 'FULL_BODY', 'BODY', 'HAIR', 'WARDROBE', 'STYLE', 'GENERAL_IDENTITY')),
    status                TEXT NOT NULL DEFAULT 'UPLOADED'
                              CHECK (status IN ('UPLOADED', 'FINALIZED', 'ARCHIVED')),
    source                TEXT NOT NULL
                              CHECK (source IN ('HUMAN_UPLOADED', 'GENERATED', 'IMPORTED')),
    notes                 TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    finalized_at          TIMESTAMPTZ
);

CREATE INDEX ix_reference_images_version ON persona_visual_reference_images (persona_visual_version_id);
CREATE INDEX ix_reference_images_role ON persona_visual_reference_images (persona_visual_version_id, role);
CREATE INDEX ix_reference_images_status ON persona_visual_reference_images (persona_visual_version_id, status);

CREATE OR REPLACE FUNCTION chk_reference_image_immutable()
RETURNS TRIGGER AS $$
DECLARE
    v_status TEXT;
BEGIN
    IF TG_OP = 'INSERT' OR TG_OP = 'UPDATE' THEN
        SELECT status INTO v_status
        FROM persona_visual_versions
        WHERE id = NEW.persona_visual_version_id;

        IF v_status IN ('published', 'archived') THEN
            RAISE EXCEPTION 'Cannot add/modify reference images for %s visual version %',
                v_status, NEW.persona_visual_version_id;
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        SELECT status INTO v_status
        FROM persona_visual_versions
        WHERE id = OLD.persona_visual_version_id;

        IF v_status IN ('published', 'archived') THEN
            RAISE EXCEPTION 'Cannot delete reference images from %s visual version %',
                v_status, OLD.persona_visual_version_id;
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    ELSE
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reference_image_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON persona_visual_reference_images
    FOR EACH ROW
    EXECUTE FUNCTION chk_reference_image_immutable();

-- =========================================================================
-- 2.9. Image jobs (durable async job queue for image generation)
-- =========================================================================

CREATE TABLE image_jobs (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    persona_visual_version_id UUID NOT NULL REFERENCES persona_visual_versions(id),
    job_type              TEXT NOT NULL DEFAULT 'IMAGE_GENERATION'
                              CHECK (job_type IN ('IMAGE_GENERATION')),
    status                TEXT NOT NULL DEFAULT 'QUEUED'
                              CHECK (status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    idempotency_key       TEXT NOT NULL,
    request_payload       TEXT NOT NULL,
    attempt_count         INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts          INTEGER NOT NULL DEFAULT 3 CHECK (max_attempts > 0),
    available_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_by_worker     TEXT,
    claimed_at            TIMESTAMPTZ,
    last_error            TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    UNIQUE (persona_visual_version_id, idempotency_key)
);

CREATE INDEX ix_image_jobs_status ON image_jobs (status);
CREATE INDEX ix_image_jobs_available ON image_jobs (available_at, status) WHERE status IN ('QUEUED', 'RETRY_WAIT');
CREATE INDEX ix_image_jobs_version ON image_jobs (persona_visual_version_id);
CREATE INDEX ix_image_jobs_worker ON image_jobs (claimed_by_worker) WHERE status = 'RUNNING';

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
    language_profile        TEXT NOT NULL DEFAULT '{}',
    active_core_version_id  UUID,
    persona_identity_id     UUID REFERENCES persona_identity(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =========================================================================
-- 4. Persona core versions (identity/voice content, versioned, never deleted)
-- =========================================================================

CREATE TABLE persona_core_versions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
    criticality         TEXT NOT NULL DEFAULT 'medium'
                            CHECK (criticality IN ('low', 'medium', 'high')),
    criticality_rank    SMALLINT GENERATED ALWAYS AS (
                            CASE criticality
                                WHEN 'high' THEN 3
                                WHEN 'medium' THEN 2
                                WHEN 'low' THEN 1
                            END
                        ) STORED,
    tier                TEXT NOT NULL DEFAULT 'hot'
                            CHECK (tier IN ('hot', 'cold')),
    status              TEXT NOT NULL DEFAULT 'open'
                            CHECK (status IN ('open', 'resolved')),
    source              TEXT NOT NULL DEFAULT 'llm_extracted'
                            CHECK (source IN ('llm_extracted', 'manual')),
    learned_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_referenced_at  TIMESTAMPTZ,
    evicted_at          TIMESTAMPTZ
);

CREATE INDEX ix_memory_facts_user_persona ON memory_facts (user_id, persona_id);
CREATE INDEX ix_memory_facts_hot_selection
    ON memory_facts (user_id, persona_id, status, criticality_rank, last_referenced_at, learned_at)
    WHERE tier = 'hot';

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
    metadata                 TEXT NOT NULL DEFAULT '{}',
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_version_matches_role CHECK (
        (role = 'assistant' AND engine_version_id IS NOT NULL AND persona_core_version_id IS NOT NULL)
        OR (role <> 'assistant' AND engine_version_id IS NULL AND persona_core_version_id IS NULL)
    ),

    CONSTRAINT chk_client_message_id_role CHECK (
        client_message_id IS NULL OR role = 'user'
    )
);

CREATE INDEX ix_messages_conversation_time ON messages (conversation_id, created_at);

CREATE UNIQUE INDEX uq_client_message_per_conversation
    ON messages (conversation_id, client_message_id)
    WHERE client_message_id IS NOT NULL;

-- =========================================================================
-- 10. Chat request executions (idempotency ledger)
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

-- Notes:
-- - `users` is intentionally not created here; it belongs to whichever
--   migration set owns auth. This file only ever references it by FK.
-- - No seed data in this migration.
