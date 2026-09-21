# Task 5 --- Latency and Reliability Hardening

## Goal

Use real diagnostics to harden the user-facing path. Measure first; do
not optimize by guesswork.

## Existing measured evidence

Intent with GPT-4o-mini + JSON mode: - Intent p50 ≈1.043s - Intent avg
≈1.406s - malformed 0/90 in live validation

Generation with DeepSeek + `provider.sort=latency`: - generation p50
≈2.280s - generation avg ≈3.548s - generation p95 ≈12.728s - total p50
≈4.659s - total avg ≈6.166s - \<=10s = 90% in the 30-turn sample

Treat these as measured samples, not guarantees.

## First verify

Do not duplicate existing: - Intent model override - JSON mode -
provider sorting - raw diagnostics - Test Chat overrides

## Productionization

If validated settings are still experimental, prepare the smallest
reversible path to production. Do not silently change production
defaults.

Record old value, new value, evidence, and rollback value.

## Provider routing

Verify latency sorting applies only to the intended workload. Do not
accidentally apply generation routing to Intent or side-channel calls.

## Intent reliability

Verify `response_format: json_object` is actually sent. Verify
request-level reinforcement is not stored in versioned Intent content.
Verify malformed output still fails safely if a provider ignores JSON
mode.

## Tail analysis

Use diagnostics to identify slow providers, provider variance, queueing,
DB latency, prompt assembly, and unnecessary serial LLM calls.

Measure p50/p75/p95/p99/max plus \<=5s, \<=8s, \<=10s, \>20s.

Inspect the slowest 10 turns by stage and provider.

## Reliability

Prove provider failure, diagnostics failure, memory extraction failure,
malformed Intent, generation timeout, and partial diagnostics all have
deterministic safe behavior.

Do not remove Persona, Engine, selected Skill, required context, or
create a new classifier merely to reduce latency.

## Report

Before/after table, exact change, evidence, rollback, residual tail, and
quality checks.
