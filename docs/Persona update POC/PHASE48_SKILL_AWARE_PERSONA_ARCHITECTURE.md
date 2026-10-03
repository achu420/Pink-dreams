# Phase 48 — Skill-Aware Persona Context Architecture Decision & Implementation Design

Design/decision phase. No production code was modified. No API calls were made. No production data was populated. No rendering policy was selected as final. LA was not declared production-ready.

Tag legend: **ESTABLISHED** (re-verified from current source this phase), **DERIVED** (a conclusion reasoned from ESTABLISHED facts), **DESIGN PROPOSAL**, **UNKNOWN**, **HUMAN DECISION REQUIRED**.

---

## 1. Executive Summary

Phase 47 concluded that no clean integration seam exists for skill-aware persona-context resolution, because the real skill key only becomes available after `RepositoryContextAssembler` has already merged persona and engine content into one opaque, budget-trimmed block. **Re-tracing the pipeline in this phase corrects that conclusion.**

Two facts, both re-verified from current source (§2), change the picture substantially:

1. **`PipelineChatEngine.process()` already calls a post-skill-selection persona-resolution hook**, immediately before generation: `personaContextResolution?.resolve(skillEnrichedContext) ?: skillEnrichedContext` (`ChatEngine.kt` line 408), feeding directly into `generator.generate(request, personaResolvedContext)`. This hook already exists at exactly the pipeline position Phase 47 was looking for.
2. **That hook's implementation, `PersonaContextResolutionStage`, is a real, wired-in, but deliberately stubbed no-op** — its own doc comment names the exact blocker: "Real implementation requires plumbing personaId through to this stage." The stage is constructed with a `PersonaContextProvider?` and is never instantiated with a non-null one anywhere in the codebase (confirmed by grep — the class name and constructor parameter appear only in their own declarations).
3. **`ContextBlock` → wire message is a 1:1, unconditional mapping** (`OpenRouterLlmClient.kt` line 236: `request.context.blocks.map { OpenRouterMessage(role = block.role, content = block.content) }`). There is no hidden requirement that persona and engine content share one block — splitting them into two separate `"system"`-role blocks produces two system messages instead of one, which is a supported, ordinary shape for a chat-completions request, not a special case.

Given these three facts, **Strategy A (post-skill resolution) is not a redesign — it is completing a hook that already exists**, combined with a small, low-risk structural change borrowed from Strategy B (splitting the currently-merged engine+persona block so the persona portion is independently replaceable). This combination is proposed below as **Strategy D**, and is the design this document recommends be implemented in a future, explicitly-authorized phase.

---

## 2. Verified Current Pipeline

**ESTABLISHED**, all re-read from source this phase (not cited from prior phase reports):

