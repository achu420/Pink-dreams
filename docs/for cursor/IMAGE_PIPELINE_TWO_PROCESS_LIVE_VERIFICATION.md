# Image Pipeline — Two-Process + Live Verification

**Date:** 2026-09-23  
**Branch:** `cursor/claude-work-followup`  
**Scope:** Operational verification against real Postgres + OpenRouter + two JVMs. No architecture redesign.

---

## 1. Executive verdict

### PASS WITH FINDINGS

Live OpenRouter generation, real Postgres persistence, two independent processes with shared storage, chat attachment (DB-verified), admin APIs/SLA, and basic security checks all succeeded after one provider-request defect fix.

Remaining caveats are documented below (event `model` attribution when env unset; admin test-chat detail UI does not surface image metadata; retention destructive path not exercised on live data; stale-worker “A returns and is rejected” proven by automated suite + kill-A reclaim, not a live dual-alive race).

---

## 2. Credential availability

| Item | Status |
|------|--------|
| Postgres `DATABASE_URL` / user / password | Provided via gitignored `run-local.secrets.ps1` |
| `OPENROUTER_API_KEY` | Provided via same (never printed / never committed) |
| Secrets in git | **No** (`git check-ignore` confirms `run-local.secrets.ps1`) |

---

## 3. OpenRouter result

**PASS** (after defect fix)

| Field | Value |
|-------|--------|
| Provider | `openrouter` |
| Model (configured default) | `openai/gpt-image-2.5-flare` |
| Smoke job | `a89e1787-1c43-42ff-a5f5-b840f492c895` |
| Final status | `SUCCEEDED` |
| Wall time (create→terminal) | ~22.7s |
| Candidates | 1 |
| MIME | `image/png` |
| Asset size | 1_494_689 bytes |
| Storage key | `jobs/{jobId}/candidates/0` |

**First attempt FAILED** with provider validation (`quality=standard`, `output_format=b64_json`, WxH `resolution`) — classified as **application defect**, fixed, rebuilt, re-verified successfully.

Event row sample: `outcome=SUCCESS`, `total_latency_ms≈19216`, `generation_latency_ms≈18849`.  
**Finding:** `model` column was `null` because `OPENROUTER_IMAGE_MODEL` was unset (handler reads env, not provider default).

---

## 4. Real Postgres result

**PASS**

- Schema init against `pinkdreams` succeeded on both processes.
- Verified rows for smoke job: `image_jobs` terminal SUCCEEDED; `generated_candidates` count=1; `image_generation_events` count=1 SUCCESS.
- Idempotent recreate returned same `jobId` with `reusedExisting=true`.
- Chat assistant message metadata persisted `imageJobId` / `imageJobStatus` / `imageAssetIds` / `imageAssetUrls`.

---

## 5. Two-process result

**PASS**

| Process | Port | `IMAGE_WORKER_NAME` | Storage |
|---------|------|---------------------|---------|
| A | 18080 | `worker-a` | shared `.tmp/image-storage` |
| B | 18081 | `worker-b` | same |

- Health 200 on both.
- Multiple OpenRouter jobs completed under dual workers (3/3 SUCCEEDED).
- Kill-A while job `RUNNING` → after lease window, job reached `SUCCEEDED` while B remained up (reclaim/completion path).

**Finding:** Live dual-alive “A finishes after B reclaimed and is rejected” was not separately instrumented (A process was killed). Ownership rejection remains covered by automated lease/hardening tests.

---

## 6. Shared-storage result

**PASS**

- Storage diagnostics on A and B: `mode=local`, `operatorDeclaredShared=true`, `probeOk=true`.
- Asset created via A served via B admin asset API (HTTP 200, matching byte length).
- Shared directory contains portable keys under `jobs/.../candidates/0` (relative paths only in this report).

---

## 7. Lease / heartbeat result

| Check | Result |
|-------|--------|
| Automated heartbeat suite | **PASS** (6 tests) |
| Live short-lease reclaim after A kill | **PASS** (job recovered to SUCCEEDED) |
| Live long generation keeping lease via heartbeat > original window | **NOT VERIFIED** (OpenRouter samples finished inside ~30s lease) |

---

## 8. Chat attachment result

**PASS** (database-verified)

- Test-chat turn with phrase `please send a photo of you smiling`.
- Log: `IMAGE_ENQUEUE` on worker-b.
- Assistant message metadata includes:
  - `imageJobId=7e1b1dd7-1ad1-4702-9f43-29b4fc8d3236`
  - `imageJobStatus=SUCCEEDED`
  - `imageAssetIds=...`
  - `imageAssetUrls=/v1/images/assets/...`

