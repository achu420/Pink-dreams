# Task 25 — live model comparison

**Evaluation:** `094b2a03-8542-47ca-9003-3eaaa66e2d06`  
**Persona:** Ananya `b5540c4f-ec74-4e5b-bf56-8d513c113964`, visual version `161e5952-3af6-4fb4-95fc-fa1b86aaec6d`  
**Seed (unchanged):** `evening at cafe`  
**References:** the same five standard slots as the acceptance jobs  
**Candidate count:** 1  
**Production model after the run:** `openai/gpt-image-2.5-flare` (`productionModelUnchanged=true`)  
**Cost:** UNAVAILABLE  
**Winner:** none. Notes were saved on the evaluation. Scores were not used to rank a model.

| Model | Job | Result | Latency | Candidate |
|---|---|---|---:|---|
| `openai/gpt-image-2.5-flare` | `d9d22f4e-513a-4d1f-94bf-75f4f7ec3f75` | SUCCEEDED | 60s | `91024b86-5417-4d6b-98d8-9f3618235394` |
| `openai/gpt-image-2.5-sunburst` | `f15d7d0e-cc59-4d83-adef-6979663361c0` | SUCCEEDED | 455s | `ce65a679-97b0-41e7-98ef-8352d573820c` |
| `bytedance-seed/seedream-5-0-pro` | `c3bd7841-43b9-4d87-80f5-9464e02fc238` | FAILED | 370s | none |

Flare and sunburst both show the same adult woman in a cafe, consistent with Ananya's `FACE_CLOSE` reference. Sunburst's first call read-timed-out; a later attempt returned the image.

Seedream rejected the request: resolution `512` is not accepted (accepted tiers are `1K` and `2K`). The error also listed `aspect_ratio` `1:1`, `output_format` `png`, `quality` `auto`, `n` `1`, and 5 `input_references`. No Seedream image was stored. Production was left on flare.
