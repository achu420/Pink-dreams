# IMAGE PIPELINE — MORNING REVIEW BRIEF

**Date:** 2026-09-23  
**Branch:** `QA`  
**Tip:** `34eebff` — live OpenRouter reference-payload fix + Task 25/27 evidence  
**App (if still running):** `http://127.0.0.1:8080`  
**Auth:** Admin session login at `/admin-login.html` (static console credentials). `ADMIN_USER_IDS` may be unset; session principal is enough for Admin APIs.

**Evidence docs:**
- Task matrix: `23-00_FULL_TASK_CROSS_VALIDATION.md`
- Live model eval: `23-25_LIVE_MODEL_EVALUATION.md`
- Live persona acceptance: `23-27_LIVE_ACCEPTANCE_REPORT.md`
- Discovery: `23-26_IMAGE_MODEL_DISCOVERY_REPORT.md`
- Status ledger: `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`

---

## Verdict for the next agent

Pipeline code for tasks **02, 08–16, 18–23, 26** is in place and tested. Live OpenRouter generation for **25** and **27** was run on 2026-09-23 with real findings. **Do not re-implement** those slices unless a finding demands a fix.

**Do next (in order):**
1. Task **24** remaining gates — two-process shared-storage soak, chat image attachment, signed-in Admin browser click-through, then update go-live table.
2. Optional fix — Seedream (and similar models) fail when the client always sends resolution `512`; map to `1K`/`2K` before claiming Seedream works.
3. Optional ops — Richa regen is provider safety-blocked on stored bathroom/cafe candidates; do not bypass safety; try a different seed or document as blocked.
4. **Do not** implement posts/social publishing or invent cost dollars.

---

## Task-level status

| Task | Verdict | What landed | Gaps / next |
| --- | --- | --- | --- |
| 02 Visual identity | DONE | Draft/edit/publish, physical guide, Admin visual UI, V011 | — |
| 08 Reference images | DONE | STANDARD_SLOTS, `requireStandardReferences=true` default | Role assignment still filename-sort when seeding; Richa FACE_CLOSE file is a captioned Ananya portrait — note, do not silently swap |
| 09 Regeneration | DONE | Preserves visualVersion, refs, model, `sourceCandidateId` | Richa live regen safety-blocked (see 27) |
| 10 Review / shortlist | DONE | SHORTLISTED / DECLINED / SAVED / IDENTITY_MISMATCH / POST_READY | Posts deferred |
| 11 Cost / SLA | DONE W/ FINDINGS | SLA Admin + API | Cost always `UNAVAILABLE`; `slaTarget` = Not configured |
| 12 Model evaluation | DONE | V013, eval APIs + Admin UI | Production model never auto-changed |
| 13 Identity consistency | DONE | `GET .../jobs/{id}/identity`, mismatch status | — |
| 14 Provider/model config | DONE | V015 singleton, precedence request > DB > env > default | No API keys in DB |
| 15 Warehouse | DONE | Filter, pagination, model/identity/sourceCandidateId | — |
| 16 Cost/SLA ops | DONE W/ FINDINGS | Queue/stuck/storage probe/recent failures | Same cost/SLA-target findings |
| 17 Release gate | CONDITIONAL | Automated suites green for prior slice | Full signed-in browser smoke still thin |
| 18–21 Overlaps | DONE (as overlaps of 09/12–15) | Documented in cross-val | Engine/skill = settings singleton (intentional) |
| 22 E2E QA runbook | DOC + CONDITIONAL | Runbook exists | Operator fill / soak incomplete |
| 23 Cost/SLA observability | DONE W/ FINDINGS | Overlap of 11/16 + slaTarget label | — |
| 24 Hardening / go-live | PARTIAL LIVE | Startup, Postgres, worker, one-process storage, flare E2E verified | **NEXT:** two-process soak, chat attach, browser sign-in walkthrough |
| 25 Model × persona eval | LIVE WITH FINDINGS | Eval `094b2a03-8542-47ca-9003-3eaaa66e2d06`; flare + sunburst OK; Seedream rejected resolution `512`; no winner; production stayed flare | Optional: fix resolution mapping for Seedream |
| 26 Model discovery | DONE | Live catalog 53 models; selected flare, sunburst, seedream-5-0-pro | — |
| 27 Real persona acceptance | LIVE WITH FINDINGS | Ananya+Richa seeded; 8 jobs on flare; bathroom+cafe stored; night+sea safety-blocked; Ananya regen OK; Richa regen blocked | Do not rewrite blocked prompts; do not use Fake/H2 for “done” |

