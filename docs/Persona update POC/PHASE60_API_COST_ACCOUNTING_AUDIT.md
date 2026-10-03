# PHASE 60 — API Cost Accounting Audit (Zero API Calls)

This phase made **zero real API calls**, sourced **no secrets**, and changed **no code**. All findings below come from static inspection of already-committed test source files and the partial log file already on disk from the Phase 59 follow-up run (`test-phase59-with-creds.log`), which was not re-executed, only read.

Labeling convention: **PROVEN** (directly observed in code/logs, this phase), **DERIVED** (follows logically from PROVEN facts), **HYPOTHESIS** (plausible, not confirmed), **UNKNOWN** (not determinable without execution, correctly left undetermined).

## 1. Executive Summary

The "$29" figure reported earlier was **not verified provider billing**. It is a **locally computed estimate**, produced by a hardcoded cost formula inside old POC test code (`PersonaContextPocLiveRealBenchmark.kt`), and there is direct arithmetic evidence that this formula's per-token rate is anomalously high relative to any plausible real DeepSeek pricing — i.e. the estimate itself is likely inflated, on top of being unverified. Separately, my earlier description of *what* produced that figure was **factually wrong**: I attributed it to one of the 32 already-known-failing `PersonaContextRealBenchmarkTest` methods; it actually came from a completely different test class, `PersonaContextPocLiveRealBenchmark`, which was not among the originally reported 38 failures at all. That correction is made explicitly in §3 and §5.

The "51/85" figure refers to **scenario objects** (data records, 85 total, each intended to run 3 model variants = up to 255 real HTTP calls), not to 51 API calls and not to 51 test methods. Static count from the log: **152 real HTTP calls actually completed, 0 logged errors**, before the process was killed partway through scenario 51.

Extrapolating "$29 for 152 calls → $X for the rest" is **not justified without further caveats** — but for a different reason than usual scenario-heterogeneity concerns: the per-call costs in the log are numerically consistent with each other (same model, similar token counts, same formula), so a linear projection *of the same flawed local estimate* is arithmetically valid. What is **not** justified is treating that projection as a real-dollar forecast, because the underlying per-token rate itself is unverified against real provider pricing and shows signs of being roughly 1000× too high for typical DeepSeek economics.

## 2. Evidence Used

- `src/test/kotlin/com/pinkdreams/chat/PersonaContextPocLiveRealBenchmark.kt` (full read)
- `src/test/kotlin/com/pinkdreams/chat/PersonaContextRealBenchmarkTest.kt` (structural grep/read, 3,400+ lines)
- `src/test/kotlin/com/pinkdreams/chat/PersonaContextPocRealBenchmark.kt` (grep)
- `src/test/kotlin/com/pinkdreams/api/admin/AdminAllowlistRoutesTest.kt` (grep)
- `src/main/kotlin/com/pinkdreams/auth/AdminAuthorizationProvider.kt` (grep)
- `src/test/kotlin/com/pinkdreams/ApplicationWiringRegressionTest.kt` (grep)
- `test-phase59-with-creds.log` (33,449 lines on disk from the earlier, already-terminated run — read only, not regenerated)
- The Phase 59 report's own recorded baseline test-result breakdown (38 failures across 5 XML files: `PersonaContextPocRealBenchmark`=1, `PersonaContextRealBenchmarkTest`=32, `Phase52ProductionBehavioralValidationTest`=2, `Phase54EngineAblationTest`=2, `Phase55SmokeTest`=1) — this list is taken as given from the prior turn's already-reported findings, since the underlying `build/test-results/test/*.xml` files were overwritten by the credentialed rerun and are no longer available to re-inspect.

No new test execution, no `gradle` invocation, no HTTP calls were made in this phase.

## 3. $29 Claim Verification

**Source located**: `calculateCost()` in `PersonaContextPocLiveRealBenchmark.kt` (line 283):

```kotlin
private fun calculateCost(model: String, inputTokens: Int, outputTokens: Int): Double {
    // DeepSeek pricing
    return (inputTokens * 0.00014) + (outputTokens * 0.00042)
}
```

