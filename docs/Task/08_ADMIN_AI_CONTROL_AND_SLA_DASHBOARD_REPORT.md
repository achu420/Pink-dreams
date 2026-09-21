# Task 8 — Admin AI Control + SLA/Runtime Dashboard: Completion Report

## Scope actually delivered vs. requested

The task spec (Parts 1-14) is large. What follows is what was actually
built, verified, and tested — every part below is either done, partially
done with an explicit boundary, or explicitly deferred with a reason.
Nothing is claimed "implemented" without live evidence in Part N below.

## A. Files changed

**Main:**
- `persistence/database/DatabaseFactory.kt` — added `LlmExchanges.skillKey` column
- `chat/ChatEngine.kt` — added `ChatContext.selectedSkillKey`; stamps it after skill selection
- `llm/LlmClient.kt` — added `GenerationRequest.skillKey`, sourced from `ChatContext.selectedSkillKey` in `.from()`
- `llm/observability/ObservableLlmClient.kt` — records `request.skillKey` on every exchange (success, budget exhaustion, exception)
- `persistence/repositories/LlmExchangeRepository.kt` — `Exchange`/`RecordInput` carry `skillKey`
- `persistence/repositories/PerformanceMetricsRepository.kt` — `MetricsFilter.skillKey`/`.outcome`; `statsBySkill()`; `stageTimingsForTurn()`
- `config/AiRuntimeSettings.kt` — added `environmentModel` (documents a real, pre-existing gap — see H)
- `api/admin/AdminObservabilityRoutes.kt` — `effective-configuration` endpoint; `bySkill` in dashboard/export; `skillKey`/`outcome` filters on exchange list; `stageTimingsMs` in turn trace; `skillKey` in CSV/summary/detail DTOs
- `Application.kt` — wires the new `AdminObservabilityRoutes` constructor params
- `resources/admin-ui.html` — Skill/Outcome filters, "By Skill" table, Effective Configuration card, stage-timings block in turn trace, skill column everywhere

**Tests:** `AdminObservabilityRoutesTest.kt` (+9), `PerformanceMetricsRepositoryTest.kt` (+7), `ObservableLlmClientTest.kt` (+3), `PipelineChatEngineSkillIntegrationTest.kt` (+2) — 21 new tests total.

## B. Database/schema changes

