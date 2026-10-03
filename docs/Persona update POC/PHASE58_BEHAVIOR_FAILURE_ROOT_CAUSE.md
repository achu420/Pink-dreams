# Phase 58 — First Real Production Behavior Failure Investigation

**Diagnosis only. Zero API calls made. No production code changed.**

---

## 1. Executive Summary

Failure A (intent discovery returning no skill for plainly on-topic biographical questions) is **understood** at the DERIVED-CONCLUSION level: the real `IntentEngineDefaultContent` prompt defines `general_chat` only residually ("ordinary conversation without a more specific interaction mode") rather than affirmatively including persona-biographical questions, and combines this with an explicit "return null rather than guessing" instruction — a structural bias toward `null` for exactly this message type, confirmed by direct reading of the current production prompt (not inferred from behavior alone). Failure B (the model contradicting correct, delivered persona data on turn 7) is **partially understood**: the causal mechanism is established at the source level — conversation history is assembled once, unfiltered, before skill selection, and includes the model's own earlier fabricated turns verbatim in every later turn's context, while the current Conversation Engine text establishes a general priority order (Persona Core > memory/history) but contains no explicit rule for resolving a direct factual contradiction between the model's own prior turn and the authoritative Persona Core supplied in the current turn. Whether this gap is *the* proximate cause of the model's specific choice on turn 7, versus a property of the underlying model's own behavior, cannot be proven from source or logs alone — Part 10's requirement to "prove from the actual captured request" cannot be fully satisfied because Phase 57 did not persist the raw `GenerationRequest` object, only aggregate observability signals and response text (disclosed precisely, not reconstructed).

Neither failure requires a Persona Context architecture change. Both plausible next steps identified (§12, §13) are outside `PersonaFieldAuthorizer`/`PersonaContextResolutionStage`/`ProductionPersonaContextService`/rendering-policy/restricted-field code, consistent with Phase 57's own finding that this architecture performed correctly at every turn.

## 2. Phase 57 Evidence Recap

Source: `PHASE57_RAW_SERVER_LOG.txt` (the complete, unedited raw server log — confirmed complete this phase, 164 lines, covers application startup through the final logged turn and an explicit `[killed]` marker at the point the process was stopped). No other raw artifact (captured `GenerationRequest`, persisted message content) survives — the test conversation's rows were deliberately cleaned up after Phase 57 concluded, per that phase's own stated data-hygiene practice, and their exact text was captured only as terminal output within that phase's own turn, not as a separate structured artifact this phase can re-parse. This is disclosed as a genuine limitation (see §17), not a STOP condition — the *skill-selection and authorization* evidence (the log lines) is completely intact and sufficient for §4; only the *raw context-block* reconstruction for §6 is partially unavailable.

## 3. Production Pipeline Trace

Confirmed by direct source read this phase (`ChatEngine.kt`, `RepositoryContextAssembler.kt`), not re-derived from memory of prior phases:

```
ChatRequest
  → PipelineChatEngine.process()
    → CONTEXT_ASSEMBLY: RepositoryContextAssembler.assemble()
        - loads ENGINE block (active Conversation Engine text)
        - loads PERSONA block = PersonaCoreVersions.content (raw baseline; real skill not yet known)
        - loads PROFILE block
        - loads MEMORY block (facts selected for this user+persona)
        - loads HISTORY blocks: messageRepository.findForConversation(id).takeLast(messageLimit)
          -- EVERY persisted message, unfiltered, verbatim, role-preserved,
             INCLUDING any prior assistant turn regardless of factual correctness
        - loads CURRENT_MESSAGE block (this turn's user text)
    → SKILL_SELECTION: LlmIntentDiscovery (real LLM call, sees current message + recent history + active skill keys)
    → PersonaContextResolutionStage.resolve() [Strategy-D]
        - reads context.selectedSkillKey (set by the stage above)
        - if null: returns context UNCHANGED (PERSONA block stays at raw baseline)
        - if non-null: PersonaFieldAuthorizer -> ProductionPersonaContextService -> replaces PERSONA block
    → GENERATION: LlmGenerator (real LLM call, sees the full assembled context including HISTORY)
    → persist assistant response as a new message (unconditionally, regardless of factual correctness)
    → (async, post-response) MEMORY_EXTRACTION: a separate LLM call proposes memory facts from the just-completed turn
```

**Established, source-confirmed fact directly relevant to both failures**: CONTEXT_ASSEMBLY (which builds the HISTORY blocks) runs **before** SKILL_SELECTION, and SKILL_SELECTION runs **before** persona resolution. Conversation history is therefore identical regardless of what skill is selected this turn, and is never filtered, corrected, or annotated based on whether a prior turn's PERSONA block was ever authorized.

