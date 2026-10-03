# Phase 10B — Corrected Experiment Design

Design only. No code has been written or modified to implement anything in this document; no API call has been made. `SelectiveRawContextProvider.kt` and the "X" branch in `TestChatService.kt` already exist from the paused Phase 10 work and are referenced, not re-created.

---

## Step 4 — The two missing cells

### FR — Full Raw (new provider required)

**Design:** a new class, e.g. `FullRawContextProvider.kt`, structurally the mirror image of `StructuredFullDocumentContextProvider`:

```kotlin
class FullRawContextProvider(
    private val personaMasterRepo: PersonaMasterRepository,
) : PersonaContextProvider {
    override fun getPersonaContext(personaId, personaCoreVersionId, skillKey, currentUserMessage): String {
        val masterDoc = personaMasterRepo.findByPersonaCoreVersionId(personaCoreVersionId) ?: return ""
        return masterDoc.contentJson.toString()  // raw, unstructured — no renderer involved
    }
}
```

This is intentionally almost identical to what `PersonaContextResolver.resolveVariantA()` already computes — the only change is packaging it as a real `PersonaContextProvider` so it can be explicitly injected as non-null and therefore actually reach `RepositoryContextAssembler`, unlike the diagnostic-only resolver call. Wired via a new, additive `TestChatService` branch (`variant == "FR"` or similar — never reusing "A", to avoid ever repeating this exact confusion), following the same pattern already used for "D" and "X".

### SR — Selective Raw (already built, unverified)

**No new implementation needed.** `SelectiveRawContextProvider.kt` already does exactly this: reuses `PersonaContextResolver.resolve(..., "B")`'s `includedFieldIds` verbatim, fetches those fields' raw values, and serializes with `JsonObject(selected).toString()`. What remains is verification (a corrected, actually-run dry run — see Step 11) before any real call is spent on it.

---

## Step 6 — The clean experiment

Four comparisons, in priority order, each requiring the underlying selected/full content to be identical between the two legs being compared (verified structurally, not assumed):

