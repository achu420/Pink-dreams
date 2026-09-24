# Persona Context POC — Real Benchmark Execution Setup

## Objective
Execute 70 realistic scenarios (15 per skill × 3 variants = 210 executions minimum) with real LLM API calls to measure:
- Token usage (persona, input, output, total)
- Cost
- Latency (p50, p95, p99)
- Generated response quality (7-dimension evaluation)
- Restricted field leakage (0 target)

## Prerequisites Checklist

### Database
- [ ] PostgreSQL 14+ running locally
- [ ] Database created: `pink_dreams`
- [ ] Schema migrated (tables created automatically on connection)

### LLM API
- [ ] OpenRouter API key obtained
- [ ] Account has sufficient balance ($50+ recommended)
- [ ] Model selected: `deepseek/deepseek-v4-flash-0731` (default) or override

### Environment Variables

Set these BEFORE running benchmark:

```bash
# PostgreSQL
export DATABASE_URL="jdbc:postgresql://localhost:5432/pink_dreams"
export DATABASE_USER="postgres"
export DATABASE_PASSWORD="your-password"

# OpenRouter / LLM
export OPENROUTER_API_KEY="your-openrouter-key"
export LLM_MODEL="deepseek/deepseek-v4-flash-0731"  # or your model choice
export LLM_PROVIDER="openrouter"

# Optional: Benchmark config
export BENCHMARK_LIMIT_SCENARIOS="10"  # Start with 10, remove for full 70
export BENCHMARK_PAUSE_MS="2000"  # Pause between API calls to avoid rate limit
```

## Execution Plan

### Phase 1: Quick Validation (10 scenarios, 1 variant)
- Run subset of 10 scenarios against Variant A only
- Validate infrastructure (DB, API connection, token measurement)
- Estimate full run time and costs
- **Time: ~5 minutes**
- **Cost: ~$0.50**

### Phase 2: Single Variant (70 scenarios, Variant A only)
- Full 70-scenario suite against production (current) behavior
- Baseline token/cost/latency measurements
- **Time: ~30-40 minutes**
- **Cost: ~$3-5**

### Phase 3: Variant B (70 scenarios)
- Same scenarios, selective persona context
- Compare token reduction against Variant A
- **Time: ~30-40 minutes**
- **Cost: ~$2-3** (fewer tokens = lower cost)

### Phase 4: Variant C (70 scenarios)
- Same scenarios, behavioral core + selective
- Compare to both A and B
- **Time: ~30-40 minutes**
- **Cost: ~$2-3**

### Phase 5: Manual Quality Evaluation
- Blinded evaluation of all 210 responses (randomized, labeled 1/2/3)
- 7-dimension quality scoring (0-5 each)
- Restricted field leakage check
- **Time: ~4-6 hours** (manual review)
- **Cost: $0** (manual)

## Total Time & Cost Estimate

| Phase | Time | Cost | Cumulative |
|---|---|---|---|
| Validation | 5 min | $0.50 | $0.50 |
| Variant A | 35 min | $4.00 | $4.50 |
| Variant B | 35 min | $2.50 | $7.00 |
| Variant C | 35 min | $2.50 | $9.50 |
| Evaluation | 5 hours | $0 | $9.50 |
| **Total** | **~6 hours** | **~$9.50** | — |

## How to Start

### 1. Start PostgreSQL

```bash
# On Mac/Linux
brew services start postgresql

# On Windows (if using WSL)
sudo service postgresql start

# Or use Docker
docker run --name pink-dreams-db \
  -e POSTGRES_DB=pink_dreams \
  -e POSTGRES_PASSWORD=password \
  -p 5432:5432 \
  -d postgres:14
```

### 2. Create Database

```bash
createdb pink_dreams -U postgres
# Or in PostgreSQL client:
CREATE DATABASE pink_dreams;
```

### 3. Set Environment Variables

```bash
export DATABASE_URL="jdbc:postgresql://localhost:5432/pink_dreams"
export DATABASE_USER="postgres"
export DATABASE_PASSWORD="password"
export OPENROUTER_API_KEY="your-key-here"
```

### 4. Run Quick Validation

```bash
cd "E:\Projects\Pink dreams"
export BENCHMARK_LIMIT_SCENARIOS="10"
./gradlew test --tests "PersonaContextPocRealBenchmark" -i
```

Expected output:
- 10 scenarios loaded ✓
- Database connected ✓
- API connected ✓
- 10 executions (Variant A only) running...
- Results CSV saved to `benchmark-results-validation.csv`

