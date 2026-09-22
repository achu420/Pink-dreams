# Image Pipeline — Live Verification

**Date:** 2026-09-22  
**Branch:** `cursor/claude-work-followup`  
**HEAD at verification:** `4ab1936` / `a0b3104` (lease heartbeat)  
**Method:** Environment audit + automated imaging suite; live OpenRouter / credentialed Postgres multi-process **not executed** when required configuration was absent.

---

## 1. Environment

| Item | Observed (no secrets) |
|------|------------------------|
| `OPENROUTER_API_KEY` | **unset** |
| `IMAGE_LIVE_SMOKE` | **unset** |
| `IMAGE_PROVIDER` | unset (app defaults: Fake when no key) |
| `IMAGE_STORAGE` / `IMAGE_STORAGE_DIR` | unset (defaults: local / `data/object-storage`) |
| `IMAGE_STORAGE_SHARED` | unset |
| `DATABASE_URL` | **unset** |
| `run-local.secrets.ps1` | **absent** |
| `.env` | absent |
| `psql` CLI | not installed |
| localhost:5432 | TCP open (listener present) |
| Intentional smoke env | **No** — secrets file / env not provisioned for this workspace |

Nothing was fabricated. No API keys, Authorization headers, or DB passwords were printed.

---

## 2. Provider

| Item | Status |
|------|--------|
| Configured live model | N/A — live path not entered |
| Default model (code) | `OPENROUTER_IMAGE_MODEL` → `openai/gpt-image-2.5-flare` when OpenRouter is constructed |
| Opt-in smoke test | `PhaseIMGOpenRouterLiveSmokeTest` (requires key + `IMAGE_LIVE_SMOKE=true`) |
| Live call this run | **NOT RUN** |

---

## 3. Real Postgres

**NOT VERIFIED**

Reason: `DATABASE_URL` / `run-local.secrets.ps1` not available. A Postgres port is listening on localhost, but this task refuses to guess credentials or JDBC URLs.

Automated path remains H2 in-memory (`DatabaseFactory.connectInMemory()`).

---

## 4. OpenRouter

**NOT RUN**

Reason: `OPENROUTER_API_KEY` unset and `IMAGE_LIVE_SMOKE` not `true`. Per task rules, no fabricated live result and no unpaid/accidental spend.

---

## 5. End-to-end result

| Layer | Status |
|-------|--------|
| Fake provider E2E (H2) | **VERIFIED** (automated suite) |
| Real provider → storage → API → message | **NOT VERIFIED** live |

Automated contract still covered by PhaseIMG / ownership / attach / shared-storage / heartbeat tests.

---

## 6. Multi-instance

**NOT VERIFIED** (live two-process)

Shared-storage **contract** previously verified with two `LocalFileObjectStorage` roots against one H2 DB (`SharedStorageMultiInstanceTest`). Real dual JVM + shared Postgres + shared volume was not run (no DB credentials / no soak harness invoked).

---

## 7. Lease heartbeat

**VERIFIED** (automated / Fake)

`ImageJobLeaseHeartbeatTest` + ownership-gated complete (`a0b3104`). Long Fake handle with heartbeat prevents stale reclaim; stale completion after reclaim still rejected.

**NOT VERIFIED** against real OpenRouter duration or real Postgres.

---

## 8. Security

**VERIFIED** (automated)

- User A / User B / anonymous ownership (`UserImageOwnershipSecurityTest`)  
- Secret redaction on `lastError` / events  
- Path traversal / MIME / size guards on `LocalFileObjectStorage`  

Live admin session against production UI: **NOT VERIFIED** this run.

---

## 9. Observability / SLA

**VERIFIED** (automated Fake path: events + admin SLA response shape)

Live sample in `GET /v1/admin/images/sla` after OpenRouter: **NOT VERIFIED**.

---

## 10. Tests

### Imaging automated tests

```text
gradle test (PhaseIMG* + imaging.* + visual PhaseIMG3 + api.images + chat.imaging)
BUILD SUCCESSFUL
tests=213 failures=0 errors=0 skipped=1
```

Skipped: OpenRouter live smoke (opt-in).

### Real Postgres

```text
NOT RUN — DATABASE_URL / secrets unavailable
```

### OpenRouter live

```text
NOT RUN — OPENROUTER_API_KEY unset; IMAGE_LIVE_SMOKE unset
```

### Multi-process

```text
NOT RUN — requires credentialed shared Postgres + shared IMAGE_STORAGE_DIR + two processes
```

---

## 11. Findings

1. **Operational gate, not architecture gap:** Core image pipeline remains production-ready with documented limitations; live provider/DB soak cannot proceed without secrets.  
2. localhost:5432 is open but unusable without configured `DATABASE_URL`.  
3. `run-local.ps1` expects gitignored `run-local.secrets.ps1` — missing here.  
4. No defects discovered that warrant code changes in this verification pass (nothing live failed because nothing live ran).

---

## 12. Remaining gaps

| Gap | Classification |
|-----|----------------|
| OpenRouter live smoke (1 generation) | **OPERATIONAL** — needs key + `IMAGE_LIVE_SMOKE=true` |
| Real Postgres E2E + row inspection | **OPERATIONAL** — needs `DATABASE_URL` (+ user/password as configured) |
| Dual-instance soak (A/B claim, lease, shared asset) | **OPERATIONAL** — needs shared DB + shared `IMAGE_STORAGE_DIR` |
| Live SLA/admin UI against real traffic | **OPERATIONAL** |
| Cloud object store | **DEFERRED** (architecture choice) |

---

## How to complete this verification later

```text
1. Create run-local.secrets.ps1 (gitignored) with DATABASE_URL + OPENROUTER_API_KEY
2. Ensure IMAGE_STORAGE_DIR is a shared writable directory for multi-process
3. IMAGE_LIVE_SMOKE=true OPENROUTER_API_KEY=… gradle test --tests …PhaseIMGOpenRouterLiveSmokeTest
4. Run app against Postgres; one admin generate + one chat image turn
5. Optionally start two processes with distinct worker names, same DB + storage
6. Re-run this document’s checklist and update sections 3–7 with measured (redacted) results
```

Do not commit secrets.

---

## Files changed (this task)

| File | Change |
|------|--------|
| `docs/for cursor/IMAGE_PIPELINE_LIVE_VERIFICATION.md` | This report |

No application code changes (no live defects found / no live runs possible).

---

## Git

```text
Branch: cursor/claude-work-followup
Commit: bcfd9b0
Push: no
```
