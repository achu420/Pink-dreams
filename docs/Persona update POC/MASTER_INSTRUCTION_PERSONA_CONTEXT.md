# MASTER INSTRUCTION --- PERSONA MASTER DATA + SELECTIVE PERSONA CONTEXT

**Version:** 1.0\
**Status:** POC baseline\
**Purpose:** Establish the canonical Persona model, field-level
restrictions, Skill Context requirements, and rules for generating the
Persona context sent to the message-generation API.

------------------------------------------------------------------------

## 1. Core Architectural Decision

The Persona Master Data is the **canonical source of truth** for a
Persona.

It is **not** the same thing as the Persona Context sent to the LLM.

The system must distinguish:

``` text
PERSONA MASTER DATA
    ↓
Skill requirements
    ↓
Persona Context Resolver
    ↓
Skill-specific Persona Context
    ↓
Conversation Engine + memories + recent conversation
    ↓
Message generation
```

The existing architecture is therefore extended, not replaced:

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

The product baseline remains:

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

The Persona remains the behavioral identity of the assistant:
personality, preferences, humor, opinions, emotional style, interests,
boundaries, history, and relationship behavior.

------------------------------------------------------------------------

# 2. Persona Master Data Principles

### 2.1 One canonical source

Every Persona fact must have one canonical location.

Do not duplicate the same fact across:

-   Persona prompt
-   Skill prompt
-   Conversation Engine prompt
-   Memory
-   hard-coded application logic

A skill may **request** a Persona concept, but should not become the
source of that Persona fact.

### 2.2 Master data is broader than generation context

The Persona Master may contain information that is never sent during
ordinary conversation.

Examples:

-   full family history
-   detailed career history
-   visual identity
-   private visual identity
-   adult intimacy profile
-   internal narrative state
-   content history
-   asset bindings
-   administrative metadata

This is intentional.

### 2.3 Retrieval is contextual

The generation API should receive the smallest Persona projection that
can produce a faithful response.

Do not optimize only for token count.

The resolver must preserve:

-   Persona identity
-   behavioral fidelity
-   factual accuracy
-   relationship continuity
-   skill behavior
-   relevant narrative state

### 2.4 Never make the LLM the database

The LLM must not be treated as the authoritative store of Persona facts.

The DB / Persona Master is authoritative.

------------------------------------------------------------------------

# 3. Logical Persona Master Structure

The canonical logical model should support these domains:

``` yaml
persona:
  meta
  identity
  life_context
  personality
  worldview
  aspirations
  preferences
  communication
  behavioral_signature
  decision_patterns
  relational
  public_expression
  visual_identity

  narrative:
    timeline
    narrative_state
    content_history

  restricted:
    private_visual_identity
    adult_intimacy

  assets:
    canonical_avatar
    reference_sets

  activation:
    status
    requirements
```

A physical DB implementation may normalize these into multiple tables
and/or JSONB documents. The logical model must remain independent of the
physical storage choice.

------------------------------------------------------------------------

# 4. Field Registry

Every Persona field that can be retrieved must have a registry
definition.

Minimum metadata:

``` yaml
field_id:
path:
semantic_type:
description:
classification:
sensitivity:
stability:
default_load:
allowed_contexts:
prohibited_contexts:
```

Example:

``` yaml
field_id: personality.core_traits
path: personality.core_traits
semantic_type: behavioral
classification: behavioral
sensitivity: normal
stability: permanent
default_load: true
```

Example:

``` yaml
field_id: life_context.family
path: life_context.family
semantic_type: background
classification: background
sensitivity: normal
stability: semi_stable
default_load: false
```

Example:

``` yaml
field_id: adult_intimacy
path: restricted.adult_intimacy
semantic_type: intimacy
classification: restricted
sensitivity: restricted
stability: changeable
default_load: false
allowed_contexts:
  - romantic_intimacy
  - foreplay
  - other_explicitly_authorized_adult_skills
prohibited_contexts:
  - general_chat
  - friendship
  - emotional_support
  - about
```

------------------------------------------------------------------------

# 5. Field Classifications

Use these logical classifications:

  -----------------------------------------------------------------------
  Classification                      Meaning
  ----------------------------------- -----------------------------------
  `identity`                          Stable identity and basic
                                      biographical facts

  `behavioral`                        Personality, communication,
                                      behavioral signature

  `background`                        Childhood, family, education,
                                      career history

  `worldview`                         Values, beliefs, opinions, social
                                      views

  `preference`                        Food, music, hobbies, interests,
                                      dislikes

  `relational`                        Attraction, relationship style,
                                      affection, trust

  `narrative`                         Current events, arcs, unresolved
                                      threads, current context

  `visual`                            Public/canonical visual identity

  `private`                           Private identity information not
                                      for ordinary generation

  `intimacy`                          Adult/intimate Persona behavior and
                                      boundaries

  `public`                            Public-expression and
                                      social-content behavior

  `aspiration`                        Goals, ambitions, dreams, future
                                      self

  `decision`                          Decision-making patterns

  `asset`                             Avatar, reference set, media
                                      bindings

  `administrative`                    Internal product metadata and
                                      activation state
  -----------------------------------------------------------------------

