# IMAGE_PIPELINE_AUDIT.md

**Task:** IMG-01 — Image Pipeline Discovery & Architecture Audit  
**Date:** 2026-09-22  
**Scope:** Inspect only. No feature implementation.  
**Git status at audit:** branch `feature/admin-3-test-chat`, working tree as synced from origin.

---

## 1. Executive result

Substantial **library-level** image infrastructure exists (visual identity, jobs, prompt compiler, provider abstraction, OpenRouter client, orchestration, PhaseIMG1–8 tests). The pipeline is **not production-wired end-to-end**.

| Concern | Result |
|---------|--------|
| Can a user/admin generate an image in a running app? | **No** |
| Is the worker started? | **No** |
| Durable blob storage? | **No** (InMemory only) |
| Chat coupling? | **None** |
| Image observability / SLA? | **Missing** |
| Formal audit/gap docs before this? | **Missing** (this file creates the audit) |

**Verdict:** Mid-pipeline foundation is reusable. Critical path to close is runtime wiring + HTTP/delivery + durable storage + chat attachment.

---

## 2. Current architecture

```text
[MISSING HTTP generate]
        ↓
ImageGenerationOrchestrator.submit (library)
        ↓
image_jobs (Postgres / H2)
        ↓
[UNWIRED] ImageJobWorker + NoOpImageJobHandler
        ↓
[UNWIRED] ImageGenerationHandler → ImageProvider (Fake / OpenRouter)
        ↓
InMemoryObjectStorage + generated_candidates (Exposed only)
        ↓
[MISSING delivery]
        ↓
Admin GET /v1/admin/personas/{id}/visual (read-only metadata)
```

Twin roadmap note: root `image-pipeline-architecture.md` describes a content-factory (daily scenes, publish, ledger). This audit follows `docs/for cursor` (chat-coupled modality). Content-factory phases remain out of scope.

---

## 3. End-to-end stage ratings

| Stage | Rating | Evidence |
|-------|--------|----------|
| HTTP | PARTIAL | Only `GET /v1/admin/personas/{personaId}/visual` (`AdminPersonaVisualRoutes`) |
| Auth | IMPLEMENTED | Admin session/dev auth + `AdminAuthorizationProvider.isAdmin()` for that GET |
| Validation | PARTIAL | Domain validators (`SceneIntent`, `GenerationRequest`, `ImageJob`, etc.); no HTTP generate DTO path |
| Context / persona | PARTIAL | Visual identity repos exist; no chat → active visual version resolution |
| Prompt | IMPLEMENTED | `PromptCompiler.compile()` — wardrobe/styleConstraints not fully folded into text |
| Provider | PARTIAL | `ImageProvider`, `FakeImageProvider`, `OpenRouterImageProvider`, `ImageProviderConfig` — **not constructed in Application** |
| Job / worker | PARTIAL / BROKEN (runtime) | Jobs + worker classes exist; worker never started; only `NoOpImageJobHandler` |
| Generation | PARTIAL | `ImageGenerationHandler.handleAsync`; deferred provider status treated as success incorrectly |
| Asset handling | PARTIAL | `ObjectStorage` + `InMemoryObjectStorage` only |
| Persistence | PARTIAL | Visual + `image_jobs` in V001; `generated_candidates` Exposed-only (not in Flyway SQL) |
| Delivery | MISSING | No download/signed URL/chat attachment |
| Observability | MISSING | No image metrics/events; LLM observability does not cover images |

---

## 4. Existing files / components

