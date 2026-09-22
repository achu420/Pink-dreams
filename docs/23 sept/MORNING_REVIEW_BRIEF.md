# IMAGE PIPELINE — MORNING REVIEW BRIEF

**Date:** 2026-09-23 (overnight autopilot)  
**Branch:** `QA`  
**Commits:**
- `f7fcf9e` — Persona visual identity Admin workflow
- `b1d28e4` — Seed prompt, 4 candidates, lifecycle, Ananya/Richa fixtures
- `b20f048` — Morning review brief
- `13e4365` — Persona Image Warehouse tab + API
- `b2880c8` — Image SLA Admin page + enriched SLA API
- `8e5944c` — Image Model Evaluation (2–3 models)
- `298ebaf` — Identity context + required refs + IDENTITY_MISMATCH
- `185d0b3` — Admin-editable image provider/model config
- `77625db` — Warehouse deepen (filter/pagination/metadata)
- `ccd4ee7` — Image SLA ops deepen (queue/stuck/storage/failures)

---

## What works now (testable)

1. **Visual Identity** — draft/edit/upload slots/publish
2. **Generate** — seed + 4 candidates, shortlist/decline/save/mismatch/regen
3. **Fixtures** — Ananya + Richa seed endpoint
4. **Warehouse** — grouped by generation, filters, pagination, mark-for-post
5. **Model Evaluation** — 2–3 models, comparison UI, notes/ratings
6. **Image config** — Admin default model (request > DB > env > default)
7. **Image SLA** — latency, candidates, queue/stuck, storage probe, recent failures; cost = UNAVAILABLE

## Morning smoke

Migrate V011–V015, seed fixtures, Generate 4, Warehouse, Model Evaluation, Image SLA.

---

## Morning smoke (10–15 min)

```text
1. Migrate DB (V011, V012, V013)
2. Run app + image worker (shared IMAGE_STORAGE_DIR, OpenRouter key if live)
3. POST /v1/admin/personas/seed-image-test-fixtures
4. Admin → Ananya → Visual Identity → confirm refs
5. Seed: "swimming in a yellow bikini at Goa beach, early morning, facing camera"
6. Generate 4 → wait → shortlist one → remark → regenerate with correction
7. Repeat on Richa with a different seed
8. Admin → Model Evaluation → pick 2 models → same seed → compare (production model unchanged)
```

Fake provider works without OpenRouter (for plumbing). Live face consistency needs OpenRouter.

---

## Still open (queued MDs)

| # | File | Status |
| --- | --- | --- |
| 08 | Reference image system | Mostly covered by Task 24 — audit/extend only |
| 09 | Regeneration feedback | Core regen landed; deepen if MD demands more |
| 10 | Review/shortlist workflow | Partial via Visual tab actions |
| 11 | Cost / SLA analytics | API exists; Admin UI + cost still missing |
| 12 | Model evaluation | Not started |
| — | Dedicated Image Warehouse page | Not started (data on Visual tab) |
| — | Model/provider Admin config | Env-only (`GET .../images/config` read) |

---

## Autopilot note

Agent continues next tasks without waiting. Keep dropping MDs into `docs/23 sept/`; they will be picked up on the next scan after each commit.
