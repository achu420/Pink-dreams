# Phase 9 — Post-Phase-8 Evidence Synthesis

Phases 6, 7, and 8 are treated as frozen. Nothing here modifies, re-scores, or reinterprets their raw data — this document only re-reads and re-combines already-recorded findings, cross-checked against current source where cited.

---

## 1. Full-document A vs. selective retrieval B/C — separating volume, structure, selection, and format

The evidence spans four logically distinct conditions, of which **three have been run** and **one has never been tested**:

| | Raw/unstructured format | Structured (headed-markdown) format |
|---|---|---|
| **Full volume** (all fields) | **A** — tested extensively (Phases 3, 6). Poor grounding (dim1=3.14/5, 0% on the Phase 7 fact-heavy occupation-adjacent set). | **D** — tested once (Phase 7). 96.7% correct on the same fact-heavy set, at ~2.2x A's real token cost. |
| **Selected volume** (skill-relevant fields only) | **Never tested.** | **B/C** — tested extensively (Phases 3, 6). Comparable-or-better quality than A, 91–95% less persona-token load. |

**What this establishes (OBSERVED):**
- Moving from A to D (volume held constant, format changed) produced the largest single quality jump measured in this entire program (0%→96.7% on the tested subset). This isolates **format/structure** as a real, independently-verified causal factor, not merely a hypothesis.
- Moving from A to B/C (volume reduced AND format changed simultaneously) also produced a large quality jump, plus a large efficiency gain.

**What this does NOT establish (the actual gap):**
- Whether B/C's quality advantage over A is attributable to selection specifically, or whether it is entirely inherited from the same structural/formatting change that made D work, with selection contributing efficiency but zero *additional* quality on top of structure. **The untested cell (selected volume + raw format) is the only condition that could isolate this.** Without it, "selective retrieval is good for quality" and "selective retrieval is good for efficiency" are both true statements that this program has *conflated* into one finding, when the evidence actually supports the efficiency claim more directly than the additional-quality-beyond-structure claim.
- This is not a criticism of Phase 6's methodology — Phase 6 was never designed to separate these two variables, and Phase 7 only added the A-vs-D comparison after the fact. It is simply the honest state of the evidence now that all three phases are read together.

## 2. Occupation retrieval — reconciled

Three separate measurements exist and they agree once compared on like terms:
1. **Experiment 3 (literal keyword match):** A=0%, B/C=25–66% depending on skill (undercounting paraphrases).
2. **Phase 7 semantic reclassification (Candidate C investigation, no new API calls):** re-reading the same 18 raw `general_chat` responses by hand, A=0% (unchanged — every A response was either an explicit wrong claim or a complete non-work deflection, never a paraphrased-correct answer), B=100%, C=83–100%.
3. **Phase 6 blinded factuality, TC-2-corrected count:** true Simran-own-occupation-identity contradictions: A=8/234, B=0/228, C=0/222.
4. **Phase 7 structured-full-document (D):** occupation-adjacent facts (e.g., "refresh my memory on where you're based") retrieved correctly in the large majority of D's 30-scenario fact-heavy set.

**Reconciliation:** the keyword-matching concern was real but **one-directional** — it undercounted B/C, never inflated A. A's occupation-retrieval failure is confirmed by all four measurements and is not a scoring artifact. Measurement (4) further confirms this is a structure-driven failure, consistent with finding #1/#3 above, not something specific to occupation as a fact category. **No new accuracy estimate is proposed here** — the four existing numbers are simply shown to agree once read on comparable terms.

## 3. Conditional field descriptions (TC-6)

