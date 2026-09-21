# AMIA --- Overnight Claude Work Package

## Purpose

Execute the remaining AMIA engineering work as a sequence of
independently verified tasks. Complete one file, test it, report
evidence, then open the next file.

## Source hierarchy

1.  Actual repository/code = implementation truth.
2.  Canonical AI behavior v3 = behavioral truth.
3.  Existing runtime experiments/reports = measured performance
    evidence.
4.  Do not invent architecture.

## Current measured direction

-   Intent candidate: `openai/gpt-4o-mini`
-   Intent structured JSON mode: enabled in the validated experiment
-   Generation: `deepseek/deepseek-v4-flash-0731`
-   Generation provider routing experiment: `provider.sort=latency`
-   Latest 30-turn latency-sorting sample: total p50 4.659s, avg 6.166s,
    90% \<=10s, 0% \>20s.
-   These are samples, not guarantees. Verify before productionizing.

## Observability principle

Capture as much LLM exchange metadata as safely possible:
exchange/correlation IDs, workload, timestamps, model, provider, routing
settings, latency, prompt/version identifiers, selected skill, intent
result, token usage, reasoning tokens, finish reason, status, errors,
retries, and raw request/response where retention/security rules allow.
Never persist secrets.

## Execution order

1.  `01_REPOSITORY_RECONNAISSANCE.md`
2.  `02_LLM_OBSERVABILITY_AND_RAW_EXCHANGE_CAPTURE.md`
3.  `03_RUNTIME_PIPELINE_AND_CONTEXT_AUDIT.md`
4.  `04_ADMIN_UI_SPEC_AND_IMPLEMENTATION.md`
5.  `05_LATENCY_RELIABILITY_HARDENING.md`
6.  `06_PRODUCTION_VALIDATION_AND_RELEASE.md`
7.  `07_FINAL_REVIEW_AND_HANDOFF.md`

## Per-task protocol

-   Read the complete task.
-   Inspect before editing.
-   Reuse existing architecture.
-   Implement only the task scope.
-   Add tests.
-   Run targeted tests and the full suite when practical.
-   Verify runtime behavior, not just DB rows.
-   Record exact files, evidence, and unresolved risks.
-   Do not commit.

## No phantom completion

For major functionality prove:
`stored → loaded → selected → assembled → sent → executed → observable → tested`

If a required component does not exist, explicitly mark:
`NEW BACKEND WORK — reason — existing code checked — smallest implementation`.

## Core separation

-   Conversation Engine: universal rules
-   Persona Core: persona identity
-   User Profile: stable user attributes
-   Memory: durable learned information
-   Recent Conversation: bounded immediate context
-   Intent: current primary goal/skill
-   Skill: current behavior mode
-   Generation: actual response
-   Memory extraction: asynchronous and non-blocking
