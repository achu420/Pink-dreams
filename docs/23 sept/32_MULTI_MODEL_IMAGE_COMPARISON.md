# Task 32 — Multi-model image comparison

**Original run:** `cb27818e-1f41-42ee-97c3-37361298b74a` (120 cells)  
**Follow-up run:** `da4f3af7-d9a5-4e72-917b-47a1fc6651ca` (72 cells: Lite / 4.5 / Qwen / Flux Pro / Klein / Riverflow)  
**GPT Zoya retry:** `76365b2a-1527-4331-8a46-82d805f1268a` (4 cells)  
**When:** 2026-09-23 / 2026-09-24  
**Production model:** unchanged (`openai/gpt-image-2.5-flare`)  
**Scoring:** identity, realism, restriction, actual cost. No winner.

Identity and realism are 0–5 admin scores from visual compare of generated candidates against each Persona’s FRONT and FACE_CLOSE references. Same prompt text across models for a given Persona.

## Restriction (combined)

| Model | Orig accepted | Follow-up accepted | Still no image |
|---|---:|---:|---|
| Seedream 5 Pro | 12 | — | — |
| Nano Banana 2 Lite | 8 | — | Gemini blocks on Zoya/Pihu P02–P03 |
| GPT Image 2 | 3 (Anaya P01/P04, Pihu P01) | 0 (Zoya retry: all 4 insufficient credits) | Safety + credits |
| Qwen Image 3 Pro | 3 (Anaya P01–P03) | 1 (Pihu P01, 3 refs, n=1) | Anaya sofa still Alibaba-blocked; remaining follow-up hit credits |
| Seedream 5 Lite | 0 (n=4 rejected) | 5 (Anaya P01, Zoya P01–P04) | Other Lite cells: credits |
| Seedream 4.5 | 0 (n=4 rejected) | 0 | Live needs ≥2K; 8 failed at 1024 then 4 failed on credits |
| FLUX.2 Pro | 0 (local “no refs”) | 2 (Anaya P01–P02, 2 refs) | Remaining 10: credits. Catalog lists `input_references` max 8; we sent 2 |
| FLUX.2 Klein 4B | 0 (local “no refs”) | 1 (Zoya P04, 2 refs) | Remaining 11: credits. Catalog max 4 refs; we sent 2 |
| Riverflow 2.5 Fast | 0 (local “no refs”) | 0 | All 12 rejected `output_format=png` (accepted jpeg). Catalog lists refs max 4; we sent 2 |
| Muse Image | 0 | — | Region unavailable |

Follow-up requested n=1 for Lite / 4.5 / Qwen; Qwen sent 3 refs; Flux/Riverflow sent FRONT+FACE_CLOSE only.

## Identity / realism (accepted cells)

Scored 0–5 against FRONT + FACE_CLOSE. Mean of accepted cells only (not a ranking).

| Model | Accepted scored | Mean identity | Mean realism |
|---|---:|---:|---:|
| Seedream 5 Pro | 12 | 4.2 | 4.1 |
| Nano Banana 2 Lite | 8 | 4.4 | 4.1 |
| GPT Image 2 | 3 | 4.7 | 4.7 |
| Qwen Image 3 Pro | 4 | 4.5 | 4.3 |
| Seedream 5 Lite | 5 | 4.4 | 4.0 |
| FLUX.2 Pro | 2 | 3.0 | 4.0 |
| FLUX.2 Klein 4B | 1 | 3.0 | 3.0 |

Notes from the visual pass:

- Seedream 5 Pro held face lock across all three Personas; sofa/profile shots dropped identity to 3.
- GPT cafe shots were the closest face matches (Anaya, Pihu).
- Qwen cafe (3 or 4 refs) also locked Anaya/Pihu faces; vacation came back modest sportswear instead of a bikini (identity still 4).
- Seedream 5 Lite matched Zoya strongly on P02–P03; often invented extra tattoos/scars.
- FLUX accepted 2 reference images (the earlier “does not support references” failure was local). Identity was weaker (bindi/bun/smile drift; Klein cloned Zoya as twins).

## Actual cost

- Original accepted: **USD 2.35076675**
- Follow-up accepted: **USD 0.360** (Lite $0.035 × 5, Flux Pro $0.060 × 2, Klein $0.016, Qwen $0.049)
- Combined recorded: **USD 2.71**
- Failures: UNAVAILABLE

## Follow-up blockers that stopped the rest

1. OpenRouter **insufficient credits** — 7 Lite, 10 Flux Pro, 11 Klein, 10 Qwen, 4 Seedream 4.5, and all 4 GPT Zoya retries.
2. Seedream 4.5 live minimum **2K** (3,686,400 px); 1K/1024 was refused. Code now requests 2K for that slot; not re-run after credits ran out.
3. Riverflow refuses `output_format=png` (jpeg only). Code now sends jpeg for riverflow; not re-run after credits ran out.

