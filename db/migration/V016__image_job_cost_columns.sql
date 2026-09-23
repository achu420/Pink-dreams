-- V016: Provider cost capture columns on image_jobs
-- OpenRouter image API does not expose per-request cost in the response body
-- (cost is only visible via the OpenRouter billing dashboard / usage API).
-- These columns are reserved for when the provider starts returning cost data.
-- Until then, provider_cost_source is written as 'UNAVAILABLE' on every
-- successful job completion to explicitly document the absence, rather than
-- leaving the column NULL which would be ambiguous.

ALTER TABLE image_jobs ADD COLUMN IF NOT EXISTS provider_cost_raw      NUMERIC(18,8);
ALTER TABLE image_jobs ADD COLUMN IF NOT EXISTS provider_cost_currency  TEXT;
ALTER TABLE image_jobs ADD COLUMN IF NOT EXISTS provider_cost_source    TEXT;
