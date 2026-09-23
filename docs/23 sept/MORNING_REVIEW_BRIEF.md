# IMAGE PIPELINE — MORNING REVIEW BRIEF

**Date:** 2026-09-23 · **Branch:** `QA`  
**Evidence:** `23-00_FULL_TASK_CROSS_VALIDATION.md` · `23-24_GO_LIVE_VERIFICATION.md` · `23-25_LIVE_MODEL_EVALUATION.md` · `23-27_LIVE_ACCEPTANCE_REPORT.md` · `23-26_IMAGE_MODEL_DISCOVERY_REPORT.md`

## Next (do in order)

1. **Optional** — map Seedream (and similar) resolution to `1K`/`2K` instead of `512`.
2. **Optional** — Richa regen safety-blocked; leave documented or retry with a different seed (do not bypass safety).
3. **Do not** — posts/social, invent cost, re-implement 02/08–16/24–27 unless a finding demands a fix.

## Task status

| Task | Verdict | Gap |
| --- | --- | --- |
| 02, 08–10, 12–15, 18–21, 23, 26 | DONE | — |
| 11, 16 | DONE W/ FINDINGS | Cost = UNAVAILABLE; SLA target not configured |
| 17, 22 | CONDITIONAL | Operator fill / browser thinner than 24 |
| 24 | LIVE W/ FINDINGS | Two-process + chat attach + Admin UI verified — see `23-24_GO_LIVE_VERIFICATION.md` |
| 25 | LIVE W/ FINDINGS | flare+sunburst OK; Seedream fail on resolution; no winner |
| 27 | LIVE W/ FINDINGS | bathroom+cafe stored; night+sea safety-blocked; Ananya regen OK; Richa regen blocked |

## Live facts

- Ananya `b5540c4f-…` / visual `161e5952-…` · Richa `26010097-…` / visual `c865e8a2-…`
- Model: `openai/gpt-image-2.5-flare` · Ports used: **8080** (worker) + **8081** (no worker) · Shared storage: `data/object-storage`
- Chat attach: job `fe5a6276-…` → message `950a46a6-…`
- Run: source `run-local.secrets.ps1` (don’t print) → `gradle.bat run`

## Deferred

Posts/social · invented pricing
