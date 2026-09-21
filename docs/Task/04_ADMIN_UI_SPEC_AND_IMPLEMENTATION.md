# Task 4 --- Admin UI: Runtime + LLM Observability

## Goal

Build the admin interface around the actual backend, without inventing
APIs or a parallel authorization system.

## Required areas

### Runtime overview

Show active versions/config for: - Conversation Engine - Intent Engine -
Memory Engine - Persona Core - skills - Intent model/budget/JSON mode -
Generation model/reasoning/budget/provider routing - production vs Test
Chat - draft/published/active state

### Conversation/Turn inspector

For a selected conversation show: - turns - total latency - stage
timings - selected skill - intent result - model/provider per call -
component versions - memory retrieval summary - exchange IDs

### LLM exchange inspector

Show: - exchange ID - time - workload - model - provider - routing -
latency - status - finish reason - prompt/completion/reasoning/total
tokens - request/response payloads - parsed result - errors - retry
information

Never display credentials/secrets.

### Latency dashboard

Filters: time, workload, model, provider, conversation, skill,
success/failure, finish reason.

Metrics: p50, p75, p95, p99, max, average, count.

Stages: context, intent, memory retrieval, prompt assembly, generation,
persistence, total.

### Routing quality

Show selection counts, None/null, malformed, budget exhaustion, labeled
accuracy where available, model comparison. Do not invent accuracy.

### Export

Provide machine-readable export using existing admin conventions.
Preserve exchange IDs.

## UX

Fast filtering, pagination, search by conversation/exchange ID, lazy
raw-payload loading, clear errors, explicit unavailable fields.

## Security

Use existing admin auth. Raw conversation/provider data is sensitive.

## Tests

Backend/frontend tests for filters, pagination, detail loading,
redaction, export, production/test separation, and missing metadata.

## Definition of done

An admin can answer: "Exactly what happened on this turn, which
model/provider ran, what was sent/returned, why was this skill selected,
and where did the time go?"
