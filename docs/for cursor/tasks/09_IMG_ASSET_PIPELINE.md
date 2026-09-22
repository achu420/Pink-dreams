# IMG-09 — Image Asset Pipeline

## Objective
Make generated image handling durable and secure from provider response through client delivery.

## Audit
Trace:
`provider result → download → validation → storage → asset record → URL/access → client`.

## Requirements
Verify or implement:
- MIME validation
- image/file validation
- maximum file size
- safe filenames/keys
- durable storage
- asset ID
- ownership
- URL generation
- duplicate handling
- failed-download handling
- orphan handling

Do not trust arbitrary provider URLs indefinitely where durable storage is required by the existing architecture.

## Security
Audit remote fetch behavior for SSRF risks and validate provider URLs against the intended provider mechanism.

## Tests
Valid image, malformed body, wrong MIME, oversized body, download timeout, storage failure, unauthorized access, duplicate asset.

## Final report
Describe actual asset lifecycle, storage, access model, security checks, schema changes, tests, live verification, and gaps.
