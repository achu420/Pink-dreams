# TASK 30 — MULTI-MODEL IMAGE BENCHMARK PREPARATION

**Date:** 2026-09-23  
**Branch:** QA  
**Status:** READY (preparation only)

## STATUS
READY

## MODELS

Live discovery source: `GET https://openrouter.ai/api/v1/images/models` (2026-09-23).  
Public list prices (reference only): `GET https://openrouter.ai/api/v1/models`.  
Actual benchmark cost field is `usage.cost` from the generation response when present; otherwise `UNAVAILABLE`. Public pricing is never invented into `actualCost`.

### Seedream 5 Pro
- live model ID: `bytedance-seed/seedream-5-0-pro`
- availability: AVAILABLE
- capability discovery: live `supported_parameters`
- reference limit: 14
- candidate limit: 1
- resolution: 1K, 2K (common 1K used)
- pricing information source: OPENROUTER_MODELS_API (reference) / actual = OPENROUTER_RESPONSE or UNAVAILABLE

### Seedream 5 Lite
- live model ID: `bytedance-seed/seedream-5-0-lite`
- availability: AVAILABLE
- capability discovery: live
- reference limit: 14
- candidate limit: 4
- resolution: 2K, 4K — **1K not listed**; closest **2K** recorded as deviation
- pricing information source: OPENROUTER_MODELS_API / actual request

### Seedream 4.5
- live model ID: `bytedance-seed/seedream-4.5`
- availability: AVAILABLE
- capability discovery: live
- reference limit: 14
- candidate limit: 10 (benchmark requests 4)
- resolution: 1K, 2K, 4K
- pricing information source: OPENROUTER_MODELS_API / actual request

### FLUX.2 Pro
- live model ID: `black-forest-labs/flux.2-pro`
- availability: AVAILABLE
- capability discovery: live
- reference limit: 8
- candidate limit: 1
- resolution: no resolution enum published (`RESOLUTION_ENUM_NOT_PUBLISHED`; request 1K/1024)
- pricing information source: OPENROUTER_MODELS_API / actual request

### FLUX.2 Klein
- live model ID: `black-forest-labs/flux.2-klein-4b`
- availability: AVAILABLE
- capability discovery: live
- reference limit: 4 (omits BACK)
- candidate limit: 1
- resolution: no resolution enum published
- pricing information source: OPENROUTER_MODELS_API / actual request

### GPT Image 2
- live model ID: `openai/gpt-image-2` (not 2.5 Flare/Sunburst)
- availability: AVAILABLE
- capability discovery: live
- reference limit: 16
- candidate limit: 10 (benchmark requests 4)
- resolution: no resolution enum published
- pricing information source: OPENROUTER_MODELS_API / actual request

### Nano Banana 2 Lite
- live model ID: `google/gemini-3.1-flash-lite-image`
- availability: AVAILABLE
- capability discovery: live
- reference limit: 14
- candidate limit: 1
- resolution: 1K
- pricing information source: OPENROUTER_MODELS_API / actual request

### Riverflow 2.5 Fast
- live model ID: `sourceful/riverflow-v2.5-fast`
- availability: AVAILABLE
- capability discovery: live
- reference limit: 4 (omits BACK)
- candidate limit: 1
- resolution: 1K, 2K
- pricing information source: OPENROUTER_MODELS_API / actual request

### Muse Image
- live model ID: `meta/muse-image`
- availability: AVAILABLE
- capability discovery: live catalog row present; `supported_parameters` empty → n/refs/resolution UNKNOWN (conservative n=1, send all 5 standard refs)
- pricing information source: OPENROUTER_MODELS_API / actual request

### Qwen Image 3 Pro
- live model ID: `qwen/qwen-image-3-pro`
- availability: AVAILABLE
- capability discovery: live
- reference limit: 4 (omits BACK)
- candidate limit: 6 (benchmark requests 4)
- resolution: 1K, 2K
- pricing information source: OPENROUTER_MODELS_API / actual request

## BENCHMARK

Prompts configured: 4 (PROMPT_01–04, exact task text)  
Personas selectable: yes — Admin multi-select; no automatic Ananya/Richa  
Reference validation: eligibility requires FRONT, FACE_CLOSE, LEFT_PROFILE, RIGHT_PROFILE, BACK  
Model capability validation: live snapshot stored on `benchmark_model`  
Cost capture: wired (`usage.cost` → `image_jobs.provider_cost_*` → `benchmark_execution.actual_cost`)  
Provider rejection capture: classified ACCEPTED / PROVIDER_REJECTED / TECHNICAL_FAILURE / MODEL_UNAVAILABLE / UNSUPPORTED_PARAMETER  
Candidate persistence: existing warehouse path (`jobs/{jobId}/candidates/{index}`) — all returned candidates stored  

Reference-selection policy: omit from the **end** of STANDARD_SLOTS (BACK first). Never random.

## ADMIN

Benchmark configuration: PASS  
Readiness check: PASS (`GET /v1/admin/images/benchmarks/readiness`)  
Comparison view: PASS (`GET /v1/admin/images/benchmarks/{id}/comparison` — no winner/ranking)  
Database: PASS (`V017__image_model_benchmark.sql`)  
API: PASS  

Admin UI: **Admin → Image Model Benchmark**

## IMAGE GENERATION
NOT RUN

## PRODUCTION MODEL
UNCHANGED

## TESTS
Focused: `ImageModelBenchmarkPreparationTest` + `AdminImageBenchmarkRoutesTest` + catalog/provider/job/eval suites — **BUILD SUCCESSFUL**.  
Imaging + Admin image tests (`com.pinkdreams.imaging.*`, `AdminImage*`, `ImageJobRepositoryRegressionTest`) — **BUILD SUCCESSFUL**, 0 failed.

## FILES CHANGED
- `db/migration/V017__image_model_benchmark.sql`
- `src/main/kotlin/com/pinkdreams/persistence/database/DatabaseFactory.kt`
- `src/main/kotlin/com/pinkdreams/imaging/benchmark/*`
- `src/main/kotlin/com/pinkdreams/api/admin/AdminImageBenchmarkRoutes.kt`
- `src/main/kotlin/com/pinkdreams/Application.kt`
- `src/main/kotlin/com/pinkdreams/imaging/provider/openrouter/OpenRouterImageModelCatalog.kt`
- `src/main/kotlin/com/pinkdreams/imaging/provider/openrouter/OpenRouterModelsPricingCatalog.kt`
- `src/main/kotlin/com/pinkdreams/imaging/provider/openrouter/OpenRouterImageProvider.kt`
- `src/main/kotlin/com/pinkdreams/imaging/provider/GenerationResult.kt`
- `src/main/kotlin/com/pinkdreams/imaging/job/ImageJobHandler.kt`
- `src/main/kotlin/com/pinkdreams/imaging/job/ImageJobWorker.kt`
- `src/main/kotlin/com/pinkdreams/imaging/orchestration/ImageGenerationHandler.kt`
- `src/main/kotlin/com/pinkdreams/persistence/repositories/ImageJobRepository.kt`
- `src/main/resources/admin-ui.html`
- `src/test/kotlin/com/pinkdreams/imaging/benchmark/ImageModelBenchmarkPreparationTest.kt`
- `src/test/kotlin/com/pinkdreams/api/admin/AdminImageBenchmarkRoutesTest.kt`
- `docs/23 sept/30_MULTI_MODEL_IMAGE_BENCHMARK_PREPARATION.md`

## COMMIT
Not created (not requested).

## NEXT STEP
WAIT FOR PERSONA SEEDING