Catalog check: Flux Pro `input_references` max 8, Klein max 4, Riverflow max 4. Sending 1–2 refs is valid; the first run never reached the provider.

---

## Evidence answers (no rerun)

Source: live `benchmark_execution` / `benchmark_evaluation` via Admin GET for runs `cb27818e-…` (120), `da4f3af7-…` (72), `76365b2a-…` (4). Dumps: `.tmp/task32-evidence-<runId>.json`.

Persona IDs: Anaya `39544329-7e16-4bdf-946b-5b0cb2ddc16d` · Zoya `a8ddbdca-ca12-4feb-be89-45f865ed080c` · Pihu `0ad5156b-8087-4378-a7d8-af5ff05eaf99`.

### 1. Original 120 cells (`Persona × Prompt × Model × status`)

Original statuses: ACCEPTED 26 · UNSUPPORTED_PARAMETER 60 · TECHNICAL_FAILURE 21 · PROVIDER_REJECTED 13.

Status codes below: `A` accepted · `R` provider rejected · `T` technical · `U` unsupported parameter.

**Seedream 5 Pro** — all 12 `A`

**Seedream 5 Lite** — all 12 `U` (local: max 1 candidate, 4 requested)

**Seedream 4.5** — all 12 `U` (same n=4 vs n=1)

**FLUX.2 Pro** — all 12 `U` (local: “does not support reference images”)

**FLUX.2 Klein 4B** — all 12 `U` (same)

**Riverflow 2.5 Fast** — all 12 `U` (same)

**Muse Image** — all 12 `T` (“not available in your region”)

```
GPT Image 2              P01  P02  P03  P04
Anaya                     A    R    R    A
Zoya                      T    R    R    R
Pihu                      A    R    R    R

Nano Banana 2 Lite       P01  P02  P03  P04
Anaya                     A    A    A    A
Zoya                      A    R    R    A
Pihu                      A    R    R    A

Qwen Image 3 Pro         P01  P02  P03  P04
Anaya                     A    A    A    R
Zoya                      T    T    T    T
Pihu                      T    T    T    T
```

### Coverage matrices (accepted vs failed)

Use **combined** best status for decision-making: original first, follow-up only where original produced no image. `A` = at least one accepted image exists. Do not treat a later `A` as the same cell as the original 120.

**Original only**

```
SEEDREAM_5_PRO           P01  P02  P03  P04
Anaya                     A    A    A    A
Zoya                      A    A    A    A
Pihu                      A    A    A    A

SEEDREAM_5_LITE          all U
SEEDREAM_4_5             all U
FLUX_2_PRO               all U
FLUX_2_KLEIN_4B          all U
RIVERFLOW_2_5_FAST       all U
MUSE_IMAGE               all T
GPT / Nano / Qwen        see §1
```

**Follow-up `da4f3af7` (72 cells)**

```
SEEDREAM_5_LITE          P01  P02  P03  P04
Anaya                     A    T    T    T     (T = insufficient credits)
Zoya                      A    A    A    A
Pihu                      T    T    T    T

SEEDREAM_4_5             P01  P02  P03  P04
Anaya                     T    T    T    T     (needs ≥2K; 1024 refused)
Zoya                      T    T    T    T     (insufficient credits)
Pihu                      T    T    T    T     (needs ≥2K)

FLUX_2_PRO               P01  P02  P03  P04
Anaya                     A    A    T    T
Zoya                      T    T    T    T
Pihu                      T    T    T    T     (T = credits after Anaya P01–P02)

FLUX_2_KLEIN_4B          P01  P02  P03  P04
Anaya                     T    T    T    T
Zoya                      T    T    T    A
Pihu                      T    T    T    T

QWEN_IMAGE_3_PRO         P01  P02  P03  P04
Anaya                     T    T    T    R     (T = credits; P04 Alibaba block)
Zoya                      T    T    T    T
Pihu                      A    T    T    T

RIVERFLOW_2_5_FAST       all T (png rejected; jpeg listed as accepted)
```

**GPT Zoya retry `76365b2a`:** P01–P04 all `T` — insufficient credits (not safety).

Apparent model differences in averages are **mostly coverage differences**. Only Seedream 5 Pro has a full 3×4 accepted grid. Nano is missing Zoya/Pihu P02–P03. GPT is three cafe/sofa cells. Lite is Anaya cafe + Zoya only. Flux is three cells. Qwen is Anaya P01–P03 + Pihu cafe.