## 4. Failure A — Intent Discovery

**Exact evidence, per failed turn** (from the raw log, request IDs cross-referenced to Phase 57's own recorded prompt text):

| Turn | User message | `SKILL_SELECTION` | `promptTokens` | `completionTokens` |
|---|---|---|---:|---:|
| 2 | "Where are you originally from?" | `none` | 1752 | 6 |
| 3 | "What do you do for work?" | `none` | 1839 | 6 |
| 4 | "Do you live alone or with someone?" | `none` | 1891 | 6 |
| 5 | explicit crude intimacy request | `none` | 1906 | 6 |

**No candidate-skill list, confidence score, or reasoning trace is logged** — `INTENT_DIAGNOSTICS` records only `outcome`, `finishReason`, and token counts (confirmed by direct read of the log format; this is the complete set of fields this call site logs). This is a genuine, disclosed **observability gap** distinct from Persona Context's own (already-adequate) observability — intent discovery's own reasoning is not currently inspectable post-hoc.

**Root cause, established from the current production prompt** (`IntentEngineDefaultContent.CONTENT`, read in full this phase):

- `general_chat` is defined only residually: *"ordinary conversation without a more specific interaction mode"* (line 90) — it is never affirmatively described as covering biographical/factual questions about the persona.
- The SELECTION RULES section instructs: *"Return null when no available skill clearly represents the interaction"* and, under DOMINANT INTENT WHEN SKILLS OVERLAP: *"When the conversation genuinely does not favour one over another, return null rather than guessing."*
- No rule in the prompt affirmatively states that a plain factual/biographical question about the persona (as opposed to, e.g., a request for advice, emotional support, or romantic interaction) should default to `general_chat`.

**Answering Phase 58's explicit Part 8 question**: this is **Option C** — *"the selector is functioning according to its current rules but those rules are insufficient for persona-grounded factual questions."* This is a **DERIVED CONCLUSION**, not an ESTABLISHED FACT — it is directly supported by the prompt's own text (an ESTABLISHED FACT: the prompt contains no affirmative general_chat trigger for biographical questions, and does contain an explicit "return null" bias), but the specific claim that *this is why the model chose null on these exact 4 turns* cannot be proven without the model's own reasoning trace, which was not captured (see §4's observability-gap note above).

