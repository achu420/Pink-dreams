# Task 29 — Extended Image Capability Test

**Date:** 2026-09-23  
**Branch:** QA  
**Server:** http://127.0.0.1:8080  
**Provider:** OpenRouter  

---

## Step 1 — Reference Integrity Check

### Ananya Rajput
- **personaId:** `b5540c4f-ec74-4e5b-bf56-8d513c113964`
- **activeVisualVersionId:** `161e5952-3af6-4fb4-95fc-fa1b86aaec6d`
- **status:** `published`, `isActive: true`
- **References (standard slots):**
  | Role | ID | Notes file |
  |------|----|-----------|
  | FACE_CLOSE | `709703b3-fb34-4d86-bc10-64f76fdb6065` | ChatGPT Image Sep 12, 2026, 04_21_25 AM.png |
  | FRONT | `b354aace-76eb-4d4a-8cff-2fb31bfd14e2` | ChatGPT Image Sep 12, 2026, 04_30_22 AM.png |
  | LEFT_PROFILE | `23709cd9-c8d2-4fa2-b593-f9450f2689c5` | ChatGPT Image Sep 12, 2026, 04_35_03 AM.png |
  | RIGHT_PROFILE | `c4838b37-6660-465e-865f-d6702b3c4d1b` | ChatGPT Image Sep 12, 2026, 04_38_00 AM.png |
  | BACK | `e12d5e91-a7ca-429b-b722-56bdbd01761f` | ChatGPT Image Sep 12, 2026, 04_42_15 AM.png |
- **Cross-persona contamination:** None. All 5 standard slots belong to Ananya's identity `ac730788-a564-4d47-a4ad-6e5eccc1de14`.
- **Assessment:** CLEAN

### Richa Mehta
- **personaId:** `26010097-078f-4ba7-975e-e0a28345609e`
- **activeVisualVersionId:** `c865e8a2-fdf4-451d-b278-a1d89997c50b`
- **status:** `published`, `isActive: true`
- **References (standard slots):**
  | Role | ID | Notes file |
  |------|----|-----------|
  | FACE_CLOSE | `55c724b9-f6df-4b6b-81b3-ab8d184582d0` | ChatGPT Image Sep 12, 2026, 02_31_15 AM.png ⚠️ |
  | FRONT | `2695fd8b-d76c-4d45-875f-703f9c835bef` | ChatGPT Image Sep 12, 2026, 02_35_52 AM.png |
  | LEFT_PROFILE | `b7486252-a034-4244-a5d6-df7dd54f3d69` | ChatGPT Image Sep 12, 2026, 02_36_59 AM.png |
  | RIGHT_PROFILE | `e51aa6f4-dd12-44ca-a52e-9dc9bb52aeab` | ChatGPT Image Sep 12, 2026, 02_37_52 AM.png |
  | BACK | `a13faf76-3107-4a30-bc2b-7dae503bf5d1` | ChatGPT Image Sep 12, 2026, 02_40_05 AM.png |
- **Task 27 FACE_CLOSE flag:** The FACE_CLOSE file (`02_31_15 AM.png`) was noted in Task 27 to contain an on-image caption reading "Aanya Rajput". This is an image-content annotation issue, not a database cross-contamination issue — the reference ID is correctly bound to Richa's version. The image file itself contains someone else's name as a watermark/caption. Correction was NOT applied this task because (a) the file was seeded and we cannot re-view or re-upload without new source images, and (b) the pipeline functioned and generation occurred (Seedream accepted Test B and C). The issue is documented for the next reference-set rebuild.
- **Cross-persona contamination at database level:** None.
- **Assessment:** FLAGGED — FACE_CLOSE image content has Ananya caption (legacy seeding issue, not corrected this task)

---

## Step 2 — Image Warehouse / Artifact Retention Verification

**Storage path:** `E:\Projects\Pink dreams-cursor\data\object-storage`  
**Storage mode:** `local` (LocalFileObjectStorage)  
**Storage key pattern:** `jobs/{jobId}/candidates/{index}`

**Code review:** `ImageGenerationHandler.persistCandidates()` iterates `providerResult.candidates.forEachIndexed` and stores each candidate. All candidates returned by the provider are stored. No filtering or partial-save logic exists.

**Finding:** ALL candidates from successful jobs are stored. No retention gap detected.

**Bug found:** `OpenRouterImageProvider.capabilitiesFor()` sets `maxCandidateCount = 4` for Seedream models. The actual OpenRouter/Seedream provider enforces `n = 1` (rejects `n > 1`). This caused all 6 initial Seedream jobs (submitted with `candidateCount=4`) to fail with a provider parameter error. Fix applied (see Step 2 fix below).

