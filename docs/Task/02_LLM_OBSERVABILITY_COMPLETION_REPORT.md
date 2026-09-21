# Task 2 — LLM Observability and Raw Exchange Capture: Completion Report

## Summary

Every LLM call site now flows through a single decorator, `ObservableLlmClient`
(`src/main/kotlin/com/pinkdreams/llm/observability/ObservableLlmClient.kt`),
wrapped exactly once around the shared `LlmClient` instance in
`ChatEngineFactory.build()`. No existing call site changed its invocation
code. Every exchange — success, provider error, parse failure, budget
exhaustion, or any other exception — is persisted to a new `llm_exchanges`
table (`DatabaseFactory.kt`, `LlmExchangeRepository.kt`), correlated by
`turn_request_id`, `conversation_id`, and an explicit, code-owned `workload`
tag (`intent_discovery`, `primary_generation`, `memory_extraction`,
`continuity_summarization`, `memory_engine_maintenance`).

## Capture fields implemented

Per the task's list: exchange ID, turn/request ID, conversation ID,
workload, `is_test_chat`, timestamp, latency (ms, measured around the
delegate call — not estimated), model, upstream provider (parsed from
OpenRouter's own `provider` field, e.g. "CoreWeave", "DeepInfra", "Relace",
"OpenAI"), prompt/completion/total/reasoning tokens, finish reason,
HTTP status code, outcome classification, error class + message, and raw
request/response bodies.

Not implemented as separate fields (not required by the capture list, and
already covered elsewhere): retries/timeouts (OpenRouterLlmClient does not
retry — a single attempt's outcome is what's recorded), provider routing
configuration and skill/intent result (already present on the message-level
`generationConfig`/`responseMetadata` diagnostics from the pre-existing
debug panel; joinable by `turn_request_id`).

## Test evidence — all 10 required properties

`src/test/kotlin/com/pinkdreams/llm/observability/ObservableLlmClientTest.kt`,
10/10 passing (`gradle test --tests "com.pinkdreams.llm.observability.ObservableLlmClientTest"` → BUILD SUCCESSFUL):

1. Successful call captured
2. Provider failure captured (via `ExchangeCapturingFake`, since a thrown
   exception has no return value to carry the exchange)
3. Finish reason captured
4. Reasoning tokens captured when supplied
5. Malformed output distinguishable from provider failure — via
   `LlmExchangeRepository.markMalformed(turnRequestId, workload)`, called
   only from `LlmIntentDiscovery` (the one caller with a precise
   parse-outcome signal; the generic decorator cannot judge content shape
   for every workload)
6. Budget exhaustion distinguishable from normal completion
7. Diagnostics persistence does not block the response
8. A diagnostics persistence failure does not fail the response (proved
   against a genuinely uninitialized schema, forcing every insert to throw)
9. Secrets excluded from the persisted request (`Authorization` never
   reaches `ProviderExchange` — redacted at capture time in
   `OpenRouterLlmClient`, not display time)
10. Test Chat exchanges distinguishable from production via `is_test_chat`

Full suite: `gradle test` → 804 tests, all green except one pre-existing,
unrelated flake (`PhaseIMG4ImageJobInfrastructureTest`) that predates this
work. (A real infrastructure bug was found and fixed along the way: the
test task had no JVM heap limit configured, which OOM-crashed under the
full suite's load — fixed by setting `maxHeapSize = "2g"` in
`build.gradle.kts`. Unrelated to the observability code itself.)

## Live evidence

Ran an 11-turn Test Chat conversation
(`conversationId = 794980c1-c60f-4727-b5d5-648dca7c4cc0`) against the
running server (real PostgreSQL, not H2) and queried `llm_exchanges`
directly. Every one of the five wired workloads produced a real row:

| exchange id | workload | model | provider | latency | tokens (prompt/completion/reasoning) | finish reason | outcome |
|---|---|---|---|---|---|---|---|
| `b55f3209-2c5d-46fc-8499-97b3aa59689c` | intent_discovery | openai/gpt-4o-mini | OpenAI | 2010ms | 1444/8/0 | stop | SUCCESS |
| `22f3c3fd-017f-4543-a21b-292b569bd765` | primary_generation | deepseek/deepseek-v4-flash-0731 | CoreWeave | 1219ms | 2067/50/0 | stop | SUCCESS |
| `d91df477-c364-4988-a0a8-58708bb47a47` | memory_extraction | deepseek/deepseek-v4-flash-0731 | DeepInfra | 4408ms | 479/92/59 | stop | SUCCESS |
| `30b7e84a-6a62-446b-923e-04f4ded84222` | continuity_summarization | deepseek/deepseek-v4-flash-0731 | Relace | 6119ms | -/102/114 | stop | **BUDGET_EXHAUSTION** |
| `998cb8b5-bc65-44f9-87f3-74d37f64c205` | memory_engine_maintenance | deepseek/deepseek-v4-flash-0731 | Relace | 27540ms | 554/2199/2248 | stop | SUCCESS |

The continuity_summarization row is a genuine, unforced failure that
occurred naturally during this run (a reasoning model exhausted its token
budget on reasoning before producing content) — captured with the exact
error class (`OpenRouterBudgetExhaustionException`), message, provider, and
token breakdown, exactly the kind of "rare generation/provider outlier"
observability is meant to surface. The memory_engine_maintenance row is a
27.5s outlier from the same run, also captured with full fidelity.

## Honest capture-coverage statement

Do not read this as "100% of all possible LLM calls, always." What is
verified:

- All five currently-wired workloads (Intent, primary generation, memory
  extraction, continuity summarization, memory-engine maintenance) produced
  real, correctly-tagged rows from one live conversation, including one
  natural failure case.
- The decorator sits at the single, shared `LlmClient` construction point in
  `ChatEngineFactory.build()`, so any future call site built through that
  factory is captured automatically without further wiring.

What is NOT separately verified:

- Call sites outside `ChatEngineFactory`'s `build()` path, if any exist or
  are added later, would not automatically be wrapped — this was not
  audited exhaustively for every code path in the repository, only
  confirmed for the five documented workloads.
- Persistence-failure handling (property 8) is proven only via a
  synthetic/broken-schema test, not observed live (a live persistence
  failure was not intentionally induced against the running Postgres
  instance).
- Retry/timeout behavior is not separately tested because
  `OpenRouterLlmClient` does not currently retry; if retry logic is added
  later, each attempt would need its own exchange row or an explicit
  attempt-count field, neither of which exists today.

## Files changed

- `persistence/database/DatabaseFactory.kt` — new `LlmExchanges` table
- `persistence/repositories/LlmExchangeRepository.kt` — new
- `llm/observability/ObservableLlmClient.kt` — new
- `llm/ProviderExchange.kt` — new `ExchangeCapturing` interface
- `llm/OpenRouterLlmClient.kt` — implements `ExchangeCapturing`; parses
  upstream `provider`; carries `provider` on
  `OpenRouterBudgetExhaustionException`
- `llm/LlmClient.kt` — `GenerationConfig.workload` field
- `chat/ChatEngineFactory.kt` — wraps the shared client once; tags every
  `GenerationConfig` construction site with its workload
- `config/AiRuntimeSettings.kt` — tags primary generation's config
- `chat/skill/LlmIntentDiscovery.kt` — calls `markMalformed` on parse
  failure
- `build.gradle.kts` — `maxHeapSize = "2g"` for the test task (unrelated
  fix, needed to run the full suite reliably)
- Tests: `ObservableLlmClientTest.kt` (new, 10 tests),
  `AiRuntimeSettingsTest.kt` (mechanical update for new field),
  `TestChatServiceTest.kt` (fixed a pre-existing `ConcurrentModificationException`
  race in 3 test methods, unrelated to this task's logic but surfaced while
  running the full suite)

## Verdict

Task 2 is complete per its stated requirements: all 10 test properties
proven, live evidence produced for all five wired workloads including a
genuine failure case, secrets excluded, Test Chat distinguishable, and the
honest coverage limits stated above.
