# TASK 28 — IMAGE MODEL CONTROLLED COMPARISON

**Date:** 2026-09-23  
**Branch:** `QA`  
**Commit:** `01ae84f`

## CURRENT PRODUCTION MODEL
`openai/gpt-image-2.5-flare`  
**Changed during this task:** NO (`modelSource=ENVIRONMENT` after matrix)

## MODELS TESTED
1. `openai/gpt-image-2.5-flare`
2. `openai/gpt-image-2.5-sunburst`
3. `bytedance-seed/seedream-5-0-pro`

Exact IDs from Task 26 / `SELECTED_FOR_EVALUATION`. Production left on flare; per-job `modelId` override used.

## Comparison constants
| Variable | Value |
|---|---|
| Personas | Ananya `b5540c4f-…` visual `161e5952-…` age 25 adult; Richa `26010097-…` visual `c865e8a2-…` age 27 adult |
| References supplied | FRONT, FACE_CLOSE, LEFT_PROFILE, RIGHT_PROFILE, BACK (5) on every job |
| width×height | 1024×1024 → OpenRouter resolution tier **1K** for all models |
| candidateCount | **1** for every cell (constant across models; Admin default is 4 — documented difference) |
| Cost | UNAVAILABLE |
| SLA target | Not configured |

## Provider restriction matrix

| Model | Prompt A night | Prompt B sea | Prompt C bath | Prompt D cafe | Notes |
|---|---|---|---|---|---|
| flare | REJECTED | REJECTED | ACCEPTED (Ananya) / REJECTED (Richa) | ACCEPTED | OpenAI safety (`sexu…`) |
| sunburst | REJECTED | REJECTED | ACCEPTED (Ananya) / REJECTED (Richa) | ACCEPTED (Ananya) / REJECTED (Richa) | Same family policy; Richa cafe also rejected this run |
| seedream | ACCEPTED | ACCEPTED | ACCEPTED | ACCEPTED | All 8 persona×prompt cells succeeded at 1K |

## MODEL 1 — flare
- **Exact ID:** `openai/gpt-image-2.5-flare`
- **Provider:** openrouter (OpenAI)
- **Availability:** VERIFIED
- **Reference support:** YES — 5 refs sent
- **Resolution:** requested 1024→`1K`; actual candidates `1024×1024`
- **Prompts tested:** A–D × both personas
- **Attempts / Successful / Rejected / Technical failures:** 8 / 3 / 5 / 0
- **Identity:** Ananya bath+cafe STRONG–ACCEPTABLE vs FACE_CLOSE; Richa cafe ACCEPTABLE
- **Prompt adherence:** C/D PASS where accepted; A/B REJECTED (not assessed)
- **Visual quality:** GOOD (photoreal, large PNG ~1.5–1.7MB)
- **Reference adherence:** ACCEPTABLE–STRONG on accepted cells
- **Regeneration:** Ananya cafe PASS (`115f6a72-…` ← `159a1ce2-…`); Richa cafe REJECTED safety (`8f10bb29-…`)
- **Latency (succeeded):** n=3 min=22s max=34s avg=29s
- **Cost:** UNAVAILABLE
- **Restrictions:** Safety blocks on night/sea (both); Richa bathroom this run
- **Limitations:** Narrower acceptance than Seedream on adult-party/bikini prompts

## MODEL 2 — sunburst
- **Exact ID:** `openai/gpt-image-2.5-sunburst`
- **Provider:** openrouter (OpenAI)
- **Availability:** VERIFIED
- **Reference support:** YES — 5 refs
- **Resolution:** 1024→`1K`; actual `1024×1024`
- **Prompts tested:** A–D × both personas
- **Attempts / Successful / Rejected / Technical failures:** 8 / 2 / 6 / 0
- **Identity:** Ananya bath+cafe STRONG–ACCEPTABLE
- **Prompt adherence:** C/D PASS (Ananya only)
- **Visual quality:** GOOD (~1.5–1.7MB)
- **Reference adherence:** ACCEPTABLE–STRONG (Ananya)
- **Regeneration:** Ananya cafe REJECTED safety (`94db0a63-…`); Richa regen NOT AVAILABLE (no succeeded Richa parent this run)
- **Latency:** n=2 min=23s max=46s avg=34s
- **Cost:** UNAVAILABLE
- **Restrictions:** Same safety pattern as flare; stricter on Richa in this sample (0/4 Richa)
- **Limitations:** Lowest success rate in this matrix (2/8)

## MODEL 3 — seedream-5-0-pro
- **Exact ID:** `bytedance-seed/seedream-5-0-pro`
- **Provider:** openrouter (ByteDance Seed)
- **Availability:** VERIFIED after resolution fix
- **Reference support:** YES — 5 refs accepted (prior Task 25 failure was resolution, not refs)
- **Resolution:** **Previous:** `512` REJECTED. **New:** request 1024→`1K` (code also maps Seedream away from `512`). **Actual:** `1024×1024` (smaller JPEG/PNG payloads ~180–260KB)
- **Prompts tested:** A–D × both personas
- **Attempts / Successful / Rejected / Technical failures:** 8 / 8 / 0 / 0
- **Identity:** ACCEPTABLE overall — faces broadly consistent; clothing/scene vary more than GPT Image cafe looks
- **Prompt adherence:** A night PASS (red off-shoulder dress, night bokeh); B–D PASS/PARTIAL on scene cues
- **Visual quality:** ACCEPTABLE–GOOD (photoreal; smaller files)
- **Reference adherence:** ACCEPTABLE
- **Regeneration:** Ananya cafe PASS (`d53bc099-…`); Richa cafe PASS (`ced1fb27-…`); model stamp preserved as seedream
- **Latency:** n=8 min=45s max=101s avg=57s
- **Cost:** UNAVAILABLE
- **Restrictions:** None observed on these four seeds
- **Limitations:** Slower; output file size much smaller than GPT Image at same declared 1024×1024

