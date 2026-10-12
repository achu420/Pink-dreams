# Merge Guide — Pink Dreams `QA` Branch

**Written for:** your coding agent, to understand what changed on this branch and how to merge it safely.

**Priority order for this merge, as given by the repo owner:**
1. **Chat architecture (Engine, Memory, Persona) — highest priority. This must be preserved intact.** If your side and this branch both touched the same chat/memory/persona files, prefer this branch's version or merge carefully field-by-field rather than overwrite.
2. **Images engine — second priority.** Lower-stakes; if there's a conflict here, it's more acceptable to resolve in whichever direction is simpler, as long as it still compiles and the admin console's image upload/cover/generation flows keep working.

---

## 1. What this branch is

A Kotlin/Ktor backend (`com.pinkdreams`) for a persona-based conversational AI product ("AMIA" in the admin console UI, "Pink Dreams" as the repo/package name). This branch (`QA`, commit `853b2a4` at push time) contains:

- A long-running feature build-out: a production "Persona Context" system (skill-aware, field-authorized persona data resolution) that replaced an earlier research-only approach.
- A set of real-production bug fixes found by actually running the app against a real database and real LLM calls (not just unit tests) — these are the most load-bearing fixes and are detailed in §4.
- Admin-console bug fixes found by actually driving the real browser UI (not just the API) — detailed in §5.

---

## 2. Core chat architecture (highest priority — preserve this)

### 2.1 The pipeline

Every chat turn flows through `PipelineChatEngine` (`src/main/kotlin/com/pinkdreams/chat/ChatEngine.kt`), a fixed, ordered pipeline of stages:

```
ChatRequest
  → ENTITLEMENT check
  → INPUT MODERATION
  → CONTEXT_ASSEMBLY        (RepositoryContextAssembler — builds ENGINE/PERSONA/PROFILE/MEMORY/HISTORY/CURRENT_MESSAGE blocks)
  → SKILL_SELECTION         (LlmIntentDiscovery — real LLM call, classifies the user's intent into one of ~26 skill keys, or null)
  → PersonaContextResolutionStage   (replaces the PERSONA block with authorized, skill-specific persona fields — see §3)
  → GENERATION              (LlmGenerator — the real response-generating LLM call, sees the full assembled context)
  → OUTPUT VALIDATION
  → PERSISTENCE             (message saved, unconditionally, regardless of correctness)
  → DELIVERY
  → (async, post-response) MEMORY_EXTRACTION   (separate LLM call, proposes memory facts from the just-completed turn — see §3.3)
```

**This exact stage order is load-bearing.** `CONTEXT_ASSEMBLY` runs *before* `SKILL_SELECTION`, and `SKILL_SELECTION` runs *before* persona resolution. Conversation history is assembled once, unfiltered, and is identical regardless of what skill ends up selected.

Every constructor parameter after `delivery` in `PipelineChatEngine` defaults to a no-op (`NoopIntentDiscovery`, null enrichers, etc.) — this is deliberate, so older callers/tests that don't know about skills/persona-context/memory still compile and behave exactly as before. **Do not remove these no-op defaults during a merge** — they're what let new features be wired in incrementally without breaking existing tests.

### 2.2 The Conversation Engine (universal behavior)

`BaselineConfiguration.CONVERSATION_ENGINE` (`src/main/kotlin/com/pinkdreams/baseline/BaselineConfiguration.kt`) is the real, single, universal behavioral system prompt sent with *every* turn, regardless of persona or skill. It's versioned like everything else here (draft → published → activated, never edited in place — see §2.4).

**This branch added a precedence rule to it** (the "Persona" section): when the model's own earlier turn conflicts with the current turn's authoritative Persona Core data, the Persona Core wins, and the earlier turn should be treated as the assistant's own past error, not defended. **Keep this paragraph** — it closes a real production bug (see §4.2).

### 2.3 Intent / Skill Discovery

`LlmIntentDiscovery` (`src/main/kotlin/com/pinkdreams/chat/skill/LlmIntentDiscovery.kt`) is the real `IntentDiscovery` implementation. It makes a real, separate LLM call per turn, using a seed prompt (`IntentEngineDefaultContent.kt`) to classify the user's message into one of the active skill keys (companionship, friendship, general_chat, romantic_intimacy, advice, etc. — ~26 total, defined in `BaselineConfiguration.SKILLS`) or `null`. It never sees the full skill catalogue's behavioral text — only the compact list of candidate keys.

