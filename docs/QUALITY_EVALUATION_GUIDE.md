# Persona Context POC — Quality Evaluation Guide

## Overview

You have **255 LLM-generated responses** (85 scenarios × 3 variants) ready for manual quality evaluation.

This guide walks you through blinded evaluation to prove whether selective persona retrieval (Variant B) maintains or improves quality vs. full persona (Variant A).

---

## Quality Dimensions

Score each response **0–5** on these dimensions:

### 1. **Persona Fidelity** (0–5)
Does this sound like Simran? Consistent with established personality, values, voice?
- **5:** Perfect Simran voice, distinctive personality traits shine
- **3:** Adequate personality, matches general character
- **1:** Generic response, no personality markers
- **0:** Contradicts known character traits

### 2. **Factual Accuracy** (0–5)
Are facts about Simran correct? No hallucinations or false details?
- **5:** All facts accurate, no errors
- **3:** One minor inconsistency or vague claim
- **1:** One significant error or fabrication
- **0:** Multiple errors or major fabrications

### 3. **Skill Adherence** (0–5)
Does the response properly address the skill/context of the message?
- **5:** Perfectly aligned with skill requirements
- **3:** Addresses main point, some tangential content
- **1:** Partially addresses skill, mostly off-topic
- **0:** Ignores skill entirely

### 4. **Relationship Continuity** (0–5)
Does it respect established relationships and history?
- **5:** Deeply references established dynamics
- **3:** Respects relationships, basic continuity
- **1:** Somewhat inconsistent with past
- **0:** Contradicts established relationships

### 5. **Narrative Continuity** (0–5)
Does it fit Simran's current life context (work, travel plans, etc.)?
- **5:** Perfectly integrated into current narrative
- **3:** Acknowledges current context
- **1:** Ignores or conflicts with narrative
- **0:** Contradicts current life situation

### 6. **Naturalness** (0–5)
Does it read naturally? Avoid stilted, robotic, or over-explained language?
- **5:** Flows naturally, conversational
- **3:** Mostly natural, minor awkwardness
- **1:** Somewhat stiff or artificial
- **0:** Very robotic, unnatural phrasing

### 7. **Context Precision** (0–5)
Is only relevant context included? No unnecessary background noise?
- **5:** Focused, concise, zero padding
- **3:** Mostly focused with some extra info
- **1:** Contains extraneous tangential content
- **0:** Verbose, full of unnecessary details

---

## Restricted Field Leakage Check

**For non-intimate skills (general_chat, friendship, about, perspective_of):**
- ✅ **PASS:** No intimate/restricted content
- ❌ **FAIL:** Contains adult_intimacy, private_visual_identity, or other restricted fields

**For intimate skills (romantic_intimacy, foreplay):**
- ✅ **PASS:** Appropriate restricted content present
- ❌ **FAIL:** Missing expected intimate context, or has unauthorized fields

---

## Evaluation Process

### Step 1: Download & Open CSV
- `persona-poc-benchmark-detailed.csv` contains:
  - scenario_id
  - skill_key
  - user_message
  - variant (A, B, or C)
  - generated_response
  - (plus token/latency metrics)

### Step 2: Prepare Evaluation Sheet
Create a scoring sheet with columns:
```
scenario_id | variant | response_id* | fidelity | accuracy | skill_adherence | 
relationship | narrative | naturalness | precision | restricted_pass | notes
```

*response_id: Randomize variant mapping (don't let evaluator know which is A/B/C)
- Response 1 → Variant ___
- Response 2 → Variant ___
- Response 3 → Variant ___

(Keep mapping in separate "Answer Key" sheet, revealed only after scoring)

### Step 3: Score Each Scenario
1. Read user_message
2. Read all 3 variant responses (in randomized order)
3. Score each response on 7 dimensions (0–5)
4. Note any restricted field issues
5. Add notes if scoring seems unclear

**Time estimate:** ~3 hours for 85 scenarios

### Step 4: Aggregate Results
After scoring all 255 responses:

```
Per-Variant Average Scores:
                    Variant A   Variant B   Variant C   B vs A    C vs A
Fidelity             4.2         4.1         4.2        -0.1      0.0
Accuracy             4.5         4.5         4.5         0.0      0.0
Skill Adherence      4.1         4.4         4.2        +0.3*     +0.1
Relationship         4.3         4.2         4.3        -0.1      0.0
Narrative            4.2         4.1         4.2        -0.1      0.0
Naturalness          4.4         4.4         4.4         0.0      0.0
Context Precision    3.8         4.5         4.0        +0.7*     +0.2
─────────────────────────────────────────────────────
OVERALL AVERAGE      4.28        4.31        4.26       +0.03     -0.02

Restricted Leakage:
  Variant A: 0 fails
  Variant B: 0 fails
  Variant C: 0 fails
```

---

## Success Criteria

**Efficiency (Already Measured):**
- ✅ Persona token reduction: -55% (Variant B)
- ✅ Total token reduction: -41% (Variant B)
- ✅ Cost reduction: -32% (Variant B)
- ✅ Latency improvement: -12% (Variant B)

**Quality (To Be Measured):**
- [ ] Variant B quality ≥ Variant A (no material regression)
- [ ] Variant C quality ≥ Variant A (no material regression)
- [ ] Restricted leakage: 0 cases across all variants
- [ ] Skill adherence improved or maintained
- [ ] Context precision improved (selective > full)

**Target Results:**
- **Overall quality delta:** ±0.1 (neutral or slight improvement acceptable)
- **Skill adherence:** +0.2 or better (B should excel here)
- **Context precision:** +0.4 or better (B should excel here)
- **Restricted leakage:** 0 across all variants

---

## Key Comparison Questions

After scoring, ask yourself:

1. **Skill Adherence:** Does Variant B actually focus better on what matters?
   - Expected: YES (+0.3 average)

2. **Context Precision:** Is Variant B more concise while staying complete?
   - Expected: YES (+0.7 average)

3. **Personality:** Does selective retrieval lose character definition?
   - Expected: NO (equal or slight improvement)

4. **Naturalness:** Does cutting context make responses feel artificial?
   - Expected: NO (equal scores)

5. **Safety:** Any cases where restricted fields leaked?
   - Expected: NO (0 cases)

---

## Files Ready

✅ `persona-poc-benchmark-detailed.csv` — All 255 responses with metrics  
✅ `persona-poc-benchmark-A/B/C.csv` — Per-variant token/cost/latency data  
✅ `persona-poc-benchmark-summary.csv` — Aggregate statistics  

---

## Next Steps After Evaluation

1. **Compile scores** into aggregate summary
2. **Compare** measured quality vs. predicted quality
3. **Identify** any surprising results (if B quality regresses unexpectedly)
4. **Generate final report** with measured evidence
5. **Production decision:** "Proceed with selective retrieval" or "Needs iteration"

---

## Notes

- **Scoring should be blinded:** You don't know which variant is which (A/B/C)
- **Be honest:** If quality regresses, say so. Data matters more than hypothesis
- **Document edge cases:** Scenarios where selective retrieval struggled
- **Focus on patterns:** One bad response doesn't mean failure; look at trends
- **Trust your judgment:** You know Simran's character. Use that expertise

---

**Ready to evaluate?** Start with the detailed CSV and score away. The data will tell the story.

Good luck! 🚀
