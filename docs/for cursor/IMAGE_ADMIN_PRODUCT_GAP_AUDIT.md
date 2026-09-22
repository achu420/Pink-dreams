# IMAGE PIPELINE — ADMIN PRODUCT GAP AUDIT

**Date:** 2026-09-23  
**Branch basis:** `QA` / image pipeline as merged  
**Task type:** AUDIT ONLY — no feature implementation  
**Evidence rule:** Claims backed by code paths, not docs alone

---

## Purpose

Compare the **current** image-pipeline implementation to the proposed Admin/product design (persona visual identity → generation → warehouse → posts). Establish exact gaps for ordered, testable follow-up work.

---

## 1. Product design audited against

Target flow:

```text
PERSONA → Visual Identity → Image Warehouse → Posts (deferred)
Admin/User request → seed + identity + refs → engine → N candidates → admin curation → warehouse
```

---

## 2. Persona visual identity — physical attributes

**Typed model:** `PhysicalGuide` — `src/main/kotlin/com/pinkdreams/visual/identity/PhysicalGuide.kt`  
**Storage:** `persona_visual_versions.physical_guide` (JSONB/text) — `DatabaseFactory.kt` / `V001__initial_schema.sql`  
**Admin write:** **NO HTTP CRUD** — only repository APIs + tests  
**Admin read:** `GET /v1/admin/personas/{id}/visual` — `AdminPersonaVisualRoutes.kt`  
**Admin UI:** Persona Detail → Visual Identity tab — **read-only JSON** (`admin-ui.html` `pdVisualHtml`)

| Attribute | Backend model | PromptCompiler emits | Admin UI edit | Status |
|-----------|---------------|----------------------|---------------|--------|
| Height | `Body.height` | **NO** | NO | PARTIAL (stored only) |
| Weight | — | — | — | **MISSING** |
| Body build | `Body.build` | YES | NO | PARTIAL |
| Muscularity | — | — | — | **MISSING** |
| Skin/complexion | `Skin.tone/undertone/texture` | tone only | NO | PARTIAL |
| Face description | `Face.*` | faceShape mainly | NO | PARTIAL |
| Body proportions | `Body.proportions` + `Anatomy.*` | **NO** | NO | PARTIAL (stored) |

### 2.2 Female-specific

| Attribute | Status |
|-----------|--------|
| Breast description / size / cup | **MISSING** |
| Belly | **MISSING** (no field) |
| Waist / hips | `Anatomy.waist/hips` stored; **not** in prompt | PARTIAL |
| Butt/body shape | **MISSING** as dedicated field |
| Nipple characteristics | **MISSING** |

### 2.3 Male-specific

No male-specific anatomy block. Same generic `Anatomy` / `Body`. **MISSING** as product model.

### 2.4 Face

| Attribute | Status |
|-----------|--------|
| Eye color/shape | Model YES; prompt YES (color/shape) |
| Hair color/length | Model YES; prompt YES |
| Hair style/texture | Model YES; prompt **NO** |
| Facial hair | **MISSING** |
| Jaw/cheek/nose/lips | Model YES; prompt **mostly NO** |

### 2.5 Distinguishing marks

| Need | Status |
|------|--------|
| Birthmarks / moles / scars / tattoos as typed structure | **MISSING** |
| Free-form list | `distinctiveFeatures: List<String>` — YES (prompt YES) |

---

## 3. Intimate visual information

| Capability | Status | Evidence |
|------------|--------|----------|
| Private anatomy description fields | **MISSING** | No types in `PhysicalGuide` |
| Breast/nipple-specific block | **MISSING** | — |
| Genital description | **MISSING** | — |
| Optional intimate reference images | **MISSING** | `ReferenceRole` has no PRIVATE (see §4) |
| Separate storage / ACL for intimate refs | **MISSING** | Same table/roles as normal refs |
| Accidental exposure via persona APIs | N/A for intimate (none stored); visual GET returns full guide JSON to **admins only** | `AdminPersonaVisualRoutes` |