**This branch added an affirmative rule** to the seed prompt: plain biographical questions about the persona ("Where are you from?", "What do you do?", "Tell me about yourself") should resolve to `general_chat`, not `null`. Before this fix, these questions silently fell through to no-skill-selected, which meant the persona had zero real facts available and the model fabricated a biography. **Keep this rule** — it's a real, verified production fix (see §4.1).

### 2.4 Versioning discipline (applies to Conversation Engine, Intent Engine, Persona Core, Skills)

All of these are "never edited in place" — a change creates a new `draft` version; `draft → published → activated` is explicit and auditable; old versions are kept forever (never deleted) so old transcripts stay reproducible. **Preserve this pattern** if your side has its own version of any of these — don't replace draft/publish/activate logic with a simpler "just update the row" approach.

---

## 3. Persona Context + Memory (highest priority — preserve this)

### 3.1 Persona Context — what it is

A given persona (e.g. "Simran") has two layers of data:
- `PersonaCoreVersions.content` — the old, flat, raw-text fallback (still used if Persona Context has no data for a field).
- A structured **master document** (`PersonaMasterDocuments`, real JSON — identity, life_context, personality, worldview, relational, adult_intimacy, etc.) plus a **field registry** (`persona_field_registry` — every addressable field, with a `sensitivity` of `normal` or `restricted`, and for restricted fields, an explicit `allowedContexts` list of which skills may ever see it) plus **per-skill field mappings** (`skill_context_requirements` — each skill names its own `requiredFields`/`recommendedFields`/`conditionalFields`/`restrictedFields`).

The resolution chain, run by `PersonaContextResolutionStage` *after* skill selection:

```
PersonaFieldAuthorizer.resolve(personaId, coreVersionId, skillKey, userMessage, renderingPolicy)
  → looks up the skill's field mapping
  → required + recommended fields: always included (if authorized)
  → conditional fields: included only if a deterministic word-overlap relevance scorer says they're relevant to the current message
  → restricted fields: included ONLY if explicitly named in this skill's mapping AND this skill is in that field's allowedContexts
  → renders the authorized fields into a PERSONA context block, replacing the raw fallback
```

