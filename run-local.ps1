cd "E:\Projects\Pink dreams"

# Set environment variables in THIS process
$env:DATABASE_URL = "jdbc:postgresql://localhost:5432/pinkdreams"
$env:DATABASE_USER = "postgres"
$env:DATABASE_PASSWORD = "password"
$env:OPENROUTER_API_KEY = "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"
$env:ADMIN_USER_IDS = "00000000-0000-0000-0000-000000000001"

Write-Host "Environment variables set:"
Write-Host "DATABASE_URL: $env:DATABASE_URL"
Write-Host "DATABASE_USER: $env:DATABASE_USER"
Write-Host ""

# Run gradle
gradle run