**Gap:** No appropriate product place yet for intimate metadata or PRIVATE refs. Do not treat `notes` / `distinctiveFeatures` as a substitute.

---

## 4. Reference image system

**Enums:** `ReferenceRole`, `ReferenceStatus`, `ReferenceSource` — `ReferenceImage.kt`  
**Table:** `persona_visual_reference_images`  
**Repo:** `ReferenceImageRepository` (upload/finalize/archive/remove/retrieve) — draft-version gated  
**HTTP upload/replace/delete:** **NONE**  
**Admin UI:** metadata table only; **images not served** (`admin-ui.html` comment)

| Target type | Current role | Upload API | Admin UI | Passed to generation |
|-------------|--------------|------------|----------|----------------------|
| Front/full-body | `FULL_BODY` / `BODY` | Repo only | NO | Auto up to 3 FINALIZED/UPLOADED |
| Face close-up | `FACE` (not FACE_CLOSE) | Repo only | NO | YES (if selected) |
| Left/right/back profile | **MISSING** dedicated roles | — | — | — |
| Extra categories later | Extensible enum + DB check | Possible | — | — |

Per-capability:

| Question | Answer |
|----------|--------|
| Admin upload via UI/API? | **NO** (repo/tests only) |
| Replace? | **NO** dedicated; remove + re-upload in repo |
| Delete? | Repo YES; HTTP/UI **NO** |
| Associated with persona? | Via visual version → identity → persona | **YES** |
| Versioned? | Tied to `persona_visual_version_id` | **YES** |
| Active/inactive? | `UPLOADED` / `FINALIZED` / `ARCHIVED` | **YES** |
| Passed to generation? | Auto-select or explicit IDs → OpenRouter `input_references` | **YES** |
| Durable storage? | `ObjectStorage` / `LocalFileObjectStorage` | **YES** |
| Access controlled? | Admin visual read; no user ref API | **PARTIAL** |

---

## 5. Visual identity versioning

| Question | Answer | Evidence |
|----------|--------|----------|
| Latest used automatically? | Active published version, else max version | `ImageGenerationService.create` |
| Snapshot/version? | **YES** — `persona_visual_versions` lifecycle draft→published→archived | Repos + DB triggers |
| Old image → identity used? | Job stores `persona_visual_version_id` FK | `image_jobs` |
| Refs change without invalidating history? | Published versions immutable; new drafts don’t rewrite old FKs | DB triggers |
| Request stores which identity/refs used? | Version id + `selectedReferenceIds` / wardrobe ids in job payload | Orchestrator payload JSON |
| Full physical_guide copied onto job? | **NO** — FK to version (immutable when published) is the snapshot | — |

---

## 6. Image generation input

**Entry:** `ImageGenerationService.CreateCommand` + `PromptCompiler` + `OpenRouterImageProvider`

| Input | Classification | Notes |
|-------|----------------|-------|
| Persona (display name as subject) | ALREADY SENT | Via scene subject identity |
| Physical guide (subset) | PARTIALLY SENT | See §2; height/anatomy/style mostly omitted from prompt text |
| Distinguishing marks (list) | ALREADY SENT | If present in guide |
| Clothing (wardrobe catalog) | NOT SENT as catalog | IDs selected; **not compiled into prompt**; free-text `outfit` scene field CAN be sent |
| Reference images (bytes) | ALREADY SENT | OpenRouter data URLs |
| Seed prompt / scene text | PARTIALLY SENT | `location`, `outfit`, `expression`, `presentation` — not a first-class “seedPrompt” field name |
| Camera / pose / lighting / style | PARTIALLY SENT | Via presentation/expression/mood if populated; Admin UI hardcodes studio defaults |
| Config (model/size/count) | PARTIALLY SENT | Env model; width/height/count on request |

Admin UI generate (`pdGenerateImages`): fixed `location: studio`, `candidateCount: 1`, 512×512 — **no free-form seed prompt box**.

---

## 7. PromptCompiler

**Class:** `com.pinkdreams.imaging.compiler.PromptCompiler`

