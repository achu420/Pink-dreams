# IMG-05 — Image Provider Abstraction

## Objective
Audit and harden the application-level abstraction between image generation and upstream providers.

## Requirements
There must be one clear application-level provider contract where practical.

Inspect whether the current implementation supports:
- generate/request
- asynchronous status polling where applicable
- cancellation where supported
- result download
- provider error normalization

Provider-specific code must remain isolated from business logic.

## Configuration
For every image model/provider setting identify:
- database-controlled
- environment-controlled
- code default
- provider default

Document precedence and runtime reload behavior.

## Multi-provider behavior
Verify model/provider selection and fallback behavior if it exists. Do not introduce provider routing merely because multiple providers are possible.

## Tests
Provider mapping, malformed provider response, timeout, upstream errors, unsupported model, and configuration resolution.

## Final report
Describe the real provider architecture, configuration precedence, fallback behavior, files, tests, live verification, and gaps.
