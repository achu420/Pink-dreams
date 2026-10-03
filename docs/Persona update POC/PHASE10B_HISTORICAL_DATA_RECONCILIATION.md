# Phase 10B — Historical Data Reconciliation

This document answers a narrower question than `PHASE10A_A_BASELINE_CORRECTION.md`: not "which conclusions are still valid" (already answered there), but **"which historical dataset, if any, can be reused as a clean control in the new FR/FS/SR/SS experiment design?"** No historical file is modified, rewritten, or deleted by this document.

---

## Classification

| Historical dataset | Classification | Reasoning |
|---|---|---|
| **Phase 6 A** (and Experiment 1/3's A) | **INVALID AS CONTROL** for any cell of the FR/FS/SR/SS matrix | Confirmed in `PHASE10A_A_BASELINE_CORRECTION.md`: real generations used `SIMRAN_PERSONA_CORE` (generic, fact-free), never the master document. Cannot stand in for FR (Full Raw) — it is not full, and its "raw" is prose, not a JSON serialization of biographical data. |
| **Phase 7 A** | **INVALID AS CONTROL**, same reasoning as above (identical mechanism, identical content, just a different scenario set) |
| **Phase 7 D** | **CLEAN CONTROL for FS (Full Structured)** | Verified this phase, independently of PHASE10A: `StructuredFullDocumentContextProvider` is a non-null provider, explicitly constructed and passed as `personaContextProvider`, whose `getPersonaContext()` output flows directly into `RepositoryContextAssembler`'s `personaContent` and then into the real generation request — traced through the same call path documented in `PHASE10B_CONTEXT_MATRIX.md`. Real data: 30 scenarios, 30 real OpenRouter calls, 100% success, already blind-scored. **Reusable as-is, zero new calls needed for this cell.** |
| **Phase 6 B/C** (and Experiment 1/3's B/C) | **CLEAN CONTROL for SS (Selective Structured), with a corpus-comparability caveat** | Verified: `ResolvedPersonaContextProvider` is genuinely wired the same way as D, confirmed non-null, confirmed its output reaches the real request. The mechanism is clean. The caveat is not about validity but about **comparability**: Phase 6's B/C data was collected on a broad, 149+79-scenario corpus, not the same 30-scenario fact-heavy corpus Phase 7 used for D. Directly comparing Phase 6 B's aggregate dim1 (4.03/5) against Phase 7 D's 96.7%-correct figure would be comparing different corpora and different scoring scales — informative directionally, not rigorously. **Usable with caveat**; a corpus-matched fresh run of B (and optionally C) is recommended for the primary comparisons, not because the old data is invalid, but because it isn't on the same scenarios. |
| **`SelectiveRawContextProvider` (X)** | **UNKNOWN — architecturally sound, empirically unverified** | See `PHASE10B_CONTEXT_MATRIX.md`'s "X's actual status" section. The class is correctly wired by source inspection; it has never been executed, dry-run or real. It cannot be classified as a clean control until a dry run actually passes. |
| **FR (Full Raw)** | **DOES NOT EXIST** | No historical dataset can be reused here under any caveat — this condition has never been actually sent to a model in this program. Confirmed by the same call-path trace used for every other row. |

---

## Historical token numbers — kept strictly separate

Three distinct numbers exist in the historical CSVs and must never be conflated, per the discipline established in `PHASE10A_A_BASELINE_CORRECTION.md` §A3 and repeated here because it is central to interpreting any reused data:

1. **Diagnostic persona-token estimate** (`persona_token_estimate` column) — computed by the harness's separate, unconnected `resolver.resolve(...)` logging call. For B/C/D this number is trustworthy (it reflects what those providers actually produce, since their real generation path uses the identical resolver/renderer call). **For A, this number is not trustworthy** — it reflects `resolveVariantA()`'s output, which was never sent.
2. **Actual provider payload tokens** — what the real, non-null provider actually returned as `personaContent`, before being wrapped into the system block. Not separately logged as a column, but reconstructable for B/C/D (their diagnostic estimate IS this number, per point 1) and reconstructable exactly for A (it is `SIMRAN_PERSONA_CORE`'s character length, ~988 tokens, since that fixed constant is what `core.content` returns).
3. **Real OpenRouter total input tokens** (`prompt_tokens` column) — always trustworthy for every variant, since it is reported directly by the API for whatever was actually sent, regardless of any wiring bug elsewhere. This is the only number that was never at risk from the variant-A discrepancy.

When reusing Phase 6/7 data in the new experiment's analysis, only numbers (2) and (3) may be used for A/FR-adjacent comparisons; number (1) must be explicitly excluded from any A-involving comparison and flagged if reused for B/C/D (where it happens to be trustworthy, but the reader should not assume that generalizes).

---

## Does a new formal correction document (beyond PHASE10A) need to be created?

**No — `PHASE10A_A_BASELINE_CORRECTION.md` already serves as the authoritative reconciliation document**, and this document is explicitly scoped as a narrower, control-reusability companion to it, not a replacement or a second independent reconciliation. Future documents that need to cite "what remains supported / what is withdrawn" should cite `PHASE10A_A_BASELINE_CORRECTION.md` §Step 5 directly; documents that need to cite "what data can I reuse as a control" should cite this document. Splitting these two concerns avoids the failure mode of re-deriving the same reconciliation slightly differently in multiple places.

**No historical CSV, score file, or Phase 6/7/8/9 document has been modified in the process of producing this document.**