This is called once per real HTTP response (line 135), using token counts parsed directly from the OpenRouter response's own `usage.prompt_tokens`/`usage.completion_tokens` fields (real, provider-reported token counts), multiplied by two **hardcoded local constants** with no provider billing API call anywhere in this file or its call graph.

**Verification against the log**: log line `[47/85] ROMANTIC_INTIMACY … Variant A … 218→232 tokens | $0.1280`. Recomputing: `218 × 0.00014 + 232 × 0.00042 = 0.03052 + 0.09744 = 0.12796 ≈ 0.1280`. **Confirmed** — the printed dollar figures are exactly this formula, not an external source.

**Classification per the instruction's schema: B — locally estimated cost.** Not A (no provider billing API was ever called), not C in the narrow sense (it uses real token counts, not a token-count estimate, but the *price* itself is a hardcoded, uncalibrated constant, which is the same category of risk as C).

**Correction to the prior turn's framing**: I previously described this cost as coming from "one of the 32 failing `PersonaContextRealBenchmarkTest` methods" (e.g. `phase27Stage2Execution`). That was incorrect. Grepping `PersonaContextRealBenchmarkTest.kt` for the log's exact print pattern (`[n/85] SKILL_KEY — category`, `Variant A/B/C…$`) finds **no match** in that file. It matches `PersonaContextPocLiveRealBenchmark.kt` line 95 exactly. That class:
- is a **separate file and separate test class** from `PersonaContextRealBenchmarkTest`
- was **not** one of the 5 XML files with failures in the original (no-credentials) baseline run reported in Phase 59 — meaning in that baseline run its single test either passed or was not discovered as failing, not that it was blocked on the same `OPENROUTER_API_KEY must be set` guard as the 32 `PersonaContextRealBenchmarkTest` methods
- has **no fail-fast guard** on a missing API key (confirmed by reading lines 55–73): if the key is missing, it prints a warning and proceeds to attempt real HTTP calls anyway, catching and logging each failure as a per-variant `"FAILED"` row rather than throwing

This means the true earlier baseline-run outcome for `PersonaContextPocLiveRealBenchmark` specifically is **UNKNOWN from currently available evidence** — its XML result was overwritten by the credentialed rerun before this audit began, and I do not have its baseline pass/fail status on record. What is certain is that it is not one of the 32 methods inside `PersonaContextRealBenchmarkTest` that were attributed to it previously.

**Verdict for the $29 figure specifically**: this is a real, internally-consistent local computation over real (provider-reported) token counts, using an unverified, hardcoded, per-token price. Whether $29 approximates real spend depends entirely on whether `0.00014`/`0.00042` per token are correct DeepSeek Flash rates — which this audit cannot confirm without either provider billing access or a trusted external pricing reference, neither of which this phase is permitted to fetch via a network call at all cost, and I did not attempt one.

**One flagged arithmetic anomaly (HYPOTHESIS, not confirmed)**: `0.00014` per input token equals `$140` per million input tokens, and `0.00042` per output token equals `$420` per million output tokens. Typical DeepSeek-class model pricing on OpenRouter for flash/mini-tier models is commonly quoted in the sub-$1-per-million-token range, roughly two to three orders of magnitude lower than these constants. This strongly suggests (but does not prove, since I have not independently verified current OpenRouter DeepSeek pricing in this phase) that the formula's constants were written for a "$ per 1,000 tokens" or similar unit and then applied as "$ per token" — a plausible ×1000 unit bug. If true, the real-dollar-equivalent local estimate for the 152 completed calls would be closer to **~$0.029**, not ~$29. This is a hypothesis with supporting arithmetic, not a verified correction — flagged explicitly rather than asserted.

## 4. 51/85 Verification

Grepped `test-phase59-with-creds.log` for the exact scenario-index marker this test prints (`[${index+1}/${scenarios.size}]`, from `PersonaContextPocLiveRealBenchmark.kt` line 95):