| Question | Answer |
|----------|--------|
| Where identity enters | `describePhysicalGuide` inside subject section |
| Where seed/scene enters | Appearance + environment + composition + mood from `SceneIntent` |
| Identity priority over scene | **YES** (guide described as authoritative; scene appended) |
| Reference instructions in text | Role list for provider refs; roles initially index-heuristic then re-resolved in handler |
| Clothing from wardrobe | **NO** |
| Persistent characteristics | PARTIAL (subset of guide) |
| Admin corrections path | **NO** |
| Paths bypassing compiler | **No alternate production compiler found**; Fake/OpenRouter both consume compiled `GenerationRequest` via handler |

**Authoritative path (production):**

```text
ImageGenerationService → Orchestrator/PromptCompiler → job payload
→ ImageJobWorker → BridgingImageJobHandler → ImageGenerationHandler
→ ImageProvider (Fake | OpenRouter)
```

Chat path reuses same service via `BestEffortImageEnqueue`.

---

## 8. Model generation

| Item | Current |
|------|---------|
| Provider | `openrouter` (or Fake if no key / `IMAGE_PROVIDER=fake`) |
| Default model | `openai/gpt-image-2.5-flare` — `ImageProviderConfig` / env `OPENROUTER_IMAGE_MODEL` |
| Configured how | Env vars (`OPENROUTER_*`, `IMAGE_*`) |
| Admin UI change model? | **NO** |
| Per-skill models? | **NO** |
| Settings stored per job? | Payload has size/count/prompt; model on **events** if env set; not a full reproducible config blob |
| Candidate count caps | `ImageGenerationService` enforces **1..4**; Fake provider max **4**; OpenRouter gpt-image profile max **10**; `SceneIntent` validates up to **100** (inconsistent layers) |
| 4 candidates? | Service allows 4; **default 1**; Admin UI & chat hardcode **1** |

---

## 9. Four-candidate generation

| Question | Current |
|----------|---------|
| Generate 4 by default? | **NO** (default 1) |
| Can request 4? | **YES** via API (`ImageGenerationService` 1..4); Admin UI currently cannot |
| Independent candidate IDs? | **YES** — `generated_candidates.id` |
| Independent candidate status? | **NO** — no candidate status column |
| Individually select/reject/remark? | **NO** |
| Regenerate one of four? | **NO** dedicated API |

---

## 10. Image Warehouse (inside Persona)

| Target | Current |
|--------|---------|
| Persona-scoped warehouse UI | **MISSING** — only job list snippet on Visual tab |
| Backend “warehouse” entity | **MISSING** — candidates hang off jobs |
| Fields: image, id, persona, timestamps, status, seed, remark, model, provider, job | **PARTIAL** — asset+job+events exist; no remark/seed field name/warehouse status |
| Target statuses (GENERATED, SHORTLISTED, DECLINED, SAVED, POST_READY, POSTED, FAILED) | **NOT IMPLEMENTED** |

**Actual job statuses:** `QUEUED`, `RUNNING`, `RETRY_WAIT`, `SUCCEEDED`, `FAILED`, `CANCELLED` — `ImageJobStatus.kt`

**Backend support for listing candidates:** via job result + persona visual aggregate (read).  
**API:** admin result/asset routes.  
**Admin UI warehouse:** **NO**.

---

## 11. Admin image actions

| Action | Status | Evidence |
|--------|--------|----------|
| View image | PARTIAL | API `GET .../assets/{id}`; UI does **not** preview |
| Select/shortlist | **NO** | — |
| Reject/decline | **NO** | — |
| Add remark | **NO** | — |
| Save (curate) | **NO** | Durable store on generate only |
| Delete candidate | **NO** product API | Retention cleaner exists ops-side |
| Regenerate | **NO** (only requeue failed **job**) | `POST .../requeue` |
| Regenerate with correction | **NO** | — |
| Compare candidates | **NO** | — |
| View generation metadata | PARTIAL | Job detail + events/SLA API; UI limited |

---

## 12. Regeneration

Target: correct face → new candidate from #2.

