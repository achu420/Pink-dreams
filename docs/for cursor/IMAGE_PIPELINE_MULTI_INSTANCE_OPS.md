# Image Pipeline — Multi-Instance Storage & Operational Readiness

**Date:** 2026-09-22  
**Branch:** `cursor/claude-work-followup`  
**Task:** Shared storage / multi-instance operational readiness (verification + contract)

---

## 1. Result

**PASS WITH FINDINGS**

Independent verification confirms:

- Worker lease-bound completion remains intact  
- Atomic local writes + path/MIME/size guards remain intact  
- Asset keys are portable (`jobs/{jobId}/candidates/{i}`)  
- **Shared storage root:** Instance B can read Instance A assets (TEST VERIFIED)  
- **Independent storage roots:** Instance B returns missing asset (null / API 404), no false success (TEST VERIFIED)  
- Storage diagnostics exposed (health mode fields + admin probe)

**Verdict unchanged:** **B — SAFE FOR MULTI-INSTANCE WITH SHARED STORAGE**

---

## 2. Storage architecture — what is actually supported?

### Supported deployment

```text
Instance A ─┐
Instance B ─┼── shared PostgreSQL
Instance C ─┘
      │
      └── shared IMAGE_STORAGE_DIR (same mount / volume)
```

Requirements:

| Requirement | Detail |
|-------------|--------|
| Database | One shared Postgres (or equivalent) for all instances |
| Storage mode | `IMAGE_STORAGE` unset or not `memory` → `LocalFileObjectStorage` |
| Storage dir | **Identical** `IMAGE_STORAGE_DIR` on every instance (default `data/object-storage`) |
| Permissions | Directory must be readable + writable by the app user; created automatically if missing |
| Operator flag | Set `IMAGE_STORAGE_SHARED=true` **after** verifying the shared mount (diagnostics only; does not change code paths) |
| Workers | Each instance may run a worker; claim + lease-bound complete prevent double-terminalization |

### Unsupported deployment

```text
Instance A → local disk A (IMAGE_STORAGE_DIR=/var/a)
Instance B → local disk B (IMAGE_STORAGE_DIR=/var/b)
```

Why unsupported:

1. Job rows live in the shared DB (Instance B can see SUCCEEDED + candidate metadata).  
2. Bytes live only under Instance A’s disk.  
3. Instance B `objectStorage.retrieve(storageKey)` returns **null** → asset API **404**.  
4. This is not data corruption or silent regeneration; it is a **missing file** failure. Chat metadata may still list `imageAssetIds` that B cannot serve.

`IMAGE_STORAGE=memory` is **single-process / test-only** — never multi-instance safe.

Object stores (S3/GCS/R2) are **not implemented**. Do not claim them.

---

## 3. Multi-instance verification

| Scenario | Evidence |
|----------|----------|
| Shared root A write → B read | `SharedStorageMultiInstanceTest` — same temp dir, two `LocalFileObjectStorage` instances, Fake worker on A, B retrieves bytes |
| Separate roots A write → B miss | Same test class — `retrieve`/`exists` false on B |
| Asset identity portable | Key scheme `jobs/{uuid}/candidates/{index}`; no JVM-local handles |
| Diagnostics | `ImageStorageDiagnostics` + `GET /v1/admin/images/storage`; health extras for mode/contract |

Live Postgres multi-process: **NOT RUN** this task (H2 + Fake used).

---

## 4. Worker safety (re-verified)

| Check | Status |
|-------|--------|
| Lease ownership persisted | YES (`claimedByWorker`, `claimedAt`) |
| Complete requires matching worker | YES (`completeJobSuccess/Failure(jobId, workerName)`) |
| Stale worker after reclaim | Rejected (null complete); newer owner intact |
| Terminal reconciliation | `reconcileMessageAttachments` each worker tick |

No worker redesign this task.

---

## 5. Retention safety

`ImageRetentionCleaner`: skips active jobs; skips message-referenced UUIDs; deletes terminal aged jobs + files under configured root.

Multi-instance note: all instances sharing DB+storage should run at most one retention schedule or accept idempotent deletes. Current design is idempotent (missing file delete is fine). No retention behavior change this task.

---

## 6. Configuration