**This is the single most safety-critical piece of this codebase.** `PersonaFieldAuthorizer` is the *only* code permitted to read a full master document — there is no code path anywhere capable of serializing an unfiltered document to the model. If your side has its own restricted-content/NSFW-gating logic, **this is almost certainly the mechanism to merge toward**, not away from — it has extensive real-production verification (zero restricted-field leakage across 250+ real API calls made during this branch's testing).

**This branch's fix**: `general_chat`'s field mapping was missing `identity.hometown`/`identity.current_city` (in `PersonaContextSeeder.kt`'s `SKILL_MAPPINGS` list) — meaning hometown/city questions, even once correctly routed to `general_chat`, still got fabricated answers because the actual fact was never supplied. One line added: `recommendedFields = listOf("life_context.occupation", "identity.hometown", "identity.current_city")`. **Keep this** — same category of fix as the earlier (already-shipped) `occupation` fix.

### 3.2 Seeding

`PersonaContextSeeder.kt` is **content-addressed and insert-only**: it only creates a field-registry entry or skill mapping if that exact ID doesn't already exist; it never updates or overwrites an existing row. **This means if you change a skill mapping in code, the real database won't pick it up automatically on restart** — the stale row has to be explicitly deleted (or updated via a migration) before the seeder will recreate it with the new content. Don't "fix" this by making the seeder overwrite-on-conflict without discussing it first — the insert-only behavior is deliberate (so admin-made manual edits in production are never silently clobbered by a code deploy).

### 3.3 Memory

`MemoryService`/`ProductionMemoryScope` — memory facts are scoped strictly per `(userId, personaId)` pair, **never global, never cross-persona**. Extraction runs as a separate, async, post-response LLM call (`MEMORY_EXTRACTION` in logs) — it never blocks the user-facing response. Facts have a `tier`/`criticality`/`status` and get selected into context on later turns via a deterministic relevance selector (`DeterministicMemoryContextSelector` — same word-overlap-scoring pattern as the Persona Context conditional-field scorer).

**Nothing in this branch changed the memory system's architecture.** It's listed here because it's one of the three "must preserve" pieces named by the repo owner — if your side has independent memory-engine changes, the merge should favor whichever version keeps the per-(user,persona) scoping strict and the extraction async/non-blocking.

### 3.4 Observability (don't lose this)

Every stage logs content-free, structured lines you can grep for: `SKILL_SELECTION`, `PERSONA_AUTHORIZER: status=... fieldCount=...`, `PERSONA_CONTEXT: status=... usedReplacement=...`, `INTENT_DIAGNOSTICS`, `MEMORY_EXTRACTION`, `MEMORY_ENGINE`, `STAGE_TIMINGS`. None of these ever log field values, message content, or restricted-field identifiers — only status/counts. This was the primary tool used to verify every fix in this branch against the real running app; keep it intact.

---

## 4. Real production fixes in this branch (chat/persona side)

Found and verified by actually running the app against a real PostgreSQL database and real OpenRouter/DeepSeek LLM calls — not just unit tests.

1. **Intent Engine**: added an affirmative `general_chat` rule for persona-biographical questions (`IntentEngineDefaultContent.kt`). Verified: 9/9 real calls across varied biographical phrasings correctly selected `general_chat` (previously 0/4 on the exact same failure turns).
2. **Conversation Engine**: added a Persona-Core-is-authoritative precedence rule for resolving self-contradictions (`BaselineConfiguration.kt`). Verified via a deterministic contract test plus a real 2-turn live sequence where the model correctly defended the authoritative fact against a false premise.
3. **Persona Context mapping**: added `identity.hometown`/`identity.current_city` to `general_chat`'s field requirements (`PersonaContextSeeder.kt`). Verified: 5/5 real hometown/city questions answered correctly and consistently post-fix (previously fabricated and inconsistent across calls).
4. **Conversation list**: `ConversationRepository.findAllForUser()` now filters to `executionMode = "PRODUCTION"`, excluding Test-Chat conversations from the regular conversation list (they were leaking into the admin Conversations tab, getting picked as "most recently active," then 404ing the moment a real message was sent — Test conversations are intentionally blocked at the message-send layer, but weren't being filtered at the list layer).

Each of these has a deterministic regression test (`PersonaBiographicalIntentTest`, `Phase59ContradictionContractRegressionTest`, `Phase62GeneralChatHometownGapTest`) plus real-API verification logged in `docs/Persona update POC/PHASE59_*`, `PHASE61_*`, `PHASE62_*`, `PHASE63_*`.

---

## 5. Images engine (second priority)

Located under `src/main/kotlin/com/pinkdreams/imaging/` (generation orchestration, providers, storage, retention, SLA/observability, benchmarking) and `src/main/kotlin/com/pinkdreams/visual/identity/` (persona visual identity: physical/private guides, wardrobe, reference images — `PersonaVisualAdminService.kt` is the main orchestration class for all of this).

Images are content-addressed in an `ObjectStorage` abstraction (`LocalFileObjectStorage` for local dev — real files under `data/object-storage/personas/{personaId}/visual-versions/{versionId}/references/{referenceId}`). A persona can have a `coverReferenceImageId` pointing at one of its reference images.

**This branch's admin-console-side fixes** (found by actually driving the real browser against the real app, not just curl):
1. **Dangling cover pointer**: deleting a reference image that was set as the cover left `personas.cover_reference_image_id` pointing at a now-gone row. Fixed in `PersonaVisualAdminService.removeReference()` — it now clears the cover pointer first if it matches.
2. **Missing API field**: `GET /v1/admin/personas/{id}` never returned the cover image at all. Added `coverImageId`/`coverImageUrl` to the response DTO (`AdminPersonaRoutes.kt`).
3. **N+1 query**: the Visual Identity tab's `GET /visual` endpoint did one DB query per image-generation job for that job's candidates — ~20s load time for personas with heavy image history. Added a batched `findByImageJobs()` lookup (`GeneratedCandidateRepository.kt`) — cut to ~4s.
4. **Dead buttons**: the entire Visual Identity tab's upload/delete/cover/save-draft/publish-activate/generate actions were silently no-oping in the real browser — they all checked a variable (`selectedPersonaId`) that only the separate, older "Persona Core Versions" panel on the Personas *list* page ever sets; the actual Persona Detail page tracks its persona in a different variable (`pdPersonaId`). Fixed by retargeting every one of these functions to `pdPersonaId` (`src/main/resources/admin-ui.html`).
5. **Dead refresh**: every one of those same actions, after succeeding, called a function (`loadPersonaDetail`) that doesn't exist anywhere in the file — the real function is `openPersonaDetail`. Fixed all 7 call sites.
6. **New feature**: a "Create new version" form was added directly to the Persona Detail page's Persona Core tab (previously the only way to create a new persona-core draft was a disconnected panel on the Personas list page). It's pre-filled with the latest version's content and scoped so it never duplicates across the list page's per-row expandable history (that history-rendering function is shared between both pages — the form only renders in the single-persona Detail context).

None of this touches the actual image-generation pipeline (providers, job lifecycle, retention, SLA) — it's all admin-console-surface bugs in how a human operator views/edits a persona's visual identity.

---

## 6. Files most likely to conflict, and how to resolve them

| File | What changed here | Merge guidance |
|---|---|---|
| `chat/skill/IntentEngineDefaultContent.kt` | Added biographical-question rule | **Keep this branch's addition.** If your side also edited this prompt, merge both additions — don't let one silently replace the other. |
| `baseline/BaselineConfiguration.kt` | Added precedence paragraph to `CONVERSATION_ENGINE`; also contains the full skill catalogue | **Keep this branch's addition.** Same merge-both guidance if your side touched `CONVERSATION_ENGINE` text. |
| `baseline/PersonaContextSeeder.kt` | Added 2 fields to `general_chat`'s mapping | **Keep this branch's addition**, and remember: the real DB needs the stale `general_chat` row deleted once for the new mapping to actually take effect (content-addressed seeder, §3.2). |
| `persistence/repositories/ConversationRepository.kt` | Added `executionMode` filter to `findAllForUser` | **Keep.** This is a real bug fix with test coverage; don't drop the filter even if your side's version of this file differs structurally. |
| `src/main/resources/admin-ui.html` | Many admin-console JS fixes (§5) | Lower priority per the stated order, but these are genuine dead-code fixes (not refactors) — if your side has a different version of this file, the safest merge is to re-apply the specific fixes in §5 on top of your version rather than take this branch's file wholesale, since this file is large (6000+ lines) and likely to have unrelated drift. |
| `api/admin/AdminPersonaRoutes.kt`, `AdminPersonaVisualRoutes.kt`, `visual/identity/PersonaVisualAdminService.kt`, `imaging/orchestration/GeneratedCandidateRepository.kt` | Cover-image field/fix, N+1 batch fix | Second priority — keep if it merges cleanly, but not worth a contentious conflict resolution over.

---

## 7. How to verify after merging

1. `gradle compileKotlin compileTestKotlin` — must be clean.
2. `gradle test` (no credentials sourced) — expect **~1,290 tests, ~1,252 pass, 38 pre-existing environment-gated failures** (they need `OPENROUTER_API_KEY`/`DATABASE_URL` sourced in the same shell — failing without those is expected, not a regression). Specifically confirm these pass: `PersonaBiographicalIntentTest`, `Phase59ContradictionContractRegressionTest`, `Phase62GeneralChatHometownGapTest`, everything under `chat/personacontext/production/`.
3. If you want to re-verify the real-production fixes live: source `run-local.secrets.ps1` (real local Postgres + real OpenRouter key, gitignored, ask the repo owner if you don't have a copy), `gradle run`, log into `/admin` (credentials in `AdminSessionAuth.kt` — a documented temporary placeholder), and re-run a couple of the biographical questions from §4 against the `simran` persona to confirm `general_chat` is selected and hometown/city come back correct.

---

## 8. What NOT to do

- Don't touch `PersonaFieldAuthorizer`, `PersonaContextResolutionStage`, or any restricted-field authorization logic without re-running the full safety-matrix test suite (`chat/personacontext/production/*Test.kt`) — this is the most security-critical code in the repo.
- Don't make `PersonaContextSeeder` overwrite-on-conflict — it's insert-only on purpose.
- Don't remove the no-op defaults on `PipelineChatEngine`'s later constructor parameters.
- Don't push `run-local.secrets.ps1` or its contents (OpenRouter API key, DB password) anywhere — it's gitignored for a reason; a prior version of this repo had a real key leak via a hardcoded value in a tracked file, since revoked.
