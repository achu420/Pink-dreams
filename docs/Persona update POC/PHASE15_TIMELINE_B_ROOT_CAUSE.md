# Phase 15 — Root-Cause Isolation of the Timeline/B Failure

**Zero API calls made.** Everything below was established by re-reading the exact `provider_payload` and `response` cells already recorded in `PHASE14_EXECUTIONS.csv`, plus a direct re-read of `PersonaContextRenderer.kt`. No historical Phase 10–14 artifact was modified. No production, mapping, resolver, or renderer file was modified.

---

## Stage 1 — Zero-cost evidence audit

### Exact Phase 14 evidence reviewed

The `provider_payload` and `response` columns of `PHASE14_EXECUTIONS.csv` for `fix_name=timeline`, both variants, reproduced here verbatim (not re-summarized):

**B (`ResolvedPersonaContextProvider`, structured):**
```
## Communication
Warm, clear, direct. ...
## Identity
Simran Kohli
Nashik, Maharashtra
Pune, Maharashtra (Kothrud)
27
## Life_context
Architect (junior)
- Moving to Pune at 21 to study architecture.
- Leaving the first corporate architecture job at 24 (felt wrong).
- Joining the smaller studio and finding work that felt more real.
- Ritu moving in about two years ago.
- Deciding on the Ladakh trip this year.
- Current delayed restaurant project (ongoing frustration and learning).
## Narrative_state
[unresolved_threads, four items]
## Personality
[core_traits, six items]
```
Response: *"Not long, no. About three years in Pune proper - moved here at 21 for architecture school. Before that, Nashik. You know it?"*

**X (`SelectiveRawContextProvider`, raw JSON):**
```json
{"identity.name":"Simran Kohli","personality.core_traits":[...],"identity.hometown":"Nashik, Maharashtra","identity.current_city":"Pune, Maharashtra (Kothrud)","communication.style":"...","narrative_state.unresolved_threads":[...],"life_context.occupation":"Architect (junior)","identity.age":27,"life_context.important_life_events":["Moving to Pune at 21 to study architecture.","Leaving the first corporate architecture job at 24 (felt wrong).","Joining the smaller studio and finding work that felt more real.","Ritu moving in about two years ago.","Deciding on the Ladakh trip this year.","Current delayed restaurant project (ongoing frustration and learning)."]}
```
Response: *"Been here since I was 21 - came to Pune for architecture school and just never really left. It's home now, even though I still get a little nostalgic for Nashik sometimes."*

### Point-by-point verification

