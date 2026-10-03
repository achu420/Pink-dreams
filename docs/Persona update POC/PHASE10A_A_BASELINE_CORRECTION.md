# Phase 10A — Critical Evidence Reconciliation: Variant-A Baseline

**Zero API calls were made to produce this document.** Everything below was established by reading source code and by exact reconstruction from static, unmodified string constants — never by running new generations. No historical CSV, score, or prior analysis document has been altered.

---

## Executive Summary

**Variant "A" never sent the detailed `simran-master.md` persona document to the model, in any experiment, at any point in this program.** Due to a `?.takeIf { it != "A" }` guard in `TestChatService.buildEngineFor()`, variant "A" always received `personaContextProvider = null`, which makes `RepositoryContextAssembler` fall back to `core.content` — the **Persona Core Version's** plain-text field, seeded from `BaselineConfiguration.SIMRAN_PERSONA_CORE`, a short (3,954-character, ~988-token) generic personality/behavior sketch with **zero biographical facts** (no Nashik, no Ritu, no family, no occupation, nothing from the master document at all).

Meanwhile, every benchmark harness's diagnostic logging call — `resolver.resolve(personaId, personaCoreVersionId, skillKey, userMessage, "A")` inside `sendTurn()` — **does** correctly invoke `PersonaContextResolver.resolveVariantA()`, which reads `PersonaMasterRepository.contentJson.toString()` (the full detailed document). This is a completely separate code path from what was actually sent to the model, called only for CSV logging.

**Net effect:** every `persona_token_estimate`, and every narrative claim of the form "variant A received the full raw document," describes content that was **never actually included in a single real generation call** across Experiments 1, 3, Phase 6, or Phase 7's variant-A executions. The real generations for "A" instead used a short, fact-free character sketch — a materially different and much simpler condition than the one every prior analysis document believed it was measuring.

---

## Exact Source Evidence

| # | File : line(s) | What it shows |
|---|---|---|
| 1 | `src/main/kotlin/com/pinkdreams/testchat/TestChatService.kt`, the `personaContextProvider = snapshot.personaContextVariant?.takeIf { it != "A" }?.let { ... }` expression inside `buildEngineFor()` | For `variant == "A"`, `takeIf` returns `null`, so the entire `?.let { }` chain evaluates to `null`. `personaContextProvider` is `null` for every variant-A conversation ever created by this harness. |
| 2 | `src/main/kotlin/com/pinkdreams/chat/context/RepositoryContextAssembler.kt:71-75` | `val personaContent = if (personaContextProvider != null) { personaContextProvider.getPersonaContext(...) } else { core.content }` — confirms the null-provider fallback path used by variant A. |
| 3 | `src/main/kotlin/com/pinkdreams/chat/context/RepositoryContextAssembler.kt:167-172`, `engineAndPersonaContent()` | The exact, fully static concatenation format of the actual system block: `"CONVERSATION ENGINE — UNIVERSAL RULES:\n" + engineContent + "\n\nPERSONA CORE:\n" + personaCoreContent`. |
| 4 | `src/main/kotlin/com/pinkdreams/persistence/repositories/PersonaCoreVersionRepository.kt:17-21` | `PersonaCoreVersion.content: String` — a plain-text field, structurally and physically separate from `PersonaMasterRepository`'s `contentJson: JsonObject` (a different table entirely: `PersonaCoreVersions` vs. `PersonaMasterDocuments`). |
| 5 | `src/main/kotlin/com/pinkdreams/baseline/BaselineSeeder.kt:132-145`, `seedPersonaCore()` | `val canonical = BaselineConfiguration.SIMRAN_PERSONA_CORE` is the exact content written into the Persona Core Version's `content` field for Simran — confirmed by direct read, not inference. |
| 6 | `src/main/kotlin/com/pinkdreams/baseline/BaselineConfiguration.kt`, `SIMRAN_PERSONA_CORE` constant | 3,954 characters (~988-token estimate). Sections: Identity, Personality, Communication style, Reciprocity, Continuity, Relationships, Emotional behavior, Independence, Boundaries, Character consistency. **No biographical content of any kind** — no name spelled beyond "Simran," no age, no city, no family, no occupation, no friends. Read in full this phase; unchanged since this program began (never edited in any prior phase). |
| 7 | `src/main/kotlin/com/pinkdreams/chat/personacontext/PersonaContextResolver.kt`, `resolveVariantA()` | `val rawText = masterDoc?.contentJson?.toString() ?: ""` — this method DOES correctly read the full detailed document. It is only ever invoked from the harness's diagnostic `resolver.resolve(...)` call (see #8), never from the actual generation path (see #1-3). |
| 8 | `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt`, `World.sendTurn()`: `val projection = resolver.resolve(personaId, personaCoreVersionId, skillKey, userMessage, variant)` | This line calls the resolver directly, purely to populate `ExecutionRecord.includedFieldIds`/`personaTokenEstimate` for the CSV. It runs BEFORE `service.sendMessage(...)` (the real generation call) and its result is **never passed into** the generation request — the two calls are entirely independent. |
| 9 | `src/main/kotlin/com/pinkdreams/chat/ChatEngineFactory.kt:118, 325-326` | `Dependencies.personaContextProvider` defaults to `null` and is passed straight through to `RepositoryContextAssembler` with no other default/override anywhere in the factory — confirms there is no other injection point that could have restored the detailed document for variant A. |
| 10 | `src/main/kotlin/com/pinkdreams/llm/OpenRouterLlmClient.kt` | Contains no request-payload logging of any kind. |
| 11 | Repo-wide search for `RawExchange`/`rawExchange`/`raw_exchange` | No matches — no raw-exchange capture system exists in this codebase; nothing persisted the literal historical request payloads. |

