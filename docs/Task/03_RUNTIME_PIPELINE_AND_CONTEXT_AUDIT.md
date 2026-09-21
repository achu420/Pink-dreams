# Task 3 --- Runtime Pipeline and Context Audit

## Goal

Verify that each architecture layer does only its job and that the
runtime actually composes the intended prompt.

## Generation prompt should contain

-   Conversation Engine
-   Persona Core
-   user/profile context
-   selected Skill
-   relevant durable memory
-   bounded recent conversation
-   current user message

## It should not contain

-   full skill catalogue
-   irrelevant memories
-   full history
-   duplicate persona/engine rules
-   admin metadata
-   diagnostics
-   implementation instructions

## Intent prompt

Verify it receives current message, bounded recent conversation, compact
routing registry, and relevant context where supported. It must not
receive full skill bodies or full Persona/Engine content.

## Context

The measured investigation found `CURRENT_RECENT_4` preferable to
smaller windows for ambiguous routing. Do not reduce it without new
evidence. Verify what the code actually sends.

## Skill proof

For representative turns prove:

`user message → Intent result → selected skill → skill loaded → skill injected → generated behavior`

Cover all supported skill families, including companionship, friendship,
emotional support, flirting, flirting practice, dating, dating practice,
relationship discussion/guidance, social/conversation practice, advice,
problem solving, confidence, encouragement, breakup support, romantic
conversation/building/intimacy, foreplay, sexual stimulation, teasing,
learning, entertainment.

## Memory

Verify retrieval is selective, durable memory is separate from recent
context, irrelevant memory is excluded, extraction is asynchronous, and
extraction failure cannot fail the response.

## Deliverable

Provide an actual pipeline table:

  Stage   Implementation   Input   Output   User-facing   Verified
  ------- ---------------- ------- -------- ------------- ----------

List every mismatch and the smallest safe correction.
