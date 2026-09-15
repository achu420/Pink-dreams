# Pink Dreams — Chat Engine & Admin API

## Project Overview

**Pink Dreams** is a Kotlin/Ktor-based backend service that powers a conversational AI platform with persona-based interactions. It's designed as a shared contract between a chat engine (handling persona interactions, context, and generation) and a partner application backend (handling auth, payments, and client integration).

**Key Purpose:**
- Manage multi-turn conversations between users and AI personas
- Provide versioned management of personas (character profiles) and conversation engines (universal rules)
- Support admin APIs for testing, publishing, and activating persona/engine versions
- Ensure at most one active conversation engine and strict version control

---

## Architecture

### Core Entities

1. **Personas** — AI character profiles (metadata)
   - Immutable identity anchors (id, slug, displayName, gender, orientation, apparentAge)
   - Status lifecycle: draft → active → retired
   - Language profiles (additional JSONB attributes)

2. **Persona Core Versions** — Versioned character content
   - Never deleted (supports transcript reproducibility)
   - Status: draft → published → archived
   - Supports changelog notes and author tracking
   - Only one can be "active" per persona

3. **Conversation Engines** — Universal rules/system prompts
   - At most one active engine (database-guaranteed unique index)
   - Status: draft → published → archived
   - Version-tracked for audit and rollback

4. **Conversations** — Per-user/persona chat sessions
   - States: active, idle, archived
   - Tracks user_id, persona_id, last_message_at
   - Medium-lived; multiple per user-persona pair

5. **Messages** — Message ledger with generation provenance
   - Roles: user, assistant, system
   - Assistant replies carry engine_version_id and persona_core_version_id
   - User messages carry client_message_id (for idempotency deduplication)
   - Constraints ensure version fields only on assistant messages

6. **Chat Request Executions** — Idempotency ledger
   - Primary key: (conversation_id, client_message_id)
   - Tracks status: processing → completed | failed
   - Supports 202 "still processing" responses and retry logic

7. **Memory Facts** — User-persona relationship scoping
   - Per user, per persona (never global)
   - Mutable fact store for learned information
   - States: open, resolved

8. **Entitlements** — User billing/quota state
   - States: trial, free, extended, paid, lapsed
   - Owned by partner's billing system

---

## Tech Stack

- **Language:** Kotlin 1.9.25 (JVM 21)
- **Web Framework:** Ktor 2.3.12
  - Content negotiation (JSON)
  - Call logging
  - Status pages & exception handling
  - Bearer token auth (partner-issued; trusts the token)
- **Database:** PostgreSQL 42.7.4
  - Connection pooling: HikariCP 5.1
  - ORM/query builder: Jetbrains Exposed 0.53.0
  - Migrations: Flyway (via schema in initiate Scheme.txt)
- **Serialization:** kotlinx-serialization-json 1.6.3
- **Logging:** Logback 1.5.7
- **Testing:** Kotlin Test, H2 in-memory DB, Ktor test host

---

## Project Structure

```
src/main/kotlin/com/pinkdreams/
├── Application.kt                    # Main entry point, Ktor module setup
├── api/
│   └── health/HealthRoutes.kt        # /health endpoint
├── auth/
│   └── DevAuthProvider.kt            # Dev-only bearer token auth
├── common/errors/
│   ├── ApiError.kt                   # Error model
│   ├── ErrorCode.kt                  # Machine-readable error codes
│   └── ErrorResponse.kt              # API error response format
├── config/
│   ├── AppConfig.kt                  # Loads from env vars (port, host, etc.)
│   ├── DatabaseConfig.kt             # PostgreSQL connection config
│   └── LlmConfig.kt                  # LLM provider configuration
└── persistence/
    └── repositories/
        ├── PersonaRepository.kt       # Persona CRUD + state transitions
        ├── PersonaCoreVersionRepository.kt  # Version management
        └── ConversationEngineRepository.kt  # Engine versioning

src/test/kotlin/com/pinkdreams/
├── HealthEndpointTest.kt             # Health check tests
├── persistence/
│   ├── Phase2RepositoryTest.kt       # Repository layer tests
│   └── SqlMigrationTest.kt           # Schema migration validation

src/main/resources/
├── logback.xml                       # Logging configuration
└── (env config via AppConfig)
```