## A1 — Diagnostic persona content (what the harness recorded)

The full `simran-master.md` document, via `resolveVariantA()` → `PersonaMasterRepository.contentJson.toString()`. This is what every `persona_token_estimate` column (4,542, constant across every A row in every CSV) and every `included_field_ids`/`excluded_field_ids` = empty-list convention for A actually reflects.

## A2 — Actual model persona content (what was actually sent)

`BaselineConfiguration.SIMRAN_PERSONA_CORE` — the short, generic, fact-free personality sketch — via the null-provider fallback to `core.content`. This is fully, byte-for-byte reconstructable right now because both `SIMRAN_PERSONA_CORE` and `CONVERSATION_ENGINE` are static constants never modified across any phase of this program; the exact persona-portion text of every historical variant-A system block is recoverable exactly, even though the full request (which also includes the dynamic profile/memory/history/current-message blocks) is not separately logged anywhere.

## A3 — What `persona_token_estimate` actually measured

For variant A specifically: the token length of a document that was **never included in the generation request**. It is not "wrong" in the sense of a bug in the estimate formula (`length/4`, same formula used for B/C, where it correctly reflects what was sent) — it is simply computed from the wrong source document for this one variant, because the diagnostic call and the generation call diverge only for `"A"`.

**Corroborating (not primary) evidence:** Phase 6's own token table shows A's mean `persona_token_estimate` (4,542) as *larger than A's own mean total prompt tokens* (2,062 real, includes engine+persona+profile+memory+history+message) — an internally impossible relationship if the estimate reflected what was actually sent, since a single component cannot exceed the observed total. This inconsistency was present in the recorded data throughout Phase 6 and should have been caught as a sanity-check failure at the time; it was not.

## A4 — Historical scope: exactly which experiments are affected

| Experiment/Phase | Used variant A for real generation? | Affected? |
|---|---|---|
| Experiment 1 | Yes | **Yes** |
| Experiment 2 | Not confirmed to include a variant-A arm in this document's available records (Experiment 2's corpus per prior session summaries focused on unknown-fact/cross-skill/multi-turn scenarios; not re-verified line-by-line this phase) | UNKNOWN — not re-verified; flagged, not assumed |
| Experiment 3 | Yes | **Yes** |
| Phase 6 | Yes (core A/B/C comparison) | **Yes** |
| Phase 7 | Yes (Candidate F: A vs D) | **Yes — this is the most consequential instance** |
| Phase 8 (TC-6) | No — B/C only | **Not affected** |
| Phase 8 (Candidate E) | No — variant B only, by explicit design | **Not affected** |
| Phase 9 | No new data; synthesizes Phases 6/7 conclusions about A | **Affected by inheritance** — its A-dependent conclusions are only as sound as Phase 6/7's, see reclassification below |

---

## Step 3 outcome: can the historical prompt be reconstructed?

