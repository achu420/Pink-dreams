# Pink Dreams — Admin QA Testing Plan

Status note first: the console served at `/admin` (`src/main/resources/admin-ui.html`) currently sends **no auth header** on any request, so every button in it will 401 until that's fixed. This plan is written to work *today* by using the API directly (Postman/curl) for anything auth-gated, and the browser console only for the parts that happen to work without login (none, currently — treat the console as view-only/broken until the auth fix lands). Once Claude Code adds the auth fields, the "API" column steps can move to "Admin UI."

All admin/API auth: **HTTP Basic**, username = a UUID from `ADMIN_USER_IDS` (seeded: `00000000-0000-0000-0000-000000000001`), password = anything non-blank.

---

## 0. Start the server

From the repo root (`E:\Projects\Pink dreams`), in PowerShell:

```powershell
.\run-local.ps1
```

This sets `DATABASE_URL`, `DATABASE_USER=postgres`, `DATABASE_PASSWORD=password`, `OPENROUTER_API_KEY`, `ADMIN_USER_IDS`, then runs `gradle run`. Confirm it's up:

```
GET http://localhost:8080/health
```
Expect `200`.

Prerequisite: Postgres running locally with a `pinkdreams` database and the migrations applied (the app runs `SchemaUtils`/Flyway on boot per the existing setup — if this is a fresh DB, first boot should create the schema; if it errors, that's the first thing to report back, not push through).

Admin console URL once the server is up: `http://localhost:8080/admin`

---

## 1. What's doable from the Admin UI right now vs. what needs the API

| Action | Admin UI (`/admin`) | Direct API |
|---|---|---|
| View personas / engines (read-only) | Loads on page open (no auth needed for GET in the UI's own fetch, but the route itself requires auth — so this will actually also 401 right now) | ✅ works |
| Create persona | ❌ (401 — no auth header sent) | ✅ works |
| Create/publish/activate core version | ❌ (401) | ✅ works |
| Create/publish/activate/archive engine | ❌ (no UI at all for this, plus 401) | ✅ works |
| Create conversation | ❌ (401) | ✅ works |
| Send chat message | ❌ (401) | ✅ works |
| View message history | ❌ (401) | ✅ works |

Bottom line for now: **do everything through the API** below. Re-test the UI column once the auth fix is in.

---

## 2. Exact API steps, in order

Use Postman or curl. Base URL `http://localhost:8080`. Every call below needs `Authorization: Basic` with username `00000000-0000-0000-0000-000000000001` and any password, plus `Content-Type: application/json` on POSTs with a body.

### Step 1 — Create a conversation engine
```
POST /v1/admin/engines
{
  "version": 1,
  "content": "You are a warm, natural conversational partner...",
  "changelogNote": "initial"
}
```
Expect `201`, capture `id` → `ENGINE_ID`. `status` should be `draft`.

### Step 2 — Publish the engine
```
POST /v1/admin/engines/{ENGINE_ID}/publish
```
Expect `200`/`204`, `status` → `published`.

### Step 3 — Activate the engine
```
POST /v1/admin/engines/{ENGINE_ID}/activate
```
Expect success, `isActive` → `true`. (DB enforces only one active engine at a time via `one_active_engine` unique index — a second activate on a different engine should fail unless the first is deactivated/archived first; worth a negative test.)

### Step 4 — Create a persona
```
POST /v1/admin/personas
{
  "slug": "test-persona",
  "displayName": "Test Persona",
  "gender": "female",
  "orientation": "straight",
  "apparentAge": 25
}
```
Note: `apparentAge` has a DB check `>= 25` — anything under that should fail with a validation/DB error; worth a negative test too. Capture `id` → `PERSONA_ID`. `status` should be `draft`.

### Step 5 — Create a persona core version
```
POST /v1/admin/personas/{PERSONA_ID}/core-versions
{
  "version": 1,
  "content": "PERSONA CORE — voice, backstory, boundaries...",
  "changelogNote": "initial"
}
```
Capture `id` → `CORE_VERSION_ID`.

### Step 6 — Publish, then activate the core version
```
POST /v1/admin/personas/{PERSONA_ID}/core-versions/{CORE_VERSION_ID}/publish
POST /v1/admin/personas/{PERSONA_ID}/core-versions/{CORE_VERSION_ID}/activate
```
Second call sets `personas.active_core_version_id` — DB trigger will reject activating anything not already `published`, worth confirming as a negative test (try activating a draft version directly).

### Step 7 — Create a conversation
```
POST /v1/conversations
{
  "personaId": "{PERSONA_ID}"
}
```
`userId` comes from the Basic-auth username automatically — do not pass it. Use a plain test-user UUID as the Basic-auth username for this call (any UUID, doesn't need to be in `ADMIN_USER_IDS` — that allowlist only gates `/v1/admin/*`). Capture `id` → `CONVERSATION_ID`.

### Step 8 — Send a chat message
```
POST /v1/conversations/{CONVERSATION_ID}/messages
Headers: Idempotency-Key: <any fresh UUID>
{
  "content": "Hey, how's your day going?"
}
```
This is the one call that spends real OpenRouter credit — the key in `run-local.ps1` is live. Expect `201`/`200` with the assistant's reply. Re-sending the **same** Idempotency-Key + body should return the same result without calling the LLM again (idempotency check) — worth a repeat-send test.

### Step 9 — Read history back
```
GET /v1/conversations/{CONVERSATION_ID}
GET /v1/conversations/{CONVERSATION_ID}/messages
```
Confirm both the user message and assistant reply are present, in order, with the assistant one carrying `engine_version_id`/`persona_core_version_id` provenance (visible via DB query below — not exposed in the message response body).

---

## 3. Data not visible via any endpoint — direct DB queries

Connect with `psql -h localhost -U postgres -d pinkdreams` (password `password`), or any Postgres client.

**Check a message's generation provenance** (which engine/persona-core version actually produced a reply — not returned by the API):
```sql
SELECT id, role, content, engine_version_id, persona_core_version_id, created_at
FROM messages
WHERE conversation_id = '{CONVERSATION_ID}'
ORDER BY created_at;
```

**Check idempotency ledger state** (to verify a duplicate send didn't re-trigger generation):
```sql
SELECT conversation_id, client_message_id, request_id, status, assistant_message_id, error_code
FROM chat_request_executions
WHERE conversation_id = '{CONVERSATION_ID}';
```

**Check which engine is currently active** (only one allowed — confirms the unique-index invariant):
```sql
SELECT id, version, status, is_active FROM conversation_engines ORDER BY created_at;
```

**Check a persona's active core version pointer**:
```sql
SELECT id, slug, status, active_core_version_id FROM personas WHERE id = '{PERSONA_ID}';
SELECT id, version, status FROM persona_core_versions WHERE persona_id = '{PERSONA_ID}' ORDER BY version;
```

**Check memory facts extracted from a conversation** (background/async, not shown in any UI or endpoint yet):
```sql
SELECT id, fact, fact_type, criticality, tier, status, learned_at
FROM memory_facts
WHERE user_id = '{USER_ID}' AND persona_id = '{PERSONA_ID}'
ORDER BY learned_at DESC;
```
Since extraction is best-effort/async (`BestEffortMemoryExtraction`, fire-and-forget on a `CompletableFuture`), don't expect rows immediately after step 8 — poll a few seconds later.

**Check a user's entitlement state** (governs whether chat is even allowed — currently hardcoded `Allowed` in `Application.kt`, so this table is unused by the running server right now, but worth confirming it's empty/not blocking):
```sql
SELECT * FROM entitlements WHERE user_id = '{USER_ID}';
```

**Check image-pipeline tables exist but are empty** (IMG-1–7 schema landed, nothing populated yet — sanity check only, not part of this testing pass):
```sql
SELECT count(*) FROM persona_identity;
SELECT count(*) FROM persona_visual_versions;
SELECT count(*) FROM image_jobs;
```

---

## 4. Known-broken items to log, not chase

- Admin console auth: no username/password fields, no `Authorization` header sent anywhere in `src/main/resources/admin-ui.html` → every button 401s.
- No engine create/publish/activate UI in the console (list only).
- Root-level `admin-ui.html` is a stale, unused duplicate of the served file.
- No persona metadata edit, no core-version archive, no conversation resume in the console.

These are implementation gaps to send back to Claude Code, not things to work around by hand-editing the HTML yourself mid-test.