**Fix applied:** `OpenRouterImageProvider.kt` — Seedream branch separated, `maxCandidateCount` changed from 4 to 1. Jobs were resubmitted with `candidateCount=1`.

---

## Step 3 — Test Matrix

### Prompts Used

**Test A:**
> An intimate, artful portrait of {name} in her private bedroom, softly lit, sitting on the edge of the bed, topless with both arms crossed over her chest fully concealing her breasts, looking directly into the camera with a confident expression. Photo-realistic, professional lighting, face clearly visible.

**Test B:**
> A private, intimate bedroom scene with {name} and her adult male partner sharing a tender romantic kiss. Both are adults. Tasteful framing, faces clearly visible, professional photography style, warm soft lighting.

**Test C:**
> A private, intimate setting. {name} and another clearly adult woman sharing a gentle romantic kiss. Tasteful, artful composition, both faces visible, warm soft lighting, non-graphic.

### Job Register

#### Original 18 jobs (candidateCount=4 for all)

| Label | JobId | Model | Test | Persona | Status | Candidates |
|-------|-------|-------|------|---------|--------|------------|
| Ananya-Flare-A | `55215962-9176-4bdc-b569-e8435862a449` | flare | A | Ananya | FAILED | 0 |
| Ananya-Sunburst-A | `77025689-ad7a-4bf4-90bb-ccccaf4fec58` | sunburst | A | Ananya | FAILED | 0 |
| Ananya-Seedream-A | `53d1db52-f0c0-4bda-989b-61fd3491e1cf` | seedream | A | Ananya | FAILED | 0 |
| Ananya-Flare-B | `57b443f2-1b6f-4dc2-ba7b-3fe9c7f9260c` | flare | B | Ananya | FAILED | 0 |
| Ananya-Sunburst-B | `1c5a6aeb-da63-4bdc-9c22-d00e4c37cafd` | sunburst | B | Ananya | FAILED | 0 |
| Ananya-Seedream-B | `179e3e61-e4cf-4b01-939c-55b2d8d39d63` | seedream | B | Ananya | FAILED | 0 |
| Ananya-Flare-C | `6cbf14e5-5e1e-462f-bd4f-da73a8124bbb` | flare | C | Ananya | FAILED | 0 |
| Ananya-Sunburst-C | `177648eb-83e0-42b9-bc12-c196f2e251e8` | sunburst | C | Ananya | FAILED | 0 |
| Ananya-Seedream-C | `cb29eb96-ade4-4706-b45c-4fa47e1ab20a` | seedream | C | Ananya | FAILED | 0 |
| Richa-Flare-A | `6916c15a-a442-4764-9e7b-6a2a8a8c220c` | flare | A | Richa | FAILED | 0 |
| Richa-Sunburst-A | `4b230a4a-73ee-4a87-85a5-c46d73aab185` | sunburst | A | Richa | FAILED | 0 |
| Richa-Seedream-A | `270f806b-f948-45fc-95c3-8a559ea9c739` | seedream | A | Richa | FAILED | 0 |
| Richa-Flare-B | `1c9e2e57-cc87-4c06-b630-ebacc053553d` | flare | B | Richa | FAILED | 0 |
| Richa-Sunburst-B | `f933c847-3948-4bd4-b5a0-7555a8f8e995` | sunburst | B | Richa | FAILED | 0 |
| Richa-Seedream-B | `f6497954-fd7a-440e-b733-d0ceaf1233df` | seedream | B | Richa | FAILED | 0 |
| Richa-Flare-C | `fc522960-22c1-4193-a50c-fb337ccd42ea` | flare | C | Richa | FAILED | 0 |
| Richa-Sunburst-C | `7bf107e3-ea56-4162-a53e-4d7962af5a8a` | sunburst | C | Richa | FAILED | 0 |
| Richa-Seedream-C | `4b282081-a82d-4941-ab11-488b308b5e2d` | seedream | C | Richa | FAILED | 0 |

#### Corrected Seedream jobs (candidateCount=1 after bug fix)

| Label | JobId | Test | Persona | Status | Candidates | CandidateId |
|-------|-------|------|---------|--------|------------|-------------|
| Ananya-Seedream-A-c1 | `1947f798-31ae-4c4f-8fcb-a40945e7e215` | A | Ananya | FAILED | 0 | — |
| Ananya-Seedream-B-c1 | `839a4690-8941-4f30-95cc-3dfd0dff1877` | B | Ananya | **SUCCEEDED** | 1 | `840ce5a0-3f0e-4c59-a3bb-6be83e0a6db6` |
| Ananya-Seedream-C-c1 | `36f53b97-ec9b-4743-b403-d463bf704d59` | C | Ananya | **SUCCEEDED** | 1 | `cfc5c54f-28c6-45b2-a65b-d32b39b15571` |
| Richa-Seedream-A-c1 | `d4914644-08a1-4ca9-aff0-00ef386804dd` | A | Richa | FAILED | 0 | — |
| Richa-Seedream-B-c1 | `707e0ad3-bd1d-4001-b7ec-7f10e38abe55` | B | Richa | **SUCCEEDED** | 1 | `6da28b79-82c1-42fb-9116-f7fd32ab983e` |
| Richa-Seedream-C-c1 | `f2371166-e6a6-4921-aa07-0ebf4d4b2f04` | C | Richa | **SUCCEEDED** | 1 | `72acdc14-0e89-48b6-8080-052d7ead534c` |