### 2. References on successful cells

Standard slots present in the warehouse (not five-of-five):

| Persona | Standard refs available | Sent on original “send all standard” cells |
|---|---|---|
| Anaya | FRONT, FACE_CLOSE, LEFT_PROFILE (3). No RIGHT, no BACK. | 3 |
| Zoya | FRONT, FACE_CLOSE, LEFT_PROFILE, BACK (4). No RIGHT. | 4 |
| Pihu | FRONT, FACE_CLOSE, LEFT_PROFILE, BACK (4). No RIGHT. | 4 |

| Model | Accepted cells | Refs actually sent |
|---|---|---|
| Seedream 5 Pro | 12 | Anaya 3; Zoya 4; Pihu 4 |
| Nano Banana 2 Lite | 8 | Anaya 3; Zoya/Pihu 4 |
| GPT Image 2 | 3 | Anaya 3; Pihu 4 |
| Qwen (orig) | Anaya P01–P03 | **3** (Anaya only had 3) |
| Qwen (follow-up) | Pihu P01 | **3** (FRONT, FACE_CLOSE, LEFT; BACK omitted by live cap) |
| Seedream 5 Lite (follow-up) | 5 | Anaya P01: 3; Zoya P01–P04: 4 |
| FLUX.2 Pro (follow-up) | Anaya P01–P02 | **2** FRONT + FACE_CLOSE |
| FLUX.2 Klein (follow-up) | Zoya P04 | **2** FRONT + FACE_CLOSE |

### 3. Identity / realism storage

**One `benchmark_evaluation` row per execution**, upserted on `execution_id`. `candidate_id` is optional (a pointer, not a per-image score table).

Reported means are **means of those 35 accepted execution scores**, not 51 individual images.

Material: GPT Anaya P01 has **4** candidates but **one** identity/realism pair. Same for Qwen Anaya P01–P03 (4 candidates each) and GPT Anaya P04 (2 candidates).

### 4. Seedream 5 Pro 12/12 coverage

Yes. All three Personas × all four prompts. n=1 each.

### 5. Nano Banana 2 Lite accepted 8

Anaya P01, P02, P03, P04. Zoya P01, P04. Pihu P01, P04.  
Zoya/Pihu P02 and P03: Gemini content moderation.

### 6. GPT Image 2 — 3 accepted + every rejection

Accepted (original only):

- Anaya P01 — safety passed; 4 candidates
- Anaya P04 — safety passed; 2 candidates
- Pihu P01 — safety passed; 4 candidates

Rejected **safety** (`PROVIDER_REJECTED`, OpenAI safety system): Anaya P02, Anaya P03; Zoya P02, P03, P04; Pihu P02, P03, P04.

Rejected **quota, not safety**:

- Original Zoya P01: `TECHNICAL_FAILURE` — OpenRouter **key daily limit**
- Retry Zoya P01–P04: `TECHNICAL_FAILURE` — OpenRouter **insufficient credits**

### 7. Qwen’s four accepted cells

Confirmed:

1. Original Anaya P01 (3 refs, n=4 requested / 4 actual)
2. Original Anaya P02 (3 refs, n=4)
3. Original Anaya P03 (3 refs, n=4)
4. Follow-up Pihu P01 (3 refs, n=1)

Original Anaya P04: Alibaba moderation. Original Zoya/Pihu all four: live I2I max 3 images, 4 sent. Follow-up Anaya P01–P03: credits (not a second accepted set).

### 8. Seedream 5 Lite’s five accepted

Confirmed exactly: **Anaya P01 + Zoya P01–P04**. All follow-up, n=1. Remaining 7 follow-up Lite cells: insufficient credits. Original 12 Lite: never reached the provider (n=4).

### 9. FLUX refs on accepted cells

Yes. Both accepted Pro cells and the Klein cell stored `references_sent=FRONT,FACE_CLOSE` (2). Catalog allows more; we capped at 2 in follow-up.

Original Flux/Riverflow **recorded** 3–4 refs on the execution row but the provider blocked locally before send.

### 10. Riverflow JPEG

**Implemented only. Not tested live after the change.** All 12 follow-up cells failed with `output_format: not supported. Accepted: jpeg` while we still sent png. Code now forces jpeg for `riverflow`. No later job used that path.

### 11. Seedream 4.5 2K

**Implemented only. Not tested successfully.** Follow-up: Anaya+Pihu (8) refused 1024×1024 (needs ≥3,686,400 px / 2K). Zoya (4) then failed on credits — still at 1024. Code now forces 2K for that slot. No accepted 4.5 image exists.

### 12. Per-image cost

Stored on the **job / execution**, not on `generated_candidate`. Failed cells: UNAVAILABLE.