One nullable column: `llm_exchanges.skill_key varchar(128)`. Auto-migrated
by the existing `SchemaUtils.createMissingTablesAndColumns` mechanism —
verified live (server started against the existing Postgres database with
no manual migration step). Nothing else changed. Deliberately the
**smallest safe structured field** requested by Part 4 — no persona/engine
version columns were added this time (that broader attribution work was
prototyped and explicitly reverted in the prior Task 7 turn per your
instruction to keep that task UI-only; Part 4 here re-scopes to skill
only, which is what's implemented).

## C. API changes

- `GET /v1/admin/observability/effective-configuration` — **new**. Returns one entry per workload (model/temperature/maxOutputTokens/reasoning/jsonMode/providerSort + source for each) exactly as `ChatEngineFactory.build()` constructs it today. Read-only, admin-gated, no persistence.
- `GET /v1/admin/observability/latency-dashboard` — now includes `bySkill`; accepts `skillKey`/`outcome` filters (added to existing `workload`/`model`/`provider`/`conversationId`/`isTestChat`/`from`/`to`).
- `GET /v1/admin/observability/exchanges` — accepts `skillKey`/`outcome` filters (applied in-memory on top of the required `conversationId`/`workload` dimension — result sets are already `limit`-capped, never a full scan).
- `GET /v1/admin/observability/turns/{turnRequestId}` — response now includes `stageTimingsMs` (nullable map).
- `GET /v1/admin/observability/export/exchanges.csv` — new `skillKey` column.
- `GET /v1/admin/observability/export/performance.json` — new `bySkill` field.
- `ExchangeSummaryResponse`/`ExchangeDetailResponse` — new `skillKey` field.

No existing endpoint's behavior changed for a request that doesn't use
the new filters — every new query param is additive and optional.

## D. UI changes

All inside the existing Observability tab (Task 7's AMIA dark-theme
restyle), no other tab touched:

- Skill (text) and Outcome (select) filter fields, wired into the shared `obsCurrentFilterParams()` so dashboard/exchange-list/turn-trace/both exports all honor them consistently (Part 7).
- New "Effective Configuration" card — a table of all 5 workloads with model/temperature/maxOutputTokens/reasoning/jsonMode/providerSort and a source badge (DATABASE/CODE_DEFAULT/ENVIRONMENT_OR_DEFAULT/PROVIDER_DEFAULT) per field.
- "By Skill" table added to the dashboard, same shape as the existing By Workload/Model/Provider tables.
- Exchange list and detail now show a Skill column/row.
- Turn trace now renders a "Pipeline stage timings" table below the exchange list when available, or an explicit "NOT CURRENTLY MEASURED" note when not.

## E. Settings that are now genuinely admin-editable

**Unchanged from Task 4/5**: `model`, `temperature`, `maxOutputTokens` via
`PUT /v1/admin/ai-settings` — still the only three settings actually
persisted in `ai_settings` and resolved per-request. No new persisted
setting was added in this task.

## F. Settings that remain code-only, and why

`intentModelOverride`, `intentJsonModeOverride`, `intentMaxOutputTokensOverride`,
`generationProviderSortOverride` remain exactly as Task 4 found them:
constructor parameters set once in `Application.kt`, not in
`AiSettingsRepository`. Part 1 explicitly permits this ("either leave it
clearly marked as code-only, or... only if straightforward and safe,
convert it"). Promoting them was assessed and **not done this pass**
because:

1. It requires new `AiSettingsRepository`/`AiSettings` columns, a new
   `UpdateAiSettingsRequest` shape, and new validation — a real schema
   and API surface expansion, not a small edit.
2. `TestChatService`'s `ConfigurationSnapshot` already pins these same
   four fields per-Test-Chat-conversation with an explicit
   `?: productionDependencies.X` fallback (a bug fixed earlier in this
   work stream). Wiring a second, DB-backed production default into that
   same precedence chain without a live regression risk needs its own
   focused pass and test updates across ~10 existing test files
   (`IntentModelOverrideTest`, `IntentJsonModeTest`,
   `IntentBudgetOverrideTest`, `GenerationProviderSortTest`, and others)
   that currently assert on the current code-only behavior.
3. Doing it safely inside this already-large task risked exactly the kind
   of half-finished, under-tested change the project's own conventions
   warn against.

Instead, Part 2's Effective Configuration view reports these four
honestly as `CODE_DEFAULT` / `PROVIDER_DEFAULT`, so an admin can SEE them
even though they can't edit them here — which is what Part 1 asks for in
the "leave clearly marked" branch.

## G. SLA dimensions available

Overall, by workload, by model, by provider, by skill, by outcome — all
five now support the full percentile/SLA-bucket/error-rate shape, and all
apply consistently across the dashboard, exchange list, turn trace (via
its own exchange set), and both exports. Persona/Conversation-Engine/
Intent-Engine-version breakdowns are **NOT available** — not attempted
this task, see Part 4's scope note above.

## H. Skill attribution implementation

The authoritative point where the selected skill exists is
`SkillSelection.Selected(skillKey)`, produced once per turn by
`IntentDiscovery.selectSkill()` inside `ChatEngine.process()`. No new
routing decision, no free-text inference: the exact same value is now
stamped onto `ChatContext.selectedSkillKey` right after that call
resolves, flows through `GenerationRequest.from()` into
`GenerationRequest.skillKey`, and `ObservableLlmClient` persists it onto
the `primary_generation` exchange (the only call that reuses the
skill-enriched context). `SkillSelection.None` and any exchange predating
this column both persist/read as `null` — the API and dashboard
deliberately do not try to distinguish them (there is no way to, and
guessing would be worse than an honest "no attribution available").

**Live-verified**: a real Test Chat message ("Hey gorgeous, you look
absolutely stunning today...") produced `selectedSkill: "romantic_conversation"`
in the chat response, and the corresponding `primary_generation` exchange
in `/v1/admin/observability/exchanges` carries `"skillKey":"romantic_conversation"`
while the same turn's `intent_discovery` and `memory_extraction` exchanges
correctly have no `skillKey` (it's specific to the generation call that
used the enriched context, per design).

## I. Stage timings available/unavailable

**Available and now exposed**: `context_assembly`, `intent_discovery`,
`memory_context_selection`, `skill_context_enrichment`, `generation`,
`regeneration` (only on a rejected-then-regenerated turn), `persist`,
`total_before_persist` — exactly the keys `ChatEngine.process()` already
measures and `RepositoryChatPersistence` already writes into the
assistant message's `metadata.lvm_stage_timings` blob. No new
instrumentation was added (per Part 6's own instruction); the new
`PerformanceMetricsRepository.stageTimingsForTurn()` only reads and
re-shapes this pre-existing data, joined by the shared `ChatRequest.requestId`.

**NOT CURRENTLY MEASURED** (per the spec's own required phrasing):
- A separate "HTTP/request start" timestamp distinct from `context_assembly`'s start
- "Prompt construction" as its own named stage (it happens inside `generation`'s measured window, not separately timed)
- "Response" and "asynchronous memory work" as stages with their own start/end (async post-delivery hooks — memory extraction, continuity, memory-engine maintenance — are fire-and-forget by design and are NOT part of `stageTimingsMs`; their own latency is separately visible per-exchange in the LLM exchange list, just not as a named pipeline stage)

Live-verified: a real turn's trace returned
`{"context_assembly":634,"intent_discovery":2495,"memory_context_selection":194,"skill_context_enrichment":120,"generation":2468,"total_before_persist":6125}`.

## J. Export changes

CSV: added `skillKey` column (empty string when null, matching the
existing convention for other nullable fields). Raw request/response
bodies remain excluded — unchanged policy from Task 6. Performance JSON:
added `bySkill`, same shape as the other breakdowns. Both exports honor
the same `skillKey`/`outcome` filters as the dashboard.

## K. Security/redaction verification

Live-tested: fetched a 200-row CSV export and grepped for
`bearer`/`api key`/`sk-or` (case-insensitive) — zero matches, same as
every prior task's verification. No new field introduced in this task
carries request/response content or credentials — `skillKey` is a
short internal identifier (e.g. `"flirting"`), never provider output.

## L. Tests

21 new tests added:
- `ObservableLlmClientTest` (+3): skill captured on success, null skill never guessed at, skill captured even on a failed exchange
- `PipelineChatEngineSkillIntegrationTest` (+2): full-pipeline proof that `GenerationRequest.skillKey` reflects the real `SkillSelection` outcome, both Selected and None
- `PerformanceMetricsRepositoryTest` (+7): `statsBySkill` grouping (including the "none" merge), `skillKey`/`outcome` filters, `findExchanges` preserves skill, `stageTimingsForTurn` happy path + two negative paths (no message, legacy metadata without the key)
- `AdminObservabilityRoutesTest` (+9): `bySkill` in dashboard + skill filter, exchange-list skill/outcome filters, null-skill exchange doesn't crash rendering, CSV skill column, effective-configuration shape + admin-gating, turn-trace stage timings present/absent

## M. Full-suite result

`gradle test --rerun`: **BUILD SUCCESSFUL**, all tests passed on this
run — the previously-tracked pre-existing `PhaseIMG4ImageJobInfrastructureTest`
flake did not even trigger this time (it's intermittent, confirmed
unrelated to this task's changes across multiple earlier task cycles in
this same work stream).

## N. Live verification

Full walkthrough performed against the running application (`/admin`,
real Postgres, real OpenRouter calls):

1. Restarted the app — the new `skill_key` column was auto-created with no manual migration.
2. `GET /v1/admin/observability/effective-configuration` returned all 5 workloads with correct sources, confirming `primary_generation` alone is `ENVIRONMENT_OR_DEFAULT`-aware while the other four are hardcoded — matching the real code, not an assumption.
3. Created a fresh Test Chat conversation and sent a flirty message; the response's `selectedSkill` was `romantic_conversation`.
4. `GET .../exchanges?conversationId=...` showed exactly 3 exchanges for that turn, with `skillKey` present only on `primary_generation`.
5. `GET .../exchanges?...&skillKey=romantic_conversation` correctly narrowed to that 1 exchange.
6. `GET .../latency-dashboard` showed `bySkill` containing both `"none"` (47 prior exchanges) and `"romantic_conversation"` (the new one).
7. `GET .../turns/{turnId}` returned `stageTimingsMs` with real millisecond values summing sensibly against `onPathLatencyMs`.
8. `GET .../export/exchanges.csv` included the `skillKey` column with the correct value on the right row.
9. Grepped a 200-row CSV export for `bearer`/`api key`/`sk-or` — zero matches.
10. Confirmed a non-admin principal gets 403 from `effective-configuration`.
11. Confirmed the admin page (`/admin`) actually serves the new markup (`obsSkillFilter`, `obsEffectiveConfig`, `loadEffectiveConfiguration`, `stageTimingsBlock` all present in the served HTML).

## O. Remaining gaps (explicit, not fabricated)

- **Parts 1 promotion of code-only settings to persisted** — not done, reasoned in F above.
- **Persona/Conversation-Engine/Intent-Engine-version SLA breakdown** — not attempted; only skill was added as the "smallest safe field" Part 4 asked for.
- **Filtering by date/time range in the UI** — the backend `from`/`to` params already existed pre-Task-8 and still work, but no date-picker UI control was added this task (out of the bounded scope taken on).
- **A dedicated "conversation" and "turn" filter row in the dashboard filters** (separate from the existing Exchange & Turn Explorer inputs) — not added; the existing explorer already covers conversation/turn lookup, just not as a dashboard-level filter dimension.
- **Model/provider filters remain exact-match** (`eq`), not substring "contains" as their UI labels suggest — this is a pre-existing Task 3/5 behavior, not something this task was asked to fix, and was left alone per the "don't change more than requested" instruction.
- **"HTTP/request start" and "prompt construction" as distinct stages** — NOT CURRENTLY MEASURED, as stated in I.

## Safety confirmation (Part 11)

No Persona Core content, Skill behavior, Conversation Engine behavior,
Memory behavior, Intent model selection, primary generation model,
provider routing, or production prompts were changed. The only
behavior-adjacent addition is that `ChatContext` now carries one extra
nullable field (`selectedSkillKey`) that no existing code reads except
the new attribution path — verified by the full test suite passing
unchanged elsewhere. Git status confirms no other files were touched.
Nothing was committed in this task, per your explicit instruction.