### Deferred (do not pick up unless asked)
- Posts / social publishing
- Invented provider cost / pricing

---

## Live run snapshot (2026-09-23)

**Personas**
- Ananya `b5540c4f-ec74-4e5b-bf56-8d513c113964` age 25 adult — visual `161e5952-3af6-4fb4-95fc-fa1b86aaec6d`
- Richa `26010097-078f-4ba7-975e-e0a28345609e` age 27 adult — visual `c865e8a2-fdf4-451d-b278-a1d89997c50b`

**Model:** `openai/gpt-image-2.5-flare` · **Cost:** UNAVAILABLE · **Provider:** real OpenRouter (not Fake/H2)

| Persona | Prompt | Job | Result |
| --- | --- | --- | --- |
| Ananya | night party | `2bbbcec2-…` | FAILED safety |
| Ananya | sea / bikini | `3e103cea-…` | FAILED safety |
| Ananya | bathroom selfie | `57fcc8e8-…` | SUCCEEDED ×4 |
| Ananya | evening at cafe | `fe5a6276-…` | SUCCEEDED ×4 |
| Richa | night party | `a9b2a80f-…` | FAILED safety |
| Richa | sea / bikini | `c8d520fc-…` | FAILED safety |
| Richa | bathroom selfie | `025e90bc-…` | SUCCEEDED ×1 (provider returned 1 not 4) |
| Richa | evening at cafe | `6bfd4fb7-…` | SUCCEEDED ×1 |

**Regen:** Ananya cafe → SUCCEEDED (`c0f9ef60-…`). Richa cafe/bath → FAILED safety (3 attempts).  
**Eval 25:** flare + sunburst SUCCEEDED; Seedream FAILED (resolution 512 not accepted; wants 1K/2K).

**Provider fixes shipped in `34eebff`:** shrink refs under OpenRouter 8 MB text limit; send `input_references` as `image_url`; treat numeric/string error codes; do not retry HTTP 4xx / safety.

---

## How to continue (operator / agent)

```text
1. Confirm tip is 34eebff (or later) on branch QA
2. Source run-local.secrets.ps1 (do not print secrets); IMAGE_STORAGE_DIR shared if multi-process
3. gradle.bat run from this repo (not the sibling "Pink dreams" tree)
4. Login → seed fixtures if personas missing → Warehouse / Model Evaluation / Image SLA
5. For Task 24: start two app processes with same DB + IMAGE_STORAGE_DIR; generate on A, read asset on B; attach one image to a chat message; walk Admin UI signed-in
6. Update 23-00 + this brief + Task 24 go-live table with VERIFIED / NOT VERIFIED per gate
```

Gradle: `C:\gradle\gradle-8.7\bin\gradle.bat` (no wrapper). Migrations V011–V015; runtime also uses Exposed `createMissingTablesAndColumns`.

---

## Recent commits (newest first)

- `34eebff` — OpenRouter ref payload fit + live 25/27 reports
- `4287bf8` — track task instruction MDs 14–27
- `ff6d30f` — Task 26 discovery + cross-validation
- `fc9c29d` — Task 17 conditional release gate
- `ccd4ee7` … `f7fcf9e` — Tasks 12–16 / warehouse / SLA / visual identity (see git log)

---

## Autopilot rules (still in force)

- Vertical slices only; reuse existing pipeline; no fake UI; no invented cost.
- Commit per task when asked or when a gate is closed; do not push unless asked.
- Adult personas only (Ananya 25 / Richa 27). Never claim models are unrestricted. Never print API keys or DB passwords.
- Governance: `IMAGE_PIPELINE_GOVERNANCE.md` (if present) + this brief for next-step priority.
