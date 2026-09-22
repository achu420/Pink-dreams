# IMAGE_PIPELINE_GAP_ANALYSIS.md

**Depends on:** [IMAGE_PIPELINE_AUDIT.md](IMAGE_PIPELINE_AUDIT.md)  
**Date:** 2026-09-22  
**Goal:** Map IMG-02…17 to current code — REUSE / IMPLEMENT / DEFER.

---

## 1. Current flow vs target flow

### Current (library only)
```text
Caller constructs ImageGenerationRequest
  → Orchestrator.submit → image_jobs row
  → (manual test) Handler.handleAsync → Fake/OpenRouter → InMemory + candidates
  → Admin GET /visual (metadata)
```

### Target (docs/for cursor minimum + hardening)
```text
Auth’d client/admin/chat turn
  → Validate request contract + idempotency
  → Resolve persona visual identity (active version)
  → PromptCompiler (identity > scene)
  → Enqueue job
  → Worker claims → provider → validate asset → durable store
  → Persist candidate + optional chat message attachment
  → Status/result API + observability + admin ops
```

---

## 2. Missing components (summary)

| Component | Action |
|-----------|--------|
| HTTP image API (create/status/result/asset) | IMPLEMENT |
| Request DTO validation at HTTP boundary | IMPLEMENT |
| Idempotent create returning existing job | IMPLEMENT |
| `ImageJobHandler` bridge to `ImageGenerationHandler` | IMPLEMENT |
| Start `ImageJobWorker` in Application | IMPLEMENT |
| LocalFile / durable `ObjectStorage` | IMPLEMENT |
| Flyway for `generated_candidates` | IMPLEMENT |
| Chat message image attachment (minimal) | IMPLEMENT |
| Image observability events/metrics | IMPLEMENT |
| Image SLA aggregation | IMPLEMENT |
| Admin generate + job ops | IMPLEMENT |
| Retention/cleanup job | IMPLEMENT |
| Deferred provider poll loop | IMPLEMENT |
| Storyline / daily gen / publish / LoRA | DEFER (architecture.md content-factory) |

---

## 3. Files that need modification

| File | Change |
|------|--------|
| `Application.kt` | Wire provider, storage, orchestrator, handler bridge, worker loop, routes |
| `AdminPersonaVisualRoutes.kt` | Optional generate trigger when wired; set `generationTriggerWired` |
| `admin-ui.html` | Enable generate when backend supports; job ops UI |
| `ImageJobRepository.kt` | Idempotent create (return existing on conflict) |
| `ImageGenerationHandler.kt` | Poll deferred provider status; classify failures |
| `ImageJobHandler.kt` | Add bridging adapter (runBlocking/coroutine) |
| `db/migration/` | Add `V00x__generated_candidates.sql` |
| Chat routes / message model | Minimal asset reference on assistant message |
| New: `api/.../ImageRoutes.kt` (or admin image routes) | Create/status/result/asset |
| New: `storage/LocalFileObjectStorage.kt` | Durable disk storage |
| New: image observability + SLA modules | Parallel to LLM observability patterns |
| New: retention cleaner | Scheduled cleanup |

---

## 4. New files required (planned)

- `src/main/kotlin/com/pinkdreams/storage/LocalFileObjectStorage.kt`
- `src/main/kotlin/com/pinkdreams/imaging/job/BridgingImageJobHandler.kt` (or equivalent)
- `src/main/kotlin/com/pinkdreams/api/ImageGenerationRoutes.kt` (user/admin create/status/result)
- `src/main/kotlin/com/pinkdreams/api/ImageAssetRoutes.kt` (authenticated asset fetch)
- `src/main/kotlin/com/pinkdreams/imaging/observability/*` (events + metrics)
- `src/main/kotlin/com/pinkdreams/imaging/retention/ImageRetentionCleaner.kt`
- `db/migration/V002__generated_candidates.sql` (or next available version)
- Tests: PhaseIMG contract/worker-bridge/API/security/observability as needed

---

## 5. Database changes

| Change | Why |
|--------|-----|
| Flyway table `generated_candidates` | Schema drift fix |
| Optional: `messages.image_asset_ids` / attachment JSON | Chat delivery (smallest fit to existing message schema) |
| Optional: `image_generation_events` or reuse observability tables with workload=IMAGE | Observability |