| Question | Answer |
|----------|--------|
| Admin correction text? | **NO** |
| Correction linked to candidate? | **NO** |
| Retain identity/refs/seed? | Requeue retries same job payload; **not** curated regen |
| New candidate vs overwrite? | Retry can replace candidates (delete-before-insert on retry path) — **job-level**, not candidate-level product regen |
| History preserved? | Job attempt count / events; **no** candidate lineage |

---

## 13. Image storage

| Item | Status |
|------|--------|
| Original asset | YES — `LocalFileObjectStorage` / keys `jobs/{id}/candidates/{i}` |
| Candidate metadata | YES — `generated_candidates` |
| MIME / size | YES |
| Multi-instance shared dir | YES (ops contract + tests) |
| Survives restart | YES (local files + DB) |
| Retention | YES cleaner; message-ref protection tested |
| Provider raw response persisted | NO (not as warehouse field) |

---

## 14. Persona Image Warehouse UI

**File:** `src/main/resources/admin-ui.html`  
**Location:** Persona Detail → tab **Visual Identity** (`pdPanel-visual`, `pdVisualHtml`)  
**Not** a dedicated “Image Warehouse” nav module.

Visible today:

- Physical guide / style JSON (read-only)
- Wardrobe table (read-only)
- Reference **metadata** (not images)
- Image jobs table (status/type/attempts/candidates)
- **Generate images** button

Missing: filters, candidate grid, previews, remarks, select/reject, regenerate, warehouse statuses.

---

## 15. Image generation UI

| Item | Classification |
|------|----------------|
| Persona selector | IMPLEMENTED (context = open persona) |
| Seed prompt input | **MISSING** |
| Refs auto-selected | PARTIAL (backend auto; UI doesn’t show selection) |
| Generate 4 | **MISSING** (hardcoded 1) |
| Generation progress | PARTIAL (status text / job id) |
| 4 candidate results UI | **MISSING** |
| Candidate actions | **MISSING** |
| Regenerate / Save / Reject / Remarks | **MISSING** |

---

## 16. SLA

| Need | Status |
|------|--------|
| Backend events | YES — `image_generation_events` |
| Queue / generation / total latency | YES (download/persistence often null) |
| Success/failure rates | YES — `GET /v1/admin/images/sla` |
| Retry count in SLA UI | PARTIAL (attempt on events; not rich Admin UI) |
| Per-model / per-provider breakdown UI | PARTIAL (fields exist; Admin UI **no** image SLA page) |
| Separate from text LLM SLA | YES (different tables/routes); Admin “SLA” screens are mostly **LLM** |

---

## 17. Cost

| Kind | Status |
|------|--------|
| ACTUAL PROVIDER COST | **NOT AVAILABLE** for images |
| ESTIMATED COST | **NOT AVAILABLE** |
| Cost per job/candidate / aggregates | **NOT AVAILABLE** |

(LLM OpenRouter cost capture exists elsewhere; **not** wired to image jobs.)

---

## 18. Image engine / skill configuration

| Item | Status |
|------|--------|
| Env-based provider/model | YES |
| Admin-editable image engine versions | **NO** |
| Image skill (`image_share`) | PARTIAL — chat/intent skill text; not generation params |
| Audit history of image engine config | **NO** |

---

## 19. User → image request

| Path | Status |
|------|--------|
| ADMIN GENERATION | YES — `POST /v1/admin/images/jobs` |
| USER GENERATION (explicit create API) | **NO** — user routes are **GET only** (`UserImageRoutes`) |
| CHAT-AUTOMATIC GENERATION | YES — `BestEffortImageEnqueue` (phrases / `image_share`) |
| Identity + refs auto-load | YES via service |
| Result on conversation | YES — message metadata `imageJobId`, `imageJobStatus`, `imageAssetIds`, `imageAssetUrls` |
| User retrieve asset | YES if owns conversation on job |

---

## 20. Post system

| Item | Classification |
|------|----------------|
| Post model / APIs / UI | **NOT IMPLEMENTED** / **DEFERRED** |
| Published image state | **NOT IMPLEMENTED** |

---

## 21. Security audit (concrete)

