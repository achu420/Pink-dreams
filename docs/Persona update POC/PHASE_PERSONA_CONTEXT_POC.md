# PHASE --- PERSONA CONTEXT SELECTIVE RETRIEVAL POC

**Version:** 1.0\
**Status:** Planned\
**Primary Persona:** Simran\
**Secondary Validation Persona:** Keerthi Reddy

------------------------------------------------------------------------

# 1. Objective

Determine whether replacing the current **full Persona prompt** with a
**skill-aware Persona Context Resolver** improves or maintains response
quality while reducing token usage.

The experiment must compare:

``` text
A — Current Full Persona
B — Selective Persona
C — Behavioral Core + Selective Persona
```

The goal is evidence, not architecture confirmation.

------------------------------------------------------------------------

# 2. Current Baseline

Current conceptual pipeline:

``` text
User Message
    ↓
Intent Engine
    ↓
Skill Selection
    ↓
Skill
    +
Conversation Engine
    +
Full Persona Context
    +
Last 10 messages
    +
Relevant User Memories
    +
Relevant Persona Memories
    ↓
LLM
```

The existing product loop is:

``` text
USER NEED
    ↓
UNDERSTAND WHAT USER WANTS
    ↓
SELECT APPROPRIATE SKILL
    ↓
USE RELEVANT MEMORY
    ↓
RESPOND AS THE PERSONA
    ↓
MAKE THE INTERACTION USEFUL / ENJOYABLE
    ↓
CONTINUE NATURALLY
```

The POC changes only the way Persona information reaches generation.

------------------------------------------------------------------------

# 3. Target Architecture

``` text
User Message
    ↓
Intent Engine
    ↓
Skill Selection
    ↓
Skill Engine / Skill Prompt
    ↓
Persona Context Resolver
    ↓
Skill-specific Persona Context
    +
Conversation Engine
    +
Last 10 messages
    +
Relevant User Memories
    +
Relevant Persona Memories
    ↓
LLM
```

The resolver is the new component under test.

------------------------------------------------------------------------

# 4. POC Variants

## Variant A --- Current

Send the existing complete Persona context.

``` text
Persona = FULL
```

This is the baseline.

------------------------------------------------------------------------

## Variant B --- Selective

Send only Persona fields requested by the selected skill and applicable
context rules.

``` text
Skill
  ↓
Context requirements
  ↓
Persona Field Registry
  ↓
Selected fields
```

No separate LLM should decide retrieval during this first experiment.

Retrieval should be deterministic so that the experiment isolates the
architecture change.

------------------------------------------------------------------------

## Variant C --- Behavioral Core + Selective

Always load a small, stable behavioral identity:

``` text
personality
communication
behavioral_signature
stable identity anchors
core relational behavior
```

Then add skill-specific Persona fields.

This variant tests whether selective retrieval needs a permanent
behavioral layer.

------------------------------------------------------------------------

# 5. Deliverables

## Phase 5.1 --- Persona Master Logical Model

Finalize:

-   Persona domains
-   field hierarchy
-   field classifications
-   stability
-   provenance
-   access classification
-   retrieval metadata
-   versioning

Do not finalize physical DB tables yet.

------------------------------------------------------------------------

## Phase 5.2 --- Persona Field Registry

Create a registry for every field that can be selected by the resolver.

Minimum:

``` yaml
field_id:
path:
semantic_type:
classification:
sensitivity:
stability:
default_load:
allowed_contexts:
prohibited_contexts:
```

------------------------------------------------------------------------

## Phase 5.3 --- Skill Definition

Standardize the Skill Engine prompt contract.

Each skill must define:

``` yaml
purpose:
when_to_use:
when_not_to_use:

behavior:
response_strategy:
conversation_flow:

persona_context:
  required:
  recommended:
  conditional:
  restricted:

memory:
  user_memory:
  persona_memory:

output:
  response_style:
  length:
  initiative:
```

------------------------------------------------------------------------

# 6. Initial Skill Set

Use the existing skills as the first POC set:

``` text
companion
dating
emotional_support
foreplay
friendship
general_chat
Intent_discovery
playful_teasing
relationship_building
relationship_discussion
romantic_conversation
romantic_intimacy
```

Add:

``` text
about
perspective_of
```

as explicit Persona-context test skills.

Do not silently replace or rename the existing skill taxonomy during the
POC.

------------------------------------------------------------------------

# 7. Why `about` and `perspective_of` Matter

## About

Example:

> "Tell me about yourself."

Expected context families:

``` text
identity
life_context
occupation
personality
preferences
aspirations
current_context
communication
```

The test is whether the system can answer richly without loading
unrelated Persona domains.

------------------------------------------------------------------------

## Perspective Of

Example:

> "What do you think about marriage?"

Expected context families:

``` text
worldview
beliefs
social_views
strong_opinions
changeable_opinions
relationship_goals
life_context
personality
communication
```

The test is whether the system retrieves the Persona's perspective
rather than the entire biography.