| Check | Result | Evidence |
|---|---|---|
| Both providers received the same underlying facts | **Confirmed identical** | Same 9 fields in both payloads: `identity.name`, `personality.core_traits`, `identity.hometown`, `identity.current_city`, `communication.style`, `narrative_state.unresolved_threads`, `life_context.occupation`, `identity.age`(27), `life_context.important_life_events` (same 6-item list, same text) |
| B rendering contains both target facts | **Confirmed** | "27" appears under `## Identity`; "Moving to Pune at 21 to study architecture." appears as the first bullet under `## Life_context` |
| X rendering contains both target facts | **Confirmed** | `"identity.age":27` and the identical life-events array, explicitly keyed |
| No additional selection difference between B and X for this call | **Confirmed** | `selected_field_ids` identical between the two rows in `PHASE14_EXECUTIONS.csv` (already verified in Phase 14 itself) |
| Engine/system content identical between B and X | **Confirmed by construction** | Both calls ran inside the same `worldTimeline` `World()` instance — a single in-memory DB seeded with exactly one Conversation Engine version (v1) by `BaselineSeeder`; there is structurally only one engine row this World could ever use for either call |
| User message byte-for-byte identical | **Confirmed** | Both calls pass the identical Kotlin string literal `"Been in this city long?"` from the same test method |
| No memory/profile/history difference | **Confirmed** | Both are freshly created conversations (`service.create(...)` immediately followed by exactly one `sendMessage(...)`) — no prior message history exists for either, no memory facts exist for either (fresh `testUserId`, memory extraction never ran before this single call in either case), and no profile record was created for either (both render `RepositoryContextAssembler.profileContent()`'s null-profile placeholder identically) |
| Recorded response matches what Phase 14 scored | **Confirmed** | Verbatim match against `PHASE14_TARGETED_FIX_VALIDATION.md` §6/§7 |

**Stage 1 conclusion: every non-format variable is confirmed identical between the two calls. The only remaining variable is how the identical underlying facts are serialized (structured markdown vs. raw JSON) and the model's own stochastic behavior.**

### Structured-vs-raw representation inspection (what is actually different)

Re-reading `PersonaContextRenderer.kt` directly (not inferring from the output alone): `render()` groups fields by classification and calls `renderValue(jsonValue, fieldId)` for each. `renderValue()`'s `JsonPrimitive` branch returns **only the bare value** — `fieldId` is accepted as a parameter but is **never used** in that branch. Per-key `**Label:**` bolding only happens inside `renderObject()`, which is invoked exclusively for *nested* objects/arrays, never for a top-level scalar field grouped directly under a classification heading.

**Concrete, verified consequence for this exact call:** under B's `## Identity` heading, four scalar fields (`identity.name`, `identity.hometown`, `identity.current_city`, `identity.age`) are joined with newlines as four bare lines — "Simran Kohli / Nashik, Maharashtra / Pune, Maharashtra (Kothrud) / 27" — with **no label identifying which line is which field**. The reader (human or model) must infer that the fourth line is her age from its position and its numeric magnitude, not from an explicit tag. In X, the identical value is `"identity.age":27` — an explicit, unambiguous key-value pair.

**This is a real, verified structural difference, established from the renderer's source code, not inferred backward from the fact that B was wrong.**

**A second candidate ambiguity was checked and found NOT to differentiate B from X:** `life_context.important_life_events` contains two other numbers embedded in its bulleted text — "21" (moving to Pune) and "24" (leaving the corporate job). This raised the hypothesis that the model might have anchored to the wrong milestone (27 − 24 = 3, which numerically matches B's stated "about three years" exactly). However, **this same list, with the same two numbers, in the same order, appears identically in X's payload** — so this specific ambiguity source cannot explain why B failed and X did not; it is present equally in both. It is recorded here as a plausible mechanism for how "three" specifically was produced (27 − 24 = 3 is an exact match), but not as a explanation of the B-vs-X divergence, since X had access to the identical ambiguous list and did not make this error (see below for why X may not be strong counter-evidence either).

**Important caveat on X's response:** X's response does not state any computed duration at all — it says "since I was 21" without deriving "6 years" or any other number. **X did not demonstrate successful arithmetic; it demonstrated avoidance of the arithmetic step entirely.** This matters for classification below: the evidence shows X producing a *safe, ungrounded-number-free* answer, not a *correct, computed* one.

---

## Stage 2 — Classification

| Candidate | Supported by evidence? |
|---|---|
| **B1 — pure arithmetic/derived-fact failure** (facts clearly presented, model just computes wrong) | **Not fully supported as stated** — "clearly presented" does not hold for B, since the age value is unlabeled in that rendering. Cannot be ruled out as a *contributing* factor, since X never actually attempted the same computation. |
| **B2 — structured-context interpretation failure** (facts present, structured representation materially obscures their relationship) | **Best-supported by the verified evidence**, with an important caveat below |
| **B3 — other/insufficient evidence** | Not selected — a concrete, source-verified structural difference was found, not merely asserted |

**Classification: B2, qualified.** The verified facts support B2 as the best-available explanation: B's rendering genuinely omits the field label for `identity.age` (and every other top-level scalar in that classification group), which is a real, mechanistic difference from X's explicit labeling, and is a plausible reason the model treated "27" as less clearly tied to "her current age" than X's unambiguous `"identity.age":27`. **This is not proven beyond doubt**, because:
1. X's correct-seeming answer did not involve the same arithmetic step at all — it sidestepped deriving a number, so X is not strong positive evidence that labeling *enables* correct arithmetic, only that it may correlate with *safer* behavior (grounding without inventing a figure).
2. The specific wrong number ("three") has an exact alternative explanation (27−24=3, from a different, correctly-labeled-in-both-conditions milestone) that does not depend on the missing age label at all — meaning the *specific* wrong answer may not even be a consequence of the age-labeling gap, even if the age-labeling gap is real and could independently matter for other cases.

**Given both, the honest position is: a real, verified B-vs-X structural difference exists (missing scalar-field labels in B's classification-grouped rendering); it is the best-supported hypothesis for why B underperformed on this exact call; it is not proven to be the specific mechanism that produced "three years" specifically, since an equally-plausible, label-independent alternative (confusing the 21 vs. 24 milestone) exists and is present identically in both conditions.**

---

## Stage 3 — Minimal targeted test design (not executed)

**Only pursue this if the question "does explicit labeling of scalar identity fields change B's behavior on this exact scenario" is worth resolving.** Design, not run:

- **New isolated diagnostic provider** (matching the FR/X isolation pattern from Phases 7/10/13 — a new class, never modifying the shared `PersonaContextRenderer`): render the *same* B-selected field set as B currently does, but explicitly label every top-level scalar (e.g., `"Age: 27"` instead of a bare `"27"` line), holding every other variable identical (same fields, same skill, same message, same engine/persona version).
- **Corpus:** exactly the one scenario ("Been in this city long?", general_chat, variant-B-equivalent-but-labeled).
- **Minimum calls:** 1 (a second, immediate repeat could be added for determinism-of-response-tendency, but LLM generation is inherently stochastic, so a repeat would measure variance, not determinism — not required to answer the narrow labeling question).
- **What would support B2 specifically:** the labeled version answers with a correct or at least non-fabricated duration (e.g., matches X's safe pattern or correctly states ~6 years).
- **What would falsify B2 as the primary mechanism:** the labeled version still states an incorrect specific duration — this would point back toward B1 (an arithmetic/derivation limitation independent of labeling) or toward the 21-vs-24 milestone-confusion mechanism (which labeling would not fix, since that ambiguity lives in the list content, not the label).

**Not executed. Requires separate authorization per the standing instruction.**

---

## What is proven

- Every non-formatting variable between the B and X timeline calls was identical (facts, selection, engine, persona version, message text, absence of memory/profile/history).
- Both required facts (`identity.age`=27, the "moved at 21" life event) were genuinely present in both providers' output.
- A real, source-verified structural difference exists: B's renderer omits field labels for top-level scalar values within a classification group; X's raw JSON labels every field explicitly.
- `timeline/B`'s specific wrong answer ("three years") has an exact alternative numeric explanation (27−24=3) unrelated to the missing age label, and that alternative source is present identically in X's payload too.

## What remains unknown

- Whether the missing age label is the actual cause of `timeline/B`'s error, a contributing factor, or coincidental to it.
- Whether X's response reflects genuinely more reliable fact-grounding or simply a different, evasive generation strategy that happened not to fabricate a number this time.
- Whether this pattern (B mis-deriving a duration) is reproducible across other scenarios or model calls, or a single-instance observation.

## Reproducibility status

**Currently a single observed case (n=1).** No repeat call has been made in this phase (none was authorized), so this cannot yet be described as a reproducible pattern — only as the first confirmed instance of a present-fact/model-use discrepancy in this program's evidence base.

## Minimal follow-up experiment, if pursued

As specified in Stage 3: one new, isolated, explicitly-labeled diagnostic provider, tested on the single target scenario, minimum 1 real call. **Not executed in this phase.**

## Exact API-call count

**0.**

## Exact files modified

**None.** This phase produced one new file: `docs/Persona update POC/PHASE15_TIMELINE_B_ROOT_CAUSE.md`. No source file, test file, mapping, registry, resolver, or historical document was modified.

## Explicit confirmation

No production code, persona content, field description, resolver logic, mapping, threshold, or prompt text was modified in this phase. No historical Phase 10–14 artifact was modified. No API call was made. No follow-up experiment was executed.
