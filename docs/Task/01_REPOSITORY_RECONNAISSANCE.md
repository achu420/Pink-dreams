# Task 1 --- Repository Reconnaissance

## Goal

Map the actual runtime before changing it.

Inspect and document actual paths/classes/functions for: - chat entry
and response path - conversation retrieval - user/profile context -
recent-message assembly - Persona Core - Intent Discovery - skill
repository/loading - memory retrieval/extraction - Conversation Engine -
prompt assembly - provider client - model configuration - provider
routing - persistence - version activation - Test Chat - admin
routes/repositories - diagnostics - DB/migrations - relevant tests

Trace one real chat:

`HTTP → context → Persona → Intent → memory → skill → prompt → LLM → persistence → response → async memory extraction`

Do not assume the documented order is the actual order.

## Required deliverable

Create an implementation map with actual repository names:

``` text
Chat entry:
Context:
Persona:
Intent:
Skills:
Memory:
Engine:
Prompt assembly:
Provider:
Generation model:
Intent model:
Provider routing:
Persistence:
Async memory:
Versioning:
Test Chat:
Admin:
Diagnostics:
Tests:
```

Also list: - confirmed - not found - ambiguous - existing technical
debt - safe next step

## Restrictions

No architecture redesign. No model change. No prompt change. No
production behavior change unless a diagnostic-only change is essential.
