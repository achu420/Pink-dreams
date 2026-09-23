# Admin API Gap Audit — 23 Sept

## Audit Summary

Full audit of `src/main/kotlin/com/pinkdreams/api/admin/`, all repositories, and schema migrations conducted on 23 Sept.

---

## Gaps Found

### Gap 1 — Admin Memory Facts CRUD **[FIXED]**

**What was missing:**
The `memory_facts` table supports create / update / soft-delete at the repository layer (`MemoryFactRepository`) but there were no admin HTTP endpoints to drive those operations. The only existing endpoint was a read-only `GET /v1/admin/users/{userId}/memory` on `AdminUserRoutes`.

**What was implemented:**

| Endpoint | Purpose |
|---|---|
| `GET /v1/admin/memory-facts?userId=&personaId=` | List facts for a user-persona relationship (or all facts for a user) |
| `POST /v1/admin/memory-facts` | Create a new memory fact manually |
| `PATCH /v1/admin/memory-facts/{factId}` | Update content and/or status of an existing fact |
| `DELETE /v1/admin/memory-facts/{factId}` | Soft-delete (sets `status="removed"`, never a SQL DELETE) |

New file: `api/admin/AdminMemoryFactsRoutes.kt`
Registered in `Application.kt`.

`MemoryFactRepository.updateStatus()` was also added to support the PATCH status update path.

---

### Gap 2 — Persona Status Transitions **[FIXED]**

**What was missing:**
`PersonaRepository` had a `retirePersona()` method but:
1. No `activatePersona()` method (draft → active transition).
2. `retirePersona()` had no guard — it would re-retire an already-retired persona silently.
3. Neither transition was exposed as an admin HTTP endpoint.

**What was implemented:**
- Added `activatePersona(personaId)` to `PersonaRepository` — only draft → active, rejects any other starting status with 409 Conflict.
- Strengthened `retirePersona()` with a guard that requires current status `active`, matching the documented lifecycle `draft → active → retired`.
- Added `POST /v1/admin/personas/{personaId}/activate` to `AdminPersonaRoutes`.
- Added `POST /v1/admin/personas/{personaId}/retire` to `AdminPersonaRoutes`.

---

### Gap 3 — Persona Visual Details **[ALREADY PRESENT]**

`AdminPersonaVisualRoutes` is comprehensive:
- `GET /v1/admin/personas/{personaId}/visual` — reads all visual versions, active pointer, references
- `POST /v1/admin/personas/{personaId}/visual/ensure` — creates identity + draft version if absent
- `PUT /v1/admin/personas/{personaId}/visual/physical-guide` — update physical attributes JSON
- `PUT /v1/admin/personas/{personaId}/visual/private-guide` — update private guide JSON
- `PUT /v1/admin/personas/{personaId}/visual/style-constraints` — update style constraints JSON
- `POST /v1/admin/personas/{personaId}/visual/publish-activate` — publish draft and set as active version
- `POST /v1/admin/personas/{personaId}/visual/references` — upload reference image (multipart)
- `GET /v1/admin/personas/{personaId}/visual/references/{referenceId}/content` — download binary
- `PATCH /v1/admin/personas/{personaId}/visual/references/{referenceId}` — update notes
- `DELETE /v1/admin/personas/{personaId}/visual/references/{referenceId}` — remove reference

No changes needed.

---

### Gap 4 — Entitlement Management **[TODO — larger architectural gap]**

**What is missing:**
There are no admin endpoints to read or modify user entitlements (`entitlements` table: `trial | free | extended | paid | lapsed`). Billing/quota state is entirely invisible to the admin console.

**Expected endpoints (not yet implemented):**
```
GET  /v1/admin/users/{userId}/entitlement          — read current state
POST /v1/admin/users/{userId}/entitlement/override  — force state (e.g. extend trial, grant paid)
```

**Why deferred:**
Entitlements are owned by the partner's billing system per the architecture spec. Mutating them from the admin console requires a clear policy decision on whether the admin panel is authoritative or advisory (i.e., does a manual override survive the next billing sync?). This should be a product decision before implementation.

---

### Gap 5 — Conversation State Management **[TODO — medium gap]**

**What is missing:**
Admin can read conversation history but cannot:
- Force-archive an active conversation
- Reopen an archived conversation
- See the full message thread inline in the conversations inspector (it's paginated separately)

**Expected endpoints (not yet implemented):**
```
POST /v1/admin/conversations/{conversationId}/archive
POST /v1/admin/conversations/{conversationId}/reopen
```

**ConversationRepository** already has state update capability; this is a routing-only gap (~30–40 lines).

**Recommendation:** Implement in the next admin sprint. No architectural decision needed.

---

### Gap 6 — No Bulk / Cross-User Memory Fact Query **[TODO — small gap]**

**What is missing:**
`GET /v1/admin/memory-facts` requires `userId`. There is no way to query all facts for a given `personaId` across all users (useful for checking if a persona-owned relationship commitment has leaked to the wrong scope).

**Recommendation:** Add `findAllForPersona(personaId)` to `MemoryFactRepository` and support `personaId`-only query in `AdminMemoryFactsRoutes`. ~20 lines.

---

### Gap 7 — Memory Fact Supersede via Admin **[TODO — small gap]**

**What is missing:**
`MemoryFactRepository.supersede()` exists (marks old fact superseded, creates a new one linked via `supersedesId`) but is not exposed as an admin endpoint. This is the correct way to correct a wrong fact without losing audit history.

**Expected endpoint:**
```
POST /v1/admin/memory-facts/{factId}/supersede   { content, factType?, criticality?, owner? }
```

**Recommendation:** Add to `AdminMemoryFactsRoutes` in the next sprint. ~25 lines.

---

### Gap 8 — Sensitive Preferences Admin Read **[TODO — medium gap]**

**What is missing:**
`SensitivePreferenceRepository` exists (holds per-user inferred preferences) but there is no admin endpoint to read or manage them. This is the companion to memory facts in the context assembly pipeline.

**Expected endpoints:**
```
GET  /v1/admin/users/{userId}/sensitive-preferences
POST /v1/admin/users/{userId}/sensitive-preferences  (manual override)
```

**Recommendation:** Implement after Entitlements if admin context visibility is a priority.

---

## Summary Table

| Gap | Status | Size |
|-----|--------|------|
| Memory Facts CRUD | **Fixed** | ~280 lines |
| Persona Status Transitions | **Fixed** | ~90 lines |
| Persona Visual Details | Already present | — |
| Entitlement Management | TODO | Architectural decision needed |
| Conversation Archive/Reopen | TODO | ~40 lines |
| Cross-User Memory Fact Query | TODO | ~20 lines |
| Memory Fact Supersede via Admin | TODO | ~25 lines |
| Sensitive Preferences Admin | TODO | ~60 lines |
