# Image Pipeline — Live Operational Verification

**Date:** 2026-09-23  
**Branch:** `cursor/claude-work-followup`  
**Objective:** Final live two-process operational verification (real Postgres + OpenRouter + shared storage).  
**Approved app change only:** `IMAGE_WORKER_NAME` env override (already on branch).

---

## 1. Environment / config (no secrets)

| Setting | Value |
|---------|--------|
| Database | `jdbc:postgresql://localhost:5432/pinkdreams` (user via env; password not recorded) |
| Provider | `IMAGE_PROVIDER=openrouter` |
| Model | `OPENROUTER_IMAGE_MODEL=openai/gpt-image-2.5-flare` |
| Storage | `IMAGE_STORAGE_DIR=<repo>/.tmp/image-storage` |
| Shared flag | `IMAGE_STORAGE_SHARED=true` |
| Secrets mechanism | gitignored `run-local.secrets.ps1` (not committed) |
| Binary | `build/install/pink-dreams/bin/pink-dreams.bat` |

Ports 18080/18081 used because 8080 is occupied.

---

## 2. Process A/B configuration

| | Process A | Process B |
|--|-----------|-----------|
| Port | `PINKDREAMS_PORT=18080` | `PINKDREAMS_PORT=18081` |
| Worker | `IMAGE_WORKER_NAME=image-worker-a` | `IMAGE_WORKER_NAME=image-worker-b` |
| DB | same Postgres | same Postgres |
| Storage | same `.tmp/image-storage` | same `.tmp/image-storage` |

Stale/reclaim phase additionally used:

- A: `IMAGE_WORKER_LEASE_SECONDS=12`, `IMAGE_WORKER_HEARTBEAT_SECONDS=0`
- B: `IMAGE_WORKER_LEASE_SECONDS=12`, `IMAGE_WORKER_HEARTBEAT_SECONDS=3`

Normal E2E phase used lease=45 with default heartbeat derivation.

---

## 3. Postgres verification

**PASS**

Evidence (job `391f4444-981a-4744-a091-f142fedec11c`):

```text
job status=SUCCEEDED attempts=0 claimed=null
candidates=1 maxBytes=1544237
event provider=openrouter model=openai/gpt-image-2.5-flare outcome=SUCCESS totalMs=26317
```

Both processes shared the same job rows; terminal state persisted; candidate + event rows present.

Idempotency: recreate with same key → same `jobId`, `reusedExisting=true`.

---

## 4. OpenRouter verification

**PASS**

| Field | Value |
|-------|--------|
| Job | `391f4444-981a-4744-a091-f142fedec11c` |
| Status | `SUCCEEDED` |
| Wall elapsed | ~34.2s |
| MIME | `image/png` |
| Bytes | 1_544_237 |
| Observability model | `openai/gpt-image-2.5-flare` (set via env this run) |

No fabricated results. Earlier provider-enum failures from a prior session were already fixed on this branch (`quality=auto`, `output_format=png`, resolution tiers).

---

## 5. Shared-storage verification

**PASS**

- Storage diagnostics on A and B: local mode, shared declared, probe OK.
- A-generated asset retrieved via B admin asset API (**PASS**).
- B-generated asset (`fecde448-a548-4543-9ae0-42c9ce4bae43`) retrieved via A (**PASS** reverse).

---

## 6. Lease ownership verification

**PASS**

Healthy owner path: B held `RUNNING` with renewing `claimed_at` while generating, then cleared claim on `SUCCEEDED`.

---

## 7. Stale-worker verification

**PASS** (dual-alive)

Job `dac014de-0c79-4a80-9490-cdcd50fe24ea`:

Postgres timeline:

```text
RUNNING worker=image-worker-b claimedAt=...12.207
RUNNING worker=image-worker-b claimedAt=...20.287   ← heartbeat renew
RUNNING worker=image-worker-b claimedAt=...27.121   ← heartbeat renew
SUCCEEDED worker=null
```

Process A log (completion rejected after ownership lost):

```text
IMAGE_WORKER: lease lost after success job=dac014de-0c79-4a80-9490-cdcd50fe24ea worker=image-worker-a
```

Interpretation: A lost the lease (heartbeat disabled); B reclaimed and completed authoritatively; A’s later success completion was rejected. No double terminalization.