| Control | Status | Evidence |
|---------|--------|----------|
| Admin auth on image admin APIs | YES | session/dev-auth + `isAdmin` |
| User job/result/asset ownership | YES | conversation userId on payload — `UserImageOwnershipSecurityTest` |
| Cross-user blocked | YES (tested) | |
| Path traversal / MIME / size | YES | `LocalFileObjectStorage` |
| Secret redaction | YES | events/`lastError` |
| Private/intimate ref protection | N/A — **feature missing** | |
| Cross-persona visual write | N/A — no write API | |

---

## 22. Database audit

| Table/entity | Purpose | Notable fields | Gaps vs product |
|--------------|---------|----------------|-----------------|
| `persona_identity` | Anchor | `active_visual_version_id` | — |
| `persona_visual_versions` | Versioned identity | `physical_guide`, `style_constraints`, status | Intimate schema; bust/weight |
| `persona_visual_wardrobe_items` | Outfit catalog | category, name, … | Not in prompt |
| `persona_visual_reference_images` | Refs | role, status, storage_key | No PRIVATE/profile angles |
| `image_jobs` | Job lifecycle | status, payload, `persona_visual_version_id`, attempts | No seedPrompt column name; no warehouse status |
| `generated_candidates` | Assets | storage_key, index, size, mime | No candidate status/remarks |
| `image_generation_events` | Observability | provider, model, latencies, outcome | No cost |

---

## 23. API audit

| Method | Path | Auth | Purpose | Status |
|--------|------|------|---------|--------|
| GET | `/v1/admin/personas/{id}/visual` | Admin | Read visual aggregate | LIVE |
| POST | `/v1/admin/images/jobs` | Admin | Create job | LIVE |
| GET | `/v1/admin/images/jobs` | Admin | List | LIVE |
| GET | `/v1/admin/images/jobs/{id}` | Admin | Detail | LIVE |
| GET | `/v1/admin/images/jobs/{id}/result` | Admin | Candidates | LIVE |
| GET | `/v1/admin/images/assets/{id}` | Admin | Bytes | LIVE |
| POST | `.../cancel` | Admin | Cancel | LIVE |
| POST | `.../requeue` | Admin | Retry failed | LIVE |
| POST | `.../attach-message` | Admin | Attach | LIVE |
| GET | `/v1/admin/images/sla` | Admin | SLA | LIVE (no UI) |
| GET | `/v1/admin/images/storage` | Admin | Storage probe | LIVE (no UI) |
| GET | `/v1/images/jobs/{id}` | User | Status | LIVE |
| GET | `/v1/images/jobs/{id}/result` | User | Result | LIVE |
| GET | `/v1/images/assets/{id}` | User | Bytes | LIVE |

**Missing vs product:** visual CRUD, ref upload, warehouse curation, remarks, select/reject, regenerate-with-correction, user POST generate, cost APIs, posts.

---

## 24. Final gap matrix

| Product Capability | Backend | API | Admin UI | Status | Gap |
| ------------------ | ------- | --- | -------- | ------ | --- |
| Persona visual attributes | PARTIAL | READ | READ | PARTIAL | Female/male/intimate fields; Admin write; prompt coverage |
| Reference images | YES | READ meta | READ meta | PARTIAL | HTTP upload/CRUD/UI; profile angles |
| Reference image categories | PARTIAL | — | — | PARTIAL | No L/R/back; no FACE_CLOSE/PRIVATE |
| Private reference images | NO | NO | NO | MISSING | Entire category |
| Seed prompt | PARTIAL | scene fields | NO box | PARTIAL | First-class seed UX + field |
| Prompt compiler | YES | — | — | PARTIAL | Wardrobe/style/height/anatomy |
| 4 candidates | YES (cap) | YES | NO | PARTIAL | Default/UI = 1 |
| Candidate warehouse | PARTIAL | job-centric | NO | PARTIAL | No persona warehouse product |
| Candidate status | NO | NO | NO | MISSING | Warehouse statuses |
| Admin remarks | NO | NO | NO | MISSING | — |
| Regeneration | PARTIAL | requeue job | NO | PARTIAL | Not candidate correction flow |
| Regeneration correction | NO | NO | NO | MISSING | — |
| Durable storage | YES | YES | NO preview | YES/PARTIAL | UI preview missing |
| User image request | PARTIAL | GET only | — | PARTIAL | Chat auto YES; explicit user create NO |
| Chat attachment | YES | metadata | — | YES | Test-chat detail may not surface fields |
| Image SLA | YES | YES | NO | PARTIAL | Wire Admin UI |
| Image cost | NO | NO | NO | MISSING | — |
| Engine configuration | PARTIAL | env | NO | PARTIAL | No admin versioned engine |
| Skill configuration | PARTIAL | skill text | existing skills UI | PARTIAL | Not generation params |
| Security | YES | YES | — | YES | Intimate ACL when feature exists |
| Post system | NO | NO | NO | DEFERRED | — |

