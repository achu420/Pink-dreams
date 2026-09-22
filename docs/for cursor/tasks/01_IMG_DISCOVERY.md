# IMG-01 — Image Pipeline Discovery & Architecture Audit

## Objective
Perform a repository-wide audit of the existing image pipeline before implementing anything.

## Hard rule
**Do not implement features in this task.** Inspect, trace, test, and report only. Do not assume the target architecture exists.

## Scope
Inspect:
- image HTTP routes and DTOs
- image services/use cases
- image jobs/workers/queues
- provider clients and model selection
- prompt builders
- persona/visual identity integration
- persistence tables/repositories
- asset storage and URL delivery
- configuration
- moderation/safety
- observability
- admin integration
- tests
- scheduled cleanup

Search the full repository for image-related code, including TODOs and abandoned implementations.

## Required trace
Document the real flow from request to delivered image:
`HTTP → auth → validation → context/persona → prompt → provider → job/worker → generation → asset handling → persistence → delivery → observability`.

Mark every stage as `IMPLEMENTED`, `PARTIAL`, `MISSING`, or `BROKEN`.

## Required evidence
For every important claim give exact file paths, classes/functions, endpoint names, tables, and relevant tests.

## Do not change
Persona Core, Conversation Engine, Intent Engine, Memory Engine, Skills, text generation, unrelated Admin UI, or unrelated database structures.

## Tests
Run only safe existing image-related tests needed to establish the baseline. Do not create broad new tests unless required to prove an existing defect.

## Final report
Return:
1. Executive result
2. Current architecture
3. Current end-to-end flow
4. Existing files/components
5. Existing APIs
6. Existing DB/storage
7. Existing providers/models
8. Existing job lifecycle
9. Existing observability
10. Existing tests
11. Gaps by severity
12. Risks
13. Recommended next task
14. Exact files changed (must be none unless a diagnostic artifact is unavoidable)
15. Git status

Stop after the audit. Do not continue into IMG-02.