**Finding:** `GET /v1/admin/test-chat/conversations/{id}` did not surface those fields in the JSON detail payload used for polling; attachment is present in `messages.metadata`.

---

## 9. Admin UI/API result

**PASS** (API; browser UI not separately automated)

Verified against running processes:

- login → generate → job list/detail → result → asset
- storage diagnostics
- SLA
- cancel (QUEUED → CANCELLED)
- requeue correctly rejected for SUCCEEDED

---

## 10. Security result

**PASS** (sampled live + prior automated ownership suite)

| Check | Result |
|-------|--------|
| Unauthenticated admin jobs | 401 |
| Unauthenticated user job | 401 |
| Path-traversal asset id | non-200 |
| Secret scan of result JSON + worker logs for `sk-or-v1-` | clean |
| Provider exchange Authorization in message metadata | `[REDACTED]` |

---

## 11. Retention result

**NOT VERIFIED** (live destructive)

No retention cleaner run against shared live DB/storage in this gate (avoid deleting non-test data). Automated retention/message-reference tests remain the contract evidence.

---

## 12. Observability / SLA result

**PASS** (smoke-sample only)

Final SLA window 24h (includes this verification traffic + earlier failures):

```text
totalEvents=9 successCount=6 failureCount=3
p50TotalLatencyMs≈25063 p95TotalLatencyMs≈74968
p50GenerationLatencyMs≈13745 p95GenerationLatencyMs≈18849
```

Note: not production traffic; sample size is small.

---

## 13. Tests executed

| Suite | Result |
|-------|--------|
| Live HTTP gate (OpenRouter + dual process) | Executed (see sections above) |
| `PhaseIMG6OpenRouterIntegrationTest` | 31 / 0 / 0 |
| `ImageJobLeaseHeartbeatTest` | 6 / 0 / 0 |
| `SharedStorageMultiInstanceTest` | 3 / 0 / 0 |
| `PhaseIMGLiveGateSeedTest` (opt-in) | used to seed persona |

---

## 14. Exact gate status

| Gate | Status |
|------|--------|
| Real Postgres | **PASS** |
| OpenRouter live | **PASS** |
| Two-process | **PASS** |
| Shared storage | **PASS** |
| Lease/heartbeat | **PASS WITH FINDINGS** |
| Chat attachment | **PASS** |
| Admin API/SLA | **PASS** |
| Security | **PASS** |
| Retention (live destructive) | **NOT VERIFIED** |

---

## 15. Defects found and fixes

1. **OpenRouter request enums outdated** (`quality=standard`, `output_format=b64_json`, WxH resolution) → provider 400 → jobs FAILED/RETRY.  
   **Fixed** in `OpenRouterImageProvider` / `ImageProviderConfig` to current API (`quality=auto`, `output_format=png`, resolution tiers `512|1K|2K|4K`).

2. **`IMAGE_WORKER_NAME` hardcoded** → blocked honest dual-worker identity.  
   **Fixed** (approved): env override with default `app-image-worker`.

---

## 16. Remaining operational gaps

- Set `OPENROUTER_IMAGE_MODEL` in runtime so observability events record model.
- Optionally expose image metadata on admin test-chat detail responses.
- Live long-lease heartbeat soak (provider duration > lease) if required before multi-region ops.
- Live retention cleaner dry-run/execute on disposable records only.
- Rotate the OpenRouter key that was pasted into chat history.

---

## 17. Files changed

| File | Change |
|------|--------|
| `src/main/kotlin/com/pinkdreams/Application.kt` | `IMAGE_WORKER_NAME` env |
| `src/main/kotlin/com/pinkdreams/imaging/provider/openrouter/OpenRouterImageProvider.kt` | OpenRouter request mapping fix |
| `src/main/kotlin/com/pinkdreams/config/ImageProviderConfig.kt` | default `outputFormat=png` |
| `src/test/kotlin/com/pinkdreams/imaging/PhaseIMGLiveGateSeedTest.kt` | opt-in Postgres persona seed |
| `.gitignore` | ignore `.tmp/` |
| `docs/for cursor/IMAGE_PIPELINE_TWO_PROCESS_LIVE_VERIFICATION.md` | this report |

**Not committed:** `run-local.secrets.ps1`, `.tmp/**`

---

## 18. Git

Recorded after commit.

---

## 19. Push status

**Not pushed.**

---

## 20. Secrets confirmation

No credentials were committed, written into docs, or left in tracked files. Worker logs/results scanned for `sk-or-v1-` → clean. Operator should rotate the key that appeared in chat.
