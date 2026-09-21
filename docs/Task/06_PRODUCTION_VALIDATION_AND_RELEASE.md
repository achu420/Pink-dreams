# Task 6 --- Production Validation and Release Gate

## Goal

Run the final controlled validation. This is a release gate, not another
blind optimization experiment.

## Validate

1.  Full automated suite: total/passed/failed/skipped and known flakes.
2.  Actual active configuration:
    -   Intent model
    -   JSON mode
    -   Intent budget/context window
    -   Generation model/reasoning/budget
    -   generation provider routing
    -   Engine/Persona/Intent/Memory versions
3.  Real provider requests: model, routing, JSON mode, generation
    config.
4.  Prompt composition: expected components present; full inactive
    catalogue/admin metadata absent.
5.  Intent: representative cases for every skill and ambiguous pairs.
6.  One genuinely accumulating long conversation.
7.  Regression checks: persona identity, tone, response length,
    capability discovery, skills, memory continuity, version activation,
    intimate/sexual skill separation, practice modes.

## Release state

Use: - `PASS` - `PASS WITH KNOWN RISK` - `BLOCKED`

Do not turn this into a ranking or quality score.

## Rollback

Document exact previous values for model, JSON mode, provider sorting,
prompt versions, and DB configuration.

## Release notes

Include
implementation/config/database/prompt/observability/test/latency/known-risk/rollback
details.

Never claim a guaranteed response time. Use measured sample language.
