# Persona Image Pipeline — Architecture (frozen)

Companion to `production-architecture.md` (chat engine) and `pink-dreams-implementation-spec.md`. This doc is the single source of truth for the image-generation subsystem — every phase instruction given to Claude Code should reference this rather than re-deriving context. Update this doc when a phase changes an architectural decision; do not let decisions live only in chat history.

Status: **frozen** after IMG-0 reconnaissance + two rounds of review (Claude + GPT). Individual phases may surface corrections — fold them back into this doc immediately, the same discipline used for the chat engine's architecture doc.

---

## 1. What this is

A separate subsystem, implemented incrementally exactly like the chat pipeline: each phase leaves the application working, with tests, before the next phase starts. It is not "an image feature bolted onto chat" — it introduces four genuinely new infrastructure primitives the codebase does not have today: **visual identity data model, durable async jobs, object storage, and (later) storyline/feed**.

## 2. Target shape

```
Persona
   │
   ├── Storyline / Timeline          [does not exist yet — separate subsystem, see §7]
   │
   └── persona_identity (existing placeholder — becomes the root)
          │
          └── Visual Identity Version (locked, immutable once terminal)
                 │
                 ├── Physical guide (face / hair / body / skin / distinctive features)
                 ├── Style constraints (stable, not daily wardrobe)
                 └── Reference images
                           │
                           ▼
                    Identity Lock
                           │
                           ▼
                    Daily Scene Planner  [needs storyline or an interim proxy — see §7]
                           │
                           ▼
                    Prompt Compiler
                           │
                           ▼
                 Image Provider (OpenRouter/FLUX, swappable)
                           │
                           ▼
                    Candidate Images  (object storage; Postgres holds metadata/provenance)
                           │
                           ▼
                    Quality Scoring (human first, automated later)
                     ┌─────┴─────┐
                     ▼           ▼
                  Reject       Accept
                     │           │
                     ▼           ▼
               Regenerate     Publish (manual gate first, automated later)
                                  │
                                  ▼
                          Content Ledger
                                  │
                                  ▼
                         Premium Candidate (manual selection first, automated later)
```

## 3. Core architectural decisions (locked)

1. **`persona_identity` is the root of visual identity**, not `personas` directly. `Persona → persona_identity → PersonaVisualVersion → references`. Do not create a competing `persona → visual_profile` relationship — `personas.persona_identity_id` already exists as the intended hook (confirmed in code; CLAUDE.md calls it a placeholder for exactly this).
2. **Visual identity versions are immutable once locked**, enforced at the database level, not only in application code. No such DB-level immutability pattern currently exists anywhere in this codebase — `persona_core_versions` only has application-code draft-only mutation guards (`PersonaCoreVersionRepository.updateContent()`'s `require(status == "draft")`) plus one DB trigger for a narrower, different invariant (`chk_active_core_version_is_published`: active must point at a published row — says nothing about editability). IMG-1 introduces the first real DB-level immutability trigger in this project; it does not retrofit `persona_core_versions` (that's a flagged cross-cutting finding for a future phase, not in scope now).
3. **"At most one active version" follows the persona-core shape, not the engine shape.** `persona_core_versions`' active-ness lives as a single FK slot on the parent (`personas.active_core_version_id`), not a partial-unique-index column on the child. `conversation_engines` uses a DB-level partial unique index (`one_active_engine ... WHERE is_active`) because it's a different shape — one active engine system-wide, not one active version per owning row. Visual identity is 1:1-per-persona like persona-core, so it follows the FK-slot pattern (`persona_identity.active_visual_version_id`), not the partial-index pattern.
4. **Wardrobe is split from scene.** `PersonaWardrobe` (what the persona owns/normally wears) is identity-level and versioned, changing rarely. "What she's wearing today" is scene-level, changes daily, and is not part of the locked visual identity. This split must be explicit before schema work starts — the two concepts collide if not separated up front.
5. **Image generation is asynchronous from day one.** The only existing async precedent in this codebase, `BestEffortMemoryExtraction`, is an in-process `CompletableFuture` on a thread pool — fire-and-forget, no durability, acceptable to lose on crash. That is not adequate for image generation (longer-running, costs money, needs to survive a restart, needs to scale to "generate for every active persona daily"). The image provider interface must be async-shaped from the start (returns a job handle / multiple candidates, not a single synchronous result) — this is why durable job infrastructure was moved to IMG-4, ahead of the provider integration, rather than bolted on after IMG-9/10 as originally sequenced.
6. **Object storage gets its own provider-style abstraction**, mirroring the `LlmClient`/`OpenRouterLlmClient` pattern. Postgres stores metadata/provenance only (mirroring how `messages` carries `engine_version_id`/`persona_core_version_id`); actual image bytes live in object storage, referenced by `storage_location`.
7. **Storyline does not exist yet** — confirmed absent from the codebase in IMG-0 (no table, no code, no docs beyond persona character-bible markdown). IMG-9 in the original roadmap assumed it existed; it doesn't. Interim approach: the daily scene planner may reuse the same memory facts already assembled for chat (`MemoryService.selectForContext`) as a cheap proxy for "what's emotionally live in this relationship" until real storyline infrastructure is built as its own subsystem. Do not couple the first visual-identity phases to storyline.
8. **No LoRA/character training yet.** Establish the reference-based baseline first, benchmark it (IMG-9 in the revised sequence), and only train if the benchmark demonstrates a real, measured need.
9. **No automated premium selection yet.** Manual selection first; automate later once real engagement/conversion data exists to rank against.
10. **No automated AI quality judge yet.** Human evaluation first, to build the benchmark dataset that later validates whether automated scoring is trustworthy.

