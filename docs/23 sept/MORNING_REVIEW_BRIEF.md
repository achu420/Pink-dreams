# IMAGE PIPELINE — MORNING REVIEW BRIEF

**Date:** 2026-09-23 (overnight autopilot)  
**Branch:** `QA`  
**Commits:**
- `f7fcf9e` — Persona visual identity Admin workflow
- `b1d28e4` — Seed prompt, 4 candidates, lifecycle, Ananya/Richa fixtures

---

## What works now (testable)

1. **Visual Identity (Admin → Persona → Visual Identity)**
   - Ensure draft, edit physical + private fields, style JSON
   - Upload/replace/remove FRONT / FACE_CLOSE / L/R profile / BACK / PRIVATE
   - Publish & activate
2. **Generate**
   - Seed prompt box + candidate count (default 4)
   - Polls job → shows candidate previews
   - Shortlist / Decline / Save / remark / Regenerate (with correction)
3. **Test fixtures**
   - `POST /v1/admin/personas/seed-image-test-fixtures`
   - Loads **Ananya** (`ananya_rajput`) + **Richa** (`richa_mehta`) from  
     `docs/23 sept/persona testing data/` (pics + attributes)

---

## Morning smoke (10–15 min)

```text
1. Migrate DB (V011, V012)
2. Run app + image worker (shared IMAGE_STORAGE_DIR, OpenRouter key if live)
3. POST /v1/admin/personas/seed-image-test-fixtures
4. Admin → Ananya → Visual Identity → confirm refs
5. Seed: "swimming in a yellow bikini at Goa beach, early morning, facing camera"
6. Generate 4 → wait → shortlist one → remark → regenerate with correction
7. Repeat on Richa with a different seed
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