---

## API Surface

All paths prefixed with `/v1`. Authentication via bearer token (partner-issued).

### Public Chat API
- `GET /health` — Liveness/readiness check (no auth)
- `POST /conversations/{conversationId}/messages` — Send message, get persona reply (idempotent by clientMessageId)
- `GET /conversations` — List user's conversations
- `POST /conversations` — Start new conversation (with optional personaId)
- `GET /conversations/{conversationId}` — Fetch one conversation
- `GET /personas` — List active personas visible to user

### Admin APIs
- `GET/POST /admin/engines` — List/create engine versions
- `POST /admin/engines/{engineId}/publish` — Move draft → published
- `POST /admin/engines/{engineId}/activate` — Activate published engine (deactivates prior one)
- `POST /admin/engines/{engineId}/archive` — Archive (must not be active)
- `GET/POST /admin/personas` — List/create personas
- `PATCH /admin/personas/{personaId}` — Update persona metadata
- `GET/POST /admin/personas/{personaId}/versions` — List/create persona versions
- `POST /admin/personas/{personaId}/versions/{versionId}/publish` — Publish version
- `POST /admin/personas/{personaId}/versions/{versionId}/activate` — Set active version
- `POST /admin/personas/{personaId}/test` — Test pipeline with live message (no conversation created)

### Error Codes
Machine-readable codes: `ENTITLEMENT_DENIED`, `MODERATION_BLOCKED`, `GENERATION_FAILED`, `VALIDATION_FAILED`, `PERSIST_FAILED`, `DELIVERY_FAILED`, `VALIDATION_ERROR`, `NOT_FOUND`, `UNAUTHORIZED`, `INTERNAL_SERVER_ERROR`

---

## Key Invariants

1. **At most one active engine** — Unique index + atomic activation (deactivates prior)
2. **Active core version must be published** — Database trigger enforces cross-table invariant
3. **Version histories never deleted** — Supports transcript reproducibility
4. **Idempotent message handling** — (conversation_id, client_message_id) deduplicates; 202 responses for in-progress requests
5. **No persona-specific code branches** — All behavior driven by versioned content; personas are metadata anchors
6. **Auth is partner-issued** — This API consumes bearer tokens; dev environment accepts X-Debug-User-Id header

---

## Getting Started

### Prerequisites
- JDK 21
- PostgreSQL 14+
- Kotlin 1.9.25 (via Gradle)

### Build & Run
```bash
gradle build
gradle run
```

Server starts on port (from env / AppConfig) on localhost.

### Database Setup
1. Create `users` table in your PostgreSQL (owned by partner's auth system)
   ```sql
   CREATE TABLE IF NOT EXISTS users (id UUID PRIMARY KEY DEFAULT gen_random_uuid());
   ```
2. Run migration `initiate Scheme.txt` (Flyway will handle this)

### Testing
```bash
gradle test
```

Uses H2 in-memory DB for unit tests; schema migrated on test startup.

---

## Configuration

Environment variables (loaded by `AppConfig.load()`):
- `PORT` — Server port (default: 8080)
- `HOST` — Server host (default: localhost)
- `DATABASE_URL` — PostgreSQL connection string
- `LLM_API_KEY` — LLM provider API key (Claude, OpenAI, etc.)
- `LLM_MODEL` — Model identifier

---

## Documentation References

- **OpenAPI spec** — `chat engine.md` (full endpoint definitions, schemas, examples)
- **Database schema** — `initiate Scheme.txt` (DDL, constraints, indices, triggers)
- **Implementation spec** — Referenced in code/schema comments (pink-dreams-implementation-spec.md)
- **Architecture** — Referenced as production-architecture.md (frozen, defines "at most one active engine" invariant)

---

## Notes for Future Work

- Persona image-identity pipeline is out of scope; `persona_identity` table is a placeholder
- Entitlements and memory facts are owned by partner's billing/context systems
- Seed data (initial engine, personas) is a separate migration or admin bootstrap step, never baked into schema
- Never edit table meanings in place; use migrations (V002, V003, etc.) per the version discipline this schema itself enforces