---

## 25. Priority classification

### P0 — Required before tomorrow’s image testing

Without these, core product testing of “identity + seed → candidates → review” is blocked or misleading:

1. **Admin seed-prompt input** (and pass through to generation) — UI currently hardcodes studio scene  
2. **Generate N=4 (or configurable) from Admin UI** — backend already allows 1..4  
3. **Candidate preview in Admin** (fetch asset bytes into Visual/warehouse view)  
4. **Minimum visual-identity write path for testing** — today guides/refs are repo/seed only; without upload/edit, testers cannot set identity for a persona in Admin  
5. **Confirm PromptCompiler emits critical identity fields used in tests** (height/build at minimum) — otherwise “identity” testing is incomplete  

### P1 — Usable Admin image workflow

6. Reference image upload/replace/finalize Admin API + UI  
7. Candidate shortlist / decline / remark + status model  
8. Regenerate-with-correction (new candidate, keep seed + identity + refs)  
9. Persona Image Warehouse list (filters, timestamps, seed, status)  
10. Image SLA page in Admin (separate from LLM SLA)  
11. Expand `PhysicalGuide` for product-critical attributes (bust/weight/marks structure) as needed for testing  

### P2 — Production hardening

12. Intimate/private attributes + PRIVATE refs + ACL  
13. Cost tracking (provider or estimated)  
14. Per-model/provider SLA breakdown UI  
15. Download/persistence latency population in events  
16. Wardrobe/styleConstraints into PromptCompiler  
17. Admin-editable image engine/model configuration with audit  

### P3 — Later / deferred

18. Posts / publishing / POST_READY / POSTED  
19. Content factory / daily generation / LoRA / social  
20. Cloud object storage  
21. Male-specific full anatomy product model (if not covered by generic expansion)  

---

## 26. Product boundary (honored)

No implementation of posts, Instagram, storyline, LoRA, cloud store, or architecture rewrite in this task.

---

## 27. Evidence index (key files)

| Area | Path |
|------|------|
| PhysicalGuide | `src/main/kotlin/com/pinkdreams/visual/identity/PhysicalGuide.kt` |
| Reference roles | `.../visual/identity/ReferenceImage.kt` |
| PromptCompiler | `.../imaging/compiler/PromptCompiler.kt` |
| ImageGenerationService | `.../imaging/orchestration/ImageGenerationService.kt` |
| Job statuses | `.../imaging/job/ImageJobStatus.kt` |
| Admin image APIs | `.../api/admin/AdminImageGenerationRoutes.kt` |
| Admin visual read | `.../api/admin/AdminPersonaVisualRoutes.kt` |
| User image APIs | `.../api/images/UserImageRoutes.kt` |
| Chat enqueue/attach | `.../chat/imaging/BestEffortImageEnqueue.kt`, `ImageMessageCompletionAttach.kt` |
| Admin UI | `src/main/resources/admin-ui.html` (`pdVisualHtml`, `pdGenerateImages`) |
| Storage | `.../storage/LocalFileObjectStorage.kt` |
| Events/SLA | `.../imaging/observability/*` |
| Schema | `db/migration/V001__initial_schema.sql`, `V010__generated_candidates_and_image_events.sql` |
| Ownership tests | `UserImageOwnershipSecurityTest.kt` |

