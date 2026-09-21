# Task 4 — Admin AI Settings Verification: Report

Goal: before building any Admin UI (Task 5), establish exactly which
settings are genuinely admin-editable in production today, which are
experimental/code-level knobs, and which exist only as Test-Chat-only
pins — so the UI built in Task 5 cannot accidentally expose a knob as
"live and production-editable" when it is actually a hardcoded startup
value or a test-only override.

This is a verification task, not an implementation task — no code changed.
Every claim below is traced to a specific file/line.

## 1. Genuinely admin-editable, production-live settings

Persisted in the `ai_settings` table (`AiSettingsRepository.kt`), resolved
per-request by `AiRuntimeSettings.resolve()`
(`config/AiRuntimeSettings.kt:56`), exposed via
`GET/PUT /v1/admin/ai-settings` (`AdminAiSettingsRoutes.kt`):

| Setting | Applies to | Validation | Precedence when unset |
|---|---|---|---|
| `model` | Primary generation AND every side-channel call (`AiRuntimeSettings.sideChannelConfig()` reads the same resolved model) | non-blank string or null | environment/default (`LlmConfig`) |
| `temperature` | Primary generation only (`generationConfig()`); side-channels always pass `null`, i.e. provider default | `0.0..2.0` | provider default (was always null pre-ADMIN-2) |
| `maxOutputTokens` | Primary generation only | `1..32000` | environment/default (`LlmConfig`) |

Characteristics that make these "real" admin settings, not experiments:
- Take effect on the **next request**, no redeploy (`AiRuntimeSettings.resolve()` is called fresh every time, never cached at startup)
- Every field defaults to `null` = "not configured here", so an empty table reproduces pre-ADMIN-2 behavior exactly (`AiSettingsRepository.kt:17-23`)
- Writes require an explicit `confirm: true` flag (`AdminAiSettingsRoutes.kt:38`) — cannot be changed by an exploratory/malformed PUT
- `GET /v1/admin/ai-settings` reports `modelSource`/`temperatureSource`/`maxOutputTokensSource` (`DATABASE` vs `ENVIRONMENT_OR_DEFAULT`), so an admin can always tell whether a value is their own override or a fallback — Task 5's UI should surface this distinction directly, not just the resolved value
- `GET /v1/admin/ai-configuration` additionally reports the active Conversation Engine / Intent Engine / Memory Engine versions and active skill keys — read-only visibility, not editable from this endpoint (versions are activated via their own dedicated `/admin/engines`, `/admin/personas/.../versions`, etc. routes per the engine-versioning invariant in CLAUDE.md)

## 2. Experimental / code-level override knobs — NOT admin-editable

These exist as constructor parameters, set once at process startup in
`Application.kt`, with no route to change them without a code change and
redeploy:

| Override | Current production value | Set at | Governs |
|---|---|---|---|
| `AiRuntimeSettings.generationProviderSortOverride` | `"latency"` | `Application.kt:129` | Primary generation's OpenRouter provider-routing hint |
| `ChatEngineFactory.Dependencies.intentModelOverride` | `"openai/gpt-4o-mini"` | `Application.kt:196` | Intent Discovery's model, independent of the admin-editable `model` above |
| `ChatEngineFactory.Dependencies.intentJsonModeOverride` | `true` | `Application.kt:197` | Intent Discovery's `response_format: json_object` mode |
| `ChatEngineFactory.Dependencies.intentMaxOutputTokensOverride` | not set (null → falls back to the code default of 600) | — | Intent Discovery's token budget |

**Why these are deliberately not in `AiSettingsRepository`**: each was
introduced during a specific latency/reliability investigation (see their
doc comments — Intent Discovery Model Latency Investigation, Make Intent
Discovery Fast + Reliable, Primary Generation Latency phases) as an
isolated, reversible experiment knob, not a durable operational setting an
admin is expected to tune routinely. Promoting any of them to
`AiSettingsRepository` is a real, deliberate expansion of the admin
surface — not something to fold into Task 5 silently. Flagging for your
call: **if Task 5's Admin UI should let an admin change Intent's model,
JSON mode, budget, or generation's provider-sort preference live, that
requires new `AiSettingsRepository` columns and a migration — not just a
new UI panel over existing state.**

## 3. Test-Chat-only pins — never touch production

Accepted only by `POST /v1/admin/test-chat/conversations`
(`AdminTestChatRoutes.CreateTestChatRequest`, `TestChatService.CreationRequest`),
captured once into a per-conversation `ConfigurationSnapshot` at creation
time and replayed identically on every later turn/regeneration
(verified by the existing test "regeneration and every later turn in a
test conversation use the identical configuration snapshot" in
`TestChatServiceTest.kt`):

| Field | Pins |
|---|---|
| `conversationEngineVersion` | A specific engine version, published or active only — never draft |
| `personaCoreVersion` | A specific persona core version |
| `intentEngineVersion` | A specific Intent Engine version |
| `memoryEngineVersion` | A specific Memory Engine version |
| `skillVersions` | Per-skill-key version pins |
| `model` / `temperature` / `maxOutputTokens` | Same shape as the admin-editable production settings, but isolated per test conversation |
| `intentModel` / `intentMaxOutputTokens` / `intentJsonMode` | Same shape as the production-only experimental overrides above, but pinnable per test conversation |
| `generationProviderSort` | Same shape as `generationProviderSortOverride`, pinnable per test conversation |

**Critical isolation guarantee, already verified by existing tests**
("a production conversation is unaffected by test chat activity",
"running a test conversation never changes the active version of
anything", "testing a new skill version never makes it the active
production version"): none of these pins can ever write back to
`ai_settings`, to any engine's active-version pointer, or to any other
production-visible state. A Test Chat conversation that does NOT
explicitly set one of the four override-shaped fields
(`intentModel`/`intentMaxOutputTokens`/`intentJsonMode`/`generationProviderSort`)
inherits the PRODUCTION instance's own current value via the
`?: productionDependencies.X` fallback in
`TestChatService.buildEngineFor()` — not `null`, and not some other
default — a fix made earlier this session specifically because the naive
`.copy()` pattern silently reset these to null (see Task 2's session
history). This means Test Chat and production run byte-identically unless
a test conversation explicitly overrides something, which is the whole
point of "Test Chat runs the same pipeline as production" (ADMIN-3).

## 4. Summary table for Task 5's Admin UI to consume

| Category | Editable how | Where Task 5's UI should point |
|---|---|---|
| model / temperature / maxOutputTokens | Live, `PUT /v1/admin/ai-settings` | A real "AI Settings" panel — genuinely production-editable today |
| Engine/Persona/Memory/Intent active **versions** | Live, existing dedicated `/admin/engines`, `/admin/personas`, etc. routes | Link to those existing panels, do not duplicate |
| generationProviderSortOverride, intentModelOverride, intentJsonModeOverride, intentMaxOutputTokensOverride | Code + redeploy only | Read-only "current experimental configuration" display (Task 5 observability), NOT an editable form control, unless you explicitly ask to promote them to `AiSettingsRepository` first |
| Any Test-Chat-only pin | Test Chat conversation creation only | Already served by the existing `/v1/admin/test-chat/*` routes; out of scope for the production observability dashboard |

## Verdict

No code changes were needed for this task — it is a verification and
documentation exercise, and every claim above traces to a specific file
and line so it can be checked against the actual running code rather than
taken on faith. The one actionable decision for you: whether to promote
the four experimental overrides to genuinely admin-editable settings
before or as part of Task 5, since that changes Task 5's scope from
"visualize existing state" to "visualize existing state AND add new
editable settings."