| # | Question | Answer | Source |
|---|---|---|---|
| 1 | Where is `personaId` available? | `ChatRequest.personaId` (line 19), in scope for the entire `PipelineChatEngine.processTurn()` call, including after skill selection. `ChatContext` itself has no `personaId` field — only `personaCoreVersionId: UUID?`. | `ChatEngine.kt` lines 15–22, 63–66 |
| 2 | Where is `PersonaCoreVersions.content` loaded? | `personaRepository.getActiveCoreVersion(request.personaId)` inside `RepositoryContextAssembler.assemble()`. | `RepositoryContextAssembler.kt` line 60 |
| 3 | Where is persona context currently assembled? | Same method, lines 70–76: `personaContent = personaContextProvider?.getPersonaContext(...) ?: core.content` (provider is always null in production), then merged into `block0` with engine content. | `RepositoryContextAssembler.kt` lines 70–76 |
| 4 | Where is engine/system context assembled? | Same call, `engineAndPersonaContent(engine.content, personaContent)` — one merged string, not separable after this point without re-deriving engine content. | `RepositoryContextAssembler.kt` line 76, 167 |
| 5 | Where does token/budget trimming occur? | `assemble()`'s `while` loop, lines 102–128, operating on `block0` (already merged) plus memory/continuity/sensitive/history blocks, before skill selection has run. | `RepositoryContextAssembler.kt` lines 102–128 |
| 6 | Where does `SKILL_SELECTION` occur? | `PipelineChatEngine.processTurn()`, lines 341–356, strictly after `CONTEXT_ASSEMBLY` (line 328–334). | `ChatEngine.kt` lines 328–356 |
| 7 | Where does the real selected skill key become available? | `skillSelection` (line 343, a `SkillSelection.Selected(skillKey)` or `.None`), immediately after `intentDiscovery.selectSkill(request, context)` returns. | `ChatEngine.kt` line 343 |
| 8 | Where does `SkillContextEnricher` run? | Lines 381–402, strictly after skill selection, appending one new `ContextBlock` (the skill's own prompt) into `context.blocks` — never touching `block0`. | `ChatEngine.kt` lines 381–402; `SkillContextEnricher.kt` |
| 9 | Where does `GENERATION` begin? | Line 410: `generator.generate(request, personaResolvedContext)`, itself fed by line 408's `personaContextResolution?.resolve(skillEnrichedContext) ?: skillEnrichedContext`. | `ChatEngine.kt` lines 407–410 |
| 10 | Which objects are still mutable/reconstructable between skill selection and generation? | `ChatContext` is a `data class` — fully reconstructable via `.copy()`; `context.blocks` is a plain `List<ContextBlock>`, freely replaceable up to the moment `generate()` is called. Nothing downstream of `CONTEXT_ASSEMBLY` re-derives blocks from the database — everything after that point operates purely on the in-memory `ChatContext`. | `ChatEngine.kt` line 63 (`data class`), 100 (`ContextBlock`) |
| 11 | Can the final model request be rebuilt after skill selection? | **Yes, and this already happens today** for the skill block (`SkillContextEnricher`) and is already wired (but stubbed) for persona (`PersonaContextResolutionStage`). `GenerationRequest.from(request, context, config)` reads `context.blocks` directly — whatever `resolve()` returns is exactly what reaches the LLM. | `LlmClient.kt` lines 74–90; `LlmGenerator.kt` line 34 |
| 12 | Can context blocks be represented separately rather than as one opaque string? | **Yes.** `OpenRouterLlmClient.buildOpenRouterRequest()` maps every `ContextBlock` to its own `OpenRouterMessage` 1:1 (line 236) — there is no code path that requires persona and engine content to occupy a single block. | `OpenRouterLlmClient.kt` lines 235–241 |

**Correction to Phase 47, stated explicitly rather than silently carried forward:** Phase 47 concluded "no skill key is available in time" as an absolute statement. That was imprecise — a real skill key *is* available before generation, and a resolution hook already exists at that exact point in the pipeline. The actual, narrower blocker (unchanged from Phase 47's own §5, now sharpened) is that `RepositoryContextAssembler` merges persona and engine content into one block *before* that point, so the existing hook (`PersonaContextResolutionStage.resolve()`) has nothing separately addressable to replace, and lacks `personaId` in its own method signature besides.

---

## 3. Current Architectural Problem

**DERIVED**, precisely, from §2:

`PersonaContextResolutionStage` is a **half-finished implementation of exactly the feature Phase 48 is asked to design** — the hook is correctly positioned, correctly wired into `PipelineChatEngine`, and correctly defaults to a no-op so production behavior is unaffected. It fails for two independent, narrow, fully addressable reasons, both named in its own doc comment (`PersonaContextResolutionStage.kt` lines 18–24) and both confirmed still true by this phase's re-trace:

1. **`resolve(context: ChatContext): ChatContext` cannot access `personaId`** — not because personaId is unavailable at the call site (`ChatEngine.processTurn()` has `request.personaId` in scope at line 408), but because the method's own signature only accepts `ChatContext`, which was never given a `personaId` field.
2. **There is no separately-addressable persona block to replace** — `block0` already merges engine and persona content into one string by the time `resolve()` runs, so even with `personaId` in hand, `resolve()` has nothing clean to substitute without re-deriving engine content independently (duplicating `RepositoryContextAssembler`'s private `engineAndPersonaContent()` logic and its own `engineRepository` lookup outside that class).

Both are small, mechanical gaps — not evidence of a deeper architectural flaw requiring a large redesign. This reframes the rest of this document: the choice is not "which of three large architectures to build," but "how to close these two specific gaps with the least new surface area."

---

## 4. Strategy A — Post-Skill Resolution

**DESIGN PROPOSAL**, evaluated against the now-corrected facts.

Concept: resolve authorized persona context at the `personaContextResolution?.resolve(...)` call site (already wired), using the real skill key already present on `skillEnrichedContext.selectedSkillKey`.

Exact code changes required (design only, not implemented this phase):
- `PersonaContextResolutionStage.resolve()` signature changes from `resolve(context: ChatContext): ChatContext` to something carrying `personaId` — the minimal change is `resolve(context: ChatContext, personaId: UUID): ChatContext`, and the call site (`ChatEngine.kt` line 408) changes to `personaContextResolution?.resolve(skillEnrichedContext, request.personaId)`. This is a one-line call-site change plus a signature change to one existing class — no new pipeline stage, no `PipelineStage` enum change.
- `PersonaContextResolutionStage` is constructed (in `ChatEngineFactory.build()`) with real dependencies: a `PersonaFieldAuthorizer`/`ProductionPersonaContextService` (Phase 46/47's already-tested foundation) plus a `PersonaCoreVersionRepository` reference so it can independently fetch `core.content` (via `findById(context.personaCoreVersionId)`, confirmed to exist — `PersonaCoreVersionRepository.kt` line 102) as the safe fallback baseline, without needing `RepositoryContextAssembler` to hand it anything extra.
- **Does `RepositoryContextAssembler` need to move later?** No. It still owns engine loading, memory/profile/continuity/sensitive-preference selection, and initial budget trimming — none of that depends on the skill key. Only the *persona* portion needs a second pass.
- **Does `ChatEngine` need to own the second-stage assembly?** No new ownership — `ChatEngine.processTurn()` already owns the call; only the stage it calls needs a real implementation.
- **Token/budget implications:** the original `block0` (engine+persona, sized using `core.content` as baseline in production today) already passed the budget check once. If `resolve()` replaces only the persona *portion* with an equal-or-smaller (typically smaller — selective vs. full) authorized rendering, budget can only improve. If a future rendering policy ever produced a *larger* persona block than the original, a second, narrower budget check is needed (see §13). This is a real, non-hypothetical risk given B/L/LA are all more verbose than a compact `core.content` in the small-scale benchmark work (Phases 6–30) — must not be silently ignored.
- **Latency implications:** one additional resolver call (already measured: sub-millisecond in-process authorization logic plus a couple of already-open-transaction repository reads — no network/API call) between `SKILL_SELECTION` and `GENERATION`. Materially smaller than the LLM call itself.
- **Failure behavior:** unchanged from Phase 46/47 — `ProductionPersonaContextService.resolvePersonaContent(...)` never throws and falls back to `core.content` on any unusable/error state.
- **Baseline fallback safety:** preserved — this strategy reuses the already-tested (32/32) Phase 46/47 foundation verbatim; nothing about "post-skill" changes the fallback contract.
- **Restricted-field protection:** preserved — same reasoning; authorization happens inside `PersonaFieldAuthorizer`, unaffected by when it's called.
- **Does this eliminate the opaque-block problem?** Only if `resolve()` can replace *just* the persona portion. With `block0` merged, it cannot, without duplicating engine-formatting logic (fragile — two places would need to agree on `engineAndPersonaContent()`'s exact formatting, and they would silently drift on the next change to either). This is Strategy A's own remaining gap, addressed by folding in Strategy B's block-splitting (§7, Strategy D).

---

## 5. Strategy B — Separate Context Blocks

**DESIGN PROPOSAL.**

Concept: `RepositoryContextAssembler` emits persona and engine as two distinct `ContextBlock`s instead of one merged `block0`.

- **Can the current architecture support this?** Yes, with very low risk — confirmed in §2 item 12 that `ContextBlock → OpenRouterMessage` is a 1:1, unconditional mapping; nothing downstream assumes exactly one merged system block.
- **Where should budget trimming happen?** Unchanged in principle — the trimming loop (`RepositoryContextAssembler.kt` lines 102–128) already operates over a `listOfNotNull(...)` of blocks; splitting `block0` into two entries in that list changes nothing about the loop's mechanics, only what gets measured/dropped as a unit. (Today, if `block0` ever had to be dropped for budget, it would drop engine+persona together — worth noting this never happens in practice, since `block0` is unconditionally required and the loop currently only ever trims memory/sensitive/continuity/history, never `block0` itself; splitting it doesn't change that invariant.)
- **Can persona be replaced after skill selection?** Yes — this is the entire point: once persona has its own block (and, in the target design, its own identifying marker — see §8), a post-skill stage can locate and replace exactly that one entry in `context.blocks` by index or by a stable identifier, without touching engine content.
- **Does skill selection itself need persona information?** **UNKNOWN**, not determinable from source alone — `LlmIntentDiscovery.selectSkill(request, context)` receives the full `ChatContext` (including whatever persona content assembly produced), so in principle skill selection could be influenced by persona content today. This phase did not audit `LlmIntentDiscovery`'s actual prompt construction to confirm whether it uses persona content as a signal; if it does, Strategy A/B/D must ensure skill selection still sees *some* reasonable persona representation (the pre-skill-selection baseline, i.e. `core.content` in production today, is sufficient and already exactly what it sees — splitting the block doesn't remove any information from what skill selection reads, it only changes representation).
- **Can block identity survive through the pipeline?** Yes, provided `ContextBlock` carries an identifying tag (see §8's proposed `blockType` field) rather than relying on list position (position-based identification is fragile the moment an optional block like `continuityBlock` is absent/present, which already varies per turn).
- **How would final rendering into the model request work?** Unchanged — still a flat list of `{role, content}` messages; splitting only adds one more list entry, mapped exactly the same way.
- **What classes need redesign?** `ContextBlock` (add an identifying field), `RepositoryContextAssembler` (emit two blocks instead of one, still simple string concatenation — no persona-authorization logic added here, that's Strategy A's job).
- **Does this create cleaner long-term architecture?** Yes, independent of the skill-timing problem — today, admin diagnostics, test assertions, or any future observability work that wants to know "what persona content string got sent" has to string-split `engineAndPersonaContent()`'s output; a real separate block removes that entirely.

Strategy B alone does not solve skill-timing — it only makes the *later* substitution possible. It requires Strategy A's hook (or an equivalent) to actually use the split.

---

## 6. Strategy C — Two-Stage / Early + Post-Skill Persona Resolution

**DESIGN PROPOSAL.**

Concept: resolve a minimal/baseline persona representation early (for skill selection and initial budget shaping), then fully resolve the authorized, skill-aware version after the real skill key is known, replacing the early version.

- **What does Stage 1 actually need?** Only enough for skill selection to function and for early budget shaping to not wildly over- or under-count. Today's production baseline, `core.content`, already serves this role — Stage 1 needs nothing new.
- **Can Stage 1 safely use `PersonaCoreVersions.content`?** Yes — that's exactly what happens today (`personaContextProvider` is null in production), so Stage 1 in this strategy is not new work, only a name for existing behavior.
- **Does Stage 1 need selective fields at all?** No — selective/authorized fields are only meaningful once the real skill is known; using them for Stage 1 would require guessing a skill (the forbidden fabrication) or running authorization twice (once speculatively, once for real) for no benefit, since Stage 1's output is discarded once skill selection completes.
- **Does Stage 1 introduce duplicated persona information?** Only transiently, within one turn's processing — Stage 1's block is fully replaced by Stage 2, never sent to the model twice.
- **Can Stage 2 cleanly replace Stage 1?** Yes, under the same mechanism as Strategy A/D (locate the persona block, replace its content).
- **Token implications:** Stage 1's `core.content`-sized block is what the *pre*-skill-selection budget trim operates against — identical to production today. Stage 2's replacement must still respect the overall budget (§13), same caveat as Strategy A.
- **Failure cases:** if Stage 2 resolution fails/is unusable, falling back to Stage 1's *already-present* `core.content` block is not just safe, it's free — no separate fallback fetch needed, since Stage 1's content is already sitting in the context and simply isn't replaced. This is a genuine, small advantage Strategy C has over pure Strategy A (which, as designed in §4, would have `PersonaContextResolutionStage` independently re-fetch `core.content` via `findById()` as its own fallback baseline — Strategy C's "leave Stage 1's block untouched on failure" avoids that extra repository call entirely).
- **Risk of conflicting persona representations:** none, provided Stage 2 always either fully replaces or fully leaves-alone the block (never partially merges) — consistent with the existing strict-substitution invariant (Phase 45 D2).
- **Is this complexity actually justified?** Largely, **Strategy C is not a separate architecture from Strategy A/D — it is Strategy A/D's natural framing once you notice that "Stage 1" is just today's existing `RepositoryContextAssembler` output and "Stage 2" is the completed `PersonaContextResolutionStage`.** The "two-stage" language in this section and Strategy A's design in §4 describe the same mechanism from two angles. This document treats them as one strategy going forward (folded into Strategy D, §7) rather than presenting Strategy C as a genuinely distinct alternative — doing otherwise would manufacture a difference that source inspection does not support.

---

## 7. Strategy D — Post-Skill Resolution Over Split Blocks

**DESIGN PROPOSAL**, justified directly by source structure (§2–§6), not invented independently of it.

Strategy D is the union of: Strategy A's already-existing, already-wired hook (`PersonaContextResolutionStage`, called at `ChatEngine.kt` line 408), completed with real logic; Strategy B's block-splitting (persona gets its own `ContextBlock`, tagged so it's reliably identifiable); and Strategy C's framing (the pre-skill block IS the fallback, not a separate fetch).

Concretely:
1. `RepositoryContextAssembler` emits persona content as its **own** `ContextBlock` (tagged, see §8), separate from the engine block, still populated with `core.content` today (production behavior byte-for-byte unchanged at this stage) and still subject to the existing budget-trimming loop as a single required unit (same trimming semantics as today's merged `block0`).
2. `PersonaContextResolutionStage.resolve(context: ChatContext, personaId: UUID)` locates that tagged block, calls `ProductionPersonaContextService.resolvePersonaContent(personaId, context.personaCoreVersionId!!, context.selectedSkillKey ?: return context, request.content, existingPersonaBlockContent)`, and — only if the result differs from the fallback (i.e., is usable) — replaces that one block's content, leaving every other block (engine, profile, memory, sensitive, continuity, skill, history, current message) completely untouched.
3. If `context.selectedSkillKey` is `SkillSelection.None` (null), `resolve()` is a guaranteed no-op — this is not a gap to design around, it is the correct behavior: no real skill key exists, so nothing changes, and the existing `core.content` (already in the persona block from step 1) is what generation uses, identical to production today.

This is why Strategy D is preferred over presenting A/B/C as competing, mutually exclusive choices: none of A, B, or C alone closes both of §3's gaps, but their union does, with less total new code than any single one of them attempted in isolation (no engine-content re-fetch duplication, no speculative double-authorization, no new pipeline stage or `PipelineStage` enum entry).

---

## 8. Target Architecture

### 8.1 Classes / interfaces (design only — not created this phase)

- **`ContextBlock`** (`ChatEngine.kt`, modified): add one field, e.g. `val blockType: BlockType = BlockType.OTHER`, where `BlockType` is a small enum (`ENGINE, PERSONA, PROFILE, MEMORY, SENSITIVE, CONTINUITY, SKILL, HISTORY, CURRENT_MESSAGE, OTHER`). Purely additive, defaulted — every existing `ContextBlock(role, content)` call site keeps compiling unchanged. Never serialized to the wire format (`OpenRouterMessage` only reads `role`/`content`), so this cannot leak into the LLM request or change its shape.
- **`RepositoryContextAssembler`** (modified): replace `block0 = ContextBlock("system", engineAndPersonaContent(engine.content, personaContent))` with two blocks: `engineBlock = ContextBlock("system", engine.content, BlockType.ENGINE)` and `personaBlock = ContextBlock("system", personaContent, BlockType.PERSONA)`, inserted adjacently in the same position `block0` occupied today (preserving today's ordering — Engine, Persona, Profile, Memory, …).
- **`PersonaContextResolutionStage`** (rewritten, no longer a stub): constructor takes `ProductionPersonaContextService` and `PersonaCoreVersionRepository`. `resolve(context: ChatContext, personaId: UUID): ChatContext`:
  - returns `context` unchanged if `context.selectedSkillKey == null`;
  - finds the block with `blockType == BlockType.PERSONA`; if none exists (defensive — should never happen once `RepositoryContextAssembler` always emits one), returns `context` unchanged;
  - calls `service.resolvePersonaContent(personaId, context.personaCoreVersionId!!, context.selectedSkillKey, /* current message */ ..., coreContent = existingPersonaBlock.content)` — note the existing persona block's content (today, always `core.content`) serves directly as the strict-substitution fallback baseline, with no extra repository call needed (§6's Stage-C insight);
  - if the returned string differs from the input baseline (i.e. the service actually substituted authorized content, meaning the result was usable), replaces that one block via `.copy()`; otherwise returns `context` unchanged.
- **`ChatEngineFactory.build()`** (modified): constructs a real `PersonaContextResolutionStage` (using the already-tested `PersonaFieldAuthorizer`/`ProductionPersonaContextService`) and passes it to `PipelineChatEngine`, gated behind an explicit, named toggle (see §17 — this is exactly the "final rendering-policy / rollout" decision this phase must not make).
- **`ChatEngine.kt`'s call site** (one-line change): `personaContextResolution?.resolve(skillEnrichedContext, request.personaId)`.

No new `PipelineStage` enum value is needed — this remains inside the existing `SKILL_SELECTION` → `GENERATION` transition, exactly where `personaContextResolution?.resolve(...)` already sits today.

### 8.2 Data flow / lifecycle

Unchanged pipeline shape; only `block0`'s internal representation and the `PersonaContextResolutionStage` body change. `ChatRequest` flows through as it always has; `ChatContext` is reconstructed via `.copy()` at each enrichment point, as `SkillContextEnricher` and the memory enricher already do today.

### 8.3 Dependency direction

`PersonaContextResolutionStage` depends on `ProductionPersonaContextService` (Phase 46/47's already-built, already-tested facade) and `PersonaCoreVersionRepository` — no new dependency on research code (`PersonaContextResolver`), consistent with invariant 11 (§9).

### 8.4 Error handling / fallback

Delegates entirely to `ProductionPersonaContextService.resolvePersonaContent(...)`, which never throws (Phase 46/47, tested). `PersonaContextResolutionStage.resolve()` itself should also be wrapped defensively (`try/catch { context }`) at the call site, matching the existing pattern used for `intentDiscovery`, `memoryContextEnricher`, and `skillContextEnricher` (`ChatEngine.kt` lines 341–402) — none of those stages are allowed to fail the turn, and persona resolution must follow the same rule.

### 8.5 Context-budget handling

See §13.

### 8.6 Rendering / authorization / skill boundaries

Unchanged from Phase 45/46: `ScalarRenderingPolicy` stays pluggable and injected into `ProductionPersonaContextService`; `PersonaFieldAuthorizer` stays the sole authorization boundary; skill key flows in one direction only (selection → resolution, never resolution → selection).

---

## 9. Before/After Pipeline

**BEFORE (current production, re-verified §2):**

```
CONTEXT_ASSEMBLY
  RepositoryContextAssembler.assemble(request)
    core.content (PersonaCoreVersions, no authorization)
    block0 = engine.content + core.content   [merged, opaque]
    budget trim
  -> ChatContext { blocks = [block0, profile, memory, ...] }

SKILL_SELECTION
  intentDiscovery.selectSkill(request, context)  -> real skillKey
  memoryContextEnricher.enrich(...)
  skillContextEnricher.enrich(...)               -> appends SKILL block
  context.selectedSkillKey = skillKey

PersonaContextResolutionStage.resolve(context)   [NO-OP STUB]

GENERATION
  generator.generate(request, context)
    -> block0 still contains only core.content, never authorized/rendered
```

**AFTER (Strategy D, design only — not implemented this phase):**

```
CONTEXT_ASSEMBLY
  RepositoryContextAssembler.assemble(request)
    core.content (PersonaCoreVersions, no authorization) -- unchanged baseline
    engineBlock  = ContextBlock(engine.content,  PERSONA=false)
    personaBlock = ContextBlock(core.content,    PERSONA=true)   [own block now]
    budget trim (engineBlock + personaBlock treated as before, still required)
  -> ChatContext { blocks = [engineBlock, personaBlock, profile, memory, ...] }

SKILL_SELECTION
  intentDiscovery.selectSkill(request, context)  -> real skillKey   [unchanged]
  memoryContextEnricher.enrich(...)                                [unchanged]
  skillContextEnricher.enrich(...)               -> appends SKILL block [unchanged]
  context.selectedSkillKey = skillKey                              [unchanged]

PersonaContextResolutionStage.resolve(context, request.personaId)  [REAL NOW]
  find personaBlock (BlockType.PERSONA)
  ProductionPersonaContextService.resolvePersonaContent(
      personaId, personaCoreVersionId, selectedSkillKey,
      request.content, coreContent = personaBlock.content)
    -> PersonaFieldAuthorizer.resolve(...)      [Phase 46, tested, unchanged]
       canAccessField() gating                  [Phase 46, tested, unchanged]
       ScalarRenderingPolicy.renderScalar(...)  [Phase 45/46, pluggable, unchanged]
    -> usable?  replace personaBlock.content
       not usable? leave personaBlock.content = core.content (already there)

GENERATION
  generator.generate(request, context)
    -> personaBlock now carries authorized, skill-aware, rendered content
       (or, on any failure/unusable state, the exact same core.content
        production already sends today)
```

---

## 10. Safety Invariants

**DERIVED**, checked against the target design in §8–§9:

| # | Invariant | Preserved by target design? | How |
|---|---|---|---|
| 1 | Never fabricate a skill key | **Yes** | `resolve()` only acts when `context.selectedSkillKey != null` — a real, model-selected key from `intentDiscovery`. No placeholder/pinned key is ever introduced. |
| 2 | Never serialize an unrestricted raw persona document into model context | **Yes** | `PersonaFieldAuthorizer` never touches `PersonaMasterDocuments` outside `canAccessField()` gating (Phase 46, tested: J/K in both Phase 47 and Phase 46 suites). Unchanged by this design. |
| 3 | Restricted fields cannot reach GENERATION unless explicitly authorized | **Yes** | Same authorizer, same gating logic, unchanged; now simply called from a different (correctly-timed) call site. |
| 4 | Unusable result cannot erase today's baseline | **Yes, and cheaper than before** | The persona block already contains `core.content` before `resolve()` runs; "unusable" means "don't replace it," not "fetch a fallback" — the invariant holds structurally, not just by convention. |
| 5 | Resolver/provider failure cannot erase today's baseline | **Yes** | Same reasoning as #4, plus `resolve()` wrapped in `try/catch { context }` at the call site (§8.4). |
| 6 | Rendering policy cannot bypass authorization | **Yes** | Unchanged from Phase 45/46 — `ScalarRenderingPolicy` only renders fields `PersonaFieldAuthorizer` already selected; it has no access to the raw document. |
| 7 | Rendering policy cannot change selected fields | **Yes, tested** | Phase 47 test H already proves this at the facade level; the facade is reused unmodified here. |
| 8 | Skill selection cannot bypass field authorization | **Yes** | `intentDiscovery.selectSkill()` never calls `PersonaFieldAuthorizer` — authorization only happens in the new post-selection stage. One-directional dependency, confirmed by §8.3. |
| 9 | PersonaId must remain correctly associated with the request | **Yes** | `request.personaId` is passed explicitly into `resolve()`, not inferred/derived; `conversation.personaId == request.personaId` is already asserted earlier in `assemble()` (`RepositoryContextAssembler.kt` line 53), unchanged. |
| 10 | Memory/profile/history remain independent of persona-field authorization | **Yes** | Those blocks are untouched by `PersonaContextResolutionStage` — it locates and replaces only the `BlockType.PERSONA` block. |
| 11 | No research-only `PersonaContextResolver` dependency | **Yes** | Target design depends only on `ProductionPersonaContextService`/`PersonaFieldAuthorizer` (Phase 46/47) — same class-reference check as Phase 47 test M would apply unchanged. |
| 12 | No benchmark-specific logic in production architecture | **Yes** | Nothing in §8's design references `PersonaContextPocSeed`, `PersonaContextRealBenchmarkTest`, or any POC-only fixture. |

All 12 invariants hold under the proposed design, based on re-verified source and the already-tested Phase 46/47 foundation it reuses without modification.

---

## 11. Token/Budget Model

**DESIGN PROPOSAL, flagging a genuine open risk rather than asserting safety by default.**

Today: `block0` (merged) is sized once, pre-skill-selection, using `core.content` as the persona component; the budget loop (§2 item 5) trims memory/sensitive/continuity/history around that fixed size.

Under Strategy D: the persona block's size can **change** after `resolve()` runs (a selective, authorized rendering is usually shorter than full `core.content`, but is not guaranteed to be — a verbose rendering policy, or a skill requiring many fields, could in principle produce a larger block than the original). Two options, both left as an explicit follow-up decision rather than resolved here:

- **(i) Cap-and-truncate-to-fallback:** if the resolved persona block's estimated token size exceeds the original `core.content` block's size, discard the resolution and keep `core.content` (a strict, simple, safe rule — never risks exceeding the budget the earlier trim already accounted for, at the cost of occasionally discarding a usable, correctly-authorized-but-larger rendering).
- **(ii) Re-run a narrow budget check:** after replacement, re-sum `context.blocks` against `tokenBudget` and, only if now over budget, fall back the persona block to `core.content` (never trim other already-fixed blocks a second time — consistent with the existing "never partially truncate a block" rule, `RepositoryContextAssembler.kt` lines 112–127).

Option (ii) is more correct (doesn't discard a usable rendering purely for being marginally larger) but requires `PersonaContextResolutionStage` to share the same `TokenEstimator`/budget constant `RepositoryContextAssembler` uses (already a public constant, `RepositoryContextAssembler.DEFAULT_TOKEN_BUDGET`, reused as-is by `SkillContextEnricher` today — precedent exists for reusing it in a second-stage enricher). This document recommends (ii) for consistency with `SkillContextEnricher`'s existing pattern, but does not treat the choice as decided — **HUMAN DECISION REQUIRED** only in the narrow sense of confirming (ii) over (i); the mechanism itself is not in question.

---

## 12. Failure Handling

**DERIVED**, consistent with §10:

- Missing/empty document, empty registry, missing skill mapping, unknown skill, restricted-field-only request, resolver exception → all already return non-`USABLE` status from `PersonaFieldAuthorizer` (Phase 46) → `ProductionPersonaContextService` falls back to the caller-supplied `coreContent` (Phase 47) → in the target design, that `coreContent` **is** the persona block's pre-existing content, so "fallback" is a no-op replacement, not a separate code path.
- `context.selectedSkillKey == null` (skill selection returned `None`, or itself failed and degraded to `None` per `ChatEngine.kt`'s existing try/catch at lines 342–355) → `resolve()` short-circuits immediately, same guarantee.
- Any exception thrown inside `resolve()` itself (defensive wrapper, §8.4) → context returned unchanged, same as every other optional enrichment stage in `ChatEngine.processTurn()`.

No new failure mode is introduced beyond what Phase 46/47 already tested; the only new failure surface is the budget interaction (§11), explicitly flagged rather than assumed away.

---

## 13. Implementation Plan (not executed this phase)

| Step | File/class | Change | Deletes/replaces existing code? | New tests required | Independently testable? |
|---|---|---|---|---|---|
| 1 | `ChatEngine.kt` — `ContextBlock` | Add `blockType: BlockType = BlockType.OTHER` field (new small enum, same file) | No — purely additive, defaulted | Compile-level only (no behavior to test in isolation) | Yes, trivially |
| 2 | `RepositoryContextAssembler.kt` | Split `block0` into `engineBlock`/`personaBlock`, tagged; keep `engineAndPersonaContent()` unused or delete it (dead code once split) | Replaces `block0` construction (lines 70–76); the private `engineAndPersonaContent()` helper (line 167) becomes dead and should be deleted, not left behind | Update existing assembler tests to assert two blocks instead of one merged block (exact test file/count not enumerated in this phase — requires locating current assembler tests) | Yes |
| 3 | `PersonaContextResolutionStage.kt` | Replace no-op stub with real logic (§8.1) | Replaces the entire current stub body | New unit tests (§16) | Yes — depends only on already-tested `ProductionPersonaContextService` |
| 4 | `ChatEngine.kt` — call site (line 408) | `resolve(skillEnrichedContext, request.personaId)` | One-line signature-call change | Covered by `PersonaContextResolutionStage`'s own tests plus one pipeline-level integration test | Yes |
| 5 | `ChatEngineFactory.kt` | Construct a real `PersonaContextResolutionStage` with `ProductionPersonaContextService`, gated behind an explicit named toggle (not silently always-on) | Adds construction logic; does not remove the existing null-default (toggle off = today's exact behavior) | Factory-level test asserting toggle-off reproduces today's byte-identical pipeline | Yes |

This sequence is designed so steps 1–3 are each independently compilable and testable before step 4 wires them together, and step 5 (the only step that can affect real production behavior) is explicitly gated and last.

---

## 14. Test Plan (designed, not written this phase — Part 12 restricts implementation to tiny, necessary supporting types, and none were judged necessary to prove this design)

Zero-API-call, deterministic tests, matching the pattern already proven in Phase 46 (19 tests) and Phase 47 (13 tests):

- **A.** Correct skill arrives before persona authorization — assert `resolve()` receives a non-null `selectedSkillKey` only after `SkillSelection.Selected` and never fabricates one when `None`.
- **B.** Wrong/missing skill cannot be fabricated — `resolve()` with `selectedSkillKey == null` returns `context` unchanged, verified by reference/content equality.
- **C.** Valid persona data → authorized context — persona block content changes to the authorized rendering when a matching skill mapping and authorized fields exist.
- **D.** Missing persona data → `PersonaCoreVersions.content` fallback — persona block content stays exactly `core.content` when no master document exists.
- **E.** Restricted field → blocked — same as Phase 46/47 D/J/K, re-asserted at this integration point.
- **F.** Unknown skill → safe behavior — persona block unchanged.
- **G.** Resolver failure → baseline preserved — forced exception path (constructible here since `PersonaContextResolutionStage` is new code, not a non-open existing class — unlike Phase 47's test G limitation) confirms `try/catch` wrapper holds.
- **H.** Rendering policy changes only representation — reused from Phase 47 test H, at the pipeline level.
- **I.** Context budget trimming does not expose raw content — assert the pre-`resolve()` budget trim never includes anything beyond `core.content`, and that post-`resolve()` budget handling (§11) never allows an oversized/unchecked block through.
- **J.** Persona block can be replaced/refined after skill selection — the core positive-path test, replacing block content and confirming only the tagged `BlockType.PERSONA` block changed.
- **K.** Engine block remains independent — `engineBlock.content` is byte-identical before and after `resolve()` runs, for every scenario in C–J.
- **L.** Memory/profile/history remain independent — same style of assertion as K, for the other block types.
- **M.** Final generation receives only authorized context — an end-to-end assertion that `GenerationRequest.from(request, resolvedContext, config).context.blocks` contains the authorized (or correctly-unchanged) persona content, not the pre-resolution version, whenever resolution succeeded.
- **N.** PersonaId remains correct through the full pipeline — `request.personaId` passed into `resolve()` matches `conversation.personaId` asserted earlier in `assemble()`.
- **O.** No dependency on research `PersonaContextResolver` — same reflection-based check as Phase 47 test M, applied to `PersonaContextResolutionStage`.

---

## 15. Open Human Decisions

**HUMAN DECISION REQUIRED**, carried forward and sharpened, not newly invented:

1. **Token-budget policy for post-resolution replacement** (§11): cap-and-fallback vs. re-check-and-fallback. Recommended: re-check (ii), for consistency with `SkillContextEnricher`'s existing precedent — but not decided by this document.
2. **Rollout gating mechanism** (§13 step 5): how the "real `PersonaContextResolutionStage` vs. no-op" toggle is controlled (config flag, per-persona flag, environment variable, etc.) — explicitly not a decision this phase makes, since making it would itself be a product/rollout decision, forbidden by this phase's instructions.
3. **Final rendering-policy choice** (Bare/AgeOnly/AllLabeled) — unchanged, still open, still not addressed here.
4. **Data-authoring mechanism** for `persona_master_documents`/`persona_field_registry`/`skill_context_requirements` — unchanged from Phase 45/47, still empty in production, out of scope for this design phase.
5. **Minimum-viable-persona definition** — unchanged, still open.

---

## 16. Explicit Non-Goals

This phase explicitly did **not**: call any external model/API; modify `ChatEngine.kt`, `RepositoryContextAssembler.kt`, `ChatEngineFactory.kt`, `PersonaContextResolutionStage.kt`, `Application.kt`, or any other production file; populate any production persona/field-registry/skill-requirement data; select a final rendering policy; declare LA (or any variant) production-ready; reinterpret Phases 19–32's evidence boundary; make a rollout recommendation; or implement any part of §13's plan beyond the design itself.

---

## 17. Final Phase Status

**Answer to Phase 48's central question:** *"Exactly how should persona context be resolved with the real selected skill before GENERATION?"* — The answer is clear and source-grounded: **complete the already-existing, already-wired `PersonaContextResolutionStage` hook** (`ChatEngine.kt` line 408), give it access to `personaId` via a one-line signature change, and give `RepositoryContextAssembler` a one-time change to emit persona content as its own tagged, independently-replaceable block instead of merging it with engine content. This is Strategy D (§7–§9): the union of Strategy A's existing hook, Strategy B's block-splitting, and Strategy C's "pre-skill content is the fallback, not a separate fetch" framing. All 12 safety invariants (§10) hold under this design, reusing the Phase 46/47 foundation (32/32 tests passing) without modification. One genuine open engineering question remains (§11's budget-recheck mechanism, a two-option decision, not an unresolved unknown), plus the five carried-forward human/product decisions (§15) — none of which block writing the code once a human makes the rollout-gating call in §15 item 2.

Phase 48 stops here. No Phase 49 is started. No code beyond this document was written. No API calls were made.
