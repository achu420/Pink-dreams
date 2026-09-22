# Image Pipeline Final Audit

**Date:** 2026-09-22  
**Branch:** `cursor/claude-work-followup`  
**Method:** Independent verification against current code (not prior audit conclusions)  
**Prior docs treated as claims:** `IMAGE_PIPELINE_AUDIT.md`, `IMAGE_PIPELINE_GAP_ANALYSIS.md`, previous `IMAGE_PIPELINE_FINAL_AUDIT.md`

---

## 1. Executive Result

**Overall: PASS WITH FINDINGS**

The chat-adjacent image pipeline (IMG-02 → IMG-16) is **implemented, wired at application boot, and test-verified** for:

- request validation + idempotency  
- persona visual identity → prompt → Fake/OpenRouter provider  
- job state machine + worker claim/retry/stale recovery  
- durable local-file storage (default) + candidate persistence  
- admin job APIs + end-user conversation-scoped retrieve APIs with ownership  
- observability events that cannot break generation  
- retention cleaner with message-metadata reference protection  
- ChatEngine best-effort post-delivery enqueue  

**Resolved prior contradiction:** end-user retrieve/ownership is **real** (`UserImageRoutes` + `UserImageOwnershipSecurityTest`). Earlier checklist rows that said “admin only / no ownership layer” were **stale documentation**, not missing code.

**Not live-verified this run:** OpenRouter (`OPENROUTER_API_KEY` unset, `IMAGE_LIVE_SMOKE` unset) → **NOT RUN**.

**Known limitations (not FAIL):** local-disk durability is host filesystem (not object-store HA); admin UI only wires Generate (other admin APIs exist without UI); ChatEngine image trigger is heuristic-only.

---

## 2. Actual Architecture

```
Admin POST /v1/admin/images/jobs
  OR Chat BestEffortImageEnqueue (post-delivery)
        ↓
ImageGenerationService.create
        ↓
ImageGenerationOrchestrator.submit
  → PromptCompiler.compile
  → ImageJobRepository.createJob (QUEUED, idempotent)
        ↓
ImageJobWorkerRuntime.poll → ImageJobWorker.processPendingJobs
  → claimJob (RUNNING)
  → ObservableImageJobHandler
       → BridgingImageJobHandler
            → ImageGenerationHandler
                 → ImageProvider (Fake | OpenRouter)
                 → ObjectStorage.store
                 → GeneratedCandidateRepository
  → SUCCEEDED | RETRY_WAIT | FAILED
        ↓
Admin GET …/result|assets  OR  User GET /v1/images/… (conversation owner)
```

| Component | Location |
|-----------|----------|
| Admin HTTP | `api/admin/AdminImageGenerationRoutes.kt` |
| User HTTP | `api/images/UserImageRoutes.kt` |
| Service | `imaging/orchestration/ImageGenerationService.kt` |
| Prompt | `imaging/compiler/PromptCompiler.kt` |
| Worker | `imaging/job/ImageJobWorker.kt` + `ImageJobWorkerRuntime.kt` |
| Provider | `FakeImageProvider` / `openrouter/OpenRouterImageProvider` |
| Storage | `LocalFileObjectStorage` (default) / `InMemoryObjectStorage` |
| Obs | `ObservableImageJobHandler` + `image_generation_events` |
| Retention | `imaging/retention/ImageRetentionCleaner.kt` |
| Chat hook | `chat/imaging/BestEffortImageEnqueue.kt` via `ChatEngineFactory` |
| DB | Flyway `V010__generated_candidates_and_image_events.sql` |

### Application wiring (verified in `Application.kt`, not inferred from class existence)

| Question | Answer | Evidence |
|----------|--------|----------|
| Is the worker actually started? | **YES** | unless `IMAGE_WORKER_ENABLED=false`; `ImageJobWorkerRuntime.start()` |
| Is the provider reachable from the worker? | **YES** | same `imageProvider` → handler → `ObservableImageJobHandler` → worker |
| Is storage durable in normal runtime? | **YES** (local disk) | default `LocalFileObjectStorage.fromEnv()`; `IMAGE_STORAGE=memory` only for tests |
| Are image routes registered? | **YES** | admin + user routes registered in module routing |
| Is retention scheduled? | **YES** | `ImageRetentionCleaner(...).startScheduled()` |
| ChatEngine image integration? | **YES** | `imageGenerationService` passed into factory → `BestEffortImageEnqueue` |

