# TASK 26 — IMAGE MODEL DISCOVERY & SELECTION

**Discovery date:** 2026-09-23  
**OpenRouter catalog checked:** YES — live `GET https://openrouter.ai/api/v1/images/models`  
**Catalog model count:** 53 (52 accept image input)

---

## Initial candidates (families)

| Family | Examples seen in catalog | Image input |
|--------|--------------------------|-------------|
| OpenAI GPT Image | `openai/gpt-image-2.5-flare`, `…-sunburst`, `gpt-image-2`, `gpt-image-1` | yes |
| ByteDance Seedream | `bytedance-seed/seedream-5-0-pro`, `…-lite` | yes |
| Qwen Image | `qwen/qwen-image-3-pro`, `qwen/qwen-image-3` | yes |
| Google Gemini Image | `google/gemini-3.1-flash-image`, `gemini-3-pro-image` | yes |
| Black Forest FLUX.2 | `flux.2-pro`, `flux.2-max`, `flux.2-flex`, `flux.2-klein-4b` | yes |
| xAI Grok Imagine | `x-ai/grok-imagine-image-2.0` | yes |
| Microsoft MAI | `microsoft/mai-image-2.6`, `…-flash` | yes |
| Recraft / Krea / Meta Muse / Sourceful | multiple | yes |

---

## Models eliminated (examples)

| Model / family | Reason |
|----------------|--------|
| `inclusionai/ming-image-0.1-design` | text-only inputs — no reference-image path for persona identity |
| Recraft vector variants | Vector/illustration focus — not primary photo-real persona identity path |
| Duplicate preview SKUs (e.g. gemini preview) | Prefer stable non-preview IDs for evaluation |
| FLUX.2 Klein 4B | Kept as alternate pool; not in first 3 — prefer verified OpenAI path + one non-OpenAI |

---

## Selected models for Task 25 evaluation

### MODEL 1
- **Name:** GPT Image 2.5 Flare  
- **Exact OpenRouter ID:** `openai/gpt-image-2.5-flare`  
- **Provider:** OpenAI via OpenRouter  
- **Image generation:** YES  
- **Reference support:** YES (catalog `input_modalities` includes `image`; verified in-code for flare)  
- **Restrictions:** Provider safety still applies; do not claim “unrestricted”  
- **Live result:** Catalog availability VERIFIED 2026-09-23  
- **Known limitations:** Cost not ingested by our pipeline yet  

### MODEL 2
- **Name:** GPT Image 2.5 Sunburst  
- **Exact OpenRouter ID:** `openai/gpt-image-2.5-sunburst`  
- **Provider:** OpenAI via OpenRouter  
- **Image generation:** YES  
- **Reference support:** YES (catalog image input)  
- **Restrictions:** Same family policy as Flare  
- **Live result:** Catalog availability VERIFIED  
- **Known limitations:** Same cost gap  

### MODEL 3
- **Name:** Seedream 5.0 Pro  
- **Exact OpenRouter ID:** `bytedance-seed/seedream-5-0-pro`  
- **Provider:** ByteDance Seed via OpenRouter  
- **Image generation:** YES  
- **Reference support:** LIKELY (catalog image input) — generation smoke still needed for policy behavior  
- **Restrictions:** Unknown product-specific refusals until live persona prompts run  
- **Live result:** Catalog availability VERIFIED; live persona generation NOT YET RUN  
- **Known limitations:** Capability profile in app is family-heuristic, not endpoint-probed  

---

## Configuration / switching

- Precedence: request override → DB `image_provider_settings` → env → default (`ImageRuntimeConfig`)
- No silent model fallback: resolved `modelId` stamped on every job payload
- Admin: `GET/PATCH /v1/admin/images/config`, discovery `GET /v1/admin/images/models/discovery`
- Evaluation shortlist constant: `OpenRouterImageModelCatalog.SELECTED_FOR_EVALUATION`

---

## Code delivered

- `OpenRouterImageModelCatalog.kt`
- `GET /v1/admin/images/models/discovery`
- Broader `capabilitiesFor` heuristics for known image-input families
- Evaluation model list includes selected Seedream ID

---

## Remaining for full Task 25/27

- Live Generate on Richa/Ananya with selected 3 models (browser + worker)
- Fill restriction classifications from real provider responses
