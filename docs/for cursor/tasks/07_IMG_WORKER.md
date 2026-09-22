# IMG-07 — Image Worker Reliability

## Objective
Harden the actual image worker/queue execution path.

## Audit
Verify:
- job pickup
- transaction boundaries
- locking/claiming
- concurrency
- provider invocation
- status updates
- worker restart behavior
- stale-job recovery
- graceful shutdown

## Reliability rules
A worker failure must:
1. leave enough state to diagnose the failure;
2. allow safe retry when retryable;
3. never create an infinite retry loop;
4. avoid duplicate successful assets.

If stale-job recovery is missing, implement it using existing scheduling/worker conventions.

## Tests
Concurrent claim, worker exception, provider timeout, restart during RUNNING, stale job, successful completion, final failure.

## Final report
Report actual worker design, concurrency controls, recovery behavior, files, tests, live verification, and unresolved operational gaps.
