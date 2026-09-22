-- V014: IDENTITY_MISMATCH candidate status (Task 13 identity consistency review)

ALTER TABLE generated_candidates DROP CONSTRAINT IF EXISTS generated_candidates_status_check;

ALTER TABLE generated_candidates
  ADD CONSTRAINT generated_candidates_status_check
  CHECK (status IN (
    'GENERATED',
    'SHORTLISTED',
    'DECLINED',
    'SAVED',
    'IDENTITY_MISMATCH',
    'POST_READY',
    'POSTED',
    'FAILED'
  ));
