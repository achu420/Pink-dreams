# Image Pipeline Production Readiness

**Date:** 2026-09-22  
**Branch:** `cursor/claude-work-followup`  
**Prior audit:** `docs/for cursor/IMAGE_PIPELINE_FINAL_AUDIT.md`  
**Method:** Source-traced verification + targeted hardening (no redesign)

---

## Executive Status

**PRODUCTION READY WITH DOCUMENTED LIMITATIONS**

Evidence supports shipping the chat-adjacent image path for single-instance (or shared-disk) deployments with Fake or OpenRouter providers, conversation-scoped user retrieve, and admin operations.

Not claimed:

- Multi-instance object-store HA (**KNOWN LIMITATION**)
- Live OpenRouter verification in this run (**NOT LIVE-VERIFIED — credentials unavailable**)
- Automatic `imageAssetIds` merge onto assistant messages after worker completion (**GAP** — clients poll job result using `imageJobId`)
- Admin UI surface for cancel/requeue/image SLA (**GAP** — APIs exist)

---

## Verified Architecture

```text
User message (ChatEngine Success)
  → BestEffortImageEnqueue (async, non-blocking)
  OR Admin POST /v1/admin/images/jobs
        ↓
ImageGenerationService.create (validation + identity)
        ↓
ImageJobRepository.createJob → QUEUED (idempotent)
        ↓
ImageJobWorkerRuntime tick
  → recoverStaleLeasedJobs
  → processRetryableJobs
  → processPendingJobs → claimJob → RUNNING
        ↓
ObservableImageJobHandler
  → BridgingImageJobHandler → ImageGenerationHandler
       → ImageProvider.submit (+ poll if deferred)
       → persistCandidates (delete-before-insert on retry)
       → ObjectStorage + generated_candidates
        ↓
SUCCEEDED | RETRY_WAIT | FAILED
        ↓
User GET /v1/images/jobs|result|assets (conversation owner)
Admin GET/POST cancel|requeue|attach-message|sla
```

Actual application states: `QUEUED` → `RUNNING` → `SUCCEEDED` | `RETRY_WAIT` → `QUEUED` | `FAILED` | `CANCELLED`.  
There is no separate `CLAIMED` / `PROVIDER_CALL` / `ASSET_PERSISTED` enum — claim sets `RUNNING`; provider + persist occur inside the handler before terminal status.

---

## Requirements Matrix

| Requirement | Implementation | Evidence | Test | Status |
|---|---|---|---|---|
| Authentication | User: `dev-auth` Basic; Admin: session/dev + `requireAdmin` | `UserImageRoutes`, `AdminImageGenerationRoutes` | Ownership + admin auth tests | **PASS** |
| User ownership | Conversation on job payload; `findByIdForUser` | `UserImageRoutes.ownsJob` | `UserImageOwnershipSecurityTest` | **PASS** |
| Job creation | Admin HTTP + Chat enqueue; **no** user POST | Routes inventory | E2E create | **PASS** (product: create not end-user HTTP) |
| Job persistence | `image_jobs` durable | Repository + schema | PhaseIMG4 | **PASS** |
| Worker startup | `ImageJobWorkerRuntime.start()` unless disabled | `Application.kt` | Wiring regression / runtime | **PASS** |
| Queue/claiming | Conditional UPDATE claim | `claimJob` | PhaseIMG4 dual-claim | **PASS** |
| Retry behavior | Retryable → `RETRY_WAIT` +60s; maxAttempts | `completeJobFailure` | PhaseIMG4 | **PASS** |
| Provider invocation | Fake / OpenRouter wired | `Application.kt` provider when | PhaseIMG5/6 | **PASS** |
| Provider failure | Error flags → fail/retry | OpenRouter `normalizeError` | PhaseIMG6 | **PASS** |
| Timeout | Provider read timeout + poll bound | Config + handler poll | Code | **PASS WITH FINDINGS** (not live-measured) |
| Asset persistence | LocalFile/InMemory + candidates | Handler persist | PhaseIMG8/E2E | **PASS** |
| Asset ownership | Same conversation gate as job | User asset GET | Ownership test | **PASS** |
| Message reference | `imageJobId`/`imageJobStatus` at enqueue | `BestEffortImageEnqueue` | Attribution test | **PASS WITH FINDINGS** (no auto asset IDs) |
| Retention | Terminal + age; skip message UUID refs | `ImageRetentionCleaner` | MessageReferenceTest | **PASS** |
| Cleanup safety | Skips QUEUED/RUNNING; skips referenced | Cleaner | Retention tests | **PASS** |
| ChatEngine integration | Post-delivery async enqueue | Factory + engine | Heuristic test + wiring | **PASS** |
| Admin visibility | Job list/detail/SLA APIs; Generate UI | Admin routes + admin-ui | Manual/API | **PASS WITH FINDINGS** |
| Security | Authz + redaction + storage guards | See Security | Ownership + security | **PASS** |
| Error reporting | `lastError` redacted; events | Repository + user routes | Security test | **PASS** |
| Idempotency | Unique (visualVersion, key) | `createJob` | PhaseIMG4 | **PASS** |
| Concurrency | Claim lock; one thread/process | Worker runtime | PhaseIMG4 | **PASS WITH FINDINGS** |
| Recovery after restart | Stale lease → QUEUED | `recoverStaleLeasedJobs` | PhaseIMG4 | **PASS WITH FINDINGS** (long jobs / lease) |
| Storage failure | Persist failure → retryable | Handler | PhaseIMG8 | **PASS** |

