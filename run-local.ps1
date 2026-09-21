cd "E:\Projects\Pink dreams"

# SECURITY: this file is tracked in git — never put real secrets here again.
# A previous version of this file had a live OpenRouter API key hardcoded in
# plaintext, which OpenRouter flagged as leaked via GitHub scanning; that key
# has since been revoked. Real values now live in the gitignored
# run-local.secrets.ps1 (see .gitignore), loaded below if present.

$secretsFile = Join-Path $PSScriptRoot "run-local.secrets.ps1"
if (Test-Path $secretsFile) {
    . $secretsFile
} else {
    Write-Host "run-local.secrets.ps1 not found — copy it from a teammate or set" -ForegroundColor Yellow
    Write-Host "DATABASE_URL / DATABASE_USER / DATABASE_PASSWORD / OPENROUTER_API_KEY / ADMIN_USER_IDS yourself." -ForegroundColor Yellow
}

Write-Host "Environment variables set:"
Write-Host "DATABASE_URL: $env:DATABASE_URL"
Write-Host "DATABASE_USER: $env:DATABASE_USER"
Write-Host ""

# Run gradle
gradle run