---

## 3. IMG-02 — Request Contract & Validation

| Check | Result | Evidence |
|-------|--------|----------|
| Body parse / invalid body | PASS | Admin create returns 400 `VALIDATION_ERROR` |
| Required idempotencyKey | PASS | `ImageGenerationService`: blank / >200 rejected |
| Dimensions | PASS | width/height `64..2048` |
| candidateCount | PASS | `1..4` |
| Invalid persona | PASS | `NoSuchElementException` → validation mapping |
| Missing visual identity | PASS | `IllegalStateException` explicit |
| Idempotency same key | PASS | `ImageJobRepository.createJob` returns existing; E2E + PhaseIMG4 |
| Different key | PASS | new job |

**Status: PASS** (TEST VERIFIED)

---

## 4. IMG-03 — Persona Visual Identity

Trace: `personaId` → `personaIdentityId` → active visual version (or explicit version) → scene + guide into `PromptCompiler`.

| Check | Result |
|-------|--------|
| Active version used | PASS |
| Version must belong to persona identity | PASS (`require`) |
| No second identity system | PASS (reuses visual identity layer) |
| Missing identity / versions | PASS (explicit exceptions) |

**Status: PASS** (TEST VERIFIED — PhaseIMG1/3/7/8 + service)

---

## 5. IMG-04 — Prompt Construction

One authoritative path: `ImageGenerationService` → `ImageGenerationOrchestrator` → `PromptCompiler.compile`.

Precedence: identity / physical guide first; scene presentation appended; scene does not overwrite guide identity (documented + PhaseIMG7).

Wardrobe IDs stored in payload; not all wardrobe text is inlined into prompt (KNOWN LIMITATION, not a FAIL for IMG-04 core).

**Status: PASS WITH FINDINGS**

---

## 6. IMG-05 — Provider Abstraction

| Provider | Wired? | When |
|----------|--------|------|
| `FakeImageProvider` | YES | `IMAGE_PROVIDER=fake` OR no `OPENROUTER_API_KEY` |
| `OpenRouterImageProvider` | YES (class + wiring) | key present and not forced fake |

Timeouts/config via `ImageProviderConfig` / env. Deferred poll in `ImageGenerationHandler`. Live smoke gated by `IMAGE_LIVE_SMOKE=true`.

**Status: PASS WITH FINDINGS** — implementation VERIFIED; live OpenRouter **NOT RUN** this audit.

---

## 7. IMG-06 — Job State Machine

Actual states: `QUEUED`, `RUNNING`, `RETRY_WAIT`, `SUCCEEDED`, `FAILED`, `CANCELLED`.

| Current | Event | Next | Valid |
|---------|-------|------|-------|
| (new) | create | QUEUED | yes |
| QUEUED | claim | RUNNING | yes |
| RUNNING | success | SUCCEEDED | yes |
| RUNNING | retryable fail, attempts < max | RETRY_WAIT → QUEUED | yes |
| RUNNING | permanent / max attempts | FAILED | yes |
| QUEUED/RUNNING/RETRY_WAIT | cancel | CANCELLED | yes |
| FAILED | admin requeue | QUEUED | yes |
| RUNNING | stale lease recovery | QUEUED | yes |

**Status: PASS** (TEST VERIFIED — PhaseIMG4)

---

## 8. IMG-07 — Worker Reliability

Boot → `ImageJobWorkerRuntime` poll → claim → execute → persist.

| Scenario | Evidence |
|----------|----------|
| Normal job | PhaseIMGPipelineE2ETest |
| Stale RUNNING | `recoverStaleLeasedJobs` + PhaseIMG4 |
| Retry / duplicate claim | PhaseIMG4 |
| Unexpected exception | treated retryable in worker |

**Status: PASS** (TEST VERIFIED)

---

## 9. IMG-08 — Retry & Failure Policy

| Failure | Retry? | Expected |
|---------|--------|----------|
| Provider timeout | yes | RETRY_WAIT / QUEUED |
| Provider 5xx / unavailable | yes | retryable |
| Rate limit / quota | yes | retryable |
| Auth / invalid_request | no | FAILED |
| Empty completed candidates | no | FAILED |
| Storage / handler exception | yes | retryable |
| Max attempts | — | FAILED |