---

## Security

| Scenario | Result |
|----------|--------|
| User A own job/result/asset | 200 |
| User B cross-user | 403 |
| Anonymous | 401 |
| User mutate (cancel/requeue) | **N/A** — no user mutate routes (admin only) |
| Admin-scoped job (no conversationId) | User 403 |
| Path traversal / bad MIME / oversized store | Rejected |
| Secrets in `lastError` / events | Redacted on persist + user/admin responses |

**Hardening this task:** `completeJobFailure` and user `lastError` responses now call `redactSecrets` (previously user path could echo raw provider error text).

IDOR on user routes: blocked by conversation ownership. Admin is privileged by design.

---

## Reliability

| Crash / failure point | Actual behavior |
|-----------------------|-----------------|
| Queued, process dies | Remains QUEUED; next worker claims |
| Claimed/RUNNING, process dies | After lease timeout (default 300s), `recoverStaleLeasedJobs` → QUEUED |
| During provider call | Same as RUNNING; may duplicate provider work if lease expires mid-call (**KNOWN LIMITATION**) |
| Provider success, persist fails | Retryable failure; **retry now replaces candidate rows** (delete-before-insert) |
| Retry exhausted | FAILED with redacted `lastError` |

**Hardening this task:** `persistCandidates` deletes prior `generated_candidates` for the job before insert so retries do not duplicate DB rows.

---

## Storage

```text
Local filesystem is currently the default.
Production multi-instance deployment requires shared/object storage.
```

- Default: `LocalFileObjectStorage.fromEnv()` (`IMAGE_STORAGE_DIR`, max bytes)
- Path keys: `jobs/{jobId}/candidates/{index}` (no client path input)
- MIME/size validated on store
- `IMAGE_STORAGE=memory` for tests only

**KNOWN LIMITATION:** not multi-node durable without shared volume/object store.

---

## Retention

- Deletes terminal jobs older than `IMAGE_RETENTION_DAYS` (default 30)
- Skips if any message `metadata` contains job UUID or candidate UUID
- Does not delete QUEUED/RUNNING
- Full `Messages` scan is **O(jobs × messages)** — **OPERATIONAL LIMITATION** at large scale

Verified: referenced retained; unreferenced deleted (`ImageRetentionMessageReferenceTest`).

---

## Provider

| Item | Status |
|------|--------|
| Abstraction | VERIFIED |
| Fake path | TEST VERIFIED |
| OpenRouter class + wiring | VERIFIED |
| Live smoke | **NOT LIVE-VERIFIED — credentials unavailable** (`OPENROUTER_API_KEY` / `IMAGE_LIVE_SMOKE` unset) |

Credentials are request headers only; not logged intentionally. Error bodies now redacted into `lastError`.

---

## ChatEngine

```text
User message → PipelineChatEngine → deliver Success
  → post-delivery hooks (Composite)
  → BestEffortImageEnqueue.dispatch
       → looksLikeImageRequest (phrases OR skill image_share)
       → CompletableFuture.runAsync → ImageGenerationService.create
       → mergeMetadata(imageJobId, imageJobStatus)
  → worker later completes job/assets
```

- Does **not** block chat reply on generation
- Returns text first; image is async
- Client uses `imageJobId` from metadata + user retrieve APIs
- Does **not** auto-attach `imageAssetIds` on completion (**GAP** / **FUTURE ENHANCEMENT**)

