# IMG-13 — Image SLA & Performance Dashboard

## Objective
Measure image performance separately from text-chat SLA.

## Metrics
At minimum where technically measurable:
- queue latency
- provider generation latency
- download latency
- persistence latency
- total image-job latency
- success rate
- failure rate
- retry rate
- timeout rate

## Dimensions
Break down where sample sizes support it:
- provider
- model
- workload
- dimensions/aspect ratio
- persona
- attempt

Use consistent percentile semantics with the existing performance system.

## Important
Do not call a small dev/test sample "production SLA". Clearly label sample population and time range.

## Dashboard
Reuse existing Admin design conventions. Avoid fake metrics or placeholder values.

## Tests
Aggregation correctness, percentile correctness, filtering, zero-data behavior, error handling.

## Final report
Include actual measurements, formulas/semantics, dashboard/API changes, test results, live verification, and statistical limitations.
