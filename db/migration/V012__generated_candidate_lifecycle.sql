-- V012: candidate lifecycle status + admin remark on generated_candidates

ALTER TABLE generated_candidates
  ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'GENERATED',
  ADD COLUMN IF NOT EXISTS admin_remark TEXT;

-- optional check: known lifecycle values
DO $$
BEGIN
  ALTER TABLE generated_candidates
    ADD CONSTRAINT generated_candidates_status_check
    CHECK (status IN (
      'GENERATED',
      'SHORTLISTED',
      'DECLINED',
      'SAVED',
      'POST_READY',
      'POSTED',
      'FAILED'
    ));
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;