**Status: PASS WITH FINDINGS** — Fake + OpenRouter classification VERIFIED in code/tests; not every HTTP code live-exercised.

---

## 10. IMG-09 — Asset Pipeline

Provider output → validation (MIME/size in `LocalFileObjectStorage`) → store under `jobs/{jobId}/candidates/{i}` → `generated_candidates` → retrieve APIs.

Path traversal rejected (E2E). Deterministic keys. Missing bytes → 404 after authz.

**Durable storage:** default local directory (`IMAGE_STORAGE_DIR` or default). Survives process restart on same host. **Not** multi-node shared object storage — **KNOWN LIMITATION**.

**Status: PASS WITH FINDINGS**

---

## 11. IMG-10 — API & Delivery

### Admin (session-auth / dev-auth + requireAdmin)

| Endpoint | Purpose |
|----------|---------|
| `POST /v1/admin/images/jobs` | create |
| `GET /v1/admin/images/jobs`, `/{jobId}`, `/{jobId}/result` | list/status/result |
| `GET /v1/admin/images/assets/{assetId}` | asset bytes |
| `POST …/cancel`, `…/requeue`, `…/attach-message` | ops |
| `GET /v1/admin/images/sla` | image SLA |

### End-user (`dev-auth` + conversation ownership) — **NOT admin-only**

| Endpoint | Purpose |
|----------|---------|
| `GET /v1/images/jobs/{jobId}` | status |
| `GET /v1/images/jobs/{jobId}/result` | result |
| `GET /v1/images/assets/{assetId}` | asset |

No end-user create API (admin or ChatEngine enqueue only). Jobs without `conversationId` → user **403** (ADMIN-SCOPED).

**Status: PASS** (TEST VERIFIED — ownership HTTP tests)

---

## 12. IMG-11 — Security

| Check | Result | Evidence |
|-------|--------|----------|
| Unauthenticated | 401 | `UserImageOwnershipSecurityTest` |
| Owner User A | 200 | same |
| User B cross-user | 403 | same |
| Admin-only job (no conversation) | 403 for users | same |
| Path traversal / bad MIME / size | rejected | PhaseIMGSecurityAndRetentionTest + E2E |
| Secret redaction in events | PASS | `redactSecrets` test |

**Classification clarity:**

- End-user retrieve: **IMPLEMENTED + TEST VERIFIED** (ownership)  
- Admin APIs: **ADMIN ONLY** (separate surface)  
- Prior audit “admin-only client retrieve”: **documentation error**, superseded

**Status: PASS**

---

## 13. IMG-12 — Observability

`ObservableImageJobHandler` records to `image_generation_events` (job id, provider, model, latency, attempt, outcome, failure class).

`ImageGenerationEventRepository.record` catches failures and returns false — **image generation still succeeds if obs persistence fails**.

**Status: PASS** (TEST VERIFIED — E2E event + repository behavior)

---

## 14. IMG-13 — Image SLA

`GET /v1/admin/images/sla?hours=` returns counts, success/failure rates, p50/p95 latencies with `samplePopulation=image_generation_events` and an explicit note that sample is not production SLA unless traffic is production.

**Data source this audit:** **test / synthetic** (Fake provider runs). Do not treat as production SLA.

Admin UI observability cards are **chat-turn SLA**, not this image endpoint (FINDING).

**Status: PASS WITH FINDINGS**

---

## 15. IMG-14 — Admin Controls

| UI element | Backend | Functional | Status |
|------------|---------|------------|--------|
| Generate images | `POST /v1/admin/images/jobs` | yes | PASS |
| Jobs table (per visual version) | visual/jobs read API | display | PASS (read-only) |
| Retry / Requeue | API exists | **no UI** | FINDING — API only |
| Cancel | API exists | **no UI** | FINDING — API only |
| Result / asset download | API exists | **no UI** | FINDING — API only |
| Image SLA | `/v1/admin/images/sla` | **no UI** | FINDING — API only |
| Obs “SLA” cards | chat metrics | not image | FINDING — mislabeled for image ops |

No fake Generate control: button posts real jobs and shows error/loading text.

**Status: PASS WITH FINDINGS**

---

## 16. IMG-15 — Retention

