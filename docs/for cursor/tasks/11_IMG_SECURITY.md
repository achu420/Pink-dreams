# IMG-11 — Image Security & Access Control

## Objective
Perform a dedicated security audit of the image pipeline and fix concrete vulnerabilities.

## Audit
Check:
- authentication
- authorization
- ownership
- cross-user asset access
- admin access
- prompt injection boundaries
- provider credential handling
- remote URL fetching / SSRF
- path traversal
- MIME validation
- file-size limits
- malicious payload handling
- log leakage
- secret leakage
- predictable asset IDs

## Requirements
A user must never retrieve another user's private image/job unless the existing product explicitly defines shared visibility.

Provider credentials must never appear in API responses, raw observability, exports, or client payloads.

## Testing
Attempt unauthorized access, cross-user access, malformed asset IDs, unsafe remote URLs, oversized/malformed uploads if applicable, and secret scanning.

## Final report
Security findings by severity, fixes, evidence, tests, live verification, and remaining risks.