## SEEDREAM RESOLUTION
- **Previous:** `512` → provider rejection  
- **New:** `1K` via 1024×1024 (+ code guard)  
- **Result:** ACCEPTED — 8/8 generations succeeded

## RICHA
Visual `c865e8a2-…`. Flare: only cafe accepted. Sunburst: all rejected. Seedream: all four prompts accepted. Regen: seedream PASS; flare REJECTED.

## ANANYA
Visual `161e5952-…`. Flare/sunburst: bath+cafe OK, night+sea REJECTED. Seedream: all four OK including night party dress. Regen: flare PASS; sunburst REJECTED; seedream PASS.

## WAREHOUSE
**PASS** — Ananya total≥23, Richa total≥8; candidates retain modelId (flare/sunburst/seedream present).

## ADMIN
**PASS** — signed-in inspection path already verified Task 24; warehouse API returns model/prompt/status; identity endpoint returns modelId + refs.

## MODEL SWITCHING
**PASS** — sequential jobs with different `modelId` without changing production config.

## HISTORICAL METADATA
**PASS** — flare job `b3b8c1c0-…` still stamps flare; seedream `d5309d98-…` stamps seedream; sunburst `79472198-…` stamps sunburst.

## SILENT FALLBACK
**PASS** — requested modelId equals identity.modelId on inspected jobs; failures remain FAILED.

## COST
**UNAVAILABLE**

## SLA
**Not configured**

## PROVIDER RESTRICTIONS
OpenAI GPT Image family: safety rejections on night-party and sea/bikini seeds (both personas); additional Richa rejections on bathroom (flare) and cafe (sunburst) this run. Seedream: none on this matrix.

## TECHNICAL FAILURES
None (0 timeouts / 0 parse errors in the 24-cell matrix). Seedream resolution bug fixed before matrix.

## IMPLEMENTATION GAPS
- Per-job `modelId` on Admin create (added for this task).
- Seedream `512`→`1K` mapping (added).
- Cost still not ingested.
- Admin UI does not need redesign for comparison; model is visible via warehouse/API.

## EVIDENCE
**Job IDs (matrix):** see `pd-t28-jobids.txt` / table above — 24 ids starting `f8de9a2a-…` … `9a82dc06-…`  
**Sample assets:** `159a1ce2-…` (Ananya flare cafe), `46a48f23-…` (Ananya sunburst cafe), `f60e011f-…` / `669b25ac-…` (Ananya seedream cafe/night), `a568982c-…` / `f1182b54-…` (Richa seedream), `5553fce7-…` (Richa flare cafe)  
**Regen jobs:** `115f6a72-…` PASS, `94db0a63-…` REJECTED, `d53bc099-…` PASS, `ced1fb27-…` PASS, `8f10bb29-…` REJECTED  
**Evaluation IDs:** N/A (matrix used job create + modelId, not eval API)

## Comparison table (descriptive only — no winner)

| Dimension | flare | sunburst | seedream-5-0-pro |
|---|---|---|---|
| Exact model ID | openai/gpt-image-2.5-flare | openai/gpt-image-2.5-sunburst | bytedance-seed/seedream-5-0-pro |
| Provider | openrouter | openrouter | openrouter |
| Image generation | yes | yes | yes |
| Reference support | 5 refs | 5 refs | 5 refs |
| Identity observations | Strong on accepted Ananya cafe/bath | Strong on accepted Ananya | Acceptable; more clothing variance |
| Prompt adherence | Pass C/D when accepted | Pass C/D Ananya | Pass A–D in this sample |
| Visual quality | GOOD large PNG | GOOD | ACCEPTABLE–GOOD smaller files |
| Reference adherence | ACCEPTABLE–STRONG | ACCEPTABLE–STRONG | ACCEPTABLE |
| Regeneration | Ananya PASS / Richa REJECTED | Ananya REJECTED | Ananya+Richa PASS |
| Restrictions | Safety A/B (+ some Richa) | Safety A/B + all Richa this run | None observed |
| Resolution | 1K / 1024² | 1K / 1024² | 1K / 1024² (was blocked at 512) |
| Reliability (this sample) | 3/8 | 2/8 | 8/8 |
| Latency | ~29s avg | ~34s avg | ~57s avg |
| Cost | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |

## FINAL STATUS
**COMPLETE**

**Evidence sufficient for production-model decision:** YES  

**Production model changed:** NO  

## Files changed
- `OpenRouterImageProvider.kt` — Seedream resolution floor `1K`
- `AdminImageGenerationRoutes.kt` — optional `modelId` on create
- `docs/23 sept/23-28_IMAGE_MODEL_CONTROLLED_COMPARISON.md` (this file)
- `MORNING_REVIEW_BRIEF.md`, `23-00_FULL_TASK_CROSS_VALIDATION.md`, `IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`

## Updated status file
`docs/23 sept/IMAGE_PIPELINE_IMPLEMENTATION_STATUS.md`