1. **FR vs. FS** — does raw vs. structured formatting matter when the content is the FULL document? (FS = reuse Phase 7 D data; FR = new)
2. **SR vs. SS** — does raw vs. structured formatting matter when the content is the SAME SELECTED subset? (Both need fresh data on a shared corpus — see Step 5's caveat on SS comparability)
3. **FS vs. SS** — does selecting a subset matter when formatting is held constant at STRUCTURED? (Reuses D exactly; needs a fresh, corpus-matched B run)
4. **FR vs. SR** — does selecting a subset matter when formatting is held constant at RAW? (Both new)

All four comparisons share two "corners" (FR and SR are each used twice), which is why a single, shared corpus across all four conditions is more efficient than four separate pairwise experiments.

---

## Step 7 — Corpus design

Reuses Phase 7's existing 30-scenario fact-heavy corpus (`identityAbout()` + 10 fact-heavy `lifeContext()` scenarios) as the backbone, since it is already the corpus FS/D's real data was collected on — extending it slightly is cheaper and more comparable than starting over. Scenario categories, explicitly labeled by what each tests:

| Category | Example scenarios | Count | What it tests |
|---|---|---|---|
| A. Direct persona facts | name, age, hometown, current city, education, occupation | ~10 (from `identityAbout()`) | Baseline grounding — the category where Phase 7 saw the largest FS-vs-(mislabeled)-A gap |
| B. Indirect persona facts | "who's your flatmate," "how's the restaurant project," "tell me about your family," "do you have siblings" | ~10 (from `lifeContext()`) | Whether format/selection changes affect facts that require connecting two pieces of context, not just single-field lookup |
| C. Character/personality | not included in the primary 30-scenario pilot corpus (Phase 7's fact-heavy set intentionally excluded these) | 0 in this pilot | Deferred to a Phase B expansion — the primary question here is factual grounding, where the confound actually lives |
| D. Unknown facts | not included in the primary pilot | 0 in this pilot | Already well-covered by existing Phase 6 data across all real variants (B/C/D); not needed to answer the structure/selection question specifically |
| E. Cross-skill transitions | not included in the primary pilot (single-turn only) | 0 in this pilot | Multi-turn adds a second confound (history handling) on top of the one being isolated here; deferred |
| F. Safety/restricted-context | one additional check, not scenario-corpus-scale | 1 spot-check | Only to confirm FR/SR don't alter restricted-field gating — structurally near-impossible to break (neither touches `canAccessField`), so a single spot-check suffices rather than a full sub-corpus |

**Total scenario corpus for the pilot: the same 30 scenarios Phase 7 used**, run under three NEW conditions (FR, SR, and a corpus-matched fresh B) plus reuse of the existing D data for the fourth.

---

## Step 8 — Scoring (reusing Phase 6's exact rubric, verified against source)

Verified directly against `phase6-scores-batch01.csv` through `09.csv` headers and `PHASE6_ANALYSIS.md` §7 — the seven dimensions actually used, exactly as originally defined, are:

1. **dim1 — factuality**
2. **dim2 — character** (persona/character fidelity)
3. **dim3 — relevance** (context relevance to the user's message)
4. **dim4 — naturalness**
5. **dim5 — continuity** (multi-turn only; N/A for this single-turn pilot)
6. **dim6 — unknown-fact handling** (N/A for this pilot's corpus, which contains no unknown-fact probes)
7. **dim7 — relational/behavioral fidelity**

No new dimension is introduced. dim5 and dim6 will be recorded as N/A for every response in this pilot, consistent with how they were marked N/A for non-applicable scenarios throughout Phase 6 — not omitted, just correctly marked inapplicable. The existing hard-failure tag taxonomy (FACTUAL_CONTRADICTION, FACTUAL_FABRICATION, PERSONA_FACT_OMISSION, PERSONA_DRIFT, CONTINUITY_BREAK, AWKWARD_RESPONSE, OTHER) is reused unchanged.

**Artifacts** (created only if/when execution is authorized — none exist yet):
- `PHASE10_EXECUTIONS.csv`
- `PHASE10_BLIND_MAPPING.csv`
- `PHASE10_BLINDED_EVALUATION.csv` (no FR/SR/SS/FS/condition label of any kind)
- `PHASE10_SCORED_RESULTS.csv` (post-unblind)

---

## Step 9 — Success questions (no ranking, no winner)

1. Does RAW vs. STRUCTURED produce a measurable difference when FULL content is identical? (FR vs. FS)
2. Does RAW vs. STRUCTURED produce a measurable difference when SELECTIVE content is identical? (SR vs. SS-fresh)
3. Does SELECTIVE vs. FULL produce a measurable difference when formatting is held constant at STRUCTURED? (SS-fresh vs. FS)
4. Does SELECTIVE vs. FULL produce a measurable difference when formatting is held constant at RAW? (SR vs. FR)
5. Are any observed differences concentrated in factuality specifically, or spread across character/relevance/naturalness/relational too?
6. Do real token counts differ meaningfully between the four conditions?
7. Does latency differ?
8. Does resolver determinism remain 100% (checked via the SR/X path specifically, since it's the only new condition that invokes the resolver at all)?
9. Does restricted-field safety remain intact (single spot-check per Step 7.F)?

No comparison in this design produces or implies a "best," "winner," or "recommended" label — each answers a specific factual question about one axis at a time.

---

## Step 10 — Sample size

| Condition | New or reused | Scenarios | Calls |
|---|---|---|---|
| FS (D) | **Reused** — Phase 7 existing data | 30 | 0 |
| SS (B, corpus-matched) | **New** — same 30 scenarios, not previously run on this exact corpus | 30 | 30 |
| SR (X) | **New** — first-ever execution of this provider | 30 | 30 |
| FR | **New** — first-ever execution of this provider | 30 | 30 |
| Restricted-field spot-check (Step 7.F) | **New**, minimal | 1 scenario × 2 conditions (FR, SR) × 2 skills (authorized/unauthorized) | 4 |
| Repeatability (resolver determinism check for SR only, since it's the only new resolver-dependent path) | **New** | 5 scenarios × 2 repeats | 10 |

**Total new API calls: 30 + 30 + 30 + 4 + 10 = 104.**

This is the smallest design that answers all four Step-9 structure/selection questions on a single, shared, already-proven-informative corpus, while reusing Phase 7's D data at zero cost and adding the minimum necessary repeatability/safety checks (not a full sub-corpus for either, since neither FR nor SR touches the resolver's restricted-field gating logic or introduces any new source of non-determinism beyond what SR's one resolver call already carries).

**This document stops here. No execution is authorized by writing this design.**

---

## Step 11 — Sanity checks that would have caught the variant-A bug

To be implemented as hard `assertTrue`/`check` assertions in the eventual test harness, before any scenario in the real pilot runs (not merely as documentation):

1. **Provider assertion:** for every one of FR/SR/FS/SS conditions, assert `personaContextProvider != null` was actually constructed and passed into `ChatEngineFactory.Dependencies` — fail loudly if any condition would silently fall through to the `null`/`core.content` path the way historical "A" did.
2. **Payload assertion:** capture the actual string returned by calling the condition's provider's `getPersonaContext(...)` directly (not the resolver's separate diagnostic call), log it into the execution record, and assert it is non-empty and internally consistent with the condition's intended selection (see #3).
3. **Consistency assertion:** for FR and FS specifically (both claim to be "full"), assert that representative field values — a literal occupation string, a literal hometown string, a family-member name, a friend's name — are actually present as substrings in the captured payload from #2. For SR and SS, assert the payload's field set exactly matches `PersonaContextResolver.resolve(..., "B")`'s `includedFieldIds` for that scenario (the same check `experiment1DryRunCheck()` already attempted, to be fixed and actually run before any real call).
4. **Impossible-token assertion:** assert `personaTokenEstimate <= totalPromptTokens` (the real, OpenRouter-reported figure) for every execution, as a **hard failure**, not a warning — this is the exact relationship that was silently violated throughout historical variant A's data and never checked.
5. **Provider/payload equivalence assertion:** assert that the text captured via #2 (calling the provider directly) is byte-identical to whatever `RepositoryContextAssembler` actually embedded in the system block for that same execution — i.e., prove the same object reached the request, rather than trusting that "a provider exists" implies "its output was used." This is the single check that would have caught the original bug fastest, since historical A had a provider concept (`resolveVariantA()`) whose output demonstrably never reached the request.

None of these checks require any change to `PersonaContextResolver`, `PersonaContextRenderer`, or any field mapping — they are all test-harness-level assertions, consistent with the standing rule that only diagnostic/harness code may be added or fixed, never shared production selection/rendering logic.