---

## 28. Discovered defects (document only — not fixed)

1. **PromptCompiler omits** height, anatomy, hair style/texture, styleConstraints, wardrobe catalog text despite data existing.  
2. **Admin Generate** hardcodes `candidateCount: 1` and studio location — cannot exercise 4-candidate product flow from UI.  
3. **No Admin HTTP** for visual guide/wardrobe/reference mutation — Admin cannot configure identity for testing without DB/repo tools.  
4. **Image SLA/storage admin APIs exist but have no Admin UI surfaces** (easy false “missing backend” read).  
5. **Candidate warehouse statuses / remarks / select-reject are entirely absent** — do not confuse job `SUCCEEDED` with product SHORTLISTED/SAVED.  
6. **Intimate/private identity is unspecified in code** — using free-text `notes` would be an unsafe workaround without ACL.  
7. **Candidate-count validation is layered inconsistently** — `SceneIntent` allows ≤100, service requires 1..4, OpenRouter gpt-image advertises max 10, Fake max 4.  
8. **Wardrobe IDs are auto-selected and stored on the job** (`selectedWardrobeIds`) but **never compiled into the prompt** — catalog clothing does not affect generation text.  
9. **Auto-selected references include `UPLOADED` as well as `FINALIZED`** (service filter) — product may intend FINALIZED-only for production consistency.

---

## 29. Final report

### Executive Summary

The pipeline is a **working generation/runtime system** (jobs, OpenRouter/Fake, durable assets, chat attach, multi-instance leases, ownership, observability). It is **not yet an Admin product** for persona visual setup, warehouse curation, 4-candidate review, regeneration-with-correction, cost, or posts.

### Already implemented

- Versioned visual identity + wardrobe + reference **data model** and repos  
- Job lifecycle, durable storage, multi-instance lease/heartbeat  
- PromptCompiler + provider abstraction + OpenRouter path  
- Admin generate/list/result/asset/cancel/requeue/attach/SLA/storage **APIs**  
- User read APIs + chat best-effort enqueue + message attachment  
- Image generation events + SLA aggregation API  
- Security basics (admin gate, ownership, storage guards, redaction)

### Partially implemented

- Physical attributes (schema incomplete; prompt incomplete)  
- Reference categories (subset of roles; no Admin upload UI)  
- Seed/scene (API fields; no Admin seed UX)  
- 4 candidates (supported; default/UI = 1)  
- Warehouse (candidates exist; no product warehouse)  
- Regeneration (job requeue only)  
- Image SLA (API yes; Admin UI no)  
- Image skill (chat guidance only)

### Missing

- Intimate/private attributes & PRIVATE refs + ACL  
- Admin visual/ref write UI & APIs  
- Candidate statuses, remarks, select/reject, compare  
- Regeneration-with-correction  
- Image cost  
- Admin image engine configuration  
- Posts/publishing  
- Explicit user “create image” API  

### P0 blockers for tomorrow

1. Seed prompt in Admin generate flow  
2. candidateCount=4 (or selectable) from Admin  
3. Candidate image preview in Admin  
4. Some writable path to set visual identity/refs for the test persona  
5. Prompt coverage for identity fields you intend to test  

### P1 after first testing

Warehouse curation (status/remarks/select), ref upload UI, correction regen, image SLA UI, guide field expansion.

### P2 production hardening

Intimate ACL, cost, richer observability, wardrobe-in-prompt, admin engine config.

### Deferred

Posts, content factory, LoRA, cloud storage, social publishing.

### Recommended implementation sequence

```text
P0 Admin generate UX (seed + count + preview)
  → P0 minimal visual/ref write for test personas
  → P0 prompt field coverage for tested attributes
  → P1 warehouse statuses + remarks + select/reject
  → P1 regenerate-with-correction
  → P1 image SLA Admin page
  → P2 intimate/private + cost + engine config
  → P3 posts
```

**Stop here.** Do not start the next implementation phase automatically.