------------------------------------------------------------------------

# 8. Initial Skill → Persona Mapping Hypothesis

This is a starting hypothesis and must be validated.

  ------------------------------------------------------------------------------------------------------------------------------------------------------------
  Persona Domain        Companion   Dating   Emotional   Friendship   General     Foreplay   Teasing   Relationship   Relationship       Romantic     Romantic
                                               Support                                                     Building     Discussion   Conversation     Intimacy
  ------------------- ----------- -------- ----------- ------------ --------- ------------ --------- -------------- -------------- -------------- ------------
  Identity                      C        O           O            O         O            O         O              O              O              O            O

  Personality                   C        C           C            C         C            C         C              C              C              C            C

  Communication                 C        C           C            C         C            C         C              C              C              C            C

  Behavioral                    C        C           C            C         C            C         C              C              C              C            C
  signature                                                                                                                                       

  Life context                  O        R           O            R         O          ---         O              R              R              O            O

  Family/background             O        O           O            O         O          ---       ---              O              O            ---          ---

  Worldview                     O        O           O            O         O          ---         O              O              R              O          ---

  Preferences                   R        O           O            R         R            O         O              O              O              R            O

  Aspirations                   O        O           O            O         O          ---       ---              O              R              O            O

  Decision patterns           ---        O           O            O         O          ---       ---              O              R              O          ---

  Relational                    O        R           O            R       ---            R         O              R              R              R            R

  Narrative state               R        R           R            R         O            R         O              R              R              R            R

  Timeline                      O        O           O            O         O          ---       ---              O              R              O            O

  Visual identity             ---        O         ---          ---       ---            O         O              O            ---              O            O

  Private visual              ---      ---         ---          ---       ---   restricted       ---            ---            ---            ---   restricted

  Adult intimacy              ---      ---         ---          ---       ---   restricted       ---            ---            ---            ---   restricted

  Public expression           ---      ---         ---          ---         O          ---       ---            ---            ---            ---          ---
  ------------------------------------------------------------------------------------------------------------------------------------------------------------

Legend:

``` text
C = behavioral core candidate
R = required
O = conditional/relevant
— = normally not loaded
restricted = explicit access gate required
```

This table is not considered final until validated.

------------------------------------------------------------------------

# 9. Restrictive Data Test

The POC must explicitly test restricted Persona fields.

Required restricted domains:

``` text
private_visual_identity
adult_intimacy
```

Test cases must confirm:

### Allowed

A legitimately authorized adult intimacy skill can request its required
restricted Persona fields.

### Denied

Ordinary skills cannot retrieve them merely because they exist.

Examples:

``` text
general_chat
friendship
emotional_support
about
perspective_of
```

must not automatically retrieve adult intimacy or private visual data.

### Auditability

Every restricted retrieval should be traceable to:

``` text
persona_id
persona_version
skill_id
skill_version
field_id
access decision
timestamp/request ID
```

The POC does not need to implement the final audit infrastructure, but
the data contract must support it.

------------------------------------------------------------------------

# 10. Test Corpus

Build approximately 12 scenarios per skill.

Each skill should include:

``` text
3 simple
3 medium
2 ambiguous
2 continuity-dependent
2 Persona-fact-dependent
```

Target:

``` text
~14 skills × 12 scenarios
≈ 168 test cases
```

The exact number may be reduced during implementation if duplicate
scenarios are removed.

------------------------------------------------------------------------

# 11. Test Categories

### Category A --- Simple conversation

Tests whether selective context works for ordinary requests.

### Category B --- Persona facts

Tests whether the correct biographical information is retrieved.

### Category C --- Persona perspective

Tests beliefs, opinions, values, and worldview.

### Category D --- Relationship continuity

Tests current relationship state, history, unresolved threads, and
user-persona continuity.

### Category E --- Narrative continuity

Tests current arcs, events, and life state.

### Category F --- Communication fidelity

Tests whether wording, tone, sentence style, humor, and mannerisms
remain Persona-consistent.

### Category G --- Restricted retrieval

Tests access boundaries.

### Category H --- Ambiguous requests

Tests whether the resolver can use conditional context rather than
blindly loading everything.

------------------------------------------------------------------------

# 12. Comparison Criteria

Every A/B/C result must be evaluated against the same criteria.

## 12.1 Persona fidelity

Does the answer behave like the same Persona?

Check:

-   tone
-   personality
-   mannerisms
-   emotional style
-   humor
-   opinions
-   relationship behavior

------------------------------------------------------------------------

## 12.2 Factual accuracy

Does the answer preserve correct Persona facts?

------------------------------------------------------------------------

## 12.3 Skill adherence

Does the response follow the selected skill?

------------------------------------------------------------------------

## 12.4 Relationship continuity

Does it preserve the correct relationship state and history?

------------------------------------------------------------------------

## 12.5 Narrative continuity

Does it know the current relevant life context without inventing facts?

------------------------------------------------------------------------

## 12.6 Naturalness