Prefer extending existing observability patterns over inventing a second telemetry store when possible.

---

## 6. API changes

| Endpoint | Purpose |
|----------|---------|
| `POST /v1/images/jobs` (or admin equivalent) | Create job from validated contract |
| `GET /v1/images/jobs/{jobId}` | Status |
| `GET /v1/images/jobs/{jobId}/result` | Candidates / asset refs |
| `GET /v1/images/assets/{assetId}` | Binary or redirect (authz) |
| Admin: list stuck/failed jobs, retry/cancel | Ops (IMG-14) |
| Admin: enable Generate button | When `generationTriggerWired=true` |

---

## 7. Admin changes

- Reflect real runtime config sources (env vs editable)
- Job table: status, provider, latency, errors
- Generate trigger only when wired
- No fake metrics

---

## 8. Test changes

- Unit: validation, idempotency, identity precedence, retry classification, state transitions
- Integration: request→job→worker→fake provider→asset→API
- Failure: timeout, 5xx, storage fail, stale RUNNING, cross-user access
- Do not delete existing PhaseIMG1–8 tests

---

## 9. IMG-02…17 mapping

| Task | Status vs code | Action |
|------|----------------|--------|
| **IMG-02** Request contract & validation | Domain validators exist; HTTP + idempotent return missing | IMPLEMENT HTTP contract + idempotent create |
| **IMG-03** Persona → image identity | Visual identity layer EXISTS | REUSE; document/test precedence; wire resolution from personaId |
| **IMG-04** Prompt construction | `PromptCompiler` EXISTS | REUSE; fold wardrobe/style if easy; single path |
| **IMG-05** Provider abstraction | Interface + OpenRouter + Fake EXIST | REUSE; wire config; fix deferred poll |
| **IMG-06** Job state machine | EXISTS | REUSE; minor fixes if invalid transitions found |
| **IMG-07** Worker reliability | Classes EXIST; not started | IMPLEMENT wiring + bridge + boot loop |
| **IMG-08** Retry/failure policy | Partial (retry wait 60s, maxAttempts) | HARDEN classification matrix + tests |
| **IMG-09** Asset pipeline | InMemory only | IMPLEMENT LocalFile storage + MIME/size checks |
| **IMG-10** API & delivery | MISSING | IMPLEMENT create/status/result/asset + chat ref |
| **IMG-11** Security | Partial (admin auth on GET) | AUDIT + fix ownership on all image endpoints |
| **IMG-12** Observability | MISSING | IMPLEMENT image events (non-blocking) |
| **IMG-13** SLA dashboard | MISSING | IMPLEMENT image-only metrics + admin view |
| **IMG-14** Admin controls | Read-only | IMPLEMENT generate + job ops reflecting backend |
| **IMG-15** Storage retention | MISSING | IMPLEMENT bounded cleanup |
| **IMG-16** Test hardening | PhaseIMG1–8 baseline | EXPAND failure/security/E2E |
| **IMG-17** Final audit | N/A | Produce PASS/FAIL report after above |

---

## 10. Risks

- Scope creep into storyline/publish (DEFER)
- Bridging suspend handler incorrectly (blocking on event loop)
- LocalFile storage without path traversal guards
- Chat schema change breaking clients — keep attachment additive

---

## 11. Implementation order (this program)

1. Durable storage + Flyway candidates  
2. Handler bridge + worker boot + provider wiring  
3. HTTP contract + idempotency (IMG-02)  
4. Identity resolution reuse (IMG-03) + prompt reuse (IMG-04)  
5. Deferred provider poll + failure matrix (IMG-05/08)  
6. Asset delivery + chat attachment (IMG-09/10)  
7. Security pass (IMG-11)  
8. Observability + SLA + admin (IMG-12–14)  
9. Retention (IMG-15)  
10. Tests + final audit (IMG-16–17)

---

## 12. Definition of Phase B done

One verified path:

```text
authenticated request → validated job → worker → Fake/OpenRouter provider
  → durable asset → GET result/asset → (optional) assistant message reference
```
