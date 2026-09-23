# IMAGE PIPELINE — MORNING REVIEW BRIEF

**Date:** 2026-09-23 · **Branch:** `QA` · **Tip:** `fcd649a`  
**Evidence:** `23-00_FULL_TASK_CROSS_VALIDATION.md` · `23-25_LIVE_MODEL_EVALUATION.md` · `23-27_LIVE_ACCEPTANCE_REPORT.md` · `23-26_IMAGE_MODEL_DISCOVERY_REPORT.md`

## Next (do in order)

1. **Task 24** — two-process shared storage soak, chat image attach, signed-in Admin browser walkthrough; update go-live gates.
2. **Optional** — map Seedream resolution to `1K`/`2K` (live eval rejected `512`).
3. **Optional** — Richa regen is provider safety-blocked; do not bypass; try another seed or leave documented.
4. **Do not** — posts/social, invent cost dollars, re-implement 02/08–16/25–27.

## Task status

| Task | Verdict | Gap |
| --- | --- | --- |
| 02, 08–10, 12–15, 18–21, 23, 26 | DONE | — |
| 11, 16 | DONE W/ FINDINGS | Cost = UNAVAILABLE; SLA target not configured |
| 17, 22 | CONDITIONAL | Browser / operator fill thin |
| 24 | PARTIAL LIVE | Two-process soak + chat attach + Admin UI click-through |
| 25 | LIVE W/ FINDINGS | Eval `094b2a03-…`: flare+sunburst OK; Seedream fail on resolution; no winner; prod stayed flare |
| 27 | LIVE W/ FINDINGS | Bathroom+cafe stored; night+sea safety-blocked; Ananya regen OK; Richa regen blocked |

## Live facts (needed to continue)

- Ananya `b5540c4f-…` / visual `161e5952-…` · Richa `26010097-…` / visual `c865e8a2-…`
- Model: `openai/gpt-image-2.5-flare` · Real OpenRouter (not Fake/H2) · Cost UNAVAILABLE
- Provider fixes in `34eebff`: shrink refs under 8 MB; `image_url` schema; no retry on 4xx/safety
- Run: source `run-local.secrets.ps1` (don’t print) → `gradle.bat run` from this repo → `/admin-login.html`

## Deferred

Posts/social · invented pricing