- 51 distinct `[n/85]` markers found, `n` ranging 1–51 — **PROVEN**: 85 is the total count of `TestScenario` records returned by `PersonaContextPocBenchmarkRunner.createBenchmarkScenarios()` (a fixed, in-code list of persona-chat test prompts spanning categories/skills), and 51 of them had begun processing before the process was terminated.
- Each scenario, per the loop structure (`for (variant in listOf('A','B','C'))`), issues **up to 3 real HTTP calls** — one per variant — not one call per scenario.
- Counted successful per-variant result lines matching the `✓ …ms | …→… tokens | $…` pattern: **152**.
- Counted `✗ ERROR` lines: **0**.

**So "51/85" means 51 of 85 scenario *records* had started (not necessarily fully finished — the 51st scenario's log entries suggest it was mid-flight when killed), and the real unit of API cost is the **152 completed HTTP calls**, not 51. 51 scenarios × 3 variants = 153 possible calls; 152 completed is consistent with the 51st scenario being interrupted after its first or second variant, matching where the process was killed.

## 5. Test vs. API Call Accounting

| Test method / class | Test cases / scenarios | API calls per scenario | Total possible API calls | Actually executed (this session) | Cost evidence |
|---|---:|---:|---:|---:|---:|
| `PersonaContextPocLiveRealBenchmark.execute live openrouter benchmark - real api calls` | 85 scenarios | 3 (variants A/B/C) | 255 | 152 completed, 0 errors, process killed mid-scenario-51 | Local formula, real token counts (§3) |
| `PersonaContextRealBenchmarkTest` (32 `@Test` methods total) | varies per method (some single-scenario, some multi-scenario loops) | varies (0 for the ~5 confirmed zero-cost methods; unknown-but-likely-nonzero for the rest) | UNKNOWN — not enumerated per-method in this audit | 19 methods reached a `FAILED`/`AssertionFailedError` state before the process was killed (partial run, order not controlled); the remaining 13 never ran | Not evaluated — see §6 for why per-method costing wasn't pursued |
| `PersonaContextPocRealBenchmark` (1 `@Test`) | UNKNOWN, requires `DATABASE_URL` | UNKNOWN | UNKNOWN | Did not run to completion (unclear if it started before kill) | Not observed |
| `Phase52ProductionBehavioralValidationTest` (2 `@Test`) | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN — no log evidence of completion or failure captured before kill | Not observed |
| `Phase54EngineAblationTest` (3 `@Test`, 2 failed in baseline) | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | Not observed |
| `Phase55SmokeTest` (1 `@Test`) | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | Not observed |

**Confirmed conclusion: 1 test method ≠ 1 API call, in either direction.** A single `@Test` method in this codebase can issue anywhere from 0 (deterministic/`interceptGeneration = true` checks) to 255 (the live benchmark) real HTTP calls. No uniform ratio exists across this test suite; each method's call count must be read from its own body.

## 6. Remaining Benchmark Inventory

`PersonaContextRealBenchmarkTest.kt` contains exactly **32 `@Test`-annotated methods**, matching the 32 baseline failures exactly. Reading the shared `World` helper class (constructed by every method), line ~173 throws `IllegalArgumentException("OPENROUTER_API_KEY must be set for the real resolver benchmark…")` if the key is absent — **this single shared guard is why all 32 methods failed identically and immediately, at zero cost, in the original no-credentials baseline run.** It does not tell us anything about what each method does once the guard passes.

Per-method static classification (based on whether the method's body sets `interceptGeneration = true`, which routes generation through a local, no-network deterministic path, vs. leaves the world's real `LlmClient` wired to a live network call):

- **Confirmed zero-cost (interceptGeneration = true found in the method body): 5** — `phase10cProviderVerification`, `phase20LabelIsolationStage1Audit`, `phase22CandidateScenarioZeroCostCheck`, `phase24Stage1Verification`, `phase27Stage1Audit`. These match a clear naming convention (`Stage1`, `Audit`, `ZeroCostCheck`) used consistently across the file for deterministic/no-network checks.
- **Confirmed real-call (explicit `interceptGeneration = false` found): 2** — `phase10dCorpusMatched2x2`, `phase10eMappingClosurePilot`.
- **Not statically confirmable either way without execution: 25** — including `singleScenarioTrace`, `fullBenchmark`, `experiment2ExpandedBenchmark`, `experiment3FullValidationBenchmark`, `phase6FullRun`, `phase7StructuredFullDocumentProbe`, `phase14TargetedFixValidation`, `phase16LabeledScalarDiagnostic`, `phase17ScalarLabelReplication`, `phase19TimelineLabelReproducibility`, `phase20Stage2Execution`, `phase21ConfirmatoryExecution`, `phase22Stage2Execution`, `phase24Stage2Execution`, `phase26Stage2Execution`, `phase27Stage2Execution`, `phase30ConfirmatoryExecution`, and others — these do not set the flag inline within the method itself (it may be set/reset by a called helper, or left at the `World` default, which was not traced further in this zero-execution audit). Their naming (`FullRun`, `Stage2Execution`, `ConfirmatoryExecution`, `TargetedFixValidation`) strongly suggests real-call intent, consistent with the "Stage 1 = zero-cost, Stage 2 = spend calls" pairing pattern visible throughout the file's own comments (e.g. line 2125 `"STAGE 1 — ZERO-COST VERIFICATION"` immediately preceding a stage that, per the file's own doc comments, spends calls) — but this is **HYPOTHESIS**, not confirmed, per the letter of this audit's zero-execution constraint.

