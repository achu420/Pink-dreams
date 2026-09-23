# Task 27 — live OpenRouter acceptance

**When:** 2026-09-23, local app on `http://127.0.0.1:8080`  
**Provider:** OpenRouter `POST /api/v1/images`  
**Model:** `openai/gpt-image-2.5-flare` (Task 26 selection; production model was not changed)  
**Cost:** UNAVAILABLE on every job. No dollar amount was inferred.  
**Fake provider / H2:** not used.

## Personas

| Persona | Id | Age | Adult | Visual version | Files uploaded | References sent |
|---|---|---:|---|---|---:|---|
| Ananya (`ananya_rajput`) | `b5540c4f-ec74-4e5b-bf56-8d513c113964` | 25 | true | `161e5952-3af6-4fb4-95fc-fa1b86aaec6d` | 11 | FRONT, FACE_CLOSE, LEFT_PROFILE, RIGHT_PROFILE, BACK |
| Richa (`richa_mehta`) | `26010097-078f-4ba7-975e-e0a28345609e` | 27 | true | `c865e8a2-fdf4-451d-b278-a1d89997c50b` | 13 | FRONT, FACE_CLOSE, LEFT_PROFILE, RIGHT_PROFILE, BACK |

Private visual guide is empty. The five sent slots are the standard identity roles. Extra uploads stay on the version and are not sent.

Richa `FACE_CLOSE` is the first file in filename order: `ChatGPT Image Sep 12, 2026, 02_31_15 AM.png`. That photo has an on-image caption naming Aanya Rajput. The cafe image followed that face. The pipeline did not swap in a different file.

## Request shape that actually reached the provider

Two provider constraints showed up before any image was stored:

1. Five original photos as base64 exceeded OpenRouter's 8 MB text limit. The provider now downscales each reference to JPEG for the request. Stored originals are unchanged.
2. OpenRouter requires `input_references[].type = "image_url"` and `image_url.url`. Role names are added as a line after the seed prompt. The stored seed prompt is unchanged.

Client errors (HTTP 4xx), including safety rejections, are not retried.

## Matrix

Requested candidate count was 4. Seed wording is the Task 27 text.

| Persona | Seed | Job | Result | Images | Duration of final attempt | Notes |
|---|---|---|---|---:|---|---|
| Ananya | night party | `2bbbcec2-8652-4115-8f11-e2c40199f77e` | FAILED | 0 | 26s | Safety rejection `req_0a0ab16e739b4f51844c32c3b98393fb` |
| Ananya | coming out of sea | `3e103cea-265c-4b19-93f3-180d2e72166a` | FAILED | 0 | 14s | Safety rejection `req_71e2f9279b8944689f7d977e83447e79` |
| Ananya | bathroom selfie in office | `57fcc8e8-e21f-492d-baf2-eb627074b542` | SUCCEEDED | 4 | 39s | `81ea1f4f-35c7-42c1-a127-92b10e50ec13`, `b7c6bacc-1743-4c00-b97f-a9f8a912d6cd`, `f1162d48-dc2a-4fb3-aa5c-c49562651a0b`, `bd359b88-5555-47ba-adb3-1d3168740e18` |
| Ananya | evening at cafe | `fe5a6276-85e7-4204-ba64-27af90b4656a` | SUCCEEDED | 4 | 25s | `91ed91f7-6aac-4e20-9cc5-9e26a43260df`, `e76a25db-badb-4edd-b707-7fc6ee0801e6`, `73d3e79e-11c0-4110-b334-64d7fd25533a`, `f98e4674-5749-446b-b6d1-efe7a0100859` |
| Richa | night party | `a9b2a80f-0583-491a-80c8-a699beba2638` | FAILED | 0 | 18s | Safety rejection `req_cac13e63130246c4896a2b4f8c5b14f7` |
| Richa | coming out of sea | `c8d520fc-2b3c-4918-918b-585a1ad8d086` | FAILED | 0 | 7s | Safety rejection `req_e08d192bcc33469c9c90d734c251c891` |
| Richa | bathroom selfie in office | `025e90bc-d2cf-403e-a0aa-f461f9b35f39` | SUCCEEDED | 1 | 33s | `8ffa6621-bded-41ab-b139-40a448d4eb89` (provider returned 1, not 4) |
| Richa | evening at cafe | `6bfd4fb7-0c13-413d-a939-c6ba945b5acb` | SUCCEEDED | 1 | 36s | `c8564bac-0987-43f5-919a-3b65135c78d4` (provider returned 1, not 4) |

Safety rejections were recorded and not bypassed. The prompts were not rewritten.

## What the stored images show

Ananya cafe candidate `91ed91f7-6aac-4e20-9cc5-9e26a43260df` matches her `FACE_CLOSE` reference: same adult woman, dark brown eyes, long dark hair, cafe setting. Richa cafe candidate `c8564bac-0987-43f5-919a-3b65135c78d4` matches the face in the file that was bound as Richa `FACE_CLOSE`, including the captioned Ananya portrait noted above.

## Regeneration

| Persona | Source candidate | Job | Result |
|---|---|---|---|
| Ananya | `91ed91f7-6aac-4e20-9cc5-9e26a43260df` | `c0f9ef60-e84a-4e08-b703-f9d243e9f3f0` | SUCCEEDED, candidate `bae73899-9e79-4633-86f5-4533d533599c`. Same visual version, model, and five references. `sourceCandidateId` is the cafe candidate. |
| Richa | `c8564bac-0987-43f5-919a-3b65135c78d4` | `8fa94f05-0063-4f4e-b794-ffc4d4b6851e` | FAILED, safety `req_72a7703d69774f96ab6bfa7f22483b50` |
| Richa | same cafe candidate, no extra correction text | `5ad37ad2-e62d-4e6a-be66-e1940c7aea86` | FAILED, safety `req_9ea92e4cc22f46e5b79c76c2b00f12cd` |
| Richa | bathroom `8ffa6621-bded-41ab-b139-40a448d4eb89` | `237acb33-30af-4d7e-b993-78364ba8d50d` | FAILED, safety `req_48b0ddd946d04c688f7b0a5f23410748` |

Ananya regeneration completed. Richa regeneration was submitted three times through the real pipeline and the provider rejected each one. Those rejections were not bypassed.

## Task 24 gates touched by this run

| Gate | Result |
|---|---|
| Application startup | VERIFIED — responding on `127.0.0.1:8080` |
| Database | VERIFIED — personas, jobs, and candidates persisted in Postgres |
| Image worker | VERIFIED — queued jobs ran to SUCCEEDED or FAILED |
| Shared storage | VERIFIED for this one process (`storageProbeOk=true`, PNG bytes readable) |
| Two-process shared storage | NOT VERIFIED |
| OpenRouter / model | VERIFIED — flare returned real PNGs |
| End-to-end generation | VERIFIED for bathroom and cafe; night and sea blocked by provider safety |
| Chat attachment | NOT VERIFIED |
| Admin API | VERIFIED — session login, seed, create, identity, result, SLA, evaluation notes |
| Admin browser click-through | PARTIAL — `/admin` redirects to the sign-in page. Signed-in warehouse clicks were not completed in the browser. |
| Lease heartbeat / crash mid-job | NOT VERIFIED live. Automated lease tests already exist. `stuckRunningJobs` was 0 on the SLA read. |
| Restart | VERIFIED in a limited way — process restarted and the eight failed jobs were requeued and ran again |
| Cost | UNAVAILABLE |
| SLA target | Not configured |
