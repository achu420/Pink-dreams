# Image Pipeline — Multi-Instance Storage, Recovery & Production Operations

**Date:** 2026-09-22  
**Branch:** `cursor/claude-work-followup`  
**Basis:** Current implementation + concrete fixes (no redesign)

---

## 1. Executive Result

**PASS WITH FINDINGS**

Job claiming was already atomic; a real lease-ownership hole on completion was found and fixed. Message attachment after crash now has idempotent reconciliation. Local disk storage remains **instance-local** by design.

**Multi-instance verdict: B — SAFE FOR MULTI-INSTANCE WITH SHARED STORAGE** (not C; not A-only after lease fix, with shared storage + shared DB).

---

## 2. Current Architecture Verified

```text
createJob (idempotent) → QUEUED
  → claimJob (CAS: QUEUED→RUNNING + claimedByWorker)
  → provider + persistCandidates (delete-before-insert)
  → completeJobSuccess/Failure ONLY if claimedByWorker matches
  → completionAttach / reconcileMessageAttachments
  → assistant metadata: imageJobId, imageJobStatus, imageAssetIds, imageAssetUrls
```

Worker tick (`ImageJobWorkerRuntime`): recover stale leases → requeue RETRY_WAIT → process QUEUED → reconcile terminal message attachments.

---

## 3. Job Concurrency

| Check | Result |
|-------|--------|
| Atomic claim | **PASS** — conditional UPDATE status=QUEUED |
| Two workers same job | **PASS** — second claim null; concurrent test 8 threads → 1 winner |
| Complete without lease | **FIXED** — requires `claimedByWorker == workerName` |
| Stale worker after B reclaim | **FIXED** — A’s complete returns null; B remains owner (Verify 28b) |
| Concurrent idempotent create | **PASS** — unique key + catch duplicate |

**Code change:** `completeJobSuccess` / `completeJobFailure` now take `workerName` and use conditional UPDATE; return `null` on lease loss. Worker ignores lease-lost results (does not fail another worker’s job).

---

## 4. Crash / Lease Recovery

| Crash point | Behavior |
|-------------|----------|
| Before provider | Remains RUNNING until lease timeout → QUEUED |
| During provider | Same; may re-run provider after recovery (**KNOWN LIMITATION**: no heartbeat) |
| After provider, before/after persist | Retryable path; candidates replaced on retry |
| After candidates, before SUCCEEDED | Lease recover → re-process; persist replaces rows |
| After SUCCEEDED, before attach | **FIXED** — `reconcileMessageAttachments` re-merges metadata |

Lease timeout: `IMAGE_WORKER_LEASE_SECONDS` (default 300), now passed from runtime.

Stuck forever? Only if worker never runs and lease recovery never runs (`IMAGE_WORKER_ENABLED=false`) — operational.

---

## 5. Message Attachment Recovery

Previously: one-shot `completionAttach` only. Crash between SUCCEEDED and attach could leave `imageJobStatus=QUEUED` without assets.

**Fix:** `ImageJobWorker.reconcileMessageAttachments` each tick (idempotent merge via `ImageMessageCompletionAttach`).

**Test:** complete without attach → reconcile → assets present.

Relationship job → assets remains in DB (`generated_candidates`) even if metadata lag; reconcile restores chat link.

---

## 6. Storage Safety

| Check | Result |
|-------|--------|
| Path traversal | Rejected (`..`, escape root) |
| MIME / size | Validated |
| Atomic write | **IMPROVED** — temp file + move (ATOMIC_MOVE when supported) |
| Missing file | retrieve → null → API 404 |
| Two instances, different disks | **BREAKS** asset GET unless shared volume |

```text
Local filesystem is the default.
Production multi-instance deployment REQUIRES shared/object storage
(or a shared IMAGE_STORAGE_DIR mount) plus a shared database.
```

No object-store adapter invented this task.

---

## 7. Retention Safety

Unchanged logic (already message-ref protected): skips QUEUED/RUNNING; skips metadata UUID refs; deletes old terminal + blobs.

Tests: prior `ImageRetentionMessageReferenceTest` retained. No code change required this section.

**FINDING:** full `Messages` scan — operational cost at scale.

---

## 8. Provider Failure Matrix

| Failure | Expected | Actual |
|---------|----------|--------|
| Provider timeout | retryable | yes (OpenRouter normalize / handler) |
| Provider 5xx | retryable | yes |
| Auth / invalid_request | permanent | yes |
| Empty completed candidates | permanent | yes |
| Unsupported MIME on store | fail persist → retryable | yes |
| Asset too large | fail persist → retryable | yes |
| Storage write failure | retryable | yes |
| Candidate DB retry | replace rows | yes (prior fix) |
| Message attach failure | recoverable | yes (reconcile) |
| Worker crash | lease recovery | yes |
| Duplicate worker | no double claim | yes |
| Stale complete | no steal | **fixed** |
| Retention race | reference-safe | yes (skip if metadata contains id) |

---

## 9. Security

