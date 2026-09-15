# Pink Dreams — Implementation Specification

Downstream of the frozen `production-architecture.md`. That document answers "what should the system be"; this one answers "what exactly does Copilot create — which files, which tables, which endpoints, which tests." The architecture is not reopened here. Where this spec adds detail beyond the architecture (notably the idempotency execution-record state machine in section 13), that's an implementation-level refinement, not a redesign — flagged inline where it happens.

Stack: Kotlin, Ktor, PostgreSQL, kotlinx.serialization, Exposed (or an equivalent Postgres-compatible persistence layer), REST/JSON, UUID identifiers throughout.

The first implementation target is API contracts + data models + persistence + chat engine integration — not the complete consumer application.

---

## 1. Ownership boundary

Two people build against one shared contract simultaneously. Neither needs to understand the other's internals to integrate.

**You own — Chat Engine + Admin**
Persona system, conversation-engine versions, persona-core versions, matching, context assembly, memory, prompt construction, LLM provider integration, input/output validation, regeneration, chat pipeline, chat persistence contract, admin APIs (engine/persona/version administration), evaluation tooling.

**Partner owns — Application backend**
Authentication, user account, user-facing profile API, onboarding, subscription/payment, entitlement-facing UI/API, conversation-list UI/API, client integration, notifications, general application infrastructure.

**Shared — the contract itself**
The PostgreSQL schema for `user_profiles`, `conversations`, `messages`, `memory_facts`, `entitlements`; the HTTP API contracts; DTOs; the authentication contract (partner issues identity, you consume it); the error contract; the idempotency contract.

Changing a shared table or contract is a conversation between both of you, not a unilateral edit — treat the contract as the interface boundary it is.

---

## 2. Service shape

One Ktor deployment initially, with the chat engine as a clearly separated internal module — not a giant `Application.kt`, and not a premature microservice split.

```
                    ┌─────────────────────────┐
                    │      Client / App        │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │   App API (Ktor)         │
                    │  Auth · User · Sub ·     │
                    │  Onboarding · Convos ·   │
                    │  Payments                │
                    └────────────┬────────────┘
                                 │
                          Chat Engine API
                                 │
                                 ▼
             ┌────────────────────────────────────┐
             │           Chat Engine              │
             │  Persona resolution · Engine        │
             │  resolution · Context assembly ·    │
             │  Memory · Prompt construction ·     │
             │  LLM generation · Validation ·      │
             │  Regeneration · Persistence ·       │
             │  Idempotency                        │
             └───────────────┬────────────────────┘
                             │
              ┌──────────────┴──────────────┐
              ▼                             ▼
       PostgreSQL                       LLM Provider
```

If the chat engine ever needs to become its own deployed service, the module boundary defined here means that's an infrastructure change, not an API or code redesign.

---

## 3. Package layout

```
src/main/kotlin/com/pinkdreams/

    Application.kt

    config/
        AppConfig.kt
        DatabaseConfig.kt
        LlmConfig.kt

    api/
        chat/          ChatRoutes.kt, ChatRequest.kt, ChatResponse.kt
        conversation/  ConversationRoutes.kt, ConversationResponse.kt
        persona/       PersonaRoutes.kt
        admin/         AdminRoutes.kt
        health/        HealthRoutes.kt

    auth/
        Authentication.kt
        CurrentUser.kt

    chat/
        ChatService.kt
        ChatPipeline.kt
        ChatRequestContext.kt
        ChatResult.kt
        moderation/
        context/
        generation/
        validation/
        memory/
        matching/

    persona/
        PersonaService.kt
        PersonaRepository.kt
        EngineRepository.kt
        PersonaCoreRepository.kt

    conversation/
        ConversationService.kt
        MessageService.kt

    memory/
        MemoryService.kt

    entitlement/
        EntitlementService.kt

    persistence/
        database/
        migrations/
        repositories/

    models/
        domain/
        api/

    llm/
        LlmClient.kt
        LlmRequest.kt
        LlmResponse.kt
        provider/

    admin/
        AdminService.kt

    common/
        errors/
        ids/
        time/
        logging/
```

Package names can shift during implementation; the separation between API, service, domain, and persistence layers may not.

---

## 4. Dependency direction (enforced, not aspirational)

```
API
 ↓
Application services
 ↓
Domain / chat engine
 ↓
Repositories / infrastructure
 ↓
PostgreSQL / external APIs
```

The chat engine must not depend on Ktor routing types. Bad:

```kotlin
class ChatService(private val call: ApplicationCall)
```

Good:

```kotlin
class ChatService(/* domain + repo dependencies */) {
    suspend fun process(request: ChatRequest): ChatResult
}
```

Ktor is the transport layer. `ChatService` and everything under `chat/` must be unit-testable without starting the Ktor server — this is what makes the chat engine independently testable against a `FakeLlmClient` (section 19) without either a running server or a real LLM bill.

---

## 5. Migrations

Keep the frozen schema. Create it via versioned migrations, not application-startup table creation.

```
db/
    migration/
        V001__initial_schema.sql
        V002__...
```

Tool choice (Flyway, or Exposed's own migration support) is an implementation detail; the requirements aren't:
- versioned, in order, checked into the repo
- runnable against a fresh database and against an existing dev database
- never destructive without an explicit, reviewed migration (no silent column drops)
- runnable in CI as a gate before tests run

---

## 6. Core schema (as already frozen — restated here for implementation reference)

### `conversation_engines`

```sql
CREATE TABLE conversation_engines (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version         INTEGER NOT NULL UNIQUE,
    content         TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'draft'
                        CHECK (status IN ('draft','published','archived')),
    is_active       BOOLEAN NOT NULL DEFAULT false,
    changelog_note  TEXT,
    created_by      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_active_implies_published CHECK (NOT is_active OR status = 'published')
);
CREATE UNIQUE INDEX one_active_engine ON conversation_engines (is_active) WHERE is_active;
```
Invariant: `is_active = true ⇒ status = 'published'`. The index guarantees *at most one* active row; *exactly one* is a production-readiness check, not a schema guarantee — the admin service should surface "no active engine" as a startup/health warning, not assume it away.

### `personas`

```sql
CREATE TABLE personas (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug                    TEXT NOT NULL UNIQUE,
    display_name            TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'draft'
                                CHECK (status IN ('draft','active','retired')),
    gender                  TEXT NOT NULL,
    orientation             TEXT NOT NULL,
    apparent_age            INTEGER NOT NULL CHECK (apparent_age >= 25),
    language_profile        JSONB NOT NULL DEFAULT '{}',
    active_core_version_id  UUID,
    persona_identity_id     UUID REFERENCES persona_identity(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
No persona-specific code branches — ever. Bad: `if (persona == "simran") { ... }`. Good: `personaRepository.get(personaId)`. All application logic operates on `persona_id`; identity and voice live entirely in `persona_core_versions.content`.

### `persona_core_versions`

```sql
CREATE TABLE persona_core_versions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    persona_id      UUID NOT NULL REFERENCES personas(id),  -- no ON DELETE CASCADE: versions outlive a retired persona
    version         INTEGER NOT NULL,
    content         TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'draft'
                        CHECK (status IN ('draft','published','archived')),
    changelog_note  TEXT,
    author          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (persona_id, version)
);