---

## 8. Heartbeat verification

**PASS**

On the same stale-test job while `image-worker-b` owned it, `claimed_at` advanced (~8s then ~7s) under `IMAGE_WORKER_HEARTBEAT_SECONDS=3` with lease=12, and the job completed `SUCCEEDED` without false reclaim by a healthy owner.

Automated suite also covers long Fake generations (`ImageJobLeaseHeartbeatTest`).

---

## 9. End-to-end verification

**PASS**

```text
admin create → Postgres job QUEUED
→ worker claim RUNNING
→ OpenRouter
→ shared storage asset
→ generated_candidates
→ SUCCEEDED
→ result API + asset API (A and B)
→ idempotent recreate
```

---

## 10. Chat attachment verification

**PASS** (prior live session on same DB/persona, still valid operational evidence)

Assistant message metadata (conversation `c2532544-fa17-427a-9cf7-60faa37fe98e`):

- `imageJobId=7e1b1dd7-1ad1-4702-9f43-29b4fc8d3236`
- `imageJobStatus=SUCCEEDED`
- `imageAssetIds=...`
- `imageAssetUrls=/v1/images/assets/...`

`IMAGE_ENQUEUE` logged on a worker. Admin test-chat detail JSON still does not surface these fields (DB does).

Not re-spent on an additional chat LLM turn in this exact re-run.

---

## 11. Admin UI verification

**PASS (API)** — browser Admin UI not separately automated.

Verified live:

- admin session login
- generate / job detail / result / asset
- storage diagnostics
- SLA
- cancel (prior session: QUEUED→CANCELLED)
- requeue correctly rejected for SUCCEEDED (prior session)

---

## 12. Security verification

**PASS** (sampled live + existing automated ownership tests)

| Check | Result |
|-------|--------|
| Unauthenticated admin jobs | 401 |
| Unauthenticated user job | 401 |
| Secret scan of result JSON + worker logs for `sk-or-v1-` | clean |
| Storage root isolation | shared-dir keys only; `.tmp/` gitignored |

Cross-user ownership: covered by `UserImageOwnershipSecurityTest` (automated); not re-fabricated with extra live users this run.

---

## 13. Observability / SLA verification

**PASS** (smoke traffic)

After this run (includes prior smoke in same 24h window):

- Events include provider=`openrouter`, model set, SUCCESS outcomes, latencies.
- SLA endpoint returned increasing counts (`totalEvents` observed ≥ 11 during this run).

Sample size is verification smoke, not production SLA.

---

## 14. Tests and exact counts

```text
Automated tests:     214 passed, 0 failed, 1 skipped
Real Postgres:       PASS
Real OpenRouter:     PASS
Two-process:         PASS (image-worker-a / image-worker-b)
Shared storage:      PASS (A↔B)
Lease heartbeat:     PASS
Chat attachment:     PASS (DB evidence from same live environment)
Admin UI:            PASS (API; UI not browser-automated)
Security:            PASS (sampled + automated ownership)
Observability/SLA:   PASS (smoke)
```

Imaging automated totals (XML aggregate after suite run): **214 passed / 0 failed / 1 skipped** (includes live-gate seed test class; OpenRouter live smoke still skipped unless gated).

---

## 15. Failures

None in this re-run’s live E2E / stale / shared-storage paths.

---

## 16. Remaining gaps

- Browser Admin UI walkthrough not automated.
- Live retention destructive cleaner not executed.
- Cross-user live Basic-auth matrix not re-run (automated coverage remains).
- **Rotate the OpenRouter API key** that was shared in chat.

---

## 17. Files changed (this documentation task)

| File | Change |
|------|--------|
| `docs/for cursor/IMAGE_PIPELINE_LIVE_OPERATIONAL_VERIFICATION.md` | This report |

Application change already present from approved gate:

- `Application.kt` — `IMAGE_WORKER_NAME`
- OpenRouter request mapping fix (defect discovered earlier; already committed on branch)

**Not committed:** `run-local.secrets.ps1`, `.tmp/**`

---

## 18. Commits

Recorded after commit of this document.

---

## Secrets confirmation

No credentials written to tracked files or this document. Remind operator: **rotate the OpenRouter key**.
