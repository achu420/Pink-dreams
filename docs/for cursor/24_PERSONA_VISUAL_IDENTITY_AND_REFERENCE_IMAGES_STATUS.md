# 24 — Persona Visual Identity & Reference Images — STATUS

**Date:** 2026-09-23  
**Task:** `docs/23 sept/02_PERSONA_VISUAL_IDENTITY.md` (TASK 24)  
**Verdict:** PASS WITH FINDINGS

---

## A. Current state before work

Versioned visual identity existed (repos + DB + read-only Admin GET). No Admin write APIs/UI. PhysicalGuide lacked weight/muscularity/chest/marks structure. ReferenceRole lacked FRONT/FACE_CLOSE/profiles/BACK/PRIVATE. No private guide column. PromptCompiler emitted a subset of identity fields. PRIVATE refs were not distinguished.

## B. Requirement mapping

| Requirement | Existing | Action | Final Status |
| --- | --- | --- | --- |
| Physical attributes | PARTIAL | EXTEND PhysicalGuide + Admin form | IMPLEMENTED |
| Appearance | PARTIAL | EXTEND + style constraints API | IMPLEMENTED |
| Private attributes | MISSING | ADD PrivateVisualGuide + private_guide column | IMPLEMENTED |
| Reference images | PARTIAL | EXTEND roles + Admin upload/serve/delete | IMPLEMENTED |
| Admin UI | READ-ONLY | EDITABLE Visual Identity tab | IMPLEMENTED |
| API | READ | ADD ensure/guides/refs/publish | IMPLEMENTED |
| Storage | YES | REUSE ObjectStorage | IMPLEMENTED |
| Identity resolution | YES | Prefer standard slots; exclude PRIVATE by default | IMPLEMENTED |
| Security | PARTIAL | Admin gate + cross-persona content 404 | IMPLEMENTED |
| Tests | PARTIAL | ADD write + service tests | IMPLEMENTED |

## C. Files changed (key)

- `PhysicalGuide.kt`, `PrivateVisualGuide.kt`, `ReferenceImage.kt`, `PersonaVisualAdminService.kt`
- `PersonaVisualVersionRepository.kt`, `PersonaRepository.kt`, `ReferenceImageRepository.kt`, `DatabaseFactory.kt`
- `db/migration/V011__visual_identity_private_and_reference_roles.sql`
- `AdminPersonaVisualRoutes.kt`, `Application.kt`
- `PromptCompiler.kt`, `ImageGenerationService.kt`, `ImageGenerationOrchestrator.kt`, `ImageGenerationRequest.kt`
- `admin-ui.html`
- Tests: `AdminPersonaVisualWriteRoutesTest.kt`, `PersonaVisualAdminServiceTest.kt`
- This status doc + implementation status update

## D. Database changes

`V011`: `persona_visual_versions.private_guide`; expanded `role` CHECK; immutability trigger includes `private_guide`. H2 tests use Exposed `createMissingTablesAndColumns`.

## E. API changes

| Method | Path |
| --- | --- |
| GET | `/v1/admin/personas/{id}/visual` (now includes privateGuide, assetUrl, isPrivate) |
| POST | `/v1/admin/personas/{id}/visual/ensure` |
| PUT | `/v1/admin/personas/{id}/visual/physical-guide` |
| PUT | `/v1/admin/personas/{id}/visual/private-guide` |
| PUT | `/v1/admin/personas/{id}/visual/style-constraints` |
| POST | `/v1/admin/personas/{id}/visual/publish-activate` |
| POST | `/v1/admin/personas/{id}/visual/references` (multipart) |
| GET | `/v1/admin/personas/{id}/visual/references/{refId}/content` |
| DELETE | `/v1/admin/personas/{id}/visual/references/{refId}` |
| PATCH | `/v1/admin/personas/{id}/visual/references/{refId}` (notes) |

## F. Admin UI changes

Persona Detail → Visual Identity: ensure draft, edit physical + private fields, style JSON, upload/replace/remove standard + PRIVATE slots with previews, publish & activate, generate images.

## G. Image pipeline integration

`ImageGenerationService` resolves active (or max) visual version; auto-selects non-PRIVATE refs preferring FRONT/FACE_CLOSE/profiles/BACK; `PromptCompiler` emits expanded physical + private guide + style constraints; jobs store `persona_visual_version_id`.

Precedence: persona identity (physical/private guide) over scene request fields.

## H. Security

- Admin-only mutation/content routes
- Non-admin → 403 (tested)
- Reference content scoped to persona identity (cross-persona → 404)
- PRIVATE refs excluded from auto generation selection
- Private guide only on admin visual APIs (not public persona list)

## I. Tests

```text
Targeted suite (AdminPersonaVisual*, PersonaVisualAdminServiceTest, PhaseIMG1/2/3, PhaseIMG7):
BUILD SUCCESSFUL
```

Important tests:

- `AdminPersonaVisualWriteRoutesTest.ensure save publish and reference workflow`
- `AdminPersonaVisualWriteRoutesTest.non-admin cannot modify visual identity`
- `AdminPersonaVisualWriteRoutesTest.delete reference from draft`
- `PersonaVisualAdminServiceTest.generation context excludes private references...`
- Existing PhaseIMG1/2/3 + AdminPersonaVisualRoutesTest (GET)

## J. Manual verification

```text
NOT VERIFIED — browser Admin UI not exercised in this session
VERIFIED — Admin write/read APIs via automated tests (ensure→save→upload→content→publish→cross-persona deny)
```

Manual procedure for morning review:

1. Admin → Persona → Visual Identity → Create/ensure draft  
2. Set height/weight/hair/etc. + private fields → Save draft  
3. Upload FRONT / FACE_CLOSE / profiles / BACK (and optional PRIVATE)  
4. Publish & activate → reload → confirm persistence + image previews  
5. Generate images (existing job path)

## K. Known gaps

- Wardrobe still not compiled into prompt text (IDs selected only)
- No dedicated user-facing private-ref ACL beyond exclusion + admin-only content route
- Browser Admin UI not manually clicked this session
- Multiple PRIVATE refs not specially limited beyond slot archive-on-replace
- Posts / warehouse / 4-candidate UX out of scope

## L. Final verdict

```text
PASS WITH FINDINGS
```

Core Admin visual identity + reference workflow is wired end-to-end (DB → API → Admin UI → generation resolution). Findings: browser manual UI not verified; wardrobe-in-prompt still deferred.