ALTER TABLE personas
    ADD CONSTRAINT fk_active_core_version
    FOREIGN KEY (active_core_version_id) REFERENCES persona_core_versions(id);
```
`personas.active_core_version_id` pointing at a non-`published` row is a cross-table invariant a CHECK can't express — enforce with a trigger on insert/update of the pointer. Versions are never deleted; "delete a persona" means `status = 'retired'`, never a row purge.

### `user_profiles` (global, not persona-scoped)

```sql
CREATE TABLE user_profiles (
    user_id             UUID PRIMARY KEY REFERENCES users(id),
    display_name        TEXT,
    preferred_language  TEXT,
    communication_style TEXT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### `memory_facts` (per user, per persona — never global) — Memory v1.2: typed, scored, tiered

```sql
CREATE TABLE memory_facts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id),
    persona_id          UUID NOT NULL REFERENCES personas(id),
    fact                TEXT NOT NULL,
    fact_type           TEXT NOT NULL CHECK (fact_type IN (
                            'past_event','future_event','mindset','weakness',
                            'aspiration','desire','habit','want','interest')),
    criticality         TEXT NOT NULL DEFAULT 'medium' CHECK (criticality IN ('low','medium','high')),
    -- 'high' > 'medium' > 'low' is NOT alphabetical order — never sort by
    -- the raw text column. This generated column is the only thing every
    -- selection/eviction query is allowed to sort by.
    criticality_rank    SMALLINT GENERATED ALWAYS AS (
                            CASE criticality WHEN 'high' THEN 3 WHEN 'medium' THEN 2 WHEN 'low' THEN 1 END
                        ) STORED,
    tier                TEXT NOT NULL DEFAULT 'hot' CHECK (tier IN ('hot','cold')),
    status              TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','resolved')),
    source              TEXT NOT NULL DEFAULT 'llm_extracted' CHECK (source IN ('llm_extracted','manual')),
    learned_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_referenced_at  TIMESTAMPTZ,
    evicted_at          TIMESTAMPTZ
);
CREATE INDEX ix_memory_facts_user_persona ON memory_facts (user_id, persona_id);
-- Both selection and eviction read this index; each just walks it from a
-- different end. See the ordering note below.
CREATE INDEX ix_memory_facts_hot_selection
    ON memory_facts (user_id, persona_id, status, criticality_rank, last_referenced_at, learned_at)
    WHERE tier = 'hot';
```
A user's relationship with persona A and with persona B are disjoint fact sets. This is the single worst bug this product could ship if merged — enforce the `(user_id, persona_id)` scoping at the repository layer, not just by convention.

**Hot/cold lifecycle.** `tier='hot'` is the active working pool, capped at 20 rows per `(user_id, persona_id)` — enforced in `MemoryService`, not the DB (eviction is a read-modify-write, not something a CHECK can express). "Evicted to cold" means removed from the hot pool for capacity reasons only — it is not deletion. Cold rows persist for a future consolidation/summarization job (not built yet) and are removed only if a separate, still-undecided user-data retention/deletion policy (implementation spec section on history/retention) says to purge them; this table does not define that policy itself.

**Eviction order**, applied whenever an insert would push a pair's hot count past 20 — evict the row that sorts *first* in: `status = 'resolved'` before `'open'`, then lowest `criticality_rank`, then oldest `last_referenced_at`, then oldest `learned_at`. A resolved, low-criticality, stale fact goes cold before an active, high-criticality, recently-referenced one.

**Extraction.** A `MemoryExtractor` runs as a best-effort step *after* `deliver`, off the synchronous chat-request path — its failure or slowness must never affect the chat response, and if the process dies before this step runs, that turn's extraction is simply lost. That's an accepted POC tradeoff, not a reason to add a queue, outbox, or Kafka now; if extraction durability matters later, that's a change behind this same interface, not a memory-model redesign.
```kotlin
interface MemoryExtractor {
    suspend fun extract(turn: CompletedTurn): ExtractionResult
}

data class ExtractionResult(
    val newFacts: List<CandidateFact>,
    val referencedFactIds: List<UUID>,  // existing hot facts this turn actually engaged with
)

data class CandidateFact(
    val fact: String,
    val factType: FactType,
    val criticality: Criticality,
)
```
Implementation calls the LLM (same or a cheaper model) with a structured-output/tool-call schema over the just-completed turn — never mixed into the persona's visible reply text. `MemoryService.record(userId, personaId, result)` then:
1. **Validates and normalizes each candidate before it's trusted** — the model's `fact_type`/`criticality` are proposals, not authoritative: reject (or coerce to a safe default) anything outside the allowed enum values, trim and reject empty or over-length (`fact` capped at a fixed max, e.g. 240 chars) text. This is a small set of deterministic checks, not a second classifier — it stops a malformed or wildly mis-scored entry from occupying a hot-pool slot, nothing more ambitious than that.
2. **Dedupes** each surviving candidate against existing hot facts for that pair by `(fact_type, fact)` exact/near-exact text match — a known-coarse heuristic, not embedding-based, per the no-vector-store invariant. A match is dropped, not re-inserted.
3. **Inserts** genuinely new facts as `tier='hot'`, `status='open'`.
4. **Updates `last_referenced_at`** on the hot facts named in `referencedFactIds` — and *only* those. Selecting a fact into a prompt (`ContextAssembler` reading it) must never itself update this field; if it did, a frequently-selected fact would perpetually refresh its own recency just by being sent, entrenching it regardless of whether the user still engages with it, which defeats the point of tracking recency at all.
5. **Runs the eviction rule above** if the hot count for that pair now exceeds 20.

**Per-turn selection** (used by `ContextAssembler`, section 10) queries only `tier='hot'` rows for the pair, ordered `status ASC (open first), criticality_rank DESC, last_referenced_at DESC NULLS LAST, learned_at DESC`, capped at the context contract's configured limit (default 10) — high-criticality, open facts are always preferred; medium/low and resolved facts fill remaining slots only if there's room. This is a different number from the 20-row hot cap: 20 is what's *retained*, up to 10 is what's *spent* on any single call.

**Guardrail — enforce this at the code-review level, not just as a comment.** `memory_facts` (all types, `weakness` and `desire` included) is read only by `ContextAssembler` and by `MemoryExtractor`/`MemoryService` for write-back. Matching (`PersonaMatcher`), entitlement (`EntitlementService`), and any future monetization or conversion logic must never import or query this table. If a future feature seems to need it for a business purpose, that's a new, explicit architectural decision — not something that falls out of this table already existing.

### `conversations`

```sql
CREATE TABLE conversations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id),
    persona_id  UUID NOT NULL REFERENCES personas(id),
    state       TEXT NOT NULL DEFAULT 'active' CHECK (state IN ('active','idle','archived')),
    last_message_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### `messages`

```sql
CREATE TABLE messages (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id          UUID NOT NULL REFERENCES conversations(id),
    role                     TEXT NOT NULL CHECK (role IN ('user','assistant','system')),
    content                  TEXT NOT NULL,
    engine_version_id        UUID REFERENCES conversation_engines(id),
    persona_core_version_id  UUID REFERENCES persona_core_versions(id),
    client_message_id        UUID,
    request_id               UUID,
    metadata                 JSONB NOT NULL DEFAULT '{}',
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_version_matches_role CHECK (
        (role = 'assistant' AND engine_version_id IS NOT NULL AND persona_core_version_id IS NOT NULL)
        OR (role <> 'assistant' AND engine_version_id IS NULL AND persona_core_version_id IS NULL)
    )
);
-- Nullable client_message_id: only user messages originate from a client.
-- A partial unique index lets NULL rows (assistant/system) coexist freely
-- while still deduplicating real client sends.
CREATE UNIQUE INDEX uq_client_message_per_conversation
    ON messages (conversation_id, client_message_id)
    WHERE client_message_id IS NOT NULL;
-- If the role check above is trusted at the DB level, add:
--   CHECK (client_message_id IS NULL OR role = 'user')
```

### `entitlements`

```sql
CREATE TABLE entitlements (
    user_id                 UUID PRIMARY KEY REFERENCES users(id),
    state                   TEXT NOT NULL CHECK (state IN ('trial','free','extended','paid','lapsed')),
    trial_started_at        TIMESTAMPTZ,
    state_changed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    extended_days_remaining INTEGER NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
Owned by the partner's application (billing/onboarding writes it); the chat engine only reads it at the `entitlement_check` pipeline stage.

---

## 7. Idempotency — execution-record model (implementation-level refinement, not a reopening of the architecture)

The architecture's invariant #11 says a `client_message_id` must never produce a duplicate assistant response. The unique index on `messages` is necessary but not sufficient by itself: a conflicting *user*-message row does not by itself tell you whether the *assistant* response finished generating, is still generating, or failed. That requires an explicit execution record.

```sql
CREATE TABLE chat_request_executions (
    conversation_id     UUID NOT NULL REFERENCES conversations(id),
    client_message_id   UUID NOT NULL,
    request_id          UUID NOT NULL,
    status              TEXT NOT NULL DEFAULT 'processing'
                            CHECK (status IN ('processing','completed','failed')),
    assistant_message_id UUID REFERENCES messages(id),
    error_code          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    PRIMARY KEY (conversation_id, client_message_id)
);
```

Pipeline entry, made atomic via the primary key as the conflict authority (not a pre-check):

```
INSERT INTO chat_request_executions (conversation_id, client_message_id, request_id, status)
VALUES (:conversationId, :clientMessageId, :requestId, 'processing')
ON CONFLICT (conversation_id, client_message_id) DO NOTHING
RETURNING *;
```
- **Row returned (insert won)** → this caller owns generation. Proceed through the pipeline; on completion, `UPDATE ... SET status='completed', assistant_message_id=:id, completed_at=now()`; on unrecoverable failure, `status='failed', error_code=:code`.
- **No row returned (conflict — already existed)** → `SELECT` the existing row.
  - `completed` → fetch `assistant_message_id`, return that message. This is the retry-after-success case.
  - `processing` → another request (or an earlier attempt still in flight) owns this send; do not generate. Poll briefly with backoff, or return a `202`-style "still processing, retry" response — pick one and document it in section 12; do not silently generate a second time.
  - `failed` → safe to retry generation for this same `client_message_id`, since nothing was ever persisted for it.

This is the concrete answer to the race condition flagged in the architecture review: two near-simultaneous retries both attempt the insert; exactly one wins by the primary key, the other observes the conflict and reads the winner's outcome instead of generating independently. No distributed lock needed at POC scale — the primary key is the lock.

---

## 8. Chat API

```
POST /v1/conversations/{conversationId}/messages
```

Request:
```json
{
  "clientMessageId": "uuid",
  "content": "Hey, how was your day?"
}
```

Response (success):
```json
{
  "requestId": "uuid",
  "message": {
    "id": "uuid",
    "role": "assistant",
    "content": "It was actually pretty good...",
    "createdAt": "2026-09-13T10:15:00Z"
  },
  "conversation": {
    "id": "uuid",
    "state": "active"
  }
}
```

The partner integrates against this shape without needing to know anything about engine/persona versions, context assembly, or validation internals.

---

## 9. Chat pipeline

```
received → entitlement_check → input_moderation → context_assembly
  → generation → output_validation → [regenerate once if flagged]
  → persist → deliver
```

Failure states (each maps to a stable error code in the error contract, section 15):

```
ENTITLEMENT_DENIED    quota exhausted or lapsed — user sees the wall, not an error
MODERATION_BLOCKED    input flagged — persona fallback line
GENERATION_FAILED     provider error/timeout — retry once, then fallback line
VALIDATION_FAILED     output failed twice — fallback line, never a system string
PERSIST_FAILED        DB write failed — real error, log loudly, don't fake a reply
DELIVERY_FAILED       client disconnected after persist — message exists, retry delivery on reconnect
```
Never mask an infrastructure failure (`PERSIST_FAILED`) behind a fake in-character reply — that's the one state that pages someone rather than falling back gracefully.

`memory_extraction` (section 6) runs after `deliver`, off this critical path — it is not a pipeline stage the client waits on and has no failure state of its own here; a failed or slow extraction just means this turn contributed no new memory facts, logged, not surfaced.

---

## 10. Context assembly

```kotlin
interface ContextAssembler {
    suspend fun assemble(
        userId: UUID,
        conversationId: UUID,
        personaId: UUID,
    ): PromptContext
}
```

Blocks, in fixed order, each a separate `system`-role message (never concatenated into one string — this preserves per-block prefix caching):

| Block | Source | Default | Truncates? |
|---|---|---|---|
| 0 | `PersonaBibleLoader` | engine + persona core, engine first | Never |
| 1 | `user_profiles` | name, language, communication style | Never |
| 2 | `memory_facts` (scoped, `tier='hot'` only) | top 10 (default) facts ranked open-before-resolved, `criticality_rank` desc, then recency, plus continuity line — see section 6's Memory v1.2 lifecycle | Shrinks first |
| 3 | `messages` | last 20 | Shrinks second |

Block 0 must be byte-identical for every user of a given persona at a given point in time — no user-specific content ever enters it.

---

## 11. Persona bible loader

```kotlin
interface PersonaBibleLoader {
    suspend fun load(personaId: UUID): PersonaBible
}

data class PersonaBible(
    val content: String,               // engine content + "\n\n" + persona core content, engine first
    val engineVersionId: UUID,
    val personaCoreVersionId: UUID,
)
```
Both version IDs travel through the whole request and are written onto the assistant `messages` row at `persist`. Cache by `persona_id` (in-process LRU or Redis); invalidate only when a reviewer flips `active_core_version_id` or `conversation_engines.is_active`.

---

## 12. LLM abstraction and provider boundary

### 12.1. Architecture overview

The chat engine communicates with LLM providers through a defined internal contract. This section documents the *existing* contract (how it works today), not future extensibility.

```
ChatEngine (provider-independent)
    ↓
LlmGenerator (provider-independent transformation layer)
    ↓
LlmClient (provider abstraction interface)
    ↓
provider adapter (e.g. OpenRouter, future implementations)
    ↓
external LLM API
```

The ChatEngine and all its orchestration (ContextAssembler, OutputValidator, MemoryService, Persistence) remain entirely provider-independent. Provider-specific HTTP/JSON details are isolated behind the LlmClient interface and do not leak into domain code.

### 12.2. LlmClient interface

```kotlin
fun interface LlmClient {
    fun generate(request: GenerationRequest): LlmResponse
}
```

**Location:** `src/main/kotlin/com/pinkdreams/llm/LlmClient.kt`

Single responsibility: translate between the internal GenerationRequest and provider-specific protocols, returning a normalized LlmResponse.

### 12.3. GenerationRequest — what enters the boundary

```kotlin
data class GenerationRequest(
    val requestId: UUID,                    // pipeline request identifier
    val userId: UUID,                       // for potential provider-level user tracking
    val conversationId: UUID,               // for provider tracing
    val personaId: UUID,                    // for provider tracing
    val engineVersionId: UUID,              // provenance: exact engine version used
    val personaCoreVersionId: UUID,         // provenance: exact persona core version used
    val context: ChatContext,               // the four-block prompt context
    val config: GenerationConfig,           // generation parameters (TBD in part)
) {
    companion object {
        fun from(request: ChatRequest, context: ChatContext, config: GenerationConfig): GenerationRequest
    }
}
```

**Semantics:**
- `requestId` — passed through to identify this turn in logs and for idempotency
- `context` — the assembled ChatContext (see section 10), a list of ContextBlocks that form the prompt sent to the provider
- `config` — model, temperature, and token limits (see section 12.4); currently all optional, allowing the provider adapter to use defaults
- Version IDs — **critical for provenance**: preserved in the GenerationRequest and returned in the response for exact message attribution at persist time

### 12.4. GenerationConfig — parameters for generation (partially TBD)

```kotlin
data class GenerationConfig(
    val model: String? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)
```

**Current state:** All fields are optional, allowing the provider adapter to apply defaults or infer from environment.

**TBD decisions** (not yet resolved in code, not yet passed from config):
- Production model identifier
- Fallback model strategy (if any)
- Default temperature (currently: provider chooses)
- Default max output tokens (currently: provider chooses)
- Provider timeout (currently: no timeout defined; provider-specific)
- Retry policy (currently: no retry; LlmGenerator catches exceptions as GENERATION_FAILED)
- Reasoning / extended-thinking enablement (currently: not part of the contract)

**Explicitly not supported (MVP):**
- Fallback models array / auto-routing
- Streaming (stream=false is the only mode; see section 12.6)
- Cost limits or accounting
- Reasoning output / reasoning_details handling
- Token usage tracking / billing data

### 12.5. LlmResponse — what the provider returns

```kotlin
data class LlmResponse(
    val content: String,                    // the generated assistant reply text
    val provider: String? = null,           // e.g. "openrouter", "fake"
    val model: String? = null,              // e.g. "openrouter/openai/gpt-4"
    val metadata: Map<String, String> = emptyMap(),  // additional provider-supplied metadata
)
```

**Semantics:**
- `content` — the text to be delivered to the user; this is what gets persisted as the assistant message
- `provider` — optional identifier of the provider (used for observability / debugging)
- `model` — optional identifier of the actual model that generated the response (may differ from what was requested if the provider auto-selected an alternative; see section 12.6 on fallback strategy)
- `metadata` — optional provider-specific fields (e.g. `finish_reason`, `usage`, etc.; currently unused in the persisted message, stored in `providerMetadata`)

**Not currently included:**
- Token usage (input/output token counts)
- Cost / billing information
- Reasoning details or extended-thinking output
- Provider request ID or trace ID (could be added to metadata if needed)

### 12.6. LlmGenerator — transformation and error mapping

```kotlin
class LlmGenerator(
    private val client: LlmClient,
    private val config: GenerationConfig = GenerationConfig(),
) : Generator {
    override fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse> {
        return try {
            val generationRequest = GenerationRequest.from(request, context, config)
            val response = client.generate(generationRequest)
            StageResult.Succeeded(
                GenerationResponse(
                    content = response.content,
                    engineVersionId = generationRequest.engineVersionId,
                    personaCoreVersionId = generationRequest.personaCoreVersionId,
                    providerMetadata = buildMap {
                        response.provider?.let { put("provider", it) }
                        response.model?.let { put("model", it) }
                        putAll(response.metadata)
                    },
                ),
            )
        } catch (_: Exception) {
            StageResult.Failed(ErrorCode.GENERATION_FAILED)
        }
    }
}
```

**Responsibilities:**
1. Construct a `GenerationRequest` from the pipeline's `ChatRequest` and `ChatContext`, preserving exact version IDs
2. Call `LlmClient.generate()`
3. Extract content and provenance from the response
4. Assemble provider metadata for observability (provider name, model, any additional fields)
5. Return a `GenerationResponse` to the pipeline with content and version IDs intact
6. Catch *any* provider exception (network, timeout, invalid response, etc.) and map it to `ErrorCode.GENERATION_FAILED`

**Critical invariant:** The `engineVersionId` and `personaCoreVersionId` from the assembled context are preserved unchanged through the LlmClient boundary and back into the response. This ensures every persisted assistant message carries the exact version metadata needed for reproducibility.

### 12.7. Provider adapter boundary (future implementation — not built yet)

The expected structure for a provider-specific implementation (e.g., OpenRouter):

```kotlin
class OpenRouterLlmClient(
    private val apiKey: String,
    private val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
) : LlmClient {
    override fun generate(request: GenerationRequest): LlmResponse {
        // Step 1: Assemble the OpenRouter HTTP request from GenerationRequest
        // - Extract context blocks from request.context
        // - Map context blocks to OpenRouter's messages format (role + content)
        // - Set model from request.config.model (or use default)
        // - Set temperature, max_tokens from request.config (or omit to use provider defaults)
        // - Set stream=false (MVP only)
        
        // Step 2: POST to OpenRouter endpoint
        // - Handle HTTP errors, timeouts, etc. (will be caught as exceptions by LlmGenerator)
        
        // Step 3: Parse response JSON
        // - Extract message.content
        // - Extract model (may differ from requested)
        // - Extract finish_reason, usage, etc. into metadata
        
        // Step 4: Return LlmResponse
        // - content = response.choices[0].message.content
        // - provider = "openrouter"
        // - model = response.model
        // - metadata = {finish_reason, usage, ...}
    }
}
```

**Key architectural constraints for any provider adapter:**
- Must implement the `LlmClient` interface only
- Must not depend on ChatEngine, ContextAssembler, OutputValidator, or any pipeline code
- Must not expose provider-specific fields or enums to the domain layer
- Must translate provider response structure into the normalized LlmResponse
- Exception handling: let exceptions propagate; LlmGenerator catches and maps them

### 12.8. FakeLlmClient — testing without provider calls

```kotlin
class FakeLlmClient(
    private val response: LlmResponse? = null,
    private val failure: RuntimeException? = null,
) : LlmClient {
    var lastRequest: GenerationRequest? = null
        private set

    override fun generate(request: GenerationRequest): LlmResponse {
        lastRequest = request
        failure?.let { throw it }
        return response ?: LlmResponse(content = "fake response", provider = "fake")
    }
}
```

**Purpose:** Enables unit and integration tests to run the entire chat pipeline without incurring LLM costs or network latency.

**Usage:** Pass it to `LlmGenerator` in tests; test code can inspect `lastRequest` to assert exact context, provenance, and config passed to the provider boundary.

### 12.9. Streaming (not implemented, MVP mode)

**Current:** `stream=false` is implicit. The entire response is generated, validated, and persisted as a single block.

**Future decision (TBD):** Whether streaming is a future enhancement. If so:
- The LlmClient contract would need to support either stream or non-stream modes
- LlmResponse would accumulate chunks into a final `content` field
- OutputValidator would run on the complete response (no per-chunk validation)
- The rest of the pipeline remains unchanged

**Streaming is not part of the current MVP and should not be added without explicit architectural review.**

### 12.10. Usage and cost accounting (not implemented, TBD)

**Current:** LlmResponse does not carry token counts or cost data; no persistence of usage metrics in this version.

**Future decision (TBD):** Whether to:
1. Add `usage: TokenUsage?` to LlmResponse (input_tokens, output_tokens)
2. Store usage in `messages.metadata` JSONB
3. Implement a separate cost-tracking persistence layer
4. Implement a cost ceiling or budget enforcement

**Currently, none of this is part of the contract.** If token tracking becomes necessary for billing or monitoring, it must be added as an explicit, separate decision. Do not invent billing tables or cost fields without that decision being made first.

### 12.11. Fallback models (not implemented, TBD)

**Current:** No fallback list. The LlmClient either succeeds or throws an exception, which LlmGenerator maps to GENERATION_FAILED.

**Future decision (TBD):** Whether to:
1. Add a `fallbackModels: List<String>?` to GenerationConfig
2. Implement provider-level retry logic with model rotation
3. Implement chat-engine-level retry (regenerate with a fallback config)

**Currently, no fallback strategy is part of the contract.** If a production environment requires fallback handling, that is a provider-adapter-level decision, not a chat-engine-level one.

### 12.12. Reasoning and extended thinking (not implemented, TBD)

**Current:** No reasoning parameters, no reasoning output handling.

**Future decision (TBD):** Whether to:
1. Add `reasoning: Boolean?` or `thinkingBudget: Int?` to GenerationConfig
2. Add `reasoning_details` to LlmResponse
3. Persist reasoning as message metadata vs. separate table vs. discard
4. Surface reasoning to the user or keep it internal

**Critical rule:** If reasoning is ever enabled, reasoning_details must **never** be mixed into the user-visible message content. If OpenRouter or another provider returns reasoning output, it must either:
- Be stored separately in message metadata (not persisted as visible content)
- Be discarded (if it's not needed for the persona's behavior)
- Have a separate, explicit storage destination

Never silently leak model reasoning into what the user sees. **This is not an implementation detail; it is a compliance boundary.**

### 12.13. Provenance preservation (critical)

The exact flow:

```
ContextAssembler returns ChatContext with:
  engineVersionId = UUID of active engine
  personaCoreVersionId = UUID of active persona core

GenerationRequest.from() preserves both IDs

LlmGenerator passes through LlmClient unchanged

LlmResponse comes back (provider-specific content)

GenerationResponse is constructed with:
  engineVersionId = from(request) / from(context)
  personaCoreVersionId = from(request) / from(context)

ChatPersistence writes the message with exact IDs:
  messages.engine_version_id = GenerationResponse.engineVersionId
  messages.persona_core_version_id = GenerationResponse.personaCoreVersionId
```

**Invariant:** Every assistant message in the database carries the exact engine and persona-core IDs that were active at the moment of generation. This allows any flagged transcript to be reproduced exactly, word for word, with the exact prompt and rules that produced it.

Do not alter this flow. Do not allow version IDs to be inferred post-hoc or looked up at message-render time. The versions are facts about the past, not properties of the present.

### 12.14. Error mapping

Provider failures are uniformly mapped:

```
provider network error / timeout / rate limit / 5xx
  → LlmClient throws exception
  → LlmGenerator catches
  → returns StageResult.Failed(ErrorCode.GENERATION_FAILED)
  → ChatEngine logs and returns error to user
```

Provider-specific error codes (429, 401, provider-specific error text) are not propagated to ChatEngine. They are logged at the provider-adapter level if needed, but the chat engine sees only one outcome: GENERATION_FAILED.

This keeps the chat engine's error handling deterministic and independent of which provider is in use.

### 12.15. Summary of TBD decisions

These are *not* part of the current implementation and must be decided separately:

- **Model selection** — which Claude / GPT / Gemini / OpenRouter model to use
- **Fallback strategy** — whether to retry with alternate models if a request fails
- **Max output tokens** — default limit for response length
- **Temperature** — default randomness/creativity setting (currently: provider chooses)
- **Timeout** — provider request timeout (currently: provider chooses)
- **Retry policy** — whether to auto-retry transient failures (currently: fail once)
- **Cost ceiling** — whether there's a cost limit per request/user
- **Token tracking** — whether to persist usage/token counts
- **Reasoning** — whether to enable reasoning/extended thinking, and where to store reasoning output
- **Streaming** — whether to implement streaming responses (currently: false only)

Do not guess these. If the implementation needs one of these, flag it explicitly and make the decision before writing provider code.

```
llm/
    LlmClient.kt         // interface (stable, no provider knowledge)
    LlmGenerator.kt      // pipeline transformation (stable)
    GenerationRequest.kt // data class (stable)
    GenerationConfig.kt  // config (stable)
    LlmResponse.kt       // data class (stable)
    FakeLlmClient.kt     // test double
    provider/
        OpenRouterLlmClient.kt  // (when built)
```

A `FakeLlmClient` (returns scripted/deterministic responses) must exist alongside any real provider adapter — this is what lets the chat engine's tests run with zero LLM spend and zero network flakiness.

---

## 12.16. OpenRouter MVP Integration — Decisions (Phase 5C.2)

This section documents the integration decisions for using OpenRouter as the first real LLM provider. **No implementation has been done yet** — this is a decision record only.

### API Surface (Current, Verified)

**Endpoint:**
```
POST https://openrouter.ai/api/v1/chat/completions
```

**Authentication:**
- Header: `Authorization: Bearer <API_KEY>`
- API key passed at construction, never in messages
- Key sourced from environment variable (TBD: exact config mechanism)

**Request Structure:**
- `model` — model identifier string (e.g. `"openai/gpt-6-astra"`)
- `messages` — array of `{role, content}` objects (matches ChatContext block format)
- `temperature` (optional, float 0-2)
- `max_tokens` (optional, integer)
- `stream` (optional, boolean; current MVP: `false` only)

**Response Structure:**
```json
{
  "id": "...",
  "model": "...",
  "choices": [
    {
      "finish_reason": "stop" | "length" | "tool_calls" | etc.,
      "message": {
        "role": "assistant",
        "content": "..."
      }
    }
  ],
  "usage": {
    "prompt_tokens": 123,
    "completion_tokens": 45,
    "total_tokens": 168
  }
}
```

**Streaming:**
- Supported by OpenRouter (`stream=true`)
- MVP: Set `stream=false` (see section 12.5 rationale)

**Optional Headers:**
- `HTTP-Referer` — app attribution
- `X-OpenRouter-Title` — app name for leaderboard

### Model Selection — MVP Recommendation

**Selection Criteria:**
- Natural conversational quality for persona interactions
- Strong instruction adherence
- Good persona consistency
- Reasonable latency (< 5s P95 acceptable)
- Reasonable cost (< $1 per conversation expected)
- Adequate context window (32K minimum, 128K+ preferred)
- Production-ready reliability

**Recommended MVP Model Shortlist:**

| Model | Identifier | Context | Input Pricing | Output Pricing | Reasoning | Quality Notes |
|---|---|---|---|---|---|---|
| GPT-6 Astra (OpenAI) | `openai/gpt-6-astra` | 1.05M | $10/M | $50/M | Yes | Excellent instruction adherence, strong reasoning; highest cost |
| DeepSeek V4.1 Flash (DeepSeek) | `deepseek/deepseek-v4.1-flash` | 1.05M | $0.15/M | $0.60/M | No | Cost-efficient, strong multi-step reasoning; emerging provider |
| Sakana Fugu Max (Sakana) | `sakana/fugu-max` | 1M | $2/M | $6/M | Yes | Multi-agent orchestration; quality-to-cost sweet spot |
| GPT Luna Latest (OpenAI) | `openai/gpt-luna-latest` | 1.05M | $0.20/M | $1.20/M | No | Most economical OpenAI option; good balance |

**Recommendation for MVP:**

**Primary: `openai/gpt-6-astra`**
- Rationale: Highest conversational quality and instruction adherence are critical for persona consistency in a chat product. Early-phase quality > cost optimization. The cost (~$0.05-0.10 per conversation at typical 1000-token turns) is acceptable for MVP.
- Fallback: Not recommended for MVP (see fallback section below); recommend monitoring one alternate if budget becomes a constraint.

**Alternative if cost becomes critical:**
`sakana/fugu-max` offers better cost-to-quality tradeoff ($0.03-0.06 per conversation) without major quality loss on conversational tasks. Transition is a production decision, not architecture blocking.

### Fallback Strategy — Recommendation

**Decision: Single Pinned Model (Option A)**

**Rationale:**
- MVP has no production measurement of failure rates per model
- Single model keeps implementation simple (no fallback routing logic)
- If a model becomes unavailable, operations team can update configuration and redeploy (acceptable for MVP)
- Provider fallback arrays (Option B) add complexity without evidence they're needed

**Not Recommended for MVP:**
- Option B (OpenRouter fallback array): Adds API surface complexity; OpenRouter doesn't guarantee fallback models will succeed either
- Option C (application-level fallback): Requires retry logic, model selection strategy, cost tracking across models — premature at MVP scale

**Fallback Implementation (Future, if needed):**
If production shows need for automatic fallback, the `GenerationConfig` could be extended:
```kotlin
data class GenerationConfig(
    val model: String? = null,
    val fallbackModels: List<String>? = null,  // TBD: future
    ...
)
```
The OpenRouterLlmClient would then handle fallback routing. This does NOT require ChatEngine changes.

### Max Output Tokens — Recommendation

**Decision: 1024 tokens default**

**Rationale:**
- Persona responses are conversational, not essays — 200-500 tokens typical
- 1024 provides headroom for multi-turn elaboration without runaway costs
- OpenRouter models default to reasonable values if omitted (no need to set)
- OutputValidator can enforce tighter limits per persona if needed

**Configuration:**
```kotlin
data class GenerationConfig(
    val maxOutputTokens: Int? = 1024,  // default; can be tuned per persona/tier
    ...
)
```

**Per-Tier Consideration (Future):**
- Free tier: 512 tokens (encourage concision)
- Paid tier: 1024 tokens (standard)
- Extended tier: 1024 tokens (same as paid)

Do not implement tiered limits yet; hardcode 1024 for MVP.

### Temperature — Recommendation

**Decision: Let provider choose (omit from request)**

**Rationale:**
- Persona consistency does not require deterministic output (users expect varied responses)
- Conversational quality benefits from some temperature (>0)
- OpenRouter and the underlying models have good defaults (typically 0.7-1.0)
- No evidence suggests temperature tuning is load-bearing for persona adherence

**Configuration:**
```kotlin
data class GenerationConfig(
    val temperature: Double? = null,  // omit from request; let provider choose
    ...
)
```

**If Production Measurement Shows Otherwise (Future):**
Lower temperature (0.5-0.7) could improve consistency. This is a tuning lever, not a blocker.

### Reasoning — Recommendation

**Decision: Disabled for MVP**

**Rationale:**
- Hidden reasoning (reasoning_details) complicates persistence and observability
- No evidence that persona-adherence tasks need reasoning
- Adds latency and cost with unclear benefit for MVP
- If enabled, reasoning_details must never leak into user-visible response (compliance boundary)
- Can be evaluated in production if quality metrics suggest it helps

**Not Implemented:**
- No `reasoning` field in `GenerationConfig`
- No reasoning_details handling
- No separate reasoning storage

**Future Evaluation (if needed):**
Research whether conversational persona tasks benefit from reasoning on models that support it. If yes, design a separate, non-visible reasoning pipeline (structured-output tool call to extract reasoning, store separately, never render).

### Streaming — MVP Behavior

**Decision: `stream=false` (no streaming)**

**Rationale (from section 12.9 rationale):**
- Generation must complete → validation → (optional regeneration) → persistence → delivery
- Validation requires the complete response before deciding to regenerate
- Streaming would block persistence until the stream completes (no latency savings)
- Client-side typing indicators can be simulated with the current pipeline latency

**Not Implemented:**
- No streaming in LlmResponse
- No chunked handling in LlmGenerator or validation

**Streaming as Future Enhancement:**
If user experience testing shows value in streaming responses, the architecture supports it:
- Add `streaming: Boolean?` to `GenerationConfig`
- `LlmResponse` would accumulate chunks
- OutputValidator would still run on the complete response
- Delivery could stream to client while persistence waits

No code changes required to unblock this; it's a provider-adapter implementation detail.

### Timeout — Recommendation

**Decision:**
- **Connection timeout:** 5 seconds (standard HTTP)
- **Read timeout:** 30 seconds (reasonable for reasoning-heavy models)
- **Overall generation timeout:** 60 seconds (abort and regenerate if exceeded)

**Rationale:**
- OpenRouter doesn't publish SLA latencies; these are estimates based on typical LLM provider behavior
- 60s overall allows for context encoding + generation + response return
- If timeout triggers, LlmGenerator catches and returns GENERATION_FAILED; ChatEngine regenerates once
- Not a hard wall; soft timeout for monitoring/alerting

**Not Implemented Yet:**
- Timeout values not in code (TBD in OpenRouterLlmClient implementation)
- Recommendation subject to revision after production measurement

**HTTP Client Configuration (TBD):**
```kotlin
// Not yet implemented; conceptual
val httpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()
```

### Retry Policy — Recommendation

**Decision: No provider-level retries**

**Rationale:**
- ChatEngine has idempotency (client_message_id) and controlled regeneration
- Transport retries risk duplicate generation without clear recovery semantics
- Provider rate-limit (429) should fail immediately and surface to user (respects quota)
- Transient provider errors (5xx) are rare enough at MVP scale to handle manually

**Current Behavior (LlmGenerator):**
```kotlin
return try {
    val response = client.generate(generationRequest)
    // success
} catch (_: Exception) {
    StageResult.Failed(ErrorCode.GENERATION_FAILED)
}
```

Provider exceptions (timeout, 429, 5xx) are caught and mapped to GENERATION_FAILED. The pipeline then:
1. Regenerates once if validation fails
2. Returns GENERATION_FAILED to user if both attempts fail
3. User can retry the entire message (idempotency protects against duplicates)

**Not Implemented:**
- No exponential backoff
- No retry-after header parsing
- No selective retry (429 vs 5xx vs timeout)

**If Production Shows Need for Retries (Rare):**
Add selective retry logic in OpenRouterLlmClient before propagating the exception:
```kotlin
// Conceptual, not implemented
if (error.status in listOf(500, 502, 503, 504)) {
    // retry once with backoff
} else {
    // propagate immediately
}
```

Do not implement this for MVP.

### Usage and Token Accounting — Recommendation

**Decision: Retain in Metadata Only (Option A)**

**Rationale:**
- LlmResponse.metadata can hold `prompt_tokens`, `completion_tokens`
- Current implementation stores metadata in GenerationResponse.providerMetadata
- Persisted in `messages.metadata` JSONB for audit purposes
- No separate cost-tracking table or billing infrastructure in MVP

**Current Contract (already satisfied):**
```kotlin
data class LlmResponse(
    val content: String,
    val provider: String? = null,
    val model: String? = null,
    val metadata: Map<String, String> = emptyMap(),  // {prompt_tokens, completion_tokens, ...}
)
```

OpenRouter response includes `usage: {prompt_tokens, completion_tokens}`, which maps naturally into `metadata`:
```kotlin
val response = LlmResponse(
    content = openrouterResponse.choices[0].message.content,
    provider = "openrouter",
    model = openrouterResponse.model,
    metadata = mapOf(
        "prompt_tokens" to openrouterResponse.usage.prompt_tokens.toString(),
        "completion_tokens" to openrouterResponse.usage.completion_tokens.toString(),
        "finish_reason" to openrouterResponse.choices[0].finish_reason,
    )
)
```

**Not Implemented:**
- No cost calculation
- No billing tables
- No cost ceiling enforcement
- No cost-per-user tracking

**Future Cost Infrastructure (if needed):**
Cost tracking is orthogonal to the LLM contract. If implemented later:
- Read tokens from `messages.metadata`
- Implement cost calculation as a separate service (not in chat engine)
- Enforce quota/ceiling at entitlement boundary, not generation boundary

### Reasoning Details — MVP Handling

**Current Status: Not Applicable**

Since reasoning is disabled for MVP (see section above), reasoning_details from OpenRouter (if enabled) is irrelevant.

**If Reasoning Is Later Enabled:**

**Critical Rule:** Reasoning output must NOT be mixed into user-visible messages.

If OpenRouter returns reasoning_details (a separate field from the message content), they must be:
1. **Stored separately** (message.metadata["reasoning_details"]) — not in message.content
2. **Never rendered** to the user
3. **Never used** to alter the visible message

The visible message (message.content) remains what the model intended the user to see.

**Implementation Pattern (Hypothetical, not built):**
```kotlin
val response = LlmResponse(
    content = openrouterResponse.choices[0].message.content,  // user sees this
    metadata = mapOf(
        "reasoning_details" to openrouterResponse.reasoning_details,  // stored, hidden
        ...
    )
)
```

Do not implement this for MVP.

### Error Normalization — MVP Behavior

**Current Flow (Section 12.14 recap):**

```
OpenRouter HTTP error / timeout / rate limit / 5xx
    ↓
OpenRouterLlmClient throws exception
    ↓
LlmGenerator catches any exception
    ↓
returns StageResult.Failed(ErrorCode.GENERATION_FAILED)
    ↓
ChatEngine logs error, returns to user
```

**Not Implemented:**
- No provider-specific error codes in ChatEngine
- No 429 (rate limit) special handling (treated same as 5xx)
- No OpenRouter error detail logging (though provider exceptions can be logged at adapter level)

**Security:**
- Provider error messages (e.g. "invalid API key", "quota exceeded") are NOT logged to user-visible responses
- Only generic GENERATION_FAILED is returned to client
- Provider error details (if needed for diagnostics) logged server-side only

### Security Requirements — MVP

**API Key Management (Not Yet Implemented):**

**Must be implemented before any provider code runs:**

1. **Source:** Environment variable or secure config (TBD: exact mechanism)
   - NOT in code
   - NOT in logs
   - NOT in messages
   - NOT visible to client

2. **Passing to OpenRouterLlmClient:**
   ```kotlin
   // Conceptual, exact mechanism TBD
   val apiKey = System.getenv("OPENROUTER_API_KEY")
       ?: throw IllegalStateException("OPENROUTER_API_KEY not set")
   val client = OpenRouterLlmClient(apiKey)
   ```

3. **HTTP Header:**
   - Always: `Authorization: Bearer <apiKey>`
   - Never expose in error messages, logs, or client responses

4. **Request/Response Logging:**
   - Log: request/response metadata (model, tokens, latency)
   - Never log: conversation content (unless explicitly debug flag set)
   - Never log: API key in any form

5. **Credential Handling in Tests:**
   - Use FakeLlmClient in unit tests (zero credentials needed)
   - Integration tests against real OpenRouter require valid key in environment
   - Never commit keys to repo; use CI secrets

**Future Enhancements (TBD):**
- Secret management service (if multi-region or delegated API key rotation)
- API key scoping (read-only, rate-limit-scoped keys)
- Key rotation automation

### Configuration (TBD — Not in MVP)

**Decided:**
- Model: hardcoded to recommended value for MVP
- Timeout: hardcoded 60s overall
- Max tokens: hardcoded 1024

**Not Yet Decided:**
- Configuration mechanism (env vars, TOML file, database, Spring properties)
- Which values should be configurable (model, timeout, max tokens, temperature, fallback list)
- How to change config without restart (feature flag service, dynamic config reload)

**Recommendation for MVP:**
Hardcode values in code or read from environment variables (simplest). Move to configurable later when product needs to tune per persona or tier.

---

## 13. Output validation

```kotlin
interface OutputValidator {
    suspend fun validate(response: String, context: ValidationContext): ValidationResult
}

sealed class ValidationResult {
    data object Passed : ValidationResult()
    data class Flagged(val reason: String) : ValidationResult()
}
```
```
generation → validation
  PASS → persist
  FLAG → regenerate once → validation
             PASS → persist
             FAIL → VALIDATION_FAILED (fallback line, no further retries)
```
Exactly one regeneration attempt, hard cap — never an unbounded retry loop. This is a toy/coarse validator at POC scale (the Phase 0 regex tripwire is its ancestor); the interface is what lets a real classifier-backed implementation swap in later without touching the pipeline.

---

## 14. Matching

```kotlin
interface PersonaMatcher {
    suspend fun match(userProfile: UserProfile, preferences: MatchingPreferences): PersonaId
}
```
Hard filters (gender ∈ interested_in, orientation compatibility, age band, language) then soft ranking (tone, archetype diversity, rotation, the randomised creator-vs-synthetic holdout slot from `product-decisions.md`). Returns only a `persona_id` — never touches persona content, prompt, engine, or the content boundary. This module can get materially more sophisticated later without the chat pipeline changing at all.

---

## 15. Entitlement

Chat engine reads `entitlements.state` at the `entitlement_check` stage. States (`trial`, `free`, `extended`, `paid`, `lapsed`) may change quota, memory depth, which personas are unlocked, generation priority, or latency. They must never change the content boundary — that's engine content, identical regardless of tier, enforced structurally by block 0 never varying with entitlement state.

---

## 16. Admin API (your surface)

```
GET    /v1/admin/engines
POST   /v1/admin/engines
GET    /v1/admin/engines/{id}
POST   /v1/admin/engines/{id}/publish
POST   /v1/admin/engines/{id}/archive

GET    /v1/admin/personas
POST   /v1/admin/personas
GET    /v1/admin/personas/{id}
PATCH  /v1/admin/personas/{id}

GET    /v1/admin/personas/{id}/versions
POST   /v1/admin/personas/{id}/versions
POST   /v1/admin/personas/{id}/versions/{versionId}/publish

POST   /v1/admin/personas/{id}/test   -- run a prompt through the engine without a real user conversation
```

Versioning rule: `draft → published → active → archived`, no direct `draft → active`. A published/active version is immutable — changing a persona means creating version N+1, never editing version N in place (this is the same immutability invariant as section 6, just restated as an API-level rule Copilot must enforce, not just a schema constraint).

---

## 17. Authentication boundary

The partner's app owns authentication end to end. The chat engine receives only an authenticated `userId` — it does not implement or duplicate login, sessions, or token issuance. For local development, provide a dev-only auth shim (e.g. a header-based fake identity) so the chat engine's tests and manual runs don't depend on the partner's auth service being up.

```
authenticated request → authenticated userId → Chat Engine
```

---

## 18. Error contract

Every API error returns a stable, predictable shape:

```json
{
  "error": {
    "code": "GENERATION_FAILED",
    "message": "Unable to generate response",
    "requestId": "uuid"
  }
}
```
`code` values are the pipeline failure states from section 9 plus a small set of generic HTTP-level codes (`VALIDATION_ERROR`, `NOT_FOUND`, `UNAUTHORIZED`). Never leak a database exception, an LLM provider exception, a stack trace, internal prompt content, or moderation-rule detail to the client.

---

## 19. Observability

Every chat request carries a `request_id`, generated at `received` and stamped through every pipeline stage and onto the persisted assistant message. Timing metadata per stage:

```json
{
  "timings": {
    "contextAssemblyMs": 12,
    "generationMs": 1850,
    "validationMs": 80,
    "persistenceMs": 15,
    "totalMs": 1980
  }
}
```
Do not log raw conversation content by default — log identifiers, timings, and outcome codes; gate full-content logging behind an explicit, narrowly-scoped debug flag if it's ever needed.

---

## 20. Testing strategy

**Unit** — `PersonaBibleLoader`, `ContextAssembler`, `PersonaMatcher`, memory-fact selection, entitlement rules, `OutputValidator`, `ChatPipeline` stage transitions, version resolution logic.

**Integration (real Postgres)** — create persona → create versions → publish → resolve active version → create conversation → send message → persist → retry same `client_message_id` → assert no duplicate generation.

**Chat engine (with `FakeLlmClient`, no real LLM calls)** — given a fixed persona/engine/persona version, fixed memory, fixed recent messages, assert the exact context blocks sent to the LLM and that the persisted assistant message stores the correct `engine_version_id` / `persona_core_version_id`.

**Critical idempotency test (must pass before calling chat production-ready):**
```
Request A, client_message_id = X
Request A retry, client_message_id = X
```
Expected: one user message, one generation, one assistant message — not two. Also test genuinely concurrent requests sharing the same `client_message_id`: expected outcome is one generation and one persisted response, with both callers resolving to the same message via the `chat_request_executions` conflict path in section 7.

---

## 21. Definition of done (MVP chat engine)

Not "it compiles." Complete when all of the following hold:
- migrations run clean from an empty database
- Ktor starts; health endpoint responds
- dev auth shim works; a real `userId` reaches the chat engine
- persona, persona-core version, and engine version can each be created
- versions can be published; active versions resolve correctly; the immutability rule holds (no in-place edit of a published/active row)
- conversation can be created; a user message can be submitted
- idempotency holds under retry and under concurrency (section 20's tests pass)
- context assembles correctly and matches the block contract in section 10
- `FakeLlmClient` and the real provider adapter both work behind the same `LlmClient` interface
- output validation runs; exactly one regeneration attempt is possible, never more
- assistant messages store exact `engine_version_id` / `persona_core_version_id`
- every error response uses a stable code from section 18; no internal detail leaks
- every request carries a `request_id`, logged through the pipeline
- unit, integration, and idempotency tests all pass
- the API contract (this document plus an OpenAPI spec, section 23) is written down, not just implied by code

---

## 22. Explicitly deferred — do not build yet

Graph database, vector database, RAG, a skill/intent router, automated persona generation, automated prompt mutation, dynamic pricing, a complex recommendation engine, distributed locks, Kafka, a microservice split, Kubernetes-specific architecture, event sourcing — unless a concrete implementation requirement forces one of these, not a hypothetical future one. The goal of this phase is a reliable, testable, versioned chat loop; everything on this list is a solved problem for a later scale this product hasn't reached yet.

---

## 23. Development phases

```
Phase 1  Foundation    Ktor project, Postgres connection, config, logging,
                        error handling, health endpoint, migration runner,
                        test infrastructure

Phase 2  Database       tables, constraints, indexes, repositories, transactions

Phase 3  Persona/Engine  repositories, version resolution, publish/archive,
                          immutability enforcement

Phase 4  Conversation    conversation + message models, client_message_id,
                          chat_request_executions, idempotency

Phase 5  Chat engine     pipeline, context assembly, memory, LLM abstraction,
                          generation, validation, regeneration, persistence

Phase 6  APIs            chat API, conversation API, persona API, admin API

Phase 7  Testing         unit, integration, concurrency, idempotency,
                          golden context/prompt tests

Phase 8  Partner integration   partner consumes the now-stable contract
```

Work one phase at a time: generate → review the actual diff/files → run tests → fix → only then move to the next phase. Do not ask for the whole backend in one pass.

---

## 24. Next artifacts after this spec

Two follow-on documents, not covered here:
1. An OpenAPI-level API contract (formalizing sections 8, 16, 18 as an actual spec file the partner can codegen against).
2. The exact PostgreSQL migration package (`V001__initial_schema.sql` onward) implementing section 6 and 7 verbatim.