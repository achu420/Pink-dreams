# IMG-12 — Image Observability

## Objective
Make image generation independently observable without mixing it into text-chat LLM metrics.

## Capture where available
- job ID
- request ID
- conversation ID
- persona ID
- workload
- provider
- model
- attempt
- queue latency
- generation latency
- download latency
- persistence latency
- total latency
- outcome
- HTTP/provider status
- error class/message
- asset size/dimensions

Respect existing privacy conventions.

## Reliability
Observability persistence must never break a user-facing image request.

## Security
Never capture API keys, Authorization headers, provider secrets, or unnecessary raw credentials.

## Admin/API
Reuse existing observability architecture where appropriate; do not create duplicate telemetry stores without justification.

## Tests
Success, failure, timeout, retry, side-channel/background behavior if present, persistence failure isolation, secret redaction.

## Final report
Document telemetry schema, storage, endpoints/admin exposure, tests, live evidence, overhead limitations, and gaps.