**Partially, and precisely where it matters most.** The exact persona-portion of every historical variant-A system block is reconstructable byte-for-byte (see A2) because it is a static constant. The full request (adding the dynamic profile/memory/recent-history/current-message blocks) cannot be reconstructed byte-for-byte for any specific historical execution, because those blocks were not separately captured in any retained artifact (confirmed: no raw-exchange table, no request logging in `OpenRouterLlmClient`, and `HybridLlmClient`'s `request.context.blocks` were read at generation time but never persisted). This does not weaken the central finding — the persona-content portion is exactly the part in question, and it is fully known.

---

## Step 4: Intended A vs. Actual A

| Property | Historical **intended** A | Historical **actual** A |
|---|---|---|
| Persona provider | `ResolvedPersonaContextProvider`/`PersonaContextResolver.resolveVariantA()` (full document, raw JSON) | `null` → `RepositoryContextAssembler`'s `core.content` fallback |
| Persona source | `PersonaMasterRepository.contentJson` (the seeded `simran-master.md` JSON) | `PersonaCoreVersionRepository.content` = `BaselineConfiguration.SIMRAN_PERSONA_CORE` |
| Biographical facts | All of them (identity, life_context, family, friends, worldview, etc.) | **None** — personality/behavior guidance only |
| Occupation | Present (`life_context.occupation`) | **Absent** |
| Family | Present (`life_context.family`, 4 named members) | **Absent** |
| Flatmate | Present (`life_context.friends`, Ritu/Prashant/Neha) | **Absent** |
| Persona token estimate (as logged) | 4,542 (this IS what was logged) | N/A — the log reflects the intended column, not actual |
| Actual prompt tokens (persona portion, reconstructed) | N/A | ~988 (character-based estimate of `SIMRAN_PERSONA_CORE` alone) |
| Actual total prompt tokens (real, observed, Phase 6 mean) | N/A (never occurred) | 2,062 (real, OpenRouter-reported, includes engine+persona+profile+memory+history+message) |

---

## Step 5: Reclassification of Prior Findings

### Experiment 3

| Finding | Classification | Reasoning |
|---|---|---|
| A occupation = 0/51 | **PARTIALLY VALID** | The 0% result itself is accurate for what A actually was (a condition with zero occupation information in context) — of course it can't retrieve a fact it was never given. The finding survives as a true statement about the (accidentally generic) A condition, but the framing "A fails to retrieve occupation from its full document" is **INVALID** — there was no document to retrieve from. |
| B/C occupation results | **NOT AFFECTED** | B/C always used explicit, correctly-wired providers built on the real master document; unaffected by the A-specific bug. |
| A unknown-fact fabrication findings | **PARTIALLY VALID** | Same reasoning as occupation — A's fabrication of specific facts is now trivially explained (no facts were ever supplied), not evidence of "losing" a fact from a big context. |
| A restricted-safety results | **NOT AFFECTED** | A was never expected to have restricted-field access in the first place (variant A's design intent was full-document, unfiltered, which includes restricted content by definition in the intended version — but since actual A never even had `intimate_anatomy`/`adult_intimacy` in context, there was structurally zero leakage risk from A specifically; this doesn't affect the B/C restricted-field safety findings, which were correctly measured on their own real providers). |
| A token/cost comparison | **INVALID** (persona-token framing) / **VALID** (total-token framing) | See the general token reclassification below. |
| A latency comparison | **VALID** | Latency reflects real generation calls that really happened with real (if smaller-than-assumed) content; not dependent on what the content was. |
| Resolver determinism | **NOT AFFECTED** | Concerns B/C's `PersonaContextResolver` invocations only; A never invokes selective logic. |

### Phase 6

| Finding | Classification | Reasoning |
|---|---|---|
| A factuality score (3.14/5) | **VALID as a measurement, INVALID as previously interpreted** | This is a real blind-scored result of real generations. It is valid data. Its interpretation ("full raw document causes poor factuality") is invalid — the actual condition measured was "generic fact-free persona," a much simpler and less informative comparison than intended. |
| A character score (3.81/5) | **VALID as a measurement, UNKNOWN as interpreted** | Character/personality-consistency scoring may be less sensitive to missing biographical facts than factuality scoring is — not clearly invalidated, but also not verified independent of this discrepancy. |
| A continuity score (4.22/5) | **VALID as a measurement, UNKNOWN as interpreted** | Same caveat as character score. |
| A hard-failure rate (29.1%) | **PARTIALLY VALID** | The specific hard-failure tags driven by missing facts (FACTUAL_CONTRADICTION, FACTUAL_FABRICATION, PERSONA_FACT_OMISSION) are now expected outcomes of a fact-free condition, not evidence of a "large context degrades retrieval" mechanism. |
| A occupation-failure count (8/234) | **PARTIALLY VALID** | Real count of a real, now differently-explained phenomenon: A never had the occupation fact, full stop. |
| A persona-drift classification (5/8 concentrated in A) | **UNKNOWN — new hypothesis surfaces** | A newly plausible explanation is now available: a model with very little specific persona material to draw on for in-character deflection of sensitive questions may be more likely to break character. This was not previously considered and is not verified either way. |
| A/B/C comparison (the Phase 6 headline finding) | **INVALID as "raw-document vs. selective" comparison** | The comparison that was actually run was "generic fact-free persona" vs. "resolver-selected specific facts," not "full document, differently formatted" vs. "selected subset." |
| Token/cost findings | **PARTIALLY VALID — see below** | |
| Safety findings (restricted-field leakage) | **NOT AFFECTED** | Concern B/C only. |

**Token/cost reclassification (applies to Experiment 3, Phase 6, and any place this framing was repeated):** The claims "B cuts persona tokens 94.9% vs. A" and "C cuts persona tokens 91.4% vs. A" are **INVALID** — they compare B/C's real persona-token figures against A's *diagnostic* (never-sent) figure. The claims "B/C reduce **total** input tokens by 18.6%/12.7% vs. A" are **VALID** — these are built from real, OpenRouter-reported `total_tokens` for all three variants, which correctly reflect what was actually sent and received regardless of this discrepancy. The efficiency story survives; the "persona-specific" framing of it does not.

### Phase 7

| Finding | Classification | Reasoning |
|---|---|---|
| A vs D "structured/full-document" finding | **INVALID as a controlled comparison** | This is the most consequential correction. D genuinely used the full detailed document, structured. A (real) used the short generic sketch, raw. The comparison silently varied THREE things at once (facts-present-or-not, volume, format), not one (format alone), contradicting the explicit design intent documented in `PHASE7_INVESTIGATION.md` §3 ("A vs. D... isolates structure from volume"). |
| "Structure rather than volume" conclusion | **INVALID as stated; UNKNOWN as an open question** | The conclusion that A's gap is "dominated by context structure/formatting, not context volume" (`PHASE6_TUNING_CANDIDATES.md` TC-1, refined in `PHASE7_INVESTIGATION.md` §3a) cannot be supported by the A-vs-D comparison, because A never had the volume/facts to begin with. Whether structure, volume, or simply fact-presence explains the gap is now genuinely **UNKNOWN** again. |
| D's 0%→96.7% grounding result | **VALID, but reinterpreted** | D's own performance is a real, correctly-generated, correctly-scored result: presenting the full document, structured, produces excellent grounding. This stands on its own. What it does NOT establish is a *contrast* with A, since A was never a genuine "full document, raw" condition in reality. |
| D's 2.2x token result | **VALID** | This compares D's real tokens against A's real tokens — both are real, observed OpenRouter figures. This finding is unaffected: D really did cost more real tokens than A's real (smaller-than-assumed) request. |
| Any conclusion that depended on "A contains the full detailed persona" | **INVALID** | By construction, none of them do. |

### Phase 8

| Finding | Classification | Reasoning |
|---|---|---|
| TC-6 (all conclusions) | **NOT AFFECTED** | TC-6 used variants B and C exclusively; never touched variant A. |
| Candidate E (all conclusions) | **NOT AFFECTED** | Candidate E was explicitly designed and run on variant B only, precisely to isolate the engine-prompt effect from variant differences — this discrepancy has no bearing on it. |

### Phase 9

| Statement | Still supported? |
|---|---|
| "A's factuality deficit is driven primarily by raw-JSON structure, not context volume" | **NO LONGER SUPPORTED.** This statement is withdrawn as a conclusion. The evidence it rested on (Phase 7's A-vs-D comparison) does not isolate structure from volume — it does not isolate anything cleanly, because variant A's actual content was never the intended full document at all. |
| "B/C's quality advantage over A may be [attributable to] selection vs. structure" (the open question Phase 9 posed) | **NOT YET ANSWERABLE, AND THE QUESTION ITSELF NEEDS RESTATING.** Phase 9 treated this as an open empirical question with three established reference points (A, D, B/C) and one missing cell. In fact there are effectively only **two** established reference points now (D, and B/C — both of which used real, correctly-wired providers), and **zero** clean data points for "full volume, raw format" or "no facts, raw format" as previously defined. The 2×2 matrix Phase 9 described must be redrawn; see "Corrected Experimental Baseline" below. |
| Every other Phase 9 finding not directly about variant A (TC-6 root cause, Candidate D's friendship-occupation grounding, resolver determinism, restricted-field safety, personal-narrative fabrication parity, flatmate-identity fabrication parity) | **NOT AFFECTED**, with one nuance below. |

**Important nuance on the "even split" findings (flatmate-identity and personal-narrative fabrication, previously read as "occurs equally across A/B/C, therefore not a resolver defect"):** this logic **still holds** for the conclusion it was used to support (ruling out a resolver/mapping defect), because B/C's fabrication rates are unaffected by this discrepancy and remain the real, informative data points. What changes is that **A's contribution to that comparison is now much less informative than previously treated**: A fabricating Ritu's identity is unsurprising (A's real context never contained Ritu at all), whereas B/C fabricating Ritu's identity despite the real fact being available in context is the substantive, still-standing, still-concerning finding. The "even 15/13/12 split" should no longer be read as "three equally-informative measurements of the same phenomenon" — it is better read as "two informative measurements (B, C) plus one trivially-explained data point (A)."

---

## Unknowns Created By This Discrepancy

- **The actual effect of full-context raw JSON is now untested.** No experiment in this program has ever presented the complete, detailed persona document in raw/unstructured form to a real generation call. What was called "variant A" throughout was not this.
- **The actual effect of structure (holding real content constant) is now untested**, for the same reason — Phase 7's D-vs-A comparison does not hold content constant.
- **The actual effect of selective retrieval, isolated from structure, remains untested** (this was already the open question Phase 9 identified; it is unaffected by this discrepancy, but it can no longer be partially triangulated using A/D as reference points the way Phase 9's framing assumed).
- **Selection vs. structure's respective contributions to quality:** cannot currently be decomposed at all with existing data. The only clean, trustworthy data points remaining are: D (full content, structured) and B/C (selected content, structured) — both structured. There is no existing raw-format data point with real biographical content in it, at any selection level.

---

## Corrected Experimental Baseline

A genuinely useful "variant A" going forward should be: **the full detailed persona document, rendered raw/unstructured, and actually passed to the model via an explicit, non-null `PersonaContextProvider`** — i.e., a provider built the same way `StructuredFullDocumentContextProvider` was for D, but serializing to raw JSON instead of calling `PersonaContextRenderer`. This would, for the first time, give this program a true "full + raw" data point that is real, not diagnostic-only. Concretely, this corrected condition is **structurally identical to a full-field version of `SelectiveRawContextProvider`** — the difference being that it should select *all* fields, not the resolver's variant-B-selected subset.

**This document does not implement or test that provider.** Per the instruction, this phase stops after the correction is complete. Designing and running the corrected experiment (and, separately, deciding how the still-live TC-6 and the still-parked Experiment 1 relate to this corrected baseline) is explicitly deferred to the next authorized phase.

---

## Confirmation

**Zero new OpenRouter API calls were made in the course of producing this document.** Every claim above was established by: (a) reading source code (`TestChatService.kt`, `RepositoryContextAssembler.kt`, `PersonaCoreVersionRepository.kt`, `BaselineSeeder.kt`, `BaselineConfiguration.kt`, `PersonaContextResolver.kt`, `ChatEngineFactory.kt`, `OpenRouterLlmClient.kt`, `PersonaContextRealBenchmarkTest.kt`), (b) exact character-counting of static, unmodified string constants, and (c) cross-checking those against already-existing, previously-recorded CSV/analysis figures. No historical CSV, score file, or Phase 6/7/8 document was modified, rewritten, or deleted.

## Files Created / Modified

- **Created:** `docs/Persona update POC/PHASE10A_A_BASELINE_CORRECTION.md` (this document).
- **Modified:** none. `SelectiveRawContextProvider.kt` and the `TestChatService.kt` "X" branch created earlier in this Phase-10 session remain as previously written (unused, not invoked, no API calls made against them) — they are unaffected by this finding and require no changes themselves, since Experiment 1 was already paused before execution.
- **Not modified:** all Phase 6/7/8 CSVs, analysis documents, and tuning decisions remain exactly as originally written.
