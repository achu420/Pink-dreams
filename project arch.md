# Pink Dreams — Production Architecture (7 systems)

Extends `ai-architecture.md` (chat/image/cost) and `phase0-findings.md` (memory v1.1, prompt versioning). Cross-references `product-decisions.md` for the entitlement ladder and matching model, which are already locked.

## Sequencing first, because this matters more than any individual design below

Seven systems at once, for a one-person-plus-Copilot POC that hasn't yet validated its core chat loop, is a lot. Building all seven as "production ready" before the eval harness and validator from `ai-architecture.md` exist would repeat the same mistake I've been pushing back on all session (the memory graph, the live skill router) at seven times the scale. So: everything below is designed for production correctness, but sequenced.

| System | POC now (weeks 1-10, per existing sequence) | Defer to post-validation |
|---|---|---|
| 1. Multi-persona | Persona schema + engine/core split, loaded from DB | Automated persona-authoring tools |
| 2. Gender/orientation | Data fields + matching (already specified in `product-decisions.md`) | — (this is cheap, do it now) |
| 3. Personalization/memory | v1.2 typed/tiered lightweight design (already specced) | Anything graph-based |
| 4. State/flow | Full state machine below — this is core plumbing, not optional | — |
| 5. History | Storage + a retention/deletion policy | Fancy audit tooling |
| 6. Self-improvement | Human-reviewed changelog loop (manual, like Phase 0) | Any automated prompt-mutation |
| 7. Payment behavior | Quota + memory-depth + persona-unlock tiers | Dynamic pricing experiments |

The two most consequential decisions in this doc: **self-improvement stays human-in-the-loop, never automated on engagement signals** (section 6), and **the payment tier never touches the content boundary** (section 7). Both are explained below, not just asserted.

### Core invariants (lock these; everything else can flex)

1. One universal engine, many persona cores — never a per-persona copy of universal rules.
2. Persona/engine versions are immutable once written; nothing is ever edited in place, only superseded.
3. Every assistant message stamps the exact engine + persona-core version that produced it.
4. Global user identity (`UserProfile`) and per-relationship memory (`MemoryFact`) are separate tables — never merged.
5. Persona matching selects a persona; it never modifies persona content (section 2).
6. Entitlement changes capability (quota, memory depth, personas unlocked); it never changes the content boundary (section 7).
7. Pipeline state, conversation state, and entitlement state are three independent state machines, not one.
8. No behavior or prompt change reaches production without a human explicitly promoting it (section 6) — no exception for "the data suggested it."
9. The application code never branches on which persona it's talking to (`if persona == "simran"`) — it operates on `persona_id` and lets the DB supply identity. This is already Rule 1 in `copilot-instructions.md`, restated here because it's foundational to all seven systems, not just persona storage.
10. No graph DB, vector store, skill router, or automated optimization until the core loop and validator are proven — this doc designs for correctness at POC scale, not hypothetical scale.
11. Every user message carries an idempotency key; processing the same key more than once must never create duplicate assistant responses.

---

## 1. Multi-persona architecture — full spec

