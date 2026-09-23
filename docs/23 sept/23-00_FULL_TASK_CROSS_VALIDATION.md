# IMAGE PIPELINE — FULL TASK CROSS-VALIDATION

**Date:** 2026-09-23  
**Branch:** `QA`  
**Method:** Code + tests + live OpenRouter generation on 2026-09-23. See `23-25_LIVE_MODEL_EVALUATION.md` and `23-27_LIVE_ACCEPTANCE_REPORT.md`.

| Task | Verdict | Evidence / notes |
|------|---------|------------------|
| 02 Visual identity | DONE | Admin visual routes, V011, seeder |
| 08 Reference images | DONE | STANDARD_SLOTS, requireStandardReferences |
| 09 Regeneration | DONE | regenerate preserves version/refs/model |
| 10 Review/shortlist | DONE | SHORTLISTED/DECLINED/SAVED/IDENTITY_MISMATCH/POST_READY |
| 11 Cost/SLA | DONE W/ FINDINGS | SLA UI; cost=UNAVAILABLE; slaTarget=Not configured |
| 12 Model evaluation | DONE | V013, eval APIs/UI, tests |
| 13 Identity consistency | DONE | identity endpoint, mismatch status |
| 14 Engine/model config | DONE | V015 ImageRuntimeConfig |
| 15 Warehouse | DONE | filter/pagination/metadata |
| 16 Cost/SLA ops | DONE W/ FINDINGS | queue/stuck/storage/failures |
| 17 Release gate | CONDITIONAL | automated PASS; live/browser NOT VERIFIED |
| 18 Provider config + candidate testing | DONE (overlap 14/12) | config+eval; live multi-model smoke pending |
| 19 Identity matching | DONE (overlap 13) | |
| 20 Regen + warehouse | DONE (overlap 09/15) | |
| 21 Engine/skill config | DONE W/ FINDINGS (overlap 14) | settings singleton; no separate Skills row (intentional) |
| 22 E2E QA runbook | DOC + CONDITIONAL | runbook exists; operator fill pending |
| 23 Cost/SLA observability | DONE W/ FINDINGS (overlap 11/16) | slaTarget label added |
| 24 Production hardening / go-live | PARTIAL LIVE | startup, DB, worker, one-process storage, flare generation verified; two-process soak and chat attachment not verified |
| 25 Model×persona evaluation | LIVE WITH FINDINGS | eval `094b2a03-8542-47ca-9003-3eaaa66e2d06`; flare and sunburst succeeded; Seedream rejected resolution 512; no winner |
| 26 Model discovery | DONE | live catalog 53 models; 3 selected; discovery API |
| 27 Real persona acceptance | LIVE WITH FINDINGS | 8 real jobs; bathroom and cafe stored; night and sea safety-blocked; Ananya regen succeeded; Richa regen safety-blocked |

## Explicitly deferred
- Posts / social publishing
- Invented cost pricing

## Still open
1. Task 24 two-process shared-storage soak, and chat attachment
2. Signed-in Admin browser click-through
3. Richa regeneration is provider-blocked on the stored bathroom and cafe candidates
4. Posts / social publishing remain deferred