The evidence justifies **refining the experiment**, not abandoning the idea and not leaving it fully unresolved. Specifically:
- The mechanism (enriched description text changes conditional-field inclusion) is confirmed by **direct code execution**, not inference — 6 of 8 flip probes worked exactly as predicted, with zero over-triggering and full determinism.
- The failure (2 of 8) has a fully-understood, narrow cause: one missing colloquial synonym ("mom" vs. "mother"), not a flaw in the scoring formula, the threshold, or the resolver.
- This is the single most precisely-diagnosed finding in the entire program (see Evidence Matrix note). Treating it as "eliminated" would discard a mechanism that is 75% empirically confirmed to work exactly as designed on the first attempt.
- Whether it is the **highest-value** next experiment (vs. the structure/selection question in §1) is addressed in `PHASE9_NEXT_EXPERIMENTS.md` — this section only concludes that *some* refined version of it remains scientifically live, not that it should be run next.

## 4. Personal-narrative fabrication (Candidate E)

Reconciling the full evidence set:
- Fabrication of specific, confident, unestablished autobiographical detail (first phone, scars, ice cream, childhood friend, earliest memory) occurs **at statistically indistinguishable rates across A, B, and C** (Phase 6) — this was already strong evidence it's not a resolver/retrieval defect.
- Candidate E tested one specific intervention: a more detailed restatement of anti-fabrication guidance that was, in substance, already present in the engine prompt. It showed **zero measured benefit and mild evidence of harm** (2/6 pairs more elaborate, one new contradiction).
- Candidate E's clean side-results (zero over-refusal, zero roleplay degradation) prove the *mechanism of engine-prompt editing itself* is safe to test — it doesn't break anything else. **This is evidence about the delivery mechanism, not about whether all possible engine-level content could help.**

**Is this realistically solvable through prompt wording alone?** The honest answer is **UNKNOWN, not "no."** Candidate E tested a narrow hypothesis: that *restating an existing preference more specifically, as an abstract instruction,* changes behavior. It failed. It did not test:
- A structurally different delivery (e.g., worked examples/demonstrations of the desired behavior embedded in the prompt, rather than an abstract rule — a "show, don't tell" approach, mechanistically distinct from another instruction sentence).
- Whether the failure is specific to *this exact question class* (playful, flirty deflection questions like "first kiss," which the model may treat as an invitation to charm rather than a factual query) versus more neutral narrative questions.
- Any non-prompt mechanism (e.g., output-side detection/regeneration), which is out of scope for a prompt/engine experiment anyway.

Per the explicit constraint, **no new generic-wording variant is proposed** — that would be the barred "retry with different wording" pattern. Whether a mechanistically distinct approach (e.g., in-context examples) is worth testing is evaluated on its own merits in `PHASE9_NEXT_EXPERIMENTS.md`, not assumed impossible.

## 5. Childhood-friend / flatmate fabrication — separated

These are **two different findings that happen to look similar on the surface** (an invented name where a real person should be), and the evidence supports treating them differently:

| | Childhood best friend | Flatmate (Ritu) |
|---|---|---|
| Is there existing canon? | **No** — `PersonaContextPocSeed.kt`'s `life_context.friends` array contains only three entries (Ritu, Prashant, Neha), all explicitly current-era; no field, positive or negative, addresses pre-college friendships. Confirmed by direct source read (again, this phase). | **Yes** — Ritu has a name, occupation (software engineer), a meeting story (college), and a defining detail (cat) that recur consistently across the correct responses in every experiment. |
| Is canon ambiguous? | No — it's simply silent/absent, not contradictory or unclear. | No — it's specific and unambiguous. |
| Is the model contradicting an established fact, or inventing into a gap? | **Inventing into a genuine gap.** | **Contradicting an established fact.** |
| What kind of fix (if any) is possible? | Any fix requires first deciding, as a matter of persona authorship, whether Simran canonically *has no* documented childhood friends (a new creative decision) or *does* have one that should be written into the document (an entirely new content-authoring task, not a bug fix). **This is explicitly a PRODUCT/AUTHORING decision, not an engineering one**, and this document does not make it. | This is squarely **model behavior** — no plausible data or mapping change explains an even 15/13/12 split across three conditions that differ in exactly what's selected. A fix, if one exists, would have to change how reliably the model reproduces an already-supplied specific fact — the same open question as findings #1/#8, not a canon question at all. |