## 4. Reuse map (what already exists and should not be reinvented)

| Concern | Existing pattern to copy | File |
|---|---|---|
| Provider abstraction | `LlmClient` fun interface + `FakeLlmClient` + concrete `OpenRouterLlmClient` + env-driven `LlmConfig` | `llm/LlmClient.kt`, `llm/OpenRouterLlmClient.kt`, `config/LlmConfig.kt` |
| Multi-stage pipeline w/ named failure states | Sealed `StageResult<T>`, `PipelineStage` enum, sealed `ChatResult` | `chat/ChatEngine.kt` |
| Idempotency ledger | Claim → processing → completed/failed on a composite PK | `persistence/RepositoryChatExecutionCoordinator.kt`, `chat_request_executions` table |
| Version lifecycle (draft → published/locked → archived) | `PersonaCoreVersionRepository` | `persistence/repositories/PersonaCoreVersionRepository.kt` |
| Active-slot invariant (1:1 owner) | `personas.active_core_version_id` FK slot | `db/migration/V001__initial_schema.sql` |
| System-wide active invariant (not our shape, don't copy for this) | `conversation_engines` partial unique index | same file |
| Best-effort async (not durable enough for images — reference only) | `BestEffortMemoryExtraction` | `chat/memory/MemoryExtraction.kt` |
| Test convention | Phase-named JUnit classes, H2 in-memory DB, no real external calls except one manual smoke test | `src/test/kotlin/com/pinkdreams/**` |

## 5. Net-new infrastructure (nothing to reuse — build fresh)

- Durable job/queue model (DB-backed claim/poll, not an in-process thread pool)
- Object storage abstraction and provider
- Storyline/timeline subsystem (deferred, interim proxy via memory facts)
- Public publishing/feed surface (doesn't exist for anything today, chat included)
- Cost/spend guardrails for generation (doesn't fully exist for chat either — more urgent here since image generation costs more per call)

## 6. Revised phase sequence

```
IMG-0   Architecture reconnaissance                 ✅ done
IMG-1   Visual Identity foundation                   ← current
IMG-2   Physical Guide + Wardrobe
IMG-3   Reference Images + Object Storage
IMG-4   Durable Image Job Infrastructure
IMG-5   Image Provider Abstraction
IMG-6   OpenRouter / FLUX integration
IMG-7   Prompt / Reference Compiler
IMG-8   Reference Finalization Workflow
IMG-9   Identity Benchmark
IMG-10  Daily Scene / Storyline Foundation (interim: memory-fact proxy)
IMG-11  Daily Image Generation Pipeline
IMG-12  Content Ledger + Provenance
IMG-13  Human Publishing Workflow
IMG-14  Quality Evaluation + Regeneration
IMG-15  Automatic Publishing
IMG-16  Premium Candidate Workflow
IMG-17  Character Training / LoRA Evaluation
IMG-18  Automated Premium Optimization
```

Durable jobs (IMG-4) intentionally sit before the provider integration (IMG-5/6) — the biggest change from the original draft sequence, because the provider interface itself needs to be async-shaped from the start (§3.5).

## 7. Open items (explicitly not decided yet — do not let a phase instruction silently assume an answer)

- Exact object storage provider (S3-compatible vendor TBD — implementation detail for IMG-3, not an architecture question)
- Exact durable job mechanism for IMG-4 (DB-polled table vs. a real queue product) — needs its own small design pass when IMG-4 starts
- Cost/spend ceiling numbers (per-generation and aggregate) — needs a number before IMG-6 goes anywhere near real spend
- Admin permission granularity for the growing image-admin surface (currently flat UUID allowlist, same as chat admin) — fine for now, revisit if the surface grows past a few endpoints
- Whether `persona_core_versions` gets retroactively hardened with the same DB-immutability trigger IMG-1 introduces — flagged, not scheduled

## 8. Process

Each `IMG-N` phase is instructed to Claude Code in VS Code individually, scoped narrowly (no implementing future phases), with an explicit completion report (files changed, schema changes, invariants enforced at DB level, tests added, total test result, any discrepancy found). The completion report is independently verified against the actual repository in this conversation before the next phase is authorized — never taken at face value. This doc gets updated whenever that verification surfaces a correction, exactly as happened before IMG-1 was sent (the DB-immutability claim).