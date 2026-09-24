-- V018: Admin persona & image management schema
-- Adds cover image support for personas, content classification & routing for image jobs,
-- test-user flag for distinguishing mock-chat users from real end users, and
-- testing version pointers for personas.

-- 1. Persona cover image support + testing version pointer
ALTER TABLE personas ADD COLUMN IF NOT EXISTS cover_reference_image_id UUID
    REFERENCES persona_visual_reference_images(id);

ALTER TABLE personas ADD COLUMN IF NOT EXISTS testing_core_version_id UUID
    REFERENCES persona_core_versions(id);

-- 2. Image job content classification, model routing & failure tracking
ALTER TABLE image_jobs ADD COLUMN IF NOT EXISTS content_classification TEXT NOT NULL DEFAULT 'GENERAL'
    CHECK (content_classification IN ('GENERAL', 'RESTRICTED'));

ALTER TABLE image_jobs ADD COLUMN IF NOT EXISTS requested_model TEXT;

ALTER TABLE image_jobs ADD COLUMN IF NOT EXISTS selected_model TEXT;

-- Ordered list of {model, outcome, error} for fallback traceability.
ALTER TABLE image_jobs ADD COLUMN IF NOT EXISTS routing_attempts JSONB;

-- Categorized failure reason; set only on terminal failure.
ALTER TABLE image_jobs ADD COLUMN IF NOT EXISTS failure_category TEXT
    CHECK (failure_category IS NULL OR failure_category IN (
        'PROVIDER_SAFETY_REJECTED',
        'INSUFFICIENT_CREDITS',
        'INVALID_REQUEST',
        'UNSUPPORTED_PARAMETER',
        'TIMEOUT',
        'TECHNICAL_FAILURE'
    ));

-- 3. Test user flag
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_test_user BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_users_is_test_user ON users(is_test_user);