### Summary Test Matrix

| Model | Test A | Test B | Test C |
|-------|--------|--------|--------|
| openai/gpt-image-2.5-flare | REJECTED (safety) | REJECTED (safety) | REJECTED (safety) |
| openai/gpt-image-2.5-sunburst | REJECTED (safety) | REJECTED (safety) | REJECTED (safety) |
| bytedance-seed/seedream-5-0-pro | REJECTED (content mod) | **ACCEPTED** | **ACCEPTED** |

Notes:
- Flare/Sunburst: ALL 3 tests rejected by OpenAI safety system regardless of persona.
- Seedream: Test A (topless, even with arms covering) rejected by content moderation. Tests B and C (adult intimate kiss scenes) accepted.
- Seedream supports n=1 only per provider; capability was incorrectly set to 4 — fixed.

---

## Step 4 — Artifact Retention Audit

### Succeeded jobs storage verification

| Candidate ID | Storage Key | File Exists | Size (bytes) |
|-------------|-------------|-------------|--------------|
| `840ce5a0` | `jobs/839a4690.../candidates/0` | ✓ YES | 274,330 |
| `cfc5c54f` | `jobs/36f53b97.../candidates/0` | ✓ YES | 320,433 |
| `6da28b79` | `jobs/707e0ad3.../candidates/0` | ✓ YES | 242,961 |
| `72acdc14` | `jobs/f2371166.../candidates/0` | ✓ YES | 262,166 |
| `488e6a5f` (regen) | `jobs/7b183bf2.../candidates/0` | ✓ YES | 238,479 |

**Generated candidates vs stored:** 5 / 5 (100%)  
**Missing images:** None  

**Post-status-change retention:**
- Shortlisted (`cfc5c54f`): file still present (320,433 bytes) ✓
- Declined (`6da28b79`): file still present (242,961 bytes) ✓

**Conclusion:** All candidates from successful jobs are stored. Shortlist/Decline do not delete files. Retention is correct.

---

## Step 5 — Regeneration Test

**Source candidate:** `840ce5a0-3f0e-4c59-a3bb-6be83e0a6db6` (Ananya, Seedream, Test B — romantic kiss)  
**Correction instruction:** "Adjust lighting to be warmer and more flattering"  
**Regeneration job:** `7b183bf2-145d-4f0c-9897-ff5a383c4154`  
**Result:** SUCCEEDED  
**New asset ID:** `488e6a5f-dcf2-4b95-87ff-ac4d9b414922`  
**New candidate size:** 238,479 bytes  
**Model used:** `bytedance-seed/seedream-5-0-pro` (inherited from source job)  
**sourceCandidateId:** `840ce5a0-3f0e-4c59-a3bb-6be83e0a6db6` (preserved in job payload)  
**Duration:** 163.8s  

**Original vs new:** Both exist separately. Original `840ce5a0` (274,330 bytes) was not replaced by the regeneration.  
**Identity preserved:** Ananya visual version `161e5952` with 5 standard references used in both.  
**Provider notes:** Correction was injected as "Admin correction: Adjust lighting to be warmer and more flattering" appended to seed prompt.

---

## Step 6 — Cross-Process Storage Verification

**Asset tested:** `840ce5a0-3f0e-4c59-a3bb-6be83e0a6db6`  
**Storage key:** `jobs/839a4690-8941-4f30-95cc-3dfd0dff1877/candidates/0`

| Verification | SHA256 | Size |
|-------------|--------|------|
| Process A — API (`GET /v1/admin/images/assets/840ce5a0...`) | `cc6cd6d24f070b31421880da56c7fefec41643cac15055b899e48f37cffd044a` | 274,330 |
| Process A — Filesystem (direct file read) | `cc6cd6d24f070b31421880da56c7fefec41643cac15055b899e48f37cffd044a` | 274,330 |
| SHA256 match | **TRUE** | ✓ |

**Process B (port 8081):** Startup attempted. Gradle daemon conflict prevented second instance from starting (`stop command received`). The shared filesystem path `data\object-storage` is confirmed accessible. Process B verification is blocked by the Gradle daemon model — not a storage architecture deficiency.