This supersedes the flat `PersonaBible` table sketched in `backend-build-prompts.md` prompt 02. Same intent (store the persona's system-prompt content), now versioned and split per the Phase 0 engine/core pattern.

### Schema

```sql
-- Almost always exactly one active row. Updated when a reviewer promotes
-- a new engine version (section 6's human-review loop), never by the app.
CREATE TABLE conversation_engines (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version         INTEGER NOT NULL UNIQUE,  -- version numbers must stay a reliable human-facing identifier
    content         TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'draft'
                        CHECK (status IN ('draft','published','archived')),
    is_active       BOOLEAN NOT NULL DEFAULT false,
    changelog_note  TEXT,
    created_by      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A draft can never be flipped active directly — this is the fix for a real gap:
    -- without it, nothing stops someone from pointing is_active at an unreviewed row.
    CONSTRAINT chk_active_implies_published CHECK (NOT is_active OR status = 'published')
);
-- Enforces AT MOST one active engine at the DB level (a partial unique index
-- cannot guarantee "exactly one" — zero is a valid transient state, e.g.
-- mid-bootstrap or right after archiving the active row). "Exactly one" is a
-- production-readiness check owned by the deployment/admin workflow, not the DB.
CREATE UNIQUE INDEX one_active_engine ON conversation_engines (is_active) WHERE is_active;

CREATE TABLE personas (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug                    TEXT NOT NULL UNIQUE,
    display_name            TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'draft'
                                CHECK (status IN ('draft','active','retired')),
    gender                  TEXT NOT NULL,
    orientation             TEXT NOT NULL,
    apparent_age            INTEGER NOT NULL CHECK (apparent_age >= 25),  -- age-appearance bar, ai-architecture.md
    language_profile        JSONB NOT NULL DEFAULT '{}',  -- {"language":"hinglish","default_pronoun":"tum"}
    active_core_version_id  UUID,  -- FK added below, after persona_core_versions exists (circular reference)
    persona_identity_id     UUID REFERENCES persona_identity(id),  -- image-identity row, ai-architecture.md
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE persona_core_versions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- NOT ON DELETE CASCADE: section 1 says "old versions are never deleted,"
    -- and a persona-delete cascading into its version history would silently
    -- make old messages unreproducible. Deleting a persona means retiring it
    -- (personas.status = 'retired'); its core versions live on forever, same
    -- as the engine's. If real user-data deletion ever requires purging a
    -- persona's content, that's a distinct, deliberate operation — never an
    -- automatic side effect of a foreign-key cascade.
    persona_id      UUID NOT NULL REFERENCES personas(id),
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

-- personas.active_core_version_id pointing at a non-'published' row is the
-- one invariant a plain CHECK can't express (it spans two tables) — enforce
-- it with a trigger instead, fired on insert/update of the pointer:
--   raise exception if the referenced persona_core_versions.status <> 'published'
-- Same trigger pattern for conversation_engines.is_active if you'd rather
-- centralize both invariants in the application layer instead — either
-- works, but pick one and don't rely on reviewer discipline alone.
```

**Versioning lives on `messages`, not `conversations`.** A conversation can run for months and outlive several prompt edits — pinning the version at conversation-creation time would make every later message wrongly attributed. The reproducibility guarantee (know exactly what produced *this specific reply*) belongs on the message:

```sql
ALTER TABLE messages
    ADD COLUMN engine_version_id       UUID REFERENCES conversation_engines(id),
    ADD COLUMN persona_core_version_id UUID REFERENCES persona_core_versions(id);

-- Only assistant replies carry a version; user messages don't.
ALTER TABLE messages ADD CONSTRAINT chk_version_matches_role CHECK (
    (role = 'assistant' AND engine_version_id IS NOT NULL AND persona_core_version_id IS NOT NULL)
    OR (role <> 'assistant' AND engine_version_id IS NULL AND persona_core_version_id IS NULL)
);
```

Old versions are never deleted — they're small text rows, not user data, and they're what makes a flagged transcript from three weeks ago reproducible ("was this the version we already fixed, or is it still live").

### Assembly logic (`context.py`)

```python
async def load_bible(persona_id: UUID) -> BibleResult:
    engine = await db.fetch_one(
        "SELECT id, content FROM conversation_engines WHERE is_active LIMIT 1")
    persona = await db.fetch_one(
        "SELECT active_core_version_id FROM personas WHERE id = :id", {"id": persona_id})
    core = await db.fetch_one(
        "SELECT id, content FROM persona_core_versions WHERE id = :id",
        {"id": persona.active_core_version_id})
    return BibleResult(
        text=engine.content + "\n\n" + core.content,
        engine_version_id=engine.id,
        persona_core_version_id=core.id,
    )
```
`text` becomes block 0 of the four-block context assembly, unchanged from `ai-architecture.md`. `engine_version_id`/`persona_core_version_id` get stamped onto the assistant message row when it's persisted. Cache this by `persona_id` (in-process LRU or Redis) since it only changes when a reviewer flips the active pointer — that keeps it cheap and, more importantly, keeps the prefix byte-identical across every user of that persona within a cache window, which is what makes provider prefix-caching actually work (guard test 3 in `backend-build-prompts.md` already asserts this).

### Promotion workflow (ties to section 6)
1. Reviewer inserts a new row into `persona_core_versions` (or `conversation_engines`) with `status = 'draft'` — this alone changes nothing live, and can't accidentally go live because of the invariant above.
2. Reviewer tests it (the Phase 0 `chat.py` pattern, pointed at staging).
3. Reviewer flips the row to `status = 'published'` — a version can be published without being active yet (e.g. prepared ahead of time), so this step is separate from step 4.
4. One transaction flips the pointer: `UPDATE personas SET active_core_version_id = :new_id WHERE id = :persona_id` (or, for the engine, deactivate-old/activate-new in the same transaction). Nothing auto-promotes — this is a human action, per section 6.
A version that never gets published just sits there as `draft` — harmless, and still useful as a record of what was tried.

### What this gets you
Adding persona #31: one `personas` row, one `persona_core_versions` row. No code change, no new engine logic, no touching `context.py`. That's the actual payoff of the Phase 0 split, now durable and queryable instead of living in two local files.

## 2. Gender and sexual orientation support

`product-decisions.md` already specifies the matching model: hard filter on presented gender against the user's `interested_in`, plus apparent-age band and language. That model assumes personas carry a `gender` field — this section just makes that concrete and extends it to orientation.

Persona fields: `gender` (how she/he/they presents), `orientation` (shapes how the persona talks about attraction and relationships — a persona written as gay should never express straight attraction as a default, for the same reason Simran shouldn't randomly claim to be into women unless that's who she is). These are **persona-core content**, not engine content — the hard rules (no explicit content, no fake meetings, safety-question handling, etc.) are identical regardless of gender or orientation. Nothing about a persona's identity should ever loosen the content boundary; the boundary is engine-level and orientation is core-level, and those two layers not touching is exactly the invariant the split protects.

User side: a single `interested_in` field (or multi-select) drives the matching hard filter already specified. No separate "skill" or code path per orientation combination — same engine, same pipeline, different `persona_core_versions` content, exactly like Simran vs. any future persona.

**Matching is a separate boundary from generation, and should live in its own module.** `product-decisions.md`'s suggested matching model is already structured this way — hard filters (gender ∈ interested_in, age band, language) then soft ranking (tone/archetype/rotation) — and the important architectural rule is that this module only *selects* a `persona_id`; it never touches persona content. The chat pipeline downstream doesn't know or care how a persona was chosen, it just resolves whatever `persona_id` it's given through the section-1 schema. Keeping these separate means the matching algorithm can get more sophisticated later (the randomised creator-vs-synthetic holdout from `product-decisions.md` item 7 slots in here) without the chat/generation code ever changing.

## 3. Personalization / user memory

Already fully specced in `phase0-findings.md` (the "Memory v1.1" section): `MemoryFact` scoped `(user_id, persona_id)`, with `learned_at`, `status` (open/resolved), `last_referenced_at`; a `last_message_at`-driven continuity line; no vector store, no graph. One addition worth making now since multi-persona is in scope: split what's global (a `UserProfile` row — name, preferred language, communication style) from what's per-relationship (`MemoryFact`, scoped per persona). A user's name is true across every persona they talk to; "the inside joke about Ritu's cat" is Simran-specific. Two tables, not one, prevents relationship facts leaking across personas — which is the single worst bug this product could ship, per the forensic audit and per `ai-architecture.md`'s memory hygiene section.

### Memory v1.2 — typed, tiered, LLM-scored facts

Extends v1.1 with structure the model itself supplies, rather than treating every fact as an undifferentiated string. Two new dimensions per fact:

- **`fact_type`** — one of `past_event`, `future_event`, `mindset`, `weakness`, `aspiration`, `desire`, `habit`, `want`, `interest`. Lets retrieval and, later, review tooling reason about *kind* of memory, not just content.
- **`criticality`** — `low` / `medium` / `high`, the model's own estimate of how much this fact should shape future replies. This is what makes "only send back the important ones" possible instead of an undifferentiated top-N by recency.

Two-tier storage, which is the direct answer to "keep 20 in stake, rest to DB with a flag":

- **Hot tier** — up to 20 facts per `(user_id, persona_id)`, the active working pool. This is what's eligible to be sent back to the model at all.
- **Cold tier** — everything evicted from hot, kept in the same table with `tier='cold'` and an `evicted_at` timestamp. **"Never deleted" here means never discarded merely because the hot pool is full** — it does not mean permanently immortal regardless of anything else. Cold facts remain available for future processing (a consolidation/summarization job, or manual review — neither built yet) until, and only until, whatever user-data retention/deletion policy gets decided (section 5 flags this as still open, pending product/legal input) says to purge them. This table doesn't define that policy; it just doesn't act as an unplanned second deletion mechanism ahead of it.

**Criticality ordering must never rely on plain text sort order.** `'high' > 'medium' > 'low'` is not alphabetical (alphabetically `medium > low > high`), so a naive `ORDER BY criticality DESC` silently sorts backwards. The schema stores an explicit generated `criticality_rank` (high=3, medium=2, low=1) specifically so every selection and eviction query sorts by that numeric rank, never by the raw text column.

Eviction rule, applied when a `(user, persona)` pair's hot count exceeds 20 — evict from the *low* end of this priority order, in this sequence: **resolved facts before open ones**, then lowest `criticality_rank`, then oldest `last_referenced_at`, then oldest `learned_at`. A `status='resolved'` fact is memory that already played out (a past event that happened, a plan that resolved) — it shouldn't out-compete an active, high-criticality fact for a hot-pool slot just because it happens to be more recent. This keeps the hot pool bounded without deleting anything.

**Per-turn selection is a further filter on top of the hot tier, not the same thing as it**, and uses the *opposite* end of the same ordering: open before resolved, highest `criticality_rank` first, most-recently-referenced first as a tiebreaker. The context contract's existing "10 facts" default doesn't change — what changes is which 10: prefer high-criticality hot facts, backfill with medium by recency if fewer than 10 high-criticality facts exist. Low-criticality hot facts are retained (they still count toward the 20-slot cap and can rise in priority later) but are the last selected for injection into an actual prompt. So "20 in stake" and "10 sent per turn" aren't in conflict — 20 is the retained working set, 10 (or fewer) is what actually gets spent on any single call.

**`last_referenced_at` is updated on actual conversational relevance, not on injection.** Being selected into a prompt by `ContextAssembler` does not by itself update this timestamp — if it did, a frequently-selected fact would perpetually refresh its own recency purely by being sent, which would let it entrench itself at the top of the ranking regardless of whether the user still cares, defeating the point of tracking recency at all. It's updated only when the extraction step (below) determines the user actually engaged with that fact again in the current turn.

**Extraction is a background pipeline stage, not part of the synchronous reply path.** Adding it before `deliver` would tax user-facing latency for something that doesn't need to be fast. Instead: after a turn is persisted and delivered, a `memory_extraction` stage runs (same or a cheaper model, structured-output/tool-call style, not free text mixed into the persona's visible reply) over the just-completed turn and proposes zero or more new candidate facts (`fact_type` + `criticality`) plus, separately, which already-stored hot facts (if any) this turn actually referenced — only the latter update `last_referenced_at`. This step is best-effort: its failure must never fail or delay the chat response, and if the process terminates before it runs, that turn's extraction is simply lost — acceptable at POC scale, and explicitly not a reason to add a job queue, outbox, or Kafka now. If memory quality later justifies durability guarantees, that's a self-contained infrastructure change behind the same `MemoryExtractor` interface, not a memory-model redesign.