`general_chat` is confirmed as a real, currently active production skill key (per `BASELINE_SEED: ... skillsAlreadyPresent=26`, and per this same conversation's own turn 1, where `general_chat` was correctly selected for "Hey Simran, how's your day going?"). The taxonomy does not intentionally exclude biographical questions (Option B is **rejected** — there is no evidence of deliberate exclusion, only an evidenced gap in affirmative coverage).

## 5. Failure B — Fabrication / Self-Contradiction

**Established facts, from the log**:

- Turns 2-4: `SKILL_SELECTION: none` → `PersonaContextResolutionStage.resolve()` returns unchanged (no `PERSONA_AUTHORIZER`/`PERSONA_CONTEXT` log lines appear for these turns, confirming the early-return branch was taken, per §3's traced code path) → PERSONA block remained the raw `PersonaCoreVersions.content` baseline, which (established in the prior production-readiness pass, re-confirmed unchanged) contains zero biographical facts.
- The model, given no persona facts, generated a self-consistent but factually wrong biography (Mumbai, UK relocation, interior designer occupation, living alone) across turns 2-4.
- Turn 7: `SKILL_SELECTION: selected='friendship'`, `PERSONA_AUTHORIZER: status=USABLE ... fieldCount=5`, `PERSONA_CONTEXT: ... usedReplacement=true` — the real, correct, authorized persona data **was** installed into the PERSONA block this turn (established from the log; `usedReplacement=true` can only be logged by the code path that follows a successful field-count-based replacement, per `PersonaContextResolutionStage.kt`'s source, re-confirmed this phase).
- The model's turn-7 response nonetheless continued the fabricated narrative (an invented older brother, a reference to "when I visit Mumbai").

**What is NOT established, and could not be proven this phase**: the exact literal content of the final assembled context blocks sent to the model on turn 7 — specifically, whether the HISTORY blocks literally contained turns 2-4's fabricated assistant text verbatim (this is the expected behavior per §3's traced code path, and no code path exists that would filter it, but Phase 57 did not persist the raw `GenerationRequest`, so this specific claim is a **DERIVED CONCLUSION from source code tracing**, not a directly-observed fact from a captured request). Per Part 10's explicit instruction not to infer this from logs alone when the actual captured request is available — the actual captured request is **not** available (disclosed, not worked around).

## 6. Exact Request/Context Reconstruction

**Partially reconstructable.** What IS known with confidence (ESTABLISHED, from source + log): the turn-7 context contained an ENGINE block (current, unmodified `CONVERSATION_ENGINE` text), a PERSONA block with 5 real authorized fields (per `fieldCount=5`), a PROFILE block, a MEMORY block, HISTORY blocks for turns 1-6 (per `RepositoryContextAssembler`'s unconditional inclusion logic), and the turn-7 CURRENT_MESSAGE. What is **NOT** independently verifiable without the raw captured request: the literal byte content of those HISTORY blocks. This is disclosed as a real gap rather than papered over.

## 7. Conversation History Analysis

**ESTABLISHED, from source**: `RepositoryContextAssembler`'s history-block construction (`messageRepository.findForConversation(conversationId).takeLast(messageLimit)`) has no correctness filter, no confidence annotation, and no mechanism to mark a persisted assistant message as "unauthorized" or "not persona-context-backed" even when, as in turns 2-4, that message was generated with zero real persona data available. A later turn's HISTORY block is therefore architecturally guaranteed to include the fabricated turns 2-4 text verbatim, with the same formatting and apparent authority as any other assistant turn (**DERIVED CONCLUSION** — the mechanism is established; that this specific text was included in turn 7's actual request is not independently confirmed, per §6).

## 8. Memory Persistence Analysis

**ESTABLISHED, from the log**: `MEMORY_EXTRACTION` ran after turn 2 (`proposed=3 accepted=3`) and after turns 4 and 6 (2 more extraction passes, one with `proposed=2 accepted=2`, one failing with `OpenRouterBudgetExhaustionException`). A separate line, `MEMORY_ENGINE: ... userApplied=6 userSkipped=0 personaApplied=1 personaSkipped=0`, confirms that **one persona-scoped memory fact was applied** at some point during this conversation. `ProductionMemoryScope.resolve(userId, personaId, conversationId)` (confirmed by source read this phase) scopes memory strictly to the `(userId, personaId)` pair — **this is not a cross-user contamination risk**: even if the applied persona-scoped fact encodes the fabricated Mumbai/UK detail, it would only affect this one test user's own future conversations with Simran, never other users'.

**UNKNOWN, and disclosed as such rather than guessed**: the literal content of the applied persona-scoped memory fact. It was never captured before the test conversation's `memory_facts` rows were deleted during Phase 57's own cleanup. Whether it encodes the fabrication cannot be confirmed or ruled out. This is a genuine, real risk pathway (a fabricated biographical detail becoming a persisted "memory," which `MEMORY_CONTEXT_SELECTION` could then resurface in yet further future turns, independent of skill selection or Persona Context) that deserves a targeted, cheap follow-up (inspect a fresh test conversation's `memory_facts` rows directly, without deleting them first) rather than being asserted as either confirmed or absent here.

## 9. Engine/Persona Grounding Precedence

**ESTABLISHED, from direct reading of the current, real `BaselineConfiguration.CONVERSATION_ENGINE` text** (re-read this phase in full, not assumed from a prior phase's summary):

> "The current user message determines what the user is asking now. The Persona Core defines who the persona is. The Conversation Engine defines universal operating behavior. The Selected Skill defines the current interaction mode. Memory and history provide supporting context. Do not allow a low-priority contextual detail to override a direct current request."

This **does** establish a general priority ordering with Persona Core ranked above memory/history. It does **not** contain any explicit rule of the specific form Phase 58 asked about — *"authoritative persona context > previous assistant statements > model inference,"* specifically addressing the case where the model's **own prior turn** directly contradicts the Persona Core supplied in the **current** turn. The existing sentence ("do not allow a low-priority contextual detail to override a direct current request") is about not being distracted from the user's current ask, not about resolving a factual conflict between the model's own past output and newly-authoritative persona data. **This is a documented architectural gap, established directly from the source text, not assumed.**

## 10. Causal Chain

**FAILURE A** (verified against source/log at each arrow, per Part 14's explicit requirement):

```
User asks a biographical question about the persona
  → [ESTABLISHED, log] LlmIntentDiscovery is invoked with the message + recent history
  → [DERIVED, from prompt text] general_chat's residual definition + the "return null
    rather than guessing" rule bias the model toward null for a message with no other
    clearly-matching named skill
  → [ESTABLISHED, log] SKILL_SELECTION: none
  → [ESTABLISHED, source] PersonaContextResolutionStage.resolve() returns context
    unchanged (context.selectedSkillKey ?: return context)
  → [ESTABLISHED, source+Phase 50] PERSONA block = raw PersonaCoreVersions.content,
    which contains zero biographical facts
  → [ESTABLISHED, observed response text] model lacks factual grounding for this turn
  → [OBSERVED, response text] fabrication
```

**FAILURE B**:

```
Turns 2-4 fabrication [OBSERVED, persisted as real assistant messages per §3's
  unconditional-persist step]
  → [DERIVED, source] persisted verbatim into conversation history, no filter exists
  → [DERIVED, source] later turns' HISTORY blocks include this text (not independently
    confirmed for turn 7 specifically -- see §6)
  → [ESTABLISHED, log] turn 7 correct persona context arrives (fieldCount=5,
    usedReplacement=true)
  → [ESTABLISHED, source] engine text establishes Persona Core > history priority
    IN GENERAL, but has no rule for resolving a direct self-contradiction
    specifically
  → [OBSERVED, response text, NOT independently proven causal] model follows/continues
    the earlier fabrication rather than the newly-supplied correct data
```

The final arrow in Failure B's chain is the one link this phase cannot upgrade from OBSERVED-correlation to ESTABLISHED-causation, per §6's disclosed limitation.

## 11. Established vs Hypothesis Matrix

| Claim | Grade |
|---|---|
| Pipeline stage order (assembly → skill selection → persona resolution → generation) | **ESTABLISHED** (source) |
| Turns 2-5 had `SKILL_SELECTION: none` | **ESTABLISHED** (log) |
| `PersonaContextResolutionStage` returns unchanged when `selectedSkillKey` is null | **ESTABLISHED** (source) |
| Raw baseline `PersonaCoreVersions.content` has zero biographical facts | **ESTABLISHED** (Phase 50, re-confirmed unchanged) |
| Turn 7 had real, authorized persona data delivered (`fieldCount=5`, `usedReplacement=true`) | **ESTABLISHED** (log) |
| `general_chat`'s definition is residual, no affirmative biographical-question trigger exists in the prompt | **ESTABLISHED** (source, direct prompt read) |
| This residual definition + "return null" bias is *why* the model chose null on turns 2-5 | **DERIVED CONCLUSION** (strongly source-supported, not proven via the model's own reasoning trace, which is not logged) |
| Conversation history includes turns 2-4's fabricated text unfiltered, architecturally | **DERIVED CONCLUSION** (mechanism established from source; not directly observed in a captured request) |
| Turn 7's actual context literally contained the fabricated history text | **HYPOTHESIS** (highly likely given §3/§7, not independently confirmed — raw request unavailable) |
| Turn 7's model behavior (continuing the fabrication) was *caused by* the missing self-contradiction-resolution rule (§9) | **HYPOTHESIS** (plausible, not provable from available evidence; the model's own behavior under correct-but-conflicting context is not separable from this specific engine-text gap without a controlled experiment) |
| One persona-scoped memory fact was applied during this conversation | **ESTABLISHED** (log) |
| That memory fact encodes the fabricated biography | **UNKNOWN** (content never captured) |
| Memory scoping is per-(user, persona), not global | **ESTABLISHED** (source) |

## 12. Candidate Fixes for Failure A

**Not recommended, not selected, not implemented — options only, per instruction.**

| Option | Component | Change | Safety implications | Regression surface | DB changes? | API experiment needed? | Deterministically testable? | Touches research code? |
|---|---|---|---|---|---|---|---|---|
| A1 | `IntentEngineDefaultContent` | Add an affirmative rule: plain factual/biographical questions about the persona with no other clear skill match should select `general_chat`, not null | Low — `general_chat` is already the least-restrictive, non-safety-relevant skill; would not affect restricted-field authorization at all | Could shift borderline classifications for other ambiguous messages toward `general_chat` more often; needs its own evaluation, not assumed safe | No (prompt text only, seeded via the existing `IntentEngineRepository` versioning mechanism, never overwriting a live active version — same pattern `BaselineSeeder` already uses) | Yes — a small, targeted set of diagnostic real calls would be needed to confirm the prompt change actually changes classification before trusting it | Partially — the prompt text change itself is deterministic to seed/version; the *classification behavior change* is not deterministically testable without real calls (it depends on real LLM judgment) | No |
| A2 | Persona-grounding architecture (see §13, Part 18's alternative) | Give persona-biographical queries a path independent of skill selection entirely | Requires new architectural surface | Large — touches `RepositoryContextAssembler`/pipeline ordering, exactly the area Rule 2 says not to touch without a demonstrated defect | Possibly (if any new field-registry semantics are needed) | Not for the mechanism itself; yes for behavioral validation | Yes for the mechanism, no for whether it "fixes" the behavior | No |

## 13. Candidate Fixes for Failure B

| Option | Component | Change | Safety implications | Regression surface | DB changes? | API experiment needed? | Deterministically testable? | Touches research code? |
|---|---|---|---|---|---|---|---|---|
| B1 | `CONVERSATION_ENGINE` | Add an explicit sentence: when the current turn's Persona Core/Persona Context conflicts with a prior assistant statement in history, the Persona Core is authoritative and the assistant should treat the prior statement as its own error, not as fact | Low direct safety risk (a grounding instruction, not a permission change) | Behavioral only — could change response style/tone in unrelated ways; needs evaluation | No (same versioned-seed mechanism as A1) | Yes — needed to confirm the added instruction actually changes model behavior in a contradiction scenario | The instruction text change is deterministic to seed; behavioral effect is not | No |
| B2 | Application-level | On persisting an assistant message, tag/flag whether it was generated with `usedReplacement=true` (i.e., backed by real authorized persona data) or not, and surface that flag (not the content) to later turns' history rendering, e.g. a subtle marker the engine text can be told to treat specially | Low — metadata only, no restricted-content exposure risk | Moderate — touches message persistence/history-rendering, a shared path used by every conversation | Possibly (a new column or reuse of existing `metadata`) | Yes, to validate | Partially | No |
| B3 | Do nothing at the architecture level; treat as a model-behavior/prompt-tuning question only | — | — | — | — | — | — | — |

## 14. Regression/Safety Analysis

None of the candidate options in §12-§13 touch `PersonaFieldAuthorizer`, `ProductionPersonaContextService`, `PersonaContextResolutionStage`, `RepositoryContextAssembler`'s restricted-field logic, or any rendering-policy code — the exact components Phase 57 already proved correct and this phase's own Rule 2 protects from casual modification. B2 is the only option with a nonzero architectural footprint (message metadata), and even it does not touch authorization or restricted-field logic. A2 is explicitly the largest-footprint option and is flagged as such — not recommended, only disclosed as the alternative Part 18 asked to be considered.

## 15. Recommended Next Engineering Step

**Not selected — per instruction, no recommendation is made, and no implementation occurs this phase.** The smallest, lowest-risk, most directly-evidenced next step **would be** A1 (§12) combined with a small number of diagnostic real calls to confirm it actually changes classification for the exact 4 failed-turn prompts from Phase 57 — but selecting and running this is explicitly deferred to a future, separately-authorized phase, per Rule 4/20 and Part 16.

## 16. Explicit Unknowns

- The literal reasoning behind each `SKILL_SELECTION: none` decision (no chain-of-thought or confidence score is logged).
- The literal byte content of turn 7's HISTORY blocks (raw request not captured).
- The literal content of the one applied persona-scoped memory fact (§8).
- Whether B1's proposed engine-text addition would actually change model behavior in a real contradiction scenario (untested).
- Whether Failure B would still occur if Failure A were fixed (i.e., whether more turns having correct persona data from the start would prevent the initial fabrication that Failure B then compounds) — plausible, not tested.

## 17. STOP Conditions

None triggered. Specifically:
- Phase 57's raw logs were available and complete (§2).
- The production pipeline was fully reconstructable from source (§3).
- Diagnosis required no production behavior change (confirmed: zero files modified this phase).
- No candidate fix requires inventing a new skill or persona rule not already discussed as an option, not a decision (§12-§13 are options, not adopted rules).
- No conclusion in this report depends on any API call — zero were made.

One genuine, disclosed evidentiary gap exists (§6: raw captured request unavailable) — this was documented precisely rather than treated as a STOP condition, since it affects only the confidence grade of specific claims (correctly downgraded to HYPOTHESIS/DERIVED in §11), not the overall diagnostic conclusion's validity.

---

**Is Failure A understood?** Yes, at the DERIVED-CONCLUSION level, directly supported by the current production prompt's own text.

**Is Failure B understood?** Partially — the mechanism (unfiltered history + no explicit contradiction-resolution rule) is established from source; the specific causal claim that this mechanism is what drove the model's turn-7 choice is a well-supported hypothesis, not a proven fact.

**Does either require a code change?** Not necessarily to Persona Context (confirmed clean). A prompt-text change (intent-discovery seed content, and/or Conversation Engine text) is the smallest candidate surface for both, per §12/§13 — but no change is made or recommended for adoption this phase.

**Smallest next implementation step, if justified?** A1 (§12) — an affirmative general_chat rule for biographical questions in `IntentEngineDefaultContent` — is the most directly evidenced, smallest-footprint candidate. **Not implemented in Phase 58.**

Phase 59 is not started.
