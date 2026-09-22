# 12 IMAGE MODEL EVALUATION — IMPLEMENTATION NOTES

**Date:** 2026-09-23  
**Verdict:** PASS WITH FINDINGS

## Implemented
- Per-request `modelId` override on job payload → OpenRouter / Fake providers (production env untouched)
- Tables `image_evaluations` + `image_evaluation_models` (V013)
- Admin APIs: `GET evaluation-models`, `POST/GET evaluations`, `GET evaluations/{id}`, `POST .../notes`
- Admin UI nav **Model Evaluation** — pick 2–3 models, same seed/persona, side-by-side candidates + notes/ratings
- Cost shown as `UNAVAILABLE` (never zero)
- Fake provider `force-fail*` model id for failure-path evaluation

## Reused
- PromptCompiler, ImageGenerationService/Orchestrator/Handler, jobs, candidates, lifecycle PATCH

## Tests
- `AdminImageModelEvaluationTest` (auth, 2-model persist, 3-model+force-fail row, notes, catalogue)

## Known gaps
- Live OpenRouter multi-model browser smoke NOT VERIFIED
- No automatic model ranking / production switch
- Eval model catalogue is env+defaults (`OPENROUTER_EVAL_MODELS`), not a full CRUD table

## Git commit
(pending)
