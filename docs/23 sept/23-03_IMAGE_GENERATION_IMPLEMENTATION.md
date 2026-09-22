# 23-03 IMAGE GENERATION — IMPLEMENTATION NOTES

**Date:** 2026-09-23  
**Task:** `IMAGE GENERATION & CANDIDATE CREATION` + candidate lifecycle overlap  
**Verdict:** PASS WITH FINDINGS

## Existing (reused)

- Image job/worker/provider/storage/PromptCompiler/visual identity (Task 24)

## Implemented this pass

- `seedPrompt` on create job + PromptCompiler “Scene seed:” section
- Admin HTTP default `candidateCount=4`
- Candidate lifecycle: `GENERATED|SHORTLISTED|DECLINED|SAVED|POST_READY|POSTED|FAILED` + `admin_remark` (V012)
- `PATCH /v1/admin/images/candidates/{id}`
- `POST /v1/admin/images/candidates/{id}/regenerate` (correction text; preserves seed; new job)
- `GET /v1/admin/images/config` (provider/model)
- Admin UI: seed textarea, count, poll results, previews, shortlist/decline/save/remark/regenerate
- Test persona seeder: Ananya + Richa from `docs/23 sept/persona testing data/`
- `POST /v1/admin/personas/seed-image-test-fixtures`

## Tests

- `AdminImageCandidateLifecycleTest` PASS
- `ImagePipelineTestPersonaSeederTest` PASS (when pics present)
- Visual write suite still PASS

## Findings / still open

- Dedicated Image Warehouse nav page not separate (candidates live on Visual tab + job result)
- Model comparison / cost / SLA UI not in this pass
- Browser morning smoke still needed with OpenRouter live
- Regeneration creates new job (does not overwrite source candidate) — intended

## Morning operator steps

1. Apply Flyway V011+V012 on Postgres
2. Start app with worker enabled + shared `IMAGE_STORAGE_DIR`
3. `POST /v1/admin/personas/seed-image-test-fixtures` (or Admin call)
4. Open Persona → Ananya/Richa → Visual Identity → seed prompt → Generate 4
5. Shortlist / remark / regenerate as needed