## 6. Resolver determinism and restricted-field safety

**Treated as established, per instruction, with no new evidence introduced against them.** 0/165 combined determinism checks (Phase 3: 90, Phase 6: 75) showed variance; 0 leakage across every authorized/unauthorized restricted-field check run in Phases 3 and 6. Phase 7/8 did not touch the resolver's selection or gating logic at all (Phase 7's variant D and Phase 8's TC-6/Candidate E experiments were built specifically to avoid touching `PersonaContextResolver.kt`), so there is no new evidence either confirming or contradicting these findings beyond what Phases 3/6 already established — they are carried forward unchanged.

---

## Approaches already tested and not supported

Only approaches that were **actually run as an isolated experiment** are listed. A failed specific implementation is not treated as proof that its entire category is impossible.

1. **Raw/unstructured full-document context (variant A).** What was established: presenting the entire persona document as compact, unlabeled JSON produces materially worse factual grounding than either selecting a subset of fields or presenting the same full document with headed-markdown structure. What was NOT established: that giving the model "a lot of information" is inherently harmful — Phase 7's variant D disproves that reading. The specific thing not supported is *this exact representation* (raw JSON dump), not "large context" as a category.

2. **Candidate E's specific anti-fabrication paragraph (Phase 8).** What was established: restating existing anti-fabrication guidance in a more specific, abstract-instruction form did not reduce confident autobiographical fabrication in a clean 6-pair blinded test. What was NOT established: that no engine-level wording of any kind could ever help, or that this class of fabrication is unfixable in principle — only that this one, specific, same-mechanism (abstract preference statement) intervention didn't work.

3. **The current conditional-field description mechanism (as originally seeded, before TC-6).** What was established: with `description` hardcoded to the field's own ID, indirect phrasings are silently excluded from B/C's context. This IS supported as a real, confirmed defect — it is not something ruled out; TC-6's Stage 1 directly demonstrated the fix works for 75% of tested phrasings. What was NOT fully established: whether a complete synonym-aware description fixes the remainder and translates into measured response-quality improvement (Stage 2 was never reached).

**No approach in this list should be read as "selective retrieval doesn't work," "structured rendering doesn't matter," or "engine-prompt changes can never help."** Each finding above is scoped exactly as narrowly as its actual experiment.

---

## Step 6 — Structure vs. retrieval: what remains open

Given Phase 7's A-vs-D result, the six candidate explanations for "why does B/C look good" reduce as follows:

- **A. Selection** — contributes confirmed **efficiency** (91–95% token reduction); contribution to **quality specifically** (beyond what structure alone provides) is **UNKNOWN** — this is the central open question from §1.
- **B. Representation/structure** — confirmed to contribute the dominant share of the *quality* effect (A→D isolates this cleanly).
- **C. Field-description quality** — confirmed to matter for *which* fields get selected at all (TC-6), a prerequisite for selection to work correctly, but orthogonal to the structure-vs-selection question in §1.
- **D. Skill-specific context assembly** — no isolated evidence either way; not tested as its own variable in any phase.
- **E. Prompt grounding (engine/skill prompt wording)** — Candidate E's null result is the only direct evidence here, and it's negative for the one mechanism tested.
- **F. Model behavior** — the best-supported explanation for the fabrication findings (#5, #6, #8) specifically, and a contributing (not sole) explanation for #1/#2/#3 (the model retrieves facts less reliably from unstructured context, which is itself a fact about model behavior, not a fixed law of the resolver).

**The evidence does not currently support preferring B/C over D (or vice versa) on quality grounds alone** — it supports preferring B/C on **efficiency** grounds, which is a different, narrower, and already well-established claim. Conflating "more efficient" with "higher quality" is exactly the trap Step 6 warned against, and this document does not repeat it.
