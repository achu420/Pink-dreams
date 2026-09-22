# 11 IMAGE COST / SLA — IMPLEMENTATION NOTES

**Date:** 2026-09-23  
**Verdict:** PASS WITH FINDINGS

## Implemented
- Expanded `GET /v1/admin/images/sla` with queue p50/p95, total p99, regenerations, candidate status counts, byProvider/byModel
- `costAvailability: UNAVAILABLE` (no invented pricing)
- Admin nav **Image SLA** page (separate from LLM Observability)

## Not implemented
- Actual provider cost capture / estimated pricing tables
- Historical charts beyond window summary

## Evidence
- `AdminImageGenerationRoutes.kt` SLA response
- `admin-ui.html` `#imagesla`
- `GeneratedCandidateRepository.countByStatusSince`
