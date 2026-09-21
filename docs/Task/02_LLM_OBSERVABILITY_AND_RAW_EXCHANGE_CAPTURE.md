# Task 2 --- LLM Observability and Raw Exchange Capture

## Goal

Make every existing LLM workload diagnosable and make provider exchanges
available for admin inspection/export.

Cover existing workloads such as Intent, Generation, Memory Extraction,
Continuity, and maintenance calls.

## Capture

For each exchange, where available: - exchange ID and
correlation/request ID - conversation/turn ID - workload/stage -
production vs Test Chat - timestamps and latency - model -
provider/upstream provider - provider routing configuration -
prompt/component versions - selected skill and intent result -
generation settings - JSON/structured-output mode - max output tokens -
prompt/completion/total tokens - reasoning tokens - finish reason -
HTTP/provider status - retries/timeouts - parsing result - error
class/message - raw request body - raw response body

## Security

Inspect existing security conventions before storing raw payloads. Never
store API keys, authorization headers, or unrelated secrets.
Conversation text is sensitive; follow existing retention/access
patterns.

Diagnostics must never fail the user response. If diagnostics
persistence fails, log it and continue.

## Admin/export readiness

Data must support filtering by conversation, workload, model, provider,
skill, latency, finish reason, failures, and versions. Preserve exchange
IDs for joins.

## Tests

Prove: 1. success captured 2. provider failure captured 3. finish reason
captured 4. reasoning tokens captured when supplied 5. malformed output
distinguishable from provider failure 6. budget exhaustion
distinguishable from normal completion 7. diagnostics do not block
response 8. diagnostics failure does not fail response 9. secrets are
excluded 10. Test Chat is distinguishable

Run a real exchange and provide its exchange ID in the report.

Do not claim 100% capture without evidence.