### Imaging
- `src/main/kotlin/com/pinkdreams/imaging/compiler/PromptCompiler.kt`
- `src/main/kotlin/com/pinkdreams/imaging/compiler/SceneIntent.kt`
- `src/main/kotlin/com/pinkdreams/imaging/job/ImageJob.kt`
- `src/main/kotlin/com/pinkdreams/imaging/job/ImageJobStatus.kt`
- `src/main/kotlin/com/pinkdreams/imaging/job/ImageJobType.kt`
- `src/main/kotlin/com/pinkdreams/imaging/job/ImageJobHandler.kt` (`NoOpImageJobHandler`)
- `src/main/kotlin/com/pinkdreams/imaging/job/ImageJobWorker.kt`
- `src/main/kotlin/com/pinkdreams/imaging/orchestration/ImageGenerationOrchestrator.kt`
- `src/main/kotlin/com/pinkdreams/imaging/orchestration/ImageGenerationHandler.kt`
- `src/main/kotlin/com/pinkdreams/imaging/orchestration/ImageGenerationRequest.kt`
- `src/main/kotlin/com/pinkdreams/imaging/orchestration/GeneratedCandidate.kt`
- `src/main/kotlin/com/pinkdreams/imaging/orchestration/GeneratedCandidateRepository.kt`
- `src/main/kotlin/com/pinkdreams/imaging/provider/ImageProvider.kt`
- `src/main/kotlin/com/pinkdreams/imaging/provider/GenerationRequest.kt`
- `src/main/kotlin/com/pinkdreams/imaging/provider/GenerationResult.kt`
- `src/main/kotlin/com/pinkdreams/imaging/provider/ProviderCapabilities.kt`
- `src/main/kotlin/com/pinkdreams/imaging/provider/FakeImageProvider.kt`
- `src/main/kotlin/com/pinkdreams/imaging/provider/openrouter/OpenRouterImageProvider.kt`

### Visual / storage / config / API
- `src/main/kotlin/com/pinkdreams/visual/identity/{PhysicalGuide,WardrobeItem,ReferenceImage}.kt`
- `src/main/kotlin/com/pinkdreams/storage/{ObjectStorage,InMemoryObjectStorage}.kt`
- `src/main/kotlin/com/pinkdreams/config/ImageProviderConfig.kt`
- `src/main/kotlin/com/pinkdreams/api/admin/AdminPersonaVisualRoutes.kt`
- `src/main/resources/admin-ui.html` (Visual Identity tab; Generate disabled)

### Repositories
- `PersonaIdentityRepository`, `PersonaVisualVersionRepository`, `PersonalGuideRepository`, `WardrobeRepository`, `ReferenceImageRepository`, `ImageJobRepository`, `GeneratedCandidateRepository`

---

## 5. Existing APIs

| Method | Path | Behavior |
|--------|------|----------|
| GET | `/v1/admin/personas/{personaId}/visual` | Read-only aggregate; `generationTriggerWired: false` |

**Absent:** POST generate, job status/result, cancel, asset download, chat image endpoints, visual CRUD HTTP.

---

## 6. Existing DB / storage

| Table | Flyway V001 | Exposed |
|-------|-------------|---------|
| persona_identity | Yes | Yes |
| persona_visual_versions | Yes | Yes |
| persona_visual_wardrobe_items | Yes | Yes |
| persona_visual_reference_images | Yes | Yes |
| image_jobs | Yes | Yes |
| generated_candidates | **No** | **Yes** |

Object storage: in-memory only. App registers a **new** `InMemoryObjectStorage()` for admin visual routes (never serves bytes).

---

## 7. Existing providers / models

- Interface: `ImageProvider` (`submit` / `getStatus` / `getResult` / `cancel`)
- Implementations: `FakeImageProvider`, `OpenRouterImageProvider`
- Config env: `OPENROUTER_API_KEY`, `IMAGE_PROVIDER`, `OPENROUTER_IMAGE_ENDPOINT`, `OPENROUTER_IMAGE_MODEL` (default `openai/gpt-image-2.5-flare`), timeouts, output format
- **Not wired in Application.kt**

---

## 8. Existing job lifecycle

**States:** `QUEUED → RUNNING → (SUCCEEDED | FAILED | CANCELLED)`; retry via `RETRY_WAIT → QUEUED`.

**Worker:** `processPendingJobs`, `processRetryableJobs`, `recoverStaleLeasedJobs` (default 300s lease).

**Gap:** Handler interface is sync `ImageJobHandler`; real work is suspend `ImageGenerationHandler.handleAsync` — not bridged.

---

## 9. Existing observability

None for images. Admin observability covers LLM chat exchanges only.

