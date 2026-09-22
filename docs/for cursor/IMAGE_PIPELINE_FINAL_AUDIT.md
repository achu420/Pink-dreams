# IMAGE PIPELINE — PRODUCTION READINESS AUDIT (IMG-17)

**Date:** 2026-09-22  
**Basis:** [IMAGE_PIPELINE_AUDIT.md](IMAGE_PIPELINE_AUDIT.md), [IMAGE_PIPELINE_GAP_ANALYSIS.md](IMAGE_PIPELINE_GAP_ANALYSIS.md), implementation in this branch  
**Traffic note:** Verification used unit/integration tests with `FakeImageProvider`. Not production traffic.

---

## 1. Executive result

**PASS WITH FINDINGS.** The minimum chat-adjacent admin image path is wired and tested: create job → worker → provider → durable/local storage → result/asset APIs → observability events → SLA summary → retention cleaner → admin generate UI. Open gaps remain around end-user (non-admin) ownership, automatic chat turn coupling inside `ChatEngine`, and real OpenRouter live verification.

---

## 2. Existing implementation (post-work)

| Stage | Status |
|-------|--------|
| HTTP create/status/result/asset | IMPLEMENTED (admin) |
| Auth | IMPLEMENTED (admin session) |
| Validation + idempotency | IMPLEMENTED |
| Persona visual identity | IMPLEMENTED (reuse) |
| Prompt | IMPLEMENTED (`PromptCompiler`) |
| Provider | IMPLEMENTED (Fake default; OpenRouter when key set) |
| Job/worker | IMPLEMENTED (boot + bridge + observable) |
| Asset storage | IMPLEMENTED (`LocalFileObjectStorage` default) |
| Delivery | IMPLEMENTED (admin asset GET + message metadata attach) |
| Observability | IMPLEMENTED (`image_generation_events`) |
| SLA | IMPLEMENTED (`GET /v1/admin/images/sla`) |
| Retention | IMPLEMENTED (`ImageRetentionCleaner`) |
| ChatEngine auto-image | IMPLEMENTED (best-effort post-delivery heuristic + optional `image_share` skill) |
| End-user asset authz | IMPLEMENTED (`GET /v1/images/jobs|result|assets` with conversation ownership) |

---

## 3. Implemented (this program)

- Formal audit + gap analysis docs  
- `LocalFileObjectStorage` with path-traversal / MIME / size guards  
- Flyway `V010__generated_candidates_and_image_events.sql`  
- Idempotent `ImageJobRepository.createJob`  
- `BridgingImageJobHandler` + deferred provider poll  
- `ImageJobWorkerRuntime` started from `Application`  
- `ImageGenerationService` + `AdminImageGenerationRoutes`  
- `ObservableImageJobHandler` + SLA endpoint  
- Admin requeue/cancel + Generate button in `admin-ui.html`  
- Message metadata attach for image assets  
- Retention cleaner  
- Tests: `PhaseIMGPipelineE2ETest`, `PhaseIMGSecurityAndRetentionTest`; Job 3 idempotency updated  

---

## 4–6. Files / DB / API changes

**Key new/changed files:** see git diff under `src/main/kotlin/com/pinkdreams/imaging/**`, `storage/LocalFileObjectStorage.kt`, `api/admin/AdminImageGenerationRoutes.kt`, `Application.kt`, `db/migration/V010__*.sql`, `docs/for cursor/*`.

**DB:** `generated_candidates` (documented), `image_generation_events`.

**APIs:**
- `POST /v1/admin/images/jobs`
- `GET /v1/admin/images/jobs`, `.../{jobId}`, `.../{jobId}/result`
- `GET /v1/admin/images/assets/{assetId}`
- `POST .../cancel`, `.../requeue`, `.../attach-message`
- `GET /v1/admin/images/sla`
- `GET /v1/images/jobs/{jobId}`, `.../result`, `GET /v1/images/assets/{assetId}` (user-owned)

---

## 7–15. Checklist answers

| # | Question | Classification | Evidence |
|---|----------|----------------|----------|
| 1 | Valid request creates image? | PASS WITH FINDINGS | E2E test with Fake; live OpenRouter not run in CI |
| 2 | Duplicates controlled? | PASS | Idempotent createJob + E2E reuse |
| 3 | Worker crash recovery? | PASS | `recoverStaleLeasedJobs` + runtime tick |
| 4 | Retryable vs permanent? | PASS WITH FINDINGS | Provider error flag + maxAttempts; matrix not exhaustive for all OpenRouter codes |
| 5 | Failed calls diagnosable? | PASS | `lastError` + image_generation_events (redacted) |
| 6 | Asset durable? | PASS WITH FINDINGS | LocalFile default; set `IMAGE_STORAGE=memory` only for tests |
| 7 | Client retrieve? | PASS WITH FINDINGS | Admin only |
| 8 | Cross-user access blocked? | PASS WITH FINDINGS | Admin gate only; no end-user ownership layer yet |
| 9 | Secrets excluded? | PASS | Redaction helper + tests |
| 10 | Admins see health? | PASS | Job list + SLA + visual tab jobs |
| 11 | Image SLA measurable? | PASS WITH FINDINGS | Separate from text; sample must be labeled |
| 12 | Stale jobs detectable? | PASS | Lease recovery |
| 13 | Storage growth understood? | PASS WITH FINDINGS | Retention cleaner; no prod growth data |
| 14 | Critical failure paths tested? | PASS WITH FINDINGS | E2E success + security unit; not full provider 5xx matrix |
| 15 | Provider/model config known? | PASS | Env: `IMAGE_PROVIDER`, `OPENROUTER_*`, `IMAGE_STORAGE*` |

---

## 16. Live verification

- `gradle test --tests com.pinkdreams.imaging.PhaseIMGPipelineE2ETest --tests com.pinkdreams.imaging.job.PhaseIMG4ImageJobInfrastructureTest` → **BUILD SUCCESSFUL**  
- Security unit tests added (run with imaging package)  
- No production OpenRouter spend in this audit  

---

## 17. Known gaps

1. OpenRouter live smoke is opt-in only (`IMAGE_LIVE_SMOKE=true` + `OPENROUTER_API_KEY`) — not default CI  
2. Content-factory phases (storyline/daily/publish/LoRA) deferred per plan  
3. `image_share` seeds as draft/inactive like other skills — activate in admin to let Intent Discovery select it (text heuristic still works without activation) 

---

## 18. Git

Branch: `cursor/claude-work-followup` (based on Claude `18e61ce`). Commit when requested by user.

---

## Classification summary

**Overall: PASS WITH FINDINGS** — chat post-delivery enqueue + end-user ownership APIs are in place; remaining gaps are live provider smoke and optional skill seed.