---

## Performance

Honest measurements from this run: **none beyond test pass timing**. No production load test.

Qualitative:

- Job create: in-process DB insert
- Worker poll: default ~2s (`IMAGE_WORKER_POLL_MS`)
- Provider latency: Fake ~instant; OpenRouter dominated by remote RTT (**NOT MEASURED live**)
- Retention: full message table scan — flag as optimization candidate, not a defect for current scale

---

## Known Limitations

### Confirmed defect (fixed this task)

1. Retry could duplicate `generated_candidates` rows → **fixed** (delete-before-insert) + regression test  
2. User/API `lastError` could leak secret-shaped strings → **fixed** (redact on store + user response)

### Architectural / operational limitation

1. Local disk default — needs shared storage for multi-instance  
2. Lease recovery without heartbeat — long generations can be double-run if lease expires mid-handle  
3. Retention full-table metadata scan  
4. Single worker thread per process (multi-process claim OK)

### Gaps / future enhancement

1. No automatic message `imageAssetIds` after success  
2. Admin UI: Generate only; no cancel/requeue/result/image-SLA controls  
3. Admin list DTO does not surface parsed user ownership / retention reason  
4. Live OpenRouter smoke not run  

---

## Admin / Observability

| Question | Answerable today? |
|----------|-------------------|
| What jobs exist? | Yes — admin list API / persona visual jobs table |
| Which user owns them? | Partially — parse `requestPayload` / events now get conversationId when present (**improved this task**) |
| State / attempts / errors? | Yes |
| Latency? | Image SLA endpoint + events |
| Provider fail? | Yes (`lastError`, events) |
| Asset attached? | Result/candidates APIs |
| Message referenced? | Not in admin UI — check message metadata / retention skip |
| Why retained/deleted? | Not surfaced — **GAP** |

Observability cards in admin-ui are **chat-turn SLA**, not `GET /v1/admin/images/sla`.

---

## Tests

```text
gradle test --tests "com.pinkdreams.PhaseIMG*"
           --tests "com.pinkdreams.imaging.*"
           --tests "com.pinkdreams.visual.identity.PhaseIMG3*"
           --tests "com.pinkdreams.api.images.*"
           --tests "com.pinkdreams.chat.imaging.*"
```

| Metric | Value |
|--------|-------|
| Result | BUILD SUCCESSFUL |
| Total (filter XML) | **199** |
| Failures | **0** |
| Errors | **0** |
| Skipped | **1** (live OpenRouter when disabled) |
| New failures | none |
| Pre-existing outside filter | **NOT VERIFIED** this task |

New/updated coverage: retry persist idempotency; `completeJobFailure` redaction; prior ownership/retention tests retained.

---

## Changes Made

| File | Change |
|------|--------|
| `GeneratedCandidateRepository.kt` | `deleteByImageJob` |
| `ImageGenerationHandler.kt` | Clear candidates before persist (retry-safe) |
| `ImageJobRepository.kt` | Redact `lastError` on failure |
| `UserImageRoutes.kt` | Redact `lastError` in responses |
| `ObservableImageJobHandler.kt` | Parse conversation/turn/persona from payload into events |
| `PhaseIMG8OrchestrationTest.kt` | Retry persist no-duplicate regression |
| `PhaseIMGSecurityAndRetentionTest.kt` | Redacted `lastError` persistence |
| `docs/for cursor/IMAGE_PIPELINE_PRODUCTION_READINESS.md` | This report |

---

## Git

```text
Branch: cursor/claude-work-followup
Commit: 8c4b03d (fix(images): harden retry persist and lastError redaction)
Push: no
```

---

## Recommendation for next task

Based only on confirmed evidence:

1. **If deploying multi-instance:** operational task to provision shared storage + document `IMAGE_STORAGE_DIR` / future object-store adapter — do not redesign chat imaging.  
2. **If product needs completed assets on the message:** small follow-up to merge `imageAssetIds` after `completeJobSuccess` when `imageJobId` is already on assistant metadata.  
3. **If go-live with OpenRouter:** run gated `IMAGE_LIVE_SMOKE=true` once with real key and attach results to this readiness doc.  
4. **System Quality Tasks 23–31** may proceed for chat/text quality **if** single-instance + Fake/OpenRouter ops constraints above are accepted.

Do not treat content-factory / LoRA / social publish as next image work unless explicitly reopened.