Where `actualCandidates=1`, job cost is the image cost.

| Cell | Candidates | Job USD | Per-image |
|---|---:|---:|---|
| Seedream 5 Pro Anaya P01–P04 | 1 | 0.051 each | 0.051 |
| Seedream 5 Pro Zoya P01–P04 | 1 | 0.054 each | 0.054 |
| Seedream 5 Pro Pihu P01–P04 | 1 | 0.054 each | 0.054 |
| Nano Anaya P01–P04 | 1 | 0.03456350 / 0.03456750 / 0.03456550 / 0.03456525 | = job |
| Nano Zoya P01 / P04 | 1 | 0.03485525 / 0.03486000 | = job |
| Nano Pihu P01 / P04 | 1 | 0.03482600 / 0.03482975 | = job |
| Seedream 5 Lite (5 cells) | 1 | 0.035 each | 0.035 |
| FLUX.2 Pro Anaya P01 / P02 | 1 | 0.060 each | 0.060 |
| FLUX.2 Klein Zoya P04 | 1 | 0.016 | 0.016 |
| Qwen Pihu P01 (follow-up) | 1 | 0.049 | 0.049 |
| Qwen Anaya P01 / P02 / P03 | 4 | 0.169 each job | **UNAVAILABLE** (job only) |
| GPT Anaya P01 | 4 | 0.462394 | **UNAVAILABLE** |
| GPT Anaya P04 | 2 | 0.149312 | **UNAVAILABLE** |
| GPT Pihu P01 | 4 | 0.318428 | **UNAVAILABLE** |

### 13. Warehouse preservation

**35 accepted executions → 51 generated candidates.** GET `/v1/admin/images/assets/{candidateId}` returned **200 for all 51** (2026-09-24). Bytes are in object storage. Warehouse list is `/v1/admin/personas/{id}/images/warehouse`; asset path is `/v1/admin/images/assets/{candidateId}` (not the benchmark JSON’s `/candidates/{id}/asset`, which 404s).

### 14. Traceability

Yes, on each execution row plus job/candidate:

| Field | Where |
|---|---|
| Persona | `benchmark_execution.persona_id` |
| Visual identity version | `visual_identity_version_id` |
| Reference IDs sent | `references_sent` (`ROLE:uuid`, comma-separated) |
| Prompt ID | `prompt_id` (PROMPT_01–04). Text resolved per Persona via `BenchmarkPersonaPrompts` at start; canonical row text is the shared fallback |
| Model | `model_id` + `slot_key` |
| Provider | `openrouter` on `benchmark_model` |
| Job ID | `job_id` → `image_jobs` |
| Candidate ID | `generated_candidates` for that job (1–4) |
| Actual cost | `actual_cost` on the **execution/job** (not per candidate) |

### 15. Treatments that make a direct comparison invalid

Do **not** treat these as the same experiment:

1. **Prompt text differs by Persona** (intentional). Same prompt ID, different wording (Pihu bedroom wilder than Anaya). Compare models **within** a Persona+prompt, not across Personas.
2. **Reference count differs by Persona**, not by model, on the original “send all standard” path: Anaya 3 vs Zoya/Pihu 4 (missing RIGHT for everyone; Anaya also missing BACK).
3. **Qwen original vs follow-up n:** Anaya Qwen used **4 candidates**; Pihu follow-up used **1**. Same model, different n.
4. **Qwen Zoya/Pihu original** sent 4 refs and never ran; Pihu follow-up sent 3. Not comparable to the failed original cells.
5. **FLUX accepted cells used 2 refs**; Seedream/Nano/GPT/Qwen accepted cells used 3 or 4. Identity means (Flux 3 vs others 4+) mix different reference budgets.
6. **Candidate count requested:** GPT n=4; Qwen orig n=4; Seedream Pro/Lite follow-up/Nano/Flux n=1. GPT/Qwen scores are one rating over a pack.
7. **Original Lite / 4.5 / Flux / Riverflow** never generated; follow-up Lite/Flux are a **different run** (and Flux/Riverflow originally never left the process).
8. **Resolution / format:** 4.5 and Riverflow follow-up used 1K/png and failed. The 2K/jpeg fixes have **no successful cells**.

Cells that **are** comparable (same Persona, same prompt text, 3 refs, n=1 or treat as single-image): Anaya P01 across Seedream 5 Pro, Nano, and (loosely) Lite. GPT/Qwen Anaya P01 used the same 3 refs and same prompt but **n=4** and a pack score.

Production `OPENROUTER_IMAGE_MODEL` remains `openai/gpt-image-2.5-flare`. No generation was started to produce this section.