---

## 10. Existing tests

| Class | Path |
|-------|------|
| PhaseIMG1VisualIdentityTest | `src/test/kotlin/com/pinkdreams/PhaseIMG1VisualIdentityTest.kt` |
| PhaseIMG1VerificationTest | `src/test/kotlin/com/pinkdreams/PhaseIMG1VerificationTest.kt` |
| PhaseIMG2PhysicalGuideAndWardrobeTest | `src/test/kotlin/com/pinkdreams/PhaseIMG2PhysicalGuideAndWardrobeTest.kt` |
| PhaseIMG3ReferenceImagesTest | `src/test/kotlin/com/pinkdreams/visual/identity/PhaseIMG3ReferenceImagesTest.kt` |
| PhaseIMG4ImageJobInfrastructureTest | `src/test/kotlin/com/pinkdreams/imaging/job/PhaseIMG4ImageJobInfrastructureTest.kt` |
| PhaseIMG5ImageProviderAbstractionTest | `src/test/kotlin/com/pinkdreams/imaging/provider/PhaseIMG5ImageProviderAbstractionTest.kt` |
| PhaseIMG6OpenRouterIntegrationTest | `src/test/kotlin/com/pinkdreams/imaging/provider/PhaseIMG6OpenRouterIntegrationTest.kt` |
| PhaseIMG7PromptCompilerTest | `src/test/kotlin/com/pinkdreams/imaging/compiler/PhaseIMG7PromptCompilerTest.kt` |
| PhaseIMG8OrchestrationTest | `src/test/kotlin/com/pinkdreams/imaging/orchestration/PhaseIMG8OrchestrationTest.kt` |
| AdminPersonaVisualRoutesTest | admin read-only API |

**Missing tests:** HTTP→worker→provider E2E; `Orchestrator.submit` HTTP path; worker bridge; durable storage; delivery; security cross-user; image observability.

---

## 11. Gaps by severity

### CRITICAL
1. No end-to-end runtime wiring in `Application.kt`
2. No generation HTTP API
3. Worker ↔ `ImageGenerationHandler` mismatch (noop only)
4. `generated_candidates` missing from Flyway SQL

### HIGH
5. InMemory storage only / disposable instance in admin path
6. Delivery missing
7. Deferred provider path incorrectly marked success
8. Idempotency on create is exception-only (unique constraint throw)
9. Chat coupling absent

### MEDIUM
10. Prompt: index-based fake reference roles; wardrobe/style underused
11. OpenRouter fragile JSON string building; process-local requestCache
12. No image moderation / cost guardrails

### LOW
13. Duplicate `GeneratedCandidate` type names across packages
14. Tautology in `ImageJob.validate()` terminal check

---

## 12. Risks

- UI implies image system while generation is unwired
- In-memory storage → silent data loss if mistakenly used in prod
- Cost explosion without quotas/idempotent HTTP layer
- False SUCCEEDED jobs when provider returns deferred status
- Schema drift (`generated_candidates`) confusing ops

---

## 13. Recommended next task

1. Write `IMAGE_PIPELINE_GAP_ANALYSIS.md` (IMG summary Phase 2)
2. Then IMG-02+ implementation starting with request contract + worker bridge + durable storage + delivery (see gap analysis)

---

## 14. Files changed

**None** (audit documentation only — this file is the deliverable).

---

## 15. Classification summary

| Area | Classification |
|------|----------------|
| Visual identity foundation | EXISTS / REUSABLE |
| Prompt compiler | EXISTS / REUSABLE (minor gaps) |
| Provider abstraction | EXISTS / NEEDS CHANGE (wire + deferred poll) |
| Job infrastructure | EXISTS / NEEDS CHANGE (start worker + bridge handler) |
| Orchestration | EXISTS / REUSABLE |
| HTTP generate / delivery | MISSING |
| Durable object storage | MISSING (abstraction EXISTS) |
| Chat coupling | MISSING |
| Observability / SLA / admin generate | MISSING |
| Tests PhaseIMG1–8 | EXISTS / REUSABLE baseline |