### 5. Run Full Benchmark (if validation passes)

```bash
unset BENCHMARK_LIMIT_SCENARIOS  # Remove limit to run all 70
./gradlew test --tests "PersonaContextPocRealBenchmark" -i
```

This will:
1. Run all 70 scenarios × 3 variants = 210 executions
2. Collect real token usage, latency, costs
3. Save results to:
   - `persona-poc-benchmark-A.csv` (Variant A results)
   - `persona-poc-benchmark-B.csv` (Variant B results)
   - `persona-poc-benchmark-C.csv` (Variant C results)
   - `persona-poc-benchmark-summary.csv` (Aggregate metrics)
   - `persona-poc-benchmark-detailed.csv` (With generated responses)

### 6. Manual Quality Evaluation

After all executions complete:

```bash
./gradlew run admin-ui
# Open http://localhost:8080/admin
# Navigate to "Persona Context POC" → "Evaluate Responses"
# Responses will be blinded (Response 1/2/3, not A/B/C)
# Score each on 7 dimensions (0-5 scale)
# Check for restricted leakage
```

### 7. Generate Final Report

```bash
./gradlew test --tests "PersonaContextPocReportGenerator" -i
```

Produces `BENCHMARK_FINAL_REPORT.md` with:
- 15 required sections
- Per-skill breakdowns
- Statistical analysis
- Limitations stated
- Evidence-based conclusions (no recommendations)

## Success Criteria

### Efficiency
- [ ] Persona token reduction (B vs A): >30%
- [ ] Total input token reduction (B vs A): >15%
- [ ] Cost reduction proportional to token reduction

### Quality
- [ ] Variant B quality ≥ Variant A (no material regression)
- [ ] Variant C quality ≥ Variant A (no material regression)
- [ ] Quality metrics captured for all 7 dimensions

### Safety
- [ ] Restricted leakage cases: **0**
- [ ] Zero unauthorized intimate field exposures

### Completeness
- [ ] 70 scenarios × 3 variants = 210 executions
- [ ] Real token measurements from API
- [ ] Per-skill results (not just aggregate)
- [ ] Final report with all 15 sections

## Troubleshooting

### "No database configured"
```
Error: DATABASE_URL environment variable is required
```
**Fix:** Set `DATABASE_URL` before running benchmark

### "LLM API key missing"
```
Error: OPENROUTER_API_KEY not configured
```
**Fix:** Set `OPENROUTER_API_KEY` to your actual key

### "Connection refused: localhost:5432"
```
Error: Failed to connect to database
```
**Fix:** Start PostgreSQL service (see "Start PostgreSQL" above)

### "Rate limited by OpenRouter"
```
Error: 429 Too Many Requests
```
**Fix:** Increase `BENCHMARK_PAUSE_MS` between calls, or wait 5 minutes

### "Persona master document not found"
```
Error: No PersonaMasterDocument for persona_core_version_id
```
**Fix:** Run seed data: `./gradlew test --tests "PersonaContextPocSeed"`

## Monitoring

During benchmark execution, watch for:

```
[14:23:45] Starting benchmark run: personas-poc-01
[14:23:47] Scenario s001 (general_chat) - Variant A...  latency: 1234ms, tokens: 2301 input / 148 output
[14:23:49] Scenario s001 (general_chat) - Variant B...  latency: 987ms, tokens: 952 input / 145 output
[14:23:50] Scenario s001 (general_chat) - Variant C...  latency: 1045ms, tokens: 1520 input / 147 output
[14:23:51] ✓ Scenario s001 complete (3/210)
...
[14:58:30] ✓ All 210 executions complete
[14:58:31] Saving results to benchmark files...
[14:58:35] Results saved:
  - persona-poc-benchmark-A.csv (70 rows)
  - persona-poc-benchmark-B.csv (70 rows)
  - persona-poc-benchmark-C.csv (70 rows)
  - persona-poc-benchmark-summary.csv (Aggregate stats)
  - persona-poc-benchmark-detailed.csv (With responses)
```

## Next Steps After Benchmark

1. **Export results** to spreadsheet for analysis
2. **Manually evaluate** quality (blinded, 7 dimensions)
3. **Analyze per-skill** patterns and regressions
4. **Generate final report** with measured data
5. **Share with team** for production go/no-go decision

---

**Document:** BENCHMARK_SETUP.md  
**Updated:** 2026-09-25  
**Status:** Ready to execute