**LLM-proposed candidates are not stored as authoritative — `MemoryService` validates and normalizes first.** The model can mislabel a trivial remark as high-criticality, so before any candidate is persisted: `fact_type` and `criticality` must be one of the allowed enum values (reject/coerce anything else rather than trust free text), the fact text must be non-empty and under a fixed max length, and it must pass the deduplication check below. This is deliberately a small set of deterministic checks, not a second classifier — the goal is to stop obviously malformed or noise entries from occupying a hot-pool slot, not to second-guess every score the model gives.

**Deduplication is an open risk, called out rather than hand-waved.** Without embeddings (the no-vector-store invariant still holds — this isn't a reason to reopen it), dedup at POC scale is a cheap heuristic: same `fact_type` plus exact or near-exact text match against existing hot facts for that `(user, persona)`, skip the insert if it matches. This will under-catch paraphrased duplicates ("she loves Ladakh" vs "wants to visit Ladakh") more than an embedding-based check would. If duplicate/near-duplicate facts turn out to clutter the hot set in practice, that's the first concrete, evidence-based reason to consider a narrow, dedup-only embedding lookup — not general vector-store infrastructure, and not before it's actually shown to be a problem.

**Guardrail, stated explicitly because the word recurs:** `weakness` and `desire` are legitimate memory types for making the persona a better, more attentive companion — remembering someone is anxious about a work deadline is exactly the kind of thing that makes continuity feel real. They must never be read by matching, entitlement, or any monetization logic. That's the same content/business firewall as the already-declined "profile the user's weakness to convert them" proposal, just restated at the schema level: this table is for the persona's memory, full stop, and it does not get a second job as a conversion-optimization signal. Any future feature that wants to read `memory_facts` for a business purpose needs an explicit, separate decision — it does not fall out of this table existing.

## Context contract (cross-cutting: depends on sections 1–3)

`ai-architecture.md` specifies four blocks by approximate token budget. Worth making this a formal contract now, since it's the interface between persona storage, memory, and generation — three systems built by three different prompts, and this is what keeps them from drifting apart.

| Block | Source | Content | Ordering rule |
|---|---|---|---|
| 0 | Section 1 (`load_bible`) | engine + persona core, concatenated engine-first | Always first — this is the cacheable prefix; nothing before it, ever |
| 1 | Section 3 (`UserProfile`) | name, language, communication style — global | Fixed key-value lines, not prose |
| 2 | Section 3 (`MemoryFact`, scoped, Memory v1.2) | top **10** facts (default, configurable) selected from the **hot tier** (max 20 retained per user/persona) by `criticality` first (high, then medium, then low as filler), `status='open'` and recent `learned_at` as tiebreakers, plus the `last_message_at` continuity line when material | Facts as `type: text` key-value lines; continuity line as one plain sentence |
| 3 | `messages` table | last **20** messages (default, configurable) | Chronological, oldest first |

These are POC defaults, not ranges — an architecture doc should hand Copilot a deterministic number to implement, with a config knob to tune later, rather than leaving "6–10" or "16–20" as an implementation-time product decision.

Rules that make this an actual contract rather than a description:
- **Block 0 must be byte-identical** for every user of a given persona at a given point in time — this is what guard test 3 (`test_bible_block_is_identical_across_users`) checks, and it's the thing that breaks if anything user-specific ever leaks into block 0.
- **Truncation direction**: if the total exceeds budget, blocks 2 and 3 shrink first (drop lowest-priority facts, then oldest turns) — blocks 0 and 1 never truncate. A cut persona identity mid-conversation is a worse failure than a shorter memory of yesterday.
- **What never enters any block**: `crm_tags` (per the existing hard rule — physically separate, never in a prompt) and anything from the moderation/vision pipeline beyond its already-summarized text description (per the user-image path in `ai-architecture.md` — the roleplay model never sees a raw image).
- **Delimiters**: each block is a separate `system`-role message rather than concatenated into one giant string (this is already what `chat.py` does with the engine/grounding split, and what `load_bible` returns as `text` for block 0) — keeps provider-side prefix caching working per-block rather than invalidating the whole prompt on any change.

## 4. State and flow management — full spec

Three independent state domains, not one giant state machine trying to represent everything. Conflating them is a real, common failure mode: "is this user active" and "did this specific message succeed" are different questions with different lifetimes, and a single model that tries to answer both ends up with states like `paid_and_waiting_for_generation` that shouldn't exist.

### A. Entitlement state (per user, long-lived)

Already decided in `product-decisions.md`: `TRIAL → FREE ⇄ EXTENDED → PAID → LAPSED`.

```sql
CREATE TABLE entitlements (
    user_id         UUID PRIMARY KEY REFERENCES users(id),
    state           TEXT NOT NULL CHECK (state IN ('trial','free','extended','paid','lapsed')),
    trial_started_at TIMESTAMPTZ,  -- set on FIRST SENT MESSAGE, not signup
    state_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    extended_days_remaining INTEGER NOT NULL DEFAULT 0,  -- referral-earned, uncapped total
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
Transitions: `trial → free` (3 days elapsed since `trial_started_at`), `free ⇄ extended` (referral credit added/exhausted), `free|extended → paid` (successful payment), `paid → lapsed` (failed mandate — needs the grace-period design flagged as open in `product-decisions.md` item 5, a real gap worth closing before billing goes live, not architecture-blocking for the POC), `lapsed → paid` (manual renewal). Every transition writes `state_changed_at`, giving you a free audit trail without a separate history table.

### B. Conversation state (per conversation, medium-lived)

`active` / `idle` / `archived`. `idle` is what drives the "last talked X ago" memory continuity line (section 3/context contract) — a conversation goes idle after some inactivity threshold (a config value, not a hardcoded number) and back to active on the next message. `archived` is user- or system-initiated and mostly affects what surfaces in a conversation list UI, not the chat pipeline itself.

### C. Message pipeline state (per message, short-lived, seconds)

The happy path, already in `ai-architecture.md`'s latency budget, formalized as states:
```
received → entitlement_check → input_moderation → context_assembly
  → generation → output_validation → [regenerate once if flagged]
  → persist → deliver (simulated stream behind typing indicator)
```

**This needs named failure states, not just a happy path** — a real gap in the original sketch. Without them, "why did this message disappear" six weeks from now has no better answer than "something happened in the pipeline." Each stage has a corresponding failure outcome:

```
ENTITLEMENT_DENIED    -- quota exhausted or lapsed; user sees the wall, not an error
MODERATION_BLOCKED    -- input flagged; persona fallback line, per rule 11
GENERATION_FAILED     -- provider error/timeout; retry once, then fallback line
VALIDATION_FAILED     -- output failed twice; fallback line, never a system string
PERSIST_FAILED        -- DB write failed; this one IS a real error, log loudly, don't fake a reply
DELIVERY_FAILED       -- client disconnected after persist; message exists, retry delivery on reconnect
```
`PERSIST_FAILED` is the one case that's a genuine infrastructure problem rather than a designed fallback path — everything else has a defined, persona-voiced recovery; that one needs paging, not a fallback line, since the reply may not exist anywhere if it fails.

**Every message gets a `request_id`** (generated at `received`, logged and stamped through every subsequent stage) alongside the `meta.timings` already specified. This is what makes "why did this fail" answerable from logs instead of guesswork — cheap to add now, painful to retrofit once there's real traffic.

**Idempotency (core invariant 11) belongs here, not bolted on later.** A mobile client on a flaky connection will retry a send after a timeout that actually succeeded server-side — without protection, that's a duplicated user message and, worse, a second independent generation the user never asked for. Fix:

```sql
-- Nullable, not NOT NULL: only user messages originate from a client and
-- carry this. Assistant messages are server-generated and have no
-- client_message_id — a NOT NULL constraint here would wrongly force a
-- client-side ID onto a row the client never sent. A partial unique index
-- (rather than a table constraint) is what lets NULL rows coexist freely
-- while still deduplicating real client sends.
ALTER TABLE messages
    ADD COLUMN client_message_id UUID;

CREATE UNIQUE INDEX uq_client_message_per_conversation
    ON messages (conversation_id, client_message_id)
    WHERE client_message_id IS NOT NULL;
-- If the schema already tags message type/role at the DB level, add a CHECK
-- there too: client_message_id IS NOT NULL only when role = 'user'.
```
The client generates one `client_message_id` per logical user message and reuses that same ID for every retry of that message — not a new ID per attempt, which would defeat idempotency entirely. Pipeline entry becomes: look up `(conversation_id, client_message_id)` first — a hit means this exact send was already processed, so return the existing persisted result instead of re-running `generation`; a miss proceeds through the normal pipeline. This is a few lines at the `received` stage and the one piece of this doc that's genuinely painful to retrofit once there's production traffic and duplicate rows to reconcile after the fact — worth doing now, not deferring.

**Idempotency must be atomic, not just constrained.** A naive `if exists(): return; else generate(); insert()` has a race: two near-simultaneous retries of the same `client_message_id` can both pass the existence check before either has inserted, and both proceed to generate — the unique index then rejects the second insert, but only after a wasted (and, worse, possibly delivered) second generation already happened. The unique `(conversation_id, client_message_id)` index is the final authority, not a pre-check hint: the `received` stage must attempt the insert (or a DB-level `INSERT ... ON CONFLICT DO NOTHING` / equivalent transactional upsert) up front, and treat a conflict as "this was already handled" — fetching and returning the winning row's result — rather than treating the pre-check as sufficient on its own. No distributed locking needed for the POC; correct transaction/conflict handling in the database is enough.

### Onboarding (not a state machine)

Age-gate → persona presentation via the matching model (section 2) → first message, which starts the trial clock (state A above). Linear enough to be a handful of route guards rather than a modeled workflow — don't build machinery for something with no branches.

## 5. Maintain history

Storage is already covered (`messages`, `conversations`, nullable `media_ref`). What's missing is a **retention and deletion policy**, and given the content sensitivity here this is a legal question as much as an engineering one — flagging it plainly rather than designing around it silently.

Concrete recommendation to take to whoever handles legal/compliance: raw message text is your highest-liability data at rest — if there's ever a breach, "we retain explicit-adjacent chat logs indefinitely" is a materially worse position than "we retain them 90 days and then purge, keeping only the derived summary/facts, which are far less sensitive." A time-boxed retention window for raw text, longer retention for the low-sensitivity derived layer (summaries, facts), and a real user-initiated delete-my-data path are the three pieces. I can design the schema/TTL mechanics once you or counsel picks the actual retention window — that number isn't mine to pick.

## 6. Self-improvement on review

This is the one I want to be most deliberate about, because there's a specific, well-documented failure mode in this exact product category: a system that "improves" by optimizing for user engagement or positive reactions tends to drift toward whatever keeps people hooked — which, for a companion chat product, is very often more explicit, more intense, or more emotionally manipulative content, not better conversation. That's not a hypothetical; it's the standard critique of engagement-optimized companion apps, and it would also walk this product directly back into the Section 67A exposure you've spent this whole session actively guarding against. So the architectural rule is not a preference, it's load-bearing: **no automated prompt or behavior mutation driven by engagement, ratings, or session-length signals, ever.**

What I'd build instead is exactly the loop you've already been running by hand in Phase 0, formalized:

1. **Signals in**: thumbs up/down per reply, an explicit "report" flag, and — separately — a sample of conversations pulled at random regardless of rating (so problems that don't generate a complaint still get seen).
2. **Review queue**: flagged and sampled conversations go to a human reviewer (you, initially) scored against the same rubric as the eval harness in `ai-architecture.md` (Hinglish naturalness, character adherence, boundary holding, refusal rate on flirty-but-legal).
3. **Change proposal**: a reviewer edits `conversation_engine.txt`-equivalent or a specific `persona_core_versions` row, exactly like the `PROMPT_CHANGELOG.md` pattern from Phase 0 — a written note on what changed and why, tied to the version hash.
4. **Promotion**: a human explicitly flips which version is `is_active` / `active_core_version_id`. Nothing auto-promotes.

This scales fine to 30 personas with one reviewer doing spot checks, and it's the same intellectual work you and I did manually this session — turned into a repeatable process instead of an ad hoc one. The moment review volume actually exceeds what a human can do is a real future problem, but it's a staffing problem to solve then, not a reason to automate the judgment call now.

## 7. Behavioral change based on payment

Legitimate, common, and fine to build: message quota by tier (already decided — 30/day free, uncapped paid/extended), response depth or memory richness (a paid user's context could carry more memory facts or a richer summary, since that's a real quality-of-relationship lever), which personas are unlocked (some personas gated behind subscription is a completely normal freemium pattern), and generation priority/latency under load if you ever need to shed load.

Not built, regardless of tier, framing, or revenue argument: any change to what content is permitted. The engine's hard rules apply identically to a free user on day one and a subscriber on day 400. This isn't a soft preference I might revisit under business pressure — it's the same reasoning as the rest of this doc: paywalling explicit content doesn't move the legal exposure somewhere safer, it moves it somewhere worse, and it's also just not something I'll design regardless of the legal analysis.

---

## What I'd actually build first, of these seven

In order: (1) the persona/engine DB schema, since everything else references it; (4) the message-pipeline state machine, since it's the backbone the validator and moderation hang off; (3) the `UserProfile`/`MemoryFact` split, since it's a two-table addition to something already speced; (5) a retention policy decision (needs your/counsel's input on the actual number, not just my design); (6) the review-queue process, which can start as a spreadsheet before it's a feature; (2) is nearly free once (1) exists; (7) is mostly already decided in `product-decisions.md` and just needs wiring into the entitlement state machine in (4).