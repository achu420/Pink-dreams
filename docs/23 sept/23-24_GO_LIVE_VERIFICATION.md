# Task 24 — Go-live verification report

**Date:** 2026-09-23  
**Branch:** `QA`  
**Commit at verification:** `d86cb1a` (tip may move after this report lands)  
**App A:** `http://127.0.0.1:8080` (worker enabled)  
**App B:** `http://127.0.0.1:8081` (`IMAGE_WORKER_ENABLED=false`)  
**Storage:** `LocalFileObjectStorage` shared dir `data/object-storage`  
**DB:** Postgres (same `DATABASE_*` for both processes)  
**Provider / model:** OpenRouter / `openai/gpt-image-2.5-flare`  
**Cost:** UNAVAILABLE · **SLA target:** Not configured

Secrets were loaded from env; none are recorded here.

## Gate results

| Gate | Result | Evidence |
|---|---|---|
| Application startup | VERIFIED | A and B both `Responding at` /health 200 |
| Database connectivity | VERIFIED | Personas, jobs, warehouse, conversations readable |
| Image worker | VERIFIED | A alone processed jobs; B had worker off |
| Shared storage | VERIFIED | SLA `storageProbeOk=true` on both |
| OpenRouter / provider | VERIFIED | Prior live jobs + SLA events openrouter=59 |
| Image model | VERIFIED | Effective flare; Admin Model Evaluation shows ENVIRONMENT source |
| End-to-end generation | VERIFIED | Prior Task 27 bathroom/cafe SUCCEEDED (see 23-27) |
| Chat attachment | VERIFIED | `POST .../jobs/fe5a6276-…/attach-message` → message `950a46a6-…` metadata includes `imageJobId` + 4 `imageAssetIds` |
| Admin workflow | VERIFIED | Signed-in UI: Image SLA metrics (n=59), Model Evaluation list COMPLETED + notes, Ananya Image Warehouse with candidates + shortlist/regen actions |
| Two-process | VERIFIED | Asset `91ed91f7-…` HTTP 200 on A and B; SHA256 identical |
| Lease heartbeat | VERIFIED (automated + live idle) | Existing lease tests; live SLA `stuckRunningJobs=0` |
| Restart / recovery | VERIFIED (limited) | Earlier requeue of FAILED jobs after process restart (Task 27) |
| Security | VERIFIED (basic) | Admin session gate; login required for Admin pages; no secrets in this report |
| Cost | UNAVAILABLE | Not invented |
| SLA / observability | VERIFIED | Image SLA page: events, success/failure, latency, candidates, by provider/model |

## Two-process procedure used

1. Process A already running on 8080 with worker + shared `IMAGE_STORAGE_DIR`.
2. Process B started on 8081 with same DB + same storage dir, worker disabled.
3. Login on B; `GET /v1/admin/images/assets/91ed91f7-…` returned the same bytes as A.
4. Warehouse on B: `GET /v1/admin/personas/b5540c4f-…/images/warehouse` total=11.

## Chat attachment procedure used

1. Existing conversation `c2532544-…`, assistant message `950a46a6-…`.
2. Attach SUCCEEDED cafe job `fe5a6276-…`.
3. Response metadata contains `imageJobId`, `imageAssetIds`, `imageAssetUrls`.

## Admin UI procedure used

1. `/admin-login.html` → signed in.
2. Image SLA → Refresh → metrics loaded.
3. Model Evaluation → list shows COMPLETED `evening at cafe` with saved notes.
4. Personas → Ananya → Image Warehouse → candidates and review actions visible.

## Remaining findings (not blockers for product testing)

- Cost remains UNAVAILABLE; SLA target not configured.
- Seedream still fails on resolution `512` (optional fix).
- Richa regen remains provider safety-blocked on stored candidates.
- Crash mid-job lease recovery was not re-run live this session (covered by automated tests).

## Verdict

**CONDITIONAL GO-LIVE for real product testing** on this machine: core pipeline, shared storage across two processes, chat attach, and Admin UI are verified. Do not claim full production readiness until cost ingestion and a configured SLA target exist if those are product requirements.