| Variable | Default | Production note |
|----------|---------|-----------------|
| `IMAGE_STORAGE` | local file (if unset / not `memory`) | Never use `memory` in prod multi-instance |
| `IMAGE_STORAGE_DIR` | `data/object-storage` | **Must be shared mount** for multi-instance |
| `IMAGE_STORAGE_MAX_BYTES` | 20MB | Per-object cap |
| `IMAGE_STORAGE_SHARED` | unset/false | Operator attestation for diagnostics only |
| `IMAGE_WORKER_*` | see prior ops doc | Lease default 300s |
| `IMAGE_RETENTION_DAYS` | 30 | Shared DB semantics |

Path normalization: relative keys only; `..` rejected; resolved path must stay under root. Directory auto-created on storage construction.

Restart required for env changes. Not runtime-reloadable.

### Diagnostics

| Surface | Exposes |
|---------|---------|
| `GET /health` | `imageStorageMode`, `imageStorageMultiInstanceContract`, `imageStorageOperatorDeclaredShared` (no absolute path) |
| `GET /v1/admin/images/storage` | mode, contract, root **label** (basename), probe r/w/ok, guidance |

Admin UI: Generate remains wired; storage status is **API-only** (document gap — no fake UI metric).

---

## 7. Tests

| Metric | Value |
|--------|-------|
| Imaging filter | **BUILD SUCCESSFUL** |
| Total | **208** |
| Failed | **0** |
| Errors | **0** |
| Skipped | **1** (live OpenRouter) |
| New tests | shared success; independent miss; probe/diagnostics |
| New failures | none |

---

## 8. Live verification

| Item | Status |
|------|--------|
| Fake provider + H2 shared-storage contract | **VERIFIED** |
| Real Postgres | **NOT VERIFIED** |
| OpenRouter | **NOT LIVE-VERIFIED** |

---

## 9. Files changed (this task)

| File | Change |
|------|--------|
| `ObjectStorage.kt` | `probeReadiness` / `StorageReadiness` |
| `LocalFileObjectStorage.kt` | probe implementation; root label |
| `InMemoryObjectStorage.kt` | probe (single-process warning) |
| `ImageStorageDiagnostics.kt` | **new** ops view |
| `HealthRoutes.kt` | optional non-secret extras |
| `Application.kt` | health image storage fields |
| `AdminImageGenerationRoutes.kt` | `GET /v1/admin/images/storage` |
| `SharedStorageMultiInstanceTest.kt` | **new** |
| `IMAGE_PIPELINE_MULTI_INSTANCE_OPS.md` | this update |

---

## 10. Remaining gaps

1. No cloud object-store adapter (intentional deferral)  
2. Admin UI does not yet display storage diagnostics (API exists)  
3. OpenRouter live smoke  
4. Real multi-process Postgres soak  

---

## Lease lifecycle & heartbeat

```text
claimJob → RUNNING + claimedByWorker + claimedAt
   ↓
ImageJobLeaseHeartbeat renews claimedAt while provider runs
   ↓
completeJobSuccess/Failure only if claimedByWorker still matches
   ↓
heartbeat stopped in finally
```

| Env | Default | Role |
|-----|---------|------|
| `IMAGE_WORKER_LEASE_SECONDS` | 300 | Stale if `claimedAt` older than this |
| `IMAGE_WORKER_HEARTBEAT_SECONDS` | `lease/3` (min 15, max lease-1) | Renew interval during handle; `0` disables |

Invariants: only lease owner renews/completes; lost renew stops heartbeat and logs; stale completion still rejected; terminal jobs are not heartbeated (heartbeat closed after handle).

---

## 11. Git

```text
Branch: cursor/claude-work-followup
Commit: a0b3104
Push: no
```

---

## Failure modes & recovery

| Failure | Operator action |
|---------|-----------------|
| Asset 404 on some instances | Verify shared mount + identical `IMAGE_STORAGE_DIR`; check admin storage probe |
| Probe not writable | Fix permissions / disk full |
| `memory` mode in prod | Switch to local + shared dir |
| Job SUCCEEDED, metadata missing assets | Worker reconcile will re-attach; or admin attach-message |

---

## Next recommended task

Gated **OpenRouter live smoke** (when credentials available), or real Postgres multi-process soak with shared `IMAGE_STORAGE_DIR`. Object-store (S3/etc.) only if shared filesystem is not available.