**Overlap**: several of these methods explicitly reuse or build on earlier phases' frozen payloads (e.g. the doc comment on `phase21ConfirmatoryExecution` at line ~2352 references "the frozen Phase 14 B/X payloads"), suggesting some later-numbered methods may re-verify structure without necessarily re-spending on the full scenario set every time — but the actual scenario count per method was not enumerated in this audit (would require reading all 32 method bodies fully, not just their `interceptGeneration` usage — not done here to stay within a reasonable audit scope; flagged as a gap).

**None of the other 4 files' internal method/scenario structure was inventoried** (`PersonaContextPocRealBenchmark`, `Phase52ProductionBehavioralValidationTest`, `Phase54EngineAblationTest`, `Phase55SmokeTest`) — out of scope for this pass given the size of the already-completed `PersonaContextRealBenchmarkTest`/`PersonaContextPocLiveRealBenchmark` audit; marked **UNKNOWN**, not estimated.

## 7. Environment Variable Analysis

Five variables are set by `run-local.secrets.ps1`: `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `OPENROUTER_API_KEY`, `ADMIN_USER_IDS`.

| Variable | Confirmed required by | Confirmed to break an unrelated test when set |
|---|---|---|
| `OPENROUTER_API_KEY` | `PersonaContextRealBenchmarkTest`'s shared `World` guard (throws if absent); `Phase52ProductionBehavioralValidationTest`, `Phase54EngineAblationTest`, `Phase55SmokeTest` (same error text pattern, per the original baseline failure messages) | Not observed to break anything when present (it only enables previously-gated real calls) |
| `DATABASE_URL` | `PersonaContextPocRealBenchmark` (`IllegalStateException: DATABASE_URL environment variable is required`); also referenced in `PersonaContextPocBenchmarkRunner.kt`, `PhaseIMGLiveGateSeedTest.kt` | Not confirmed either way in this audit |
| `DATABASE_USER`, `DATABASE_PASSWORD` | Implied alongside `DATABASE_URL` wherever a real Postgres connection is constructed | Not confirmed either way in this audit |
| `ADMIN_USER_IDS` | Read by `AdminAuthorizationProvider.kt` line 55 (`System.getenv("ADMIN_USER_IDS") ?: ""`) | **Confirmed.** `AdminAllowlistRoutesTest.kt` line 177 contains the explicit comment: *"The test JVM does not set ADMIN_USER_IDS, so the env set is empty and…"* — the test `refuses to remove the last admin when the env var is empty` is written to require this variable be **absent/empty**. Sourcing `run-local.secrets.ps1` sets it to a real, non-empty value (`00000000-0000-0000-0000-000000000001`), which directly contradicts this test's stated precondition. This is a **PROVEN**, code-commented explanation for that specific failure appearing only in the credentialed run — not a Fix A/B regression, not a database-state issue, a documented environment-assumption conflict. |

`ApplicationWiringRegressionTest` and `AdminConsoleChatFlowVerificationTest` also newly failed in the credentialed run. `ApplicationWiringRegressionTest` constructs its own explicit `testDatabaseConfig` (`jdbc:h2:mem:unused`) and `testLlmConfig` (`apiKey = "test-key-no-openrouter-call"`), so it does not obviously inherit the sourced secrets for those two values — the specific mechanism of its failure is **UNKNOWN** from this audit and was not chased further (would require re-running, which this phase forbids). `AdminConsoleChatFlowVerificationTest`'s failure mechanism is likewise **UNKNOWN**.

**Conclusion for §7's stated goal**: confirmed, with direct code evidence for at least one of the three newly-appearing failures (`AdminAllowlistRoutesTest`), that sourcing the complete production secrets file changes the behavior of tests that were never designed to run with those variables set. This is sufficient to establish that "run the entire suite with all secrets sourced" mixes concerns that should be kept separate — it is **not** a clean way to validate only the 38 originally-gated tests, because it simultaneously perturbs an unknown number of other, unrelated tests.

## 8. Safe Validation Strategy (design only — not implemented, not run)

Rather than sourcing all five secrets globally for the whole suite, the safer shape is to scope environment variables to exactly the test classes that need them, run separately:

1. **API-key-only tests** (`PersonaContextRealBenchmarkTest`'s confirmed zero-cost methods, `Phase52/54/55`'s guard checks): run with only `OPENROUTER_API_KEY` set, via a filtered `gradle test --tests "com.pinkdreams.chat.PersonaContextRealBenchmarkTest.phase10cProviderVerification"` (and the other 4 confirmed-zero-cost methods) — this validates that the *guard* now passes and the *zero-cost* logic itself still behaves correctly, at genuinely zero API spend, without touching `ADMIN_USER_IDS` or `DATABASE_URL` at all.
2. **Database-only tests** (`PersonaContextPocRealBenchmark`): run with only `DATABASE_URL`/`DATABASE_USER`/`DATABASE_PASSWORD` set, `OPENROUTER_API_KEY` and `ADMIN_USER_IDS` left unset, isolating whether its failure was purely the missing-DB guard or something deeper.
3. **Admin-authorization tests** (`AdminAllowlistRoutesTest` and similar): run with `ADMIN_USER_IDS` explicitly **unset or empty**, matching their documented precondition, never inheriting the production admin ID.
4. **The confirmed real-spend methods** (the 25 "not statically confirmable" `PersonaContextRealBenchmarkTest` methods, and `PersonaContextPocLiveRealBenchmark`'s 255-call method): if ever run, run **one method at a time**, with an explicit pre-agreed cost cap and a hard scenario-count limit (e.g. patch the scenario list to 3–5 entries for a smoke check rather than the full 85), and only after independently confirming the real per-token price with the provider (not the codebase's own hardcoded constant) — directly addressing the §3 anomaly before trusting any dollar figure from these tests again.

This strategy has **not** been implemented or executed — per this phase's constraints, it is a design only.

## 9. Cost-Cap Analysis

- **Actual observed local-estimate cost/request** (input+output combined, per the flawed formula, from the 152 completed calls): total logged ≈ $28.99 ÷ 152 ≈ **$0.191/request** (local-estimate basis, not provider-verified; see §3's ×1000 caveat).
- **Actual observed local-estimate cost/scenario** (3 requests/scenario): ≈ **$0.573/scenario** on the same unverified basis.
- **Minimum remaining cost** (34 unstarted scenarios × 0 completed variants, best case if somehow free): $0 — not a meaningful floor given the formula always charges per real call made.
- **Maximum defensible remaining cost, same flawed local-estimate basis**: 34 scenarios × 3 variants × ~$0.191/request ≈ **$19.48** (local-estimate basis only).
- **Reasonable estimate**: on the local-estimate basis, roughly **$15–20** to finish the remaining 34 scenarios of that one method, consistent with per-scenario cost observed so far (fairly stable across the 51 completed, no obvious outliers). On the (unverified, hypothesis-only) ×1000-corrected basis from §3, this would instead be on the order of **$0.015–0.02**.

**This is not a defensible *real-dollar* projection** without resolving the §3 pricing-constant anomaly — it is a defensible projection of *what the same flawed local formula would report*, which is a different and much weaker claim. Per the instruction: where the underlying pricing input isn't verified, the honest statement is **"insufficient evidence for a defensible real-dollar cost projection."**

## 10. What Can and Cannot Be Extrapolated

**Can be extrapolated (with caveats stated)**: the local-formula dollar figure, scenario-to-scenario, because token counts and the formula are stable and consistent across the 51 observed scenarios (no evidence of retries, no logged failures, same model throughout, similar prompt/response lengths visible in the log). A same-formula linear projection for the remaining 34 scenarios of *this one method* is arithmetically reasonable.

**Cannot be extrapolated**:
- From this one method's local-formula cost to a real-dollar figure, without independently verifying `0.00014`/`0.00042` per token against actual OpenRouter/DeepSeek pricing (§3).
- From this one method's cost to the other 25 not-statically-confirmed `PersonaContextRealBenchmarkTest` methods — their scenario counts and call structure were not enumerated in this audit (§6), and at least 5 of the 32 methods in that file are confirmed zero-cost, so a flat "32 methods × similar cost" assumption would be directly contradicted by known evidence.
- To `PersonaContextPocRealBenchmark`, `Phase52/54/55` — their internal structure was not inventoried (§6), marked UNKNOWN.
- Across models/providers — all evidence gathered is specific to whatever model `LlmConfig.from(AppConfig.load())` resolved to during that run (not independently re-confirmed in this zero-execution audit, though the code comment says "DeepSeek pricing").

## 11. Final Classifications

**"$29" statement**: **C — Partially supported / extrapolation not justified as a real-dollar claim.** The $28.99 total is a faithfully-computed, internally consistent aggregation over real (provider-reported) token counts — that part is solid. But it is not provider-verified billing, and the per-token rate it uses shows a strong, unaddressed anomaly (§3) suggesting the true dollar exposure could be roughly 1000× smaller. Calling it "actual spend" (as the prior turn did) was **not supported**; calling it a properly-calibrated "estimate" is also not fully supported until the rate is checked against real pricing.

**"Full suite with all secrets sourced is safe and representative"**: **NOT SUPPORTED.** Confirmed directly (§7) that at least one unrelated test (`AdminAllowlistRoutesTest`) has a code-documented precondition that the credentialed run violates, and two more (`ApplicationWiringRegressionTest`, `AdminConsoleChatFlowVerificationTest`) newly failed under credentials for reasons not present in the clean baseline. Running the whole suite with every production secret sourced conflates unrelated concerns and does not cleanly answer "do the 38 originally-gated tests pass with credentials" — it answers a broader, messier question involving tests never designed to see those values.

## 12. Conclusion

- **Was $29 actual provider spend, an estimate, or unknown?** An **estimate** — specifically a locally hardcoded, internally consistent computation over real token counts, not provider-verified, with a flagged (not confirmed) ×1000 unit-scale anomaly that makes its accuracy as a real-dollar figure doubtful.
- **Does 51/85 justify extrapolating total cost?** Only for projecting *what the same local formula would continue to report* for the remaining scenarios of that one method (~$15–20 more, same unverified basis) — not for a verified real-dollar total, and not for the other 25 unexamined `PersonaContextRealBenchmarkTest` methods or the 3 other untouched test files.
- **Is the "25 more methods" cost claim supported?** **No.** That framing conflated two different test files (§3's correction) and asserted a uniform per-method cost that direct inspection contradicts — at least 5 of the 32 `PersonaContextRealBenchmarkTest` methods are confirmed zero-cost by design, and the other 25's actual scenario/call counts were never enumerated before the prior turn's cost claim was made.
- **Is running the entire suite with all secrets sourced valid?** **No** — confirmed to perturb at least one unrelated test via a documented precondition conflict (§7), with two more newly-failing tests whose cause is unconfirmed but was absent from the clean baseline.
- **What is the safest next validation action?** Run only the 5 confirmed-zero-cost `PersonaContextRealBenchmarkTest` methods with just `OPENROUTER_API_KEY` set (§8, item 1) — this validates the credential-gated code path at genuine zero API spend, without touching `ADMIN_USER_IDS` or `DATABASE_URL`, and without going anywhere near the expensive real-call methods until the §3 pricing anomaly is independently resolved.