------------------------------------------------------------------------

# 6. Restrictive Details Are First-Class Persona Data

Sensitive or restricted Persona information must live in the Persona
Master when it defines the Persona.

It must **not** be embedded as hidden prose in a skill.

The Persona Master must support field-level restrictions.

Required restriction metadata:

``` yaml
access:
  classification: normal | private | restricted | admin_only

retrieval:
  default: true | false
  allowed_skills: []
  denied_skills: []

generation:
  allowed: true | false
  requires_authorization: true | false

storage:
  encrypted: true | false
  audit_required: true | false

versioning:
  independently_versioned: true | false
```

## Restricted Persona domains

At minimum:

``` text
restricted.private_visual_identity
restricted.adult_intimacy
```

Future restricted domains may include other private or operationally
sensitive Persona data.

### Critical rule

Restricted fields are **never loaded merely because the Persona is
adult**.

They are loaded only when:

1.  the current skill legitimately requires them;
2.  the application authorization/access gate permits them;
3.  the field is allowed for that skill;
4.  the generation pipeline explicitly requests them.

The existence of a restricted field does not imply that it should be
available to ordinary conversation.

------------------------------------------------------------------------

# 7. Private Visual Identity

Private visual identity is distinct from public visual identity.

Public:

``` text
visual_identity
```

can describe canonical appearance, clothing, public visual style, etc.

Restricted:

``` text
restricted.private_visual_identity
```

may contain private visual-authoring information.

It must have:

``` yaml
access_policy:
  classification: restricted
  public_content_access: false
  normal_conversation_access: false
  image_generation_access: restricted
  admin_access: true
```

The exact private content is product-authored and is not required for
the POC.

The POC only needs to prove that the retrieval boundary works.

------------------------------------------------------------------------

# 8. Adult Intimacy Profile

Adult intimacy belongs to the Persona Master because it can define how
an adult Persona behaves in authorized romantic/intimate interactions.

It must remain separate from ordinary:

-   personality
-   communication
-   relational
-   public identity

Suggested structure:

``` yaml
restricted:
  adult_intimacy:
    meta:
      version:
      status:

    romantic_style:
    flirting_style:
    affection_style:
    intimacy_pacing:
    initiation_style:
    responsiveness:
    comfort_and_hesitation:
    boundaries:
    consent_preferences:
    escalation_preferences:
    aftercare_style:

    access_policy:
      classification: restricted
      normal_conversation_access: false
      authorized_skill_access: true
      image_generation_access: restricted
      admin_access: true
```

The POC should test **field selection and access control**, not author
explicit intimate content.

------------------------------------------------------------------------

# 9. Persona Versioning

Persona version is a top-level version of the canonical Persona state.

A Persona version must identify:

``` yaml
persona_id:
version:
schema_version:
status:
created_at:
updated_at:
locked_at:
authored_by:
```

A version change should be traceable.

The generation pipeline must know exactly which Persona version produced
a response.

Recommended runtime metadata:

``` text
persona_id
persona_version
skill_id
skill_version
context_projection_version
```

This makes response debugging possible.

------------------------------------------------------------------------

# 10. Stable vs Changeable Persona Data

Every important field should have a stability class.

Recommended values:

``` text
permanent
semi_stable
changeable
narrative
session
```

Examples:

``` text
name                    → permanent
core personality        → permanent
communication style     → permanent
occupation              → semi_stable
relationship status     → changeable
current project         → narrative
current emotional state → session/narrative
current arc             → narrative
```

A changeable field must not silently rewrite historical memories.

------------------------------------------------------------------------

# 11. Provenance

Persona facts should support provenance:

``` yaml
provenance:
  source: authored | derived | inferred | unknown
  authored_by:
  source_ref:
  confidence:
```

The system must distinguish:

-   authored facts
-   derived calculations
-   inferred values
-   unknown values

Never silently turn an inference into a canonical fact.

------------------------------------------------------------------------

# 12. Skill Definitions

Skills must describe the behavior they provide and the Persona
information they need.

Skills must not contain the canonical Persona facts.

Suggested structure:

``` yaml
skill:
  meta:
    skill_id:
    version:
    status:

  purpose:
  when_to_use:
  when_not_to_use:

  behavior:
  response_strategy:
  conversation_flow:

  persona_context:
    required: []
    recommended: []
    conditional: []
    restricted: []

  memory:
    user_memory:
    persona_memory:

  conversation:
    history_window:
    continuity_requirements:

  output:
    response_style:
    length:
    initiative:

  transitions:
    allowed:
    prohibited:
```

Skills request **semantic Persona concepts**, not database paths.

Prefer:

``` yaml
persona_context:
  required:
    - communication_style
    - relationship_style
```

instead of:

``` yaml
persona_context:
  required:
    - persona.relational.relationship_style
```

The resolver maps semantic requirements to actual Persona fields.

------------------------------------------------------------------------

# 13. Persona Context Resolver

The resolver performs:

``` text
Skill requirements
        +
Current user message
        +
Conversation state
        +
Authorization
        ↓
Persona Field Registry
        ↓
Selected Persona fields
        ↓
Context projection
```

The resolver must support:

-   required fields
-   recommended fields
-   conditional fields
-   restricted fields
-   denied fields

It must never retrieve restricted fields solely because they exist.

------------------------------------------------------------------------

# 14. Behavioral Core

The POC must test a third architecture in addition to full and selective
Persona context.

### A --- Full Persona

``` text
FULL PERSONA
```

### B --- Selective Persona

``` text
SKILL-SPECIFIC PERSONA FIELDS
```

### C --- Behavioral Core + Selective Persona

The Behavioral Core is the minimum Persona identity needed to preserve
the feeling that the same person is speaking.

Likely core candidates:

``` text
personality
communication
behavioral_signature
core relational behavior
stable identity anchors
```

The exact core is a POC hypothesis and must be validated rather than
assumed.

------------------------------------------------------------------------

# 15. Context Assembly Rules

The final generation context may contain:

``` text
Skill instructions
+
Conversation Engine rules
+
Persona context projection
+
Last 10 messages
+
Relevant user memories
+
Relevant Persona memories
```

The system must keep these sources conceptually separate.

Do not flatten them into an indistinguishable blob before debugging.

------------------------------------------------------------------------

# 16. No Context Leakage

A successful implementation must prevent:

``` text
general_chat
    ↓
adult_intimacy
```

or:

``` text
friendship
    ↓
private_visual_identity
```

unless explicitly authorized and required.

Likewise, internal metadata must never be exposed as Persona knowledge:

``` text
persona_id
field_id
retrieval policy
access classification
version metadata
internal asset references
```

------------------------------------------------------------------------

# 17. POC Personas

### Primary

``` text
Simran
```

Reason:

-   existing Persona
-   existing production-style prompt
-   existing skills
-   existing behavior baseline
-   direct comparison against the current pipeline

### Secondary

``` text
Keerthi Reddy
```

Reason:

-   rich narrative structure
-   public/private separation
-   visual identity
-   restricted domains
-   relationships
-   family
-   career
-   opinions
-   aspirations

Keerthi is the schema-generalization test after Simran.

------------------------------------------------------------------------

# 18. Required POC Comparison

Run three variants:

``` text
A — Current Full Persona
B — Selective Persona
C — Behavioral Core + Selective Persona
```

Hold constant:

-   model
-   user message
-   skill
-   Conversation Engine
-   last 10 messages
-   user memories
-   Persona memories
-   generation parameters

Only Persona context strategy should change.

------------------------------------------------------------------------

# 19. Success Criteria

Measure:

### Quality

-   Persona fidelity
-   factual accuracy
-   naturalness
-   skill adherence
-   relationship continuity
-   emotional appropriateness
-   narrative continuity

### Retrieval

-   false inclusion
-   false exclusion
-   context leakage
-   restricted-field violations

### Efficiency

-   Persona input tokens
-   total input tokens
-   output tokens
-   total prompt tokens
-   latency
-   estimated cost

### User experience

-   personalization
-   relevance
-   continuity
-   naturalness
-   user-rated satisfaction

Initial POC targets:

``` text
Persona-token reduction:       >= 30%
Total prompt reduction:        >= 15–25%
Persona fidelity:              >= current baseline
Factual accuracy:              >= current baseline
Skill adherence:               >= current baseline
Relationship continuity:       >= current baseline
Restricted leakage:            0
Meaningful missing-context:    <= current baseline
Latency:                       no meaningful regression
```

These are POC thresholds, not permanent product SLAs.

------------------------------------------------------------------------

# 20. Do Not Implement the Final DB Schema Yet

First finalize:

``` text
Logical Persona Master
        ↓
Field Registry
        ↓
Skill Context Requirements
        ↓
Context Resolver
        ↓
POC results
```

Only after the POC should the physical database model be finalized.

The DB should encode proven requirements, not assumptions.

------------------------------------------------------------------------

# 21. Non-Negotiable Architectural Rules

1.  Persona Master is the source of truth.
2.  Persona Master is not the generation prompt.
3.  Skills request context; they do not own Persona facts.
4.  Context Resolver decides which fields are loaded.
5.  Restricted Persona data is first-class data with access metadata.
6.  Restricted data is never loaded by default.
7.  Behavioral identity must not disappear merely because selective
    retrieval is enabled.
8.  Recent conversation and memories remain separate context sources.
9.  Persona version and Skill version must be traceable at generation
    time.
10. No production migration before the POC demonstrates the approach.