Does the response sound natural rather than assembled from database
fields?

------------------------------------------------------------------------

## 12.7 Context precision

Measure:

``` text
false inclusion
false exclusion
```

------------------------------------------------------------------------

## 12.8 Restricted leakage

Target:

``` text
0
```

------------------------------------------------------------------------

## 12.9 Token efficiency

Record:

``` text
persona_tokens
total_input_tokens
output_tokens
total_prompt_tokens
```

Calculate:

``` text
persona_token_reduction
total_prompt_reduction
```

------------------------------------------------------------------------

## 12.10 Latency and cost

Record where available:

``` text
resolver_latency
LLM_latency
total_latency
estimated_cost
```

------------------------------------------------------------------------

# 13. Success Matrix

  Metric                       Requirement
  ---------------------------- -----------------------------------------
  Persona fidelity             \>= current baseline
  Factual accuracy             \>= current baseline
  Skill adherence              \>= current baseline
  Relationship continuity      \>= current baseline
  Narrative continuity         \>= current baseline
  Naturalness                  \>= current baseline
  Context leakage              0 new failures
  Restricted-field leakage     0
  Meaningful missing context   \<= baseline
  Persona-token reduction      \>= 30% target
  Total prompt reduction       \>= 15--25% target
  Latency                      No meaningful regression
  Cost                         Lower or materially improved efficiency
  User satisfaction            \>= current baseline

A variant does not pass merely because it saves tokens.

Quality regressions must be identified and explained.

------------------------------------------------------------------------

# 14. Evaluation Method

For each test case store:

``` yaml
test_id:
persona_id:
persona_version:
skill_id:
skill_version:
user_message:

variant:
  A_full:
  B_selective:
  C_core_selective:

context_fields_loaded:

metrics:
  persona_tokens:
  total_input_tokens:
  output_tokens:
  latency:

evaluation:
  persona_fidelity:
  factual_accuracy:
  skill_adherence:
  relationship_continuity:
  narrative_continuity:
  naturalness:
  context_precision:
  restricted_leakage:

notes:
```

Human evaluation should be blinded to the variant where practical.

Do not tell the evaluator which response used full vs selective context.

------------------------------------------------------------------------

# 15. Primary Persona --- Simran

Simran is the primary POC because:

-   she already exists
-   her Persona context is already used by the current pipeline
-   existing skills are already defined around the current system
-   the existing behavior provides a real baseline

The first objective is not to create a better Persona.

It is to determine whether the same Persona can be represented more
efficiently and accurately at generation time.

------------------------------------------------------------------------

# 16. Secondary Persona --- Keerthi Reddy

After the Simran POC succeeds, repeat the core experiment with Keerthi.

Keerthi is specifically useful for validating:

``` text
rich narrative state
family relationships
career history
public/private separation
visual identity
restricted visual information
adult intimacy domain
multiple relationship threads
aspirations
worldview
```

If the architecture works for both, confidence in schema generality
increases.

------------------------------------------------------------------------

# 17. Decision Gates

## Gate 1 --- Logical model

Do we have a complete Persona Master model?

If no:

``` text
stop
```

------------------------------------------------------------------------

## Gate 2 --- Field registry

Can every selected Persona field be addressed independently?

If no:

``` text
stop
```

------------------------------------------------------------------------

## Gate 3 --- Skill requirements

Can skills request semantic Persona context without knowing database
paths?

If no:

``` text
stop
```

------------------------------------------------------------------------

## Gate 4 --- Resolver

Can the resolver deterministically produce a context projection?

If no:

``` text
stop
```

------------------------------------------------------------------------

## Gate 5 --- Simran results

If selective retrieval:

-   preserves quality
-   avoids leakage
-   materially reduces Persona tokens

then proceed.

If not, inspect false exclusions and revise the mapping.

------------------------------------------------------------------------

## Gate 6 --- Keerthi validation

If the same logical model works without Persona-specific hacks, proceed
to DB design.

------------------------------------------------------------------------

# 18. Final Phase Deliverable

At the end of the POC produce:

``` text
1. Final Persona Master logical schema
2. Final Persona Field Registry
3. Final Skill Definition schema
4. Validated Skill → Persona mapping
5. Persona Context Resolver specification
6. A/B/C benchmark results
7. Token/cost/latency results
8. Quality evaluation
9. Restricted-data access results
10. Decision on production architecture
11. Requirements for physical DB schema
```

Only after these are complete should the project create the production
database model.

------------------------------------------------------------------------

# 19. Explicit Non-Goals

This phase does NOT:

-   redesign all existing Personas
-   rewrite every skill
-   finalize the production DB schema
-   implement dynamic LLM-based retrieval
-   redesign Memory Engine
-   redesign Intent Engine
-   redesign Conversation Engine
-   author private visual content
-   author adult-intimacy content
-   change product relationship policy

This phase tests one architectural question:

> **Can the system produce better or equal Persona behavior by sending
> the generation model only the Persona information relevant to the
> current skill and context?**