| Behavior | Result |
|----------|--------|
| Retention days | `IMAGE_RETENTION_DAYS` default 30 |
| Deletes | terminal jobs with old `completedAt` + candidates + blobs |
| Protects active | never deletes QUEUED/RUNNING |
| Protects message-referenced | **YES** — skip if metadata contains job/candidate UUID |
| Missing files | delete continues on DB; blob delete best-effort |
| Scheduled | yes, 24h daemon from Application |

Fixed during this verification: message-reference skip + `ImageRetentionMessageReferenceTest`.

**Status: PASS** (TEST VERIFIED)

---

## 17. IMG-16 — Test Hardening

Existing PhaseIMG1–8 preserved. Added:

- `UserImageOwnershipSecurityTest` (owner / cross-user / anon / no-conversation)  
- `ImageRetentionMessageReferenceTest` (referenced skip + unreferenced delete)

This run (`gradle test` imaging/PhaseIMG/ownership/retention/chat imaging): **BUILD SUCCESSFUL**.

**Status: PASS**

---

## 18. ChatEngine Integration

```
user message → ChatEngine deliver Success
        ↓
post-delivery hooks
        ↓
BestEffortImageEnqueue.dispatch
        ↓ (async, non-blocking)
ImageGenerationService.create (conversationId + userId + turnRequestId)
        ↓
worker → asset
        ↓
assistant metadata: imageJobId, imageJobStatus
```

**Trigger (do not redesign):** `selectedSkillKey == image_share` OR text contains phrases (`show me`, `selfie`, `send a pic/photo`, etc.).

Normal text turns: heuristic false → no enqueue. Failures logged; chat Success unaffected. Heuristic unit-tested; full chat-turn E2E with live worker **not** separately asserted beyond wiring + attribution payload tests.

**Status: PASS WITH FINDINGS** — VERIFIED wiring + heuristic; full chat HTTP E2E for image attach: **NOT VERIFIED** as a single end-to-end browser/chat test this run.

---

## 19. Admin UI Verification

See §15 table. Generate is wired; cancel/requeue/result/image-SLA have backends but no admin-ui controls (not decorative fakes — simply absent). Obs SLA is chat workload.

---

## 20. OpenRouter Live Smoke Test

| Result | Detail |
|--------|--------|
| **NOT RUN** | no live provider credentials/configuration available (`OPENROUTER_API_KEY` unset; `IMAGE_LIVE_SMOKE` unset) |

Opt-in test exists: `PhaseIMGOpenRouterLiveSmokeTest`.

---

## 21. Security Verification

| Scenario | Result |
|----------|--------|
| User A → own job/result/asset | 200 |
| User B → User A resources | 403 |
| Anonymous → job/asset | 401 |
| User → admin job without conversationId | 403 |
| Secrets in event text | redacted |

**End-user authorization:** IMPLEMENTED + TEST VERIFIED  
**Admin operations:** ADMIN ONLY (intended)

---

## 22. Storage / Retention Verification

| Item | Classification |
|------|----------------|
| Default storage | LocalFileObjectStorage — durable for single host |
| Restart preserves files | YES on same `IMAGE_STORAGE_DIR` |
| Memory storage | test / explicit env only |
| Retention scheduled | YES |
| Message-referenced protection | YES (this audit fix) |

---

## 23. Test Results

Command (representative):

```text
gradle test --tests "com.pinkdreams.PhaseIMG*"
           --tests "com.pinkdreams.imaging.*"
           --tests "com.pinkdreams.visual.identity.PhaseIMG3*"
           --tests "com.pinkdreams.api.images.*"
           --tests "com.pinkdreams.chat.imaging.*"
```

| Metric | Value |
|--------|-------|
| Result | **BUILD SUCCESSFUL** |
| Total (filtered suites XML) | **197** |
| Failed | **0** |
| Errors | **0** |
| Skipped | **1** (live OpenRouter smoke when not enabled) |
| New ownership/retention tests | passed |
| Pre-existing PhaseIMG1–8 | preserved / passed in filter |
| New failures introduced | **none** |

Full-repo suite beyond imaging was not required for this IMG baseline; unrelated failures (if any outside filter) are not claimed here.

---

## 24. Final IMG-02 → IMG-17 Matrix

