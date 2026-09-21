# Task 7 --- Final Review and Morning Handoff

## Goal

Create a concise evidence package for human review before
commit/release.

## Required report

### Executive summary

What changed, what was verified, current configuration, latency,
reliability, open risks.

### Actual architecture

Show the real pipeline with repository components.

### Exchange coverage

Report: - total exchanges - raw requests stored - raw responses stored -
token metadata - provider metadata - errors - unavoidable gaps

### Configuration

  Workload       Model   Reasoning   JSON   Budget   Provider routing
  -------------- ------- ----------- ------ -------- ------------------
  Intent         ...     ...         ...    ...      ...
  Generation     ...     ...         ...    ...      ...
  Memory/other   ...     ...         ...    ...      ...

### Prompt/version snapshot

List actual active versions.

### Latency

Report context, intent, memory retrieval, prompt assembly, generation,
persistence, total with p50/p95/max/avg and threshold percentages.

### Reliability

Provider failures, timeouts, malformed output, budget exhaustion,
retries, persistence failures, diagnostics failures, memory extraction
failures.

### Quality

Measured routing and behavioral checks. Do not invent a score.

### Changed files

Exact paths.

### DB changes

Tables/columns/migrations.

### Tests

Full suite and targeted tests.

### Risks

Separate confirmed issue, suspected issue, and unverified hypothesis.

### Human review checklist

-   inspect production config
-   inspect prompt/version changes
-   inspect raw exchange retention/security
-   inspect Admin UI
-   inspect latency distribution
-   inspect known quality tradeoffs
-   approve/reject release
-   commit

## Final rule

Do not commit automatically. Leave the tree reviewable and provide the
exact evidence needed for the human decision.
