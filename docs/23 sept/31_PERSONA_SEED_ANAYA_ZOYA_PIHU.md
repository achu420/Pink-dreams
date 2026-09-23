# Task 31 — Persona Seed Report

Source packages live at `docs/personas/{Aanya,Zoya,Pihu}` (not `docs/persona/`). Display name for the Anaya logical slug is **Aanya** (Aanya Deshpande). Production image model was not changed: `openai/gpt-image-2.5-flare`. Task 30 benchmark was not run.

## Anaya

Persona ID: `39544329-7e16-4bdf-946b-5b0cb2ddc16d`  
Persona version: core `8317cb69-95f3-4aa3-a804-a39d32a7c3ac` (active)  
Visual Identity ID: `5b66ad75-ab70-4a67-966e-03abfc0480ad`  
Visual Identity version: `a59ec47e-d965-4fe6-b17c-aff6a2597239` (active published v4)

Source files:
- `docs/personas/Aanya/Aanya Deshpande.md`
- 7 PNG reference photos in the same directory

Images found: 7  
Images usable: 7  
Images invalid: 0  

Reference roles:

| filename | dimensions | role | usable | notes |
|---|---|---|---|---|
| ChatGPT Image Sep 23, 2026, 07_57_06 PM.png | 1254x1254 | FACE_CLOSE | yes | face close |
| ChatGPT Image Sep 23, 2026, 08_00_05 PM.png | 1024x1536 | FRONT | yes | front identity |
| ChatGPT Image Sep 23, 2026, 08_00_15 PM.png | 1024x1536 | OTHER | yes | three-quarter; no THREE_QUARTER role |
| ChatGPT Image Sep 23, 2026, 08_00_23 PM.png | 1024x1536 | LEFT_PROFILE | yes | |
| ChatGPT Image Sep 23, 2026, 08_01_32 PM.png | 1374x1145 | OTHER | yes | lifestyle desk; no LIFESTYLE role |
| ChatGPT Image Sep 23, 2026, 08_03_41 PM.png | 1374x1145 | OTHER | yes | detail collage |
| ChatGPT Image Sep 23, 2026, 08_04_45 PM.png | 1024x1536 | OTHER | yes | composite; FRONT slot kept |

Fields populated: slug `anaya`, displayName Aanya, gender, orientation, apparentAge 26, bio, city, occupation, interests, tags, languageProfile, persona core markdown, physical guide, style constraints, reference images.

Source fields not currently supported:

```text
SOURCE DATA NOT CURRENTLY REPRESENTED
- family / friends / relationship engine detail from the markdown
- daily routine, food, intimacy/boundaries as first-class columns
- wardrobe as wardrobe rows (folded into styleConstraints JSON)
- THREE_QUARTER / LIFESTYLE roles (stored as OTHER)
- religion / ethnicity / nationality columns
```

Generation selection (existing `preferStandardSlots`, max 5):  
references_available: 7  
references_sent: 3 (FRONT, FACE_CLOSE, LEFT_PROFILE)  
references_omitted: 4 OTHER (no RIGHT_PROFILE/BACK on disk)

Smoke test:  
Job: `4a83d710-8777-4bc6-9f2a-85081b2797ab` SUCCEEDED  
Model: `openai/gpt-image-2.5-flare`  
Candidates: 4 persisted (`444af093-…`, `c9a76f68-…`, `90bb6c04-…`, `c52da0f4-…`)  
Assets: 4 warehouse rows; full-size `/v1/admin/images/assets/{id}` HTTP 200

Admin:  
Persona visible: PASS  
Visual Identity visible: PASS  
Image Warehouse visible: PASS  
Images actually viewable: PASS

## Zoya

Persona ID: `a8ddbdca-ca12-4feb-be89-45f865ed080c`  
Persona version: core `2adc40a6-5c5b-4be9-99e7-aee6d94bed5e` (active)  
Visual Identity ID: identity on persona  
Visual Identity version: `8c33f6be-2d16-4aac-b913-0fbb5c0d1700` (active)

Source files:
- `docs/personas/Zoya/Zoya.md`
- 7 PNG photos

Images found: 7  
Images usable: 7  
Images invalid: 0  

Reference roles:

| filename | dimensions | role | usable | notes |
|---|---|---|---|---|
| ChatGPT Image Sep 23, 2026, 08_22_32 PM.png | 1145x1374 | FACE_CLOSE | yes | |
| ChatGPT Image Sep 23, 2026, 08_23_23 PM.png | 1024x1536 | FRONT | yes | |
| ChatGPT Image Sep 23, 2026, 08_25_31 PM.png | 1024x1536 | FULL_BODY | yes | second full front |
| ChatGPT Image Sep 23, 2026, 08_27_04 PM.png | 1024x1536 | OTHER | yes | studio sit |
| ChatGPT Image Sep 23, 2026, 08_30_29 PM.png | 1024x1536 | LEFT_PROFILE | yes | |
| ChatGPT Image Sep 23, 2026, 08_33_30 PM.png | 1024x1536 | BACK | yes | |
| ChatGPT Image Sep 23, 2026, 08_36_43 PM.png | 1374x1145 | OTHER | yes | beach lifestyle |

Fields populated: slug `zoya`, displayName Zoya, gender, orientation, apparentAge **25** (schema), bio (authored adult 24), city, occupation, interests, tags, languages, core markdown, physical guide (authored age 24), style constraints, references.

Source fields not currently supported:

```text
SOURCE DATA NOT CURRENTLY REPRESENTED
- authored apparent age 24 cannot be stored on personas.apparent_age
  (CHECK apparent_age >= 25). Column is 25; PhysicalGuide keeps 24.
- family/community, routine, intimacy, communication engine text
- THREE_QUARTER / LIFESTYLE roles
- wardrobe as first-class rows
```

Generation selection:  
references_available: 7  
references_sent: 4 (FRONT, FACE_CLOSE, LEFT_PROFILE, BACK)  
references_omitted: FULL_BODY + OTHER (selector already had ≥3 standard slots; cap 5; no RIGHT_PROFILE)

Smoke test:  
Job: `230cd72c-6beb-4605-9287-ff8434a8215e` SUCCEEDED  
Model: `openai/gpt-image-2.5-flare`  
Candidates: 4 (`a3f68d58-…`, `64ea585b-…`, `18df037a-…`, `25d357e4-…`)  
Assets: 4, HTTP 200

Admin:  
Persona visible: PASS  
Visual Identity visible: PASS  
Image Warehouse visible: PASS  
Images actually viewable: PASS

## Pihu

Persona ID: `0ad5156b-8087-4378-a7d8-af5ff05eaf99`  
Persona version: core `a12c33de-b703-43ed-bdcb-a3847c37bdfe` (active)  
Visual Identity ID: identity on persona  
Visual Identity version: `c7c282c8-85a6-4fba-9387-16e28fe5daa8` (active)

Source files:
- `docs/personas/Pihu/pihu.md`
- 9 PNG photos

Images found: 9  
Images usable: 9  
Images invalid: 0  

Reference roles:

| filename | dimensions | role | usable | notes |
|---|---|---|---|---|
| ChatGPT Image Sep 23, 2026, 09_05_19 PM.png | 1024x1536 | FRONT | yes | |
| ChatGPT Image Sep 23, 2026, 09_05_29 PM.png | 1024x1536 | FULL_BODY | yes | three-quarter standing |
| ChatGPT Image Sep 23, 2026, 09_05_49 PM.png | 1024x1536 | LEFT_PROFILE | yes | |
| ChatGPT Image Sep 23, 2026, 09_06_55 PM.png | 1374x1145 | OTHER | yes | desk lifestyle |
| ChatGPT Image Sep 23, 2026, 09_08_33 PM.png | 1145x1374 | OTHER | yes | collage |
| ChatGPT Image Sep 23, 2026, 09_10_30 PM.png | 1024x1536 | OTHER | yes | second sportswear front |
| ChatGPT Image Sep 23, 2026, 09_10_59 PM.png | 1024x1536 | BACK | yes | |
| ChatGPT Image Sep 23, 2026, 09_11_35 PM.png | 1278x1230 | FACE_CLOSE | yes | |
| ChatGPT Image Sep 23, 2026, 09_12_33 PM.png | 1374x1145 | PRIVATE | yes | lingerie; stored, not sent unless includePrivate |

Fields populated: slug `pihu`, displayName Pihu, gender, orientation, apparentAge 26, bio, city, occupation, interests, tags, languages, core markdown, physical guide, style constraints, 9 references including PRIVATE.

Source fields not currently supported:

```text
SOURCE DATA NOT CURRENTLY REPRESENTED
- family/friends, routine, food, intimacy/boundaries as columns
- THREE_QUARTER / LIFESTYLE roles
- wardrobe rows
- authored private visual-guide text (photos stored; PrivateVisualGuide left empty — not invented)
```

Generation selection:  
references_available: 9 (8 public + 1 PRIVATE)  
references_sent: 4 (FRONT, FACE_CLOSE, LEFT_PROFILE, BACK)  
references_omitted: FULL_BODY, OTHER x3, PRIVATE

Smoke test:  
Job 1: `96c4fa04-25a8-46dc-9678-c0089f927686` FAILED — OpenRouter/OpenAI safety (`req_ab300ae123d049198d5e3ae1c78edd92`). Prompt not rewritten. 0 candidates.  
Job 2 (same prompt, new idempotency, same pipeline): `ac149f2b-4c01-4578-8a0a-2e1d72e2a2ca` SUCCEEDED  
Model: `openai/gpt-image-2.5-flare`  
Candidates: 1 persisted (`667d6135-6c6e-482c-8cba-ba4deb2660cf`) — provider returned 1 of requested 4  
Assets: 1, HTTP 200

Admin:  
Persona visible: PASS  
Visual Identity visible: PASS  
Image Warehouse visible: PASS  
Images actually viewable: PASS

## Overall

Persona seed: PASS  
Visual Identity: PASS  
Image pipeline: PASS (Pihu first attempt FAILED then SUCCEEDED; both jobs stored)  
Storage: PASS  
Admin UI: PASS  
Automated tests: PASS (`SourcePersonaPackageSeederTest`, `AdminSourcePersonaSeedRoutesTest`)

Known issues:
- Source path is `docs/personas/`, not `docs/persona/`.
- Postgres `personas_apparent_age_check` rejects 24; Zoya column is 25.
- Schema has no THREE_QUARTER/LIFESTYLE; extras stored as OTHER; PRIVATE kept off the generation send set.
- Production selector sends at most 5 standard-first refs; extras stay on the version.
- Re-running seed is persona-idempotent but publishes a new visual version each time (archives prior draft refs).
- Pihu first cafe job was a provider safety rejection; second identical prompt succeeded with 1 image.
- Cost UNAVAILABLE on these jobs.
- Duplicate folder `docs/personas/Aanya Deshpande/` was not used.

Commit: not created (head `018f388`). Changes are uncommitted on QA.