| Task | Requirement | Actual implementation | Test/evidence | Status |
|------|-------------|----------------------|---------------|--------|
| IMG-02 | Request contract | Service + admin routes validation; idempotent createJob | PhaseIMG4/E2E | **PASS** |
| IMG-03 | Persona identity | Active visual version → SceneIntent | PhaseIMG1/3/7 | **PASS** |
| IMG-04 | Prompt | Single PromptCompiler path | PhaseIMG7 | **PASS WITH FINDINGS** |
| IMG-05 | Provider | Fake default; OpenRouter when keyed | PhaseIMG5/6; live NOT RUN | **PASS WITH FINDINGS** |
| IMG-06 | State machine | QUEUED…CANCELLED + RETRY_WAIT | PhaseIMG4 | **PASS** |
| IMG-07 | Worker | Runtime started in Application | PhaseIMG4 + wiring | **PASS** |
| IMG-08 | Retry | Provider error flags + maxAttempts | PhaseIMG4 + OpenRouter normalize | **PASS WITH FINDINGS** |
| IMG-09 | Asset | LocalFile + candidates | E2E + security | **PASS WITH FINDINGS** |
| IMG-10 | API | Admin full + user retrieve | Ownership HTTP tests | **PASS** |
| IMG-11 | Security | Authn/authz/redaction/path guards | Ownership + security tests | **PASS** |
| IMG-12 | Observability | image_generation_events; non-fatal | E2E | **PASS** |
| IMG-13 | SLA | Admin image SLA endpoint | Code + response note | **PASS WITH FINDINGS** |
| IMG-14 | Admin | Generate UI + admin APIs | admin-ui + routes | **PASS WITH FINDINGS** |
| IMG-15 | Retention | Cleaner + message-ref skip | ImageRetentionMessageReferenceTest | **PASS** |
| IMG-16 | Tests | PhaseIMG1–8 + new ownership/retention | gradle BUILD SUCCESSFUL | **PASS** |
| IMG-17 | Audit | This document | Independent verification | **PASS** |

---

## 25. Remaining Gaps

1. Live OpenRouter smoke not executed (credentials absent).  
2. Admin UI lacks cancel/requeue/result/image-SLA controls (APIs exist).  
3. Admin UI “SLA” cards measure chat turns, not image_generation_events.  
4. Chat full HTTP turn → asset attachment E2E not separately run this audit.  
5. Local-file storage is not multi-instance object storage.  
6. Wardrobe selection not fully expanded into prompt text.

---

## 26. Deferred Work

- Storyline / daily content factory  
- Instagram / social publishing  
- LoRA training  
- Large-scale content-factory architecture (`image-pipeline-architecture.md` twin)  

These remain **DEFERRED** by scope.

---

## 27. Files Changed (this verification)

| File | Change |
|------|--------|
| `src/main/kotlin/com/pinkdreams/imaging/retention/ImageRetentionCleaner.kt` | Skip deletion when job/candidate UUIDs appear in message metadata |
| `src/test/kotlin/com/pinkdreams/api/images/UserImageOwnershipSecurityTest.kt` | **new** — owner / cross-user / anon / no-conversation |
| `src/test/kotlin/com/pinkdreams/imaging/retention/ImageRetentionMessageReferenceTest.kt` | **new** — referenced vs unreferenced cleanup |
| `docs/for cursor/IMAGE_PIPELINE_FINAL_AUDIT.md` | Rewritten evidence-backed audit |

---

## 28. Git Status

```text
Branch: cursor/claude-work-followup (tracks origin)
HEAD prior to this commit: 23a8b1f
Push: no (unless requested)
```

This commit includes the retention fix, ownership/retention tests, and this audit rewrite.

---

## Classification glossary (for critical surfaces)

| Surface | Status |
|---------|--------|
| End-user authorization | **IMPLEMENTED** + **TEST VERIFIED** |
| ChatEngine integration | **IMPLEMENTED** + **TEST VERIFIED** (heuristic/wiring); full chat E2E **NOT VERIFIED** |
| OpenRouter | **IMPLEMENTED**; **NOT LIVE VERIFIED** this run |
| Worker startup | **IMPLEMENTED** + **VERIFIED** in Application |
| Durable storage | **IMPLEMENTED** (local disk) — **KNOWN LIMITATION** vs cloud object store |
| Retention | **IMPLEMENTED** + **TEST VERIFIED** (incl. message refs) |
| Admin operations | **IMPLEMENTED** (API); UI Generate only — **PASS WITH FINDINGS** |