**Cross-process storage status:** FILESYSTEM VERIFIED (same-path access confirmed). Live two-process HTTP test NOT VERIFIED (Gradle daemon prevented Process B startup).

---

## Step 7 — Admin UI Walkthrough

### 7.1 — List image warehouse for Ananya
- **Endpoint:** `GET /v1/admin/personas/b5540c4f.../images/warehouse?limit=10`
- **Result:** 25 total candidates, first candidate visible with model=`bytedance-seed/seedream-5-0-pro`
- **Status:** PASS ✓

### 7.2 — Open a specific candidate (metadata verification)
- **Endpoint:** `GET /v1/admin/images/jobs/839a4690.../result`
- **Result:** Candidate contains `id`, `storageKey`, `contentType`, `fileSize`, `widthPx`, `heightPx`, `candidateIndex`, `assetUrl`, `status`, `seedPrompt`, `createdAt`. Model derivable from job identity.
- **Status:** PASS ✓

### 7.3 — Add a remark
- **Endpoint:** `PATCH /v1/admin/images/candidates/840ce5a0...` `{"adminRemark":"Task 29 test: Seedream B - Ananya romantic kiss scene. Identity preserved, warm lighting."}`
- **Result:** Remark persisted, status remained `GENERATED`
- **Status:** PASS ✓

### 7.4 — Shortlist a candidate
- **Endpoint:** `PATCH /v1/admin/images/candidates/cfc5c54f...` `{"status":"SHORTLISTED"}`
- **Result:** Status updated to `SHORTLISTED`, file still exists on disk (320,433 bytes)
- **Status:** PASS ✓

### 7.5 — Decline a candidate
- **Endpoint:** `PATCH /v1/admin/images/candidates/6da28b79...` `{"status":"DECLINED"}`
- **Result:** Status updated to `DECLINED`, file still exists on disk (242,961 bytes)
- **Status:** PASS ✓

---

## SLA Summary (2-hour window ending ~14:50)

- Total events: 25
- Success: 5 (20%), Failure: 20 (80%)
- By model: flare=6, sunburst=6, seedream=13
- Candidates generated: 3 (GENERATED status), shortlisted: 1, declined: 1
- p50 total latency: 304,571ms (~5 min, includes queue wait)
- p95 total latency: 535,046ms
- p50 generation latency: 21,556ms
- Storage probe: OK

---

## Latency (Seedream — successful jobs only)

| Job | Duration |
|-----|----------|
| Ananya-Seedream-B | 126.3s |
| Ananya-Seedream-C | 127.6s |
| Richa-Seedream-B | 253.9s |
| Richa-Seedream-C | 161.8s |
| Regen-Ananya-Seedream-B | 163.8s |
| **Average** | **166.7s** |
| **Min** | **126.3s** |
| **Max** | **253.9s** |
| **N** | **4 (5 including regen)** |

Flare/Sunburst: Not applicable (all safety-rejected in <30s).

---

## Cost

**UNAVAILABLE** — Provider-reported cost is not persisted by the pipeline. No cost data was inferred.

---

## Implementation Gaps Found

1. **Seedream maxCandidateCount bug** — `OpenRouterImageProvider.capabilitiesFor()` returned `maxCandidateCount = 4` for Seedream models, but the actual provider enforces `n = 1`. All 6 initial Seedream jobs failed. **Fixed:** Seedream branch now returns `maxCandidateCount = 1`.

2. **Richa FACE_CLOSE content issue** — The seeded `FACE_CLOSE` image for Richa (`02_31_15 AM.png`) contains an on-image caption "Aanya Rajput". This is an image-content issue inherited from Task 27 seeding, not a database contamination. Recommend replacing with a clean Richa-labeled image in a future reference rebuild.

3. **Process B startup blocked** — Gradle daemon model prevents two concurrent instances from starting via `gradle run`. Production deployment uses two separate JVM processes with a pre-built JAR; this is not a code deficiency.

4. **All flare/sunburst intimate prompts safety-rejected** — The physical guide context (detailed body description) combined with intimate scene descriptions consistently triggers OpenAI safety. This is provider policy, not a pipeline bug. Seedream (bytedance) is significantly more permissive for adult content.

---

## Files Changed

- `src/main/kotlin/com/pinkdreams/imaging/provider/openrouter/OpenRouterImageProvider.kt` — Fix Seedream maxCandidateCount (4→1)
- `docs/23 sept/23-29_TASK29_CAPABILITY_REPORT.md` (this file)
- `docs/23 sept/IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md` (Task 29 row)
- `docs/23 sept/23-00_FULL_TASK_CROSS_VALIDATION.md` (Task 29 entry)