Prior ownership tests still apply (User A 200 / B 403 / anon 401). Secrets redacted in `lastError` and events. Storage paths not returned as filesystem paths (API asset URLs only).

No weakening of auth this task.

---

## 10. Observability

Events capture job id, provider, model, attempt, outcome, latencies; conversation/turn/persona from payload when present. Obs failure does not fail the job (`record` catch).

---

## 11. Admin Operations

| Capability | Status |
|------------|--------|
| Generate | UI + API wired |
| List/detail/result/asset | API |
| Cancel / requeue / attach-message | API only (no UI) |
| Image SLA | API only (admin-ui SLA cards are chat-turn metrics) |

No fake Generate button. Unavailable UI controls are absent, not silent no-ops.

---

## 12. Tests

| Metric | Value |
|--------|-------|
| Filter result | **BUILD SUCCESSFUL** |
| Total | **204** |
| Failed | **0** |
| Errors | **0** |
| Skipped | **1** (live OpenRouter) |
| New tests | concurrent claim; concurrent idempotent create; lease steal blocked (28b); attach reconcile |
| PhaseIMG1–8 | preserved |
| Live Postgres E2E this run | **NOT RUN** |
| New failures | none |
---

## 13. Live Verification

| Mode | Status |
|------|--------|
| Unit / integration (H2 + Fake) | **VERIFIED** |
| Real Postgres | **NOT VERIFIED** this run |
| Fake provider | **VERIFIED** |
| OpenRouter | **NOT LIVE-VERIFIED** — credentials unavailable |

---

## 14. Multi-Instance Verdict

**B. SAFE FOR MULTI-INSTANCE WITH SHARED STORAGE**

Evidence:

- Shared DB + atomic claim + lease-bound completion → safe job processing across instances  
- LocalFileObjectStorage without shared mount → assets not visible across instances → **not C**  
- Single-instance remains safe (**A** also true as a subset)

**Not D** — defects that allowed cross-worker completion steal are fixed and tested.

---

## 15. Remaining Production Gaps

1. Shared/object storage adapter (or documented NFS/SMB mount) for true multi-instance assets  
2. Lease heartbeat for generations longer than lease timeout (avoids duplicate provider spend)  
3. OpenRouter live smoke when credentials available  
4. Admin UI for cancel/requeue/image SLA  
5. Retention query scaling (indexed metadata search)  
6. Real Postgres multi-process soak test  

---

## 16. Files Changed

| File | Change |
|------|--------|
| `ImageJobRepository.kt` | Lease-bound complete success/failure |
| `ImageJobWorker.kt` | Pass workerName; lease-lost handling; reconcile attachments |
| `ImageJobWorkerRuntime.kt` | Configurable lease; call reconcile |
| `LocalFileObjectStorage.kt` | Temp+atomic move writes |
| `PhaseIMG4ImageJobInfrastructureTest.kt` | Lease steal tests + API updates |
| `ImageJobMultiInstanceHardeningTest.kt` | **new** |
| `PhaseIMGSecurityAndRetentionTest.kt` | completeJobFailure signature |
| `IMAGE_PIPELINE_MULTI_INSTANCE_OPS.md` | this report |

---

## 17. Git

```text
Branch: cursor/claude-work-followup
Commit: 958e28b
Push: no
```

---

## Configuration Reference (Part 6)

| Key | Default | Reload | Admin |
|-----|---------|--------|-------|
| `IMAGE_PROVIDER` | openrouter (Fake if no key / fake) | restart | no |
| `OPENROUTER_API_KEY` | — | restart | no |
| `OPENROUTER_IMAGE_MODEL` | gpt-image-2.5-flare | restart | no |
| `OPENROUTER_IMAGE_ENDPOINT` | openrouter images API | restart | no |
| `IMAGE_CONNECT/READ_TIMEOUT_SECONDS` | 10 / 300 | restart | no |
| `IMAGE_STORAGE` | local (≠ memory) | restart | no |
| `IMAGE_STORAGE_DIR` | data/object-storage | restart | no |
| `IMAGE_STORAGE_MAX_BYTES` | 20MB | restart | no |
| `IMAGE_WORKER_ENABLED` | true | restart | no |
| `IMAGE_WORKER_POLL_MS` | 2000 | restart | no |
| `IMAGE_WORKER_BATCH` | 5 | restart | no |
| `IMAGE_WORKER_LEASE_SECONDS` | 300 | restart | no |
| `IMAGE_RETENTION_DAYS` | 30 | restart | no |
| Retry delay | 60s hardcoded | code | no |
| maxAttempts | 3 default | per job | no |

Effective values: not exposed in Admin UI; infer from env + job `lastError` / events.

---

## Next recommended Image Pipeline task

**Shared storage / object-store adapter (or production runbook for shared `IMAGE_STORAGE_DIR`)** plus optional lease heartbeat — only after ops chooses multi-instance topology. Until then, single-instance or multi-instance-with-shared-volume is the supported path; OpenRouter live smoke remains the other gated verification.
