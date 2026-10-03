# Phase 27 — Independent Generalization Boundary: Stage 1 Audit

**Zero API calls made to produce this document**, except the zero-cost, network-free `interceptGeneration=true` provider-to-request check (real pipeline, no network calls). No historical Phase 10–26 artifact, production file, mapping, resolver, renderer, persona data, or engine logic was modified. **Stage 2 is NOT executed by this document and requires separate, explicit authorization.**

---

## Purpose

Determine whether the Phase 26 result (a statistically significant B-vs-LA difference on the Pune-timeline scenario, p≈0.00036) generalizes to the independent sister-age-gap scenario first established in Phase 22, using the same statistically rigorous framework (equal N=20/group, interleaved order, pre-registered Fisher's exact test) that Phase 26 used but Phase 22 did not.

## 1. Documents re-read

`PHASE23_DECISION_BOUNDARY.md`, `PHASE25_DECISION_AUDIT.md`, `PHASE26_CONTROLLED_LABEL_EFFECT_DESIGN.md`, `PHASE26_CONTROLLED_LABEL_EFFECT_RESULTS.md`, `PHASE22_GENERALIZATION_DESIGN.md`.

## 2. Scenario

**"Is there a big age gap between you and your sister in your family?"** — skill `about`, pure baseline mapping (no fix of any kind, matching Phase 22's original design exactly). Ground truth: Anjali (older sister) = 29, Simran (`identity.age`) = 27, correct derived gap = 2 years.

## 3. Re-verification of all required invariants (live, zero-cost)

All 11 checks re-run from scratch in a fresh `World()` (not merely assumed carried-over from Phase 22), via `phase27Stage1Audit()`, all PASS:

| # | Check | Result |
|---|---|---|
| 1 | B and LA select exactly the same fields | PASS |
| 2 | `identity.age` selected (required, baseline `about`, no fix) | PASS |
| 3 | `life_context.family` selected (native conditional, baseline description, no fix) | PASS |
| 4 | No mapping/registry fix applied (confirmed by construction) | PASS |
| 5 | No restricted field present | PASS |
| 6 | B and LA differ only in the intended `identity.age` scalar labeling (`27` → `Age: 27`, nothing else) | PASS |
| 7 | Anjali's age (29) present in both payloads, unaffected by labeling | PASS |
| 8 | History/memory/profile absent for both (fresh conversation + fresh `testUserId`) | PASS |
| 9 | Deterministic provider output (B/LA identical on repeat) | PASS |
| 10 | Provider output verbatim in the captured request, for both B and LA (via `interceptGeneration`, zero network calls) | PASS |

**No STOP condition was triggered.** The scenario, field selection, and providers are unchanged since Phase 22 — confirmed by direct re-verification, not by assumption. `AgeOnlyLabeledScalarDiagnosticProvider` and `ResolvedPersonaContextProvider` remain exactly as they were; no production, mapping, or persona file was touched between Phase 22 and this audit.

## 4. Hypotheses (pre-registered)

- **H1** — the LA-over-B effect generalizes to an independent derived-fact scenario (the sister-age-gap scenario shows a statistically significant LA>B difference, per the same Fisher's-exact-test framework used in Phase 26).
- **H2** — the Phase 26 result is substantially scenario-specific (the sister scenario does not show a significant difference, or shows one of materially smaller magnitude, consistent with Phase 22's original small-sample finding of only a 10-point gap).
- **H3** — the available evidence remains insufficient to distinguish H1 from H2 (an inconclusive result at the pre-registered thresholds).

## 5. Relationship to Phase 22

Phase 22 already tested this exact scenario with 10×B/10×LA (blocked order, not interleaved), finding B=8/10 (80%) and LA=9/10 (90%) — a 10-point gap, far smaller than Phase 26's 60-point gap on the Pune-timeline scenario, and not statistically tested at the time. **Phase 22's own data is historical reference only and is not merged into Phase 27's new sample** — per instruction, "do not combine Phase 22 and Phase 27 after seeing the results in a way that changes the pre-registered test." Phase 27's new 40-call sample (if authorized) will be analyzed entirely on its own terms, exactly as Phase 26 was.

One point of honest tension is disclosed here, before any new data exists: Phase 22's B rate (80%) was already high, leaving limited headroom for LA to show a large gap under the *original* n=10 sample — this is the same "ceiling effect" reasoning documented in Phase 22's own results (§10-11) and in Phase 17 for pure-recall scenarios. If Phase 27's new, larger, interleaved sample reproduces a high B rate again, a small/insignificant gap should be interpreted as consistent with H2 (scenario-specific, likely due to lower task difficulty for B), not as disproving any effect that exists specifically under harder conditions (like the Pune-timeline scenario). This interpretive framing is stated now, before execution, so it cannot be adjusted after seeing results.

## 6. Proposed Stage 2 design (for separate authorization — not executed here)

- **Conditions**: B vs LA only (no L), matching Phase 26 and the instruction's explicit exclusion.
- **Sample size**: 20 per condition, 40 total — chosen for direct comparability with Phase 26, not derived from a fresh power calculation specific to this scenario (disclosed as a comparability choice, not a scenario-specific statistical justification).
- **Execution order**: interleaved (B, LA, B, LA, ...), frozen before the first call, matching Phase 26's methodology exactly.
- **Primary scoring**: CORRECT / WRONG / AMBIGUOUS on the arithmetic question (must correctly identify Anjali, correctly use the two ages, correctly state a 2-year gap or unambiguous equivalent).
- **Secondary, non-collapsed axis**: entity-binding (correct sibling / wrong sibling / ambiguous), tracked independently and never allowed to silently alter the arithmetic score — matching the two-axis discipline established in Phase 26's design for hometown/city contradictions.
- **Statistical plan**: identical to Phase 26 — two-sided Fisher's exact test, α=0.05, on the CORRECT vs. NOT-CORRECT (WRONG+AMBIGUOUS) 2×2 table.
- **Interpretation rule** (pre-registered, per instruction):
  - p<0.05 and LA>B → evidence supporting generalization (H1).
  - p≥0.05 with a small/insubstantial difference → evidence against a clear generalization signal (H2).
  - p≥0.05 with a material difference → INCONCLUSIVE (H3).
  - Significant B>LA → evidence against the hypothesized direction.
- **24/27 observation**: tracked identically to every prior phase — absence is never treated as disproof of internal use.

## 7. What Stage 2 would NOT establish, regardless of outcome (pre-registered)

- A universal labeling benefit across scenarios, personas, or skills.
- Causality in any context.
- Effect size generalization beyond the two scenarios tested (Pune timeline, sister age-gap).
- Resolution of the 27−24 alternative.
- Any comparison of LA's benefit versus L's (L is excluded from this design, per instruction, exactly as in Phase 26).
- Production suitability of any labeling change.

---

## Result: is the sister scenario still clean, and is the 40-call design valid?

**Yes to both.** All 11 required invariants were re-verified live and passed. The 40-call, interleaved, B-vs-LA design mirrors Phase 26's methodology precisely enough to support a direct, comparable statistical test, with the one disclosed caveat (§5) that this scenario's historically higher B baseline may limit headroom for a large effect, independent of whether a real labeling effect exists.

**Stage 2 is not executed by this document.** Per instruction, this phase stops here for separate authorization.

---

## Exact API-call count

**0.**

## Files created

- `docs/Persona update POC/PHASE27_GENERALIZATION_AUDIT.md` (this document)

## Files modified

- `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` — added `phase27Stage1Audit()` only (additive, zero-cost, no API calls).

## Not modified

Any Phase 10–26 document, any historical CSV, any production mapping/resolver/renderer/persona/engine file, any B/LA/L implementation file.
