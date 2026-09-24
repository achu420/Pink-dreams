# Persona Context POC — Benchmark Scenarios

## Scenario Design (40 total, ~6 per skill)

### General Chat (6 scenarios)

1. **gc_001_casual** — "Hey, how's your day going?"
   - Skill: general_chat
   - Expected fields: personality.core_traits, communication.voice, narrative_state.current_arcs
   - Type: Cold start, casual

2. **gc_002_work** — "What are you working on these days?"
   - Expected fields: life_context.occupation, narrative_state.current_arcs
   - Type: Cold start, work-focused

3. **gc_003_continuity** — Message after 5-turn history: "That sounds tough. How are you handling it?"
   - Expected fields: personality.core_traits, decision_patterns
   - Type: Recent continuity, emotional context

4. **gc_004_preference** — "Do you prefer beaches or mountains?"
   - Expected fields: preferences.activities, worldview.strong_opinions
   - Type: Preference query

5. **gc_005_ambiguous** — "Tell me something about yourself that surprised you recently."
   - Expected fields: personality, background, narrative_state
   - Type: Ambiguous, requires inference

6. **gc_006_personality** — "How do you usually react when something goes wrong?"
   - Expected fields: personality.core_traits, decision_patterns, worldview.values
   - Type: Behavioral query

---

### Friendship (6 scenarios)

1. **fs_001_friend** — "Tell me about your closest friend."
   - Expected fields: relational.close_friends, relational.attachment_style
   - Type: Cold start, relational

2. **fs_002_ritu** — "How long have you known Ritu?"
   - Expected fields: relational.close_friends (Ritu entry), background.life_events
   - Type: Specific relationship

3. **fs_003_conflict** — "How do you usually handle it when you disagree with a friend?"
   - Expected fields: personality.core_traits, decision_patterns, relational.attachment_style
   - Type: Conflict handling

4. **fs_004_boundary** — After history showing closeness: "Do you ever feel like people ask too much of you?"
   - Expected fields: preferences.boundaries, relational.attachment_style
   - Type: Boundary/continuity

5. **fs_005_advice** — "Should I tell my friend they hurt me?"
   - Expected fields: worldview.values, decision_patterns, personality.empathy
   - Type: Advice, high-stakes

6. **fs_006_deepening** — "I feel like I can really be myself with you, you know?"
   - Expected fields: relational.attachment_style, personality, communication.voice
   - Type: Emotional deepening

---

### Romantic Conversation (6 scenarios)

1. **rc_001_attraction** — "I find you really interesting. What attracted you to [context]?"
   - Expected fields: personality, relational.romantic_style, preferences.romantic
   - Type: Flirtation

2. **rc_002_relationship** — "What do you think makes a good relationship?"
   - Expected fields: worldview.values, relational.romantic_style, aspirations
   - Type: Relationship philosophy

3. **rc_003_past** — "Have you been in love before?"
   - Expected fields: narrative_state.relationship_arcs, personality, worldview.values
   - Type: Personal history

4. **rc_004_emotion** — "I think I might be falling for you. How does that make you feel?"
   - Expected fields: relational.romantic_style, personality.empathy, communication.voice
   - Type: High emotional content

5. **rc_005_boundaries** — "What are you comfortable with?"
   - Expected fields: relational.romantic_style, preferences, intimacy.comfort_and_boundaries
   - Type: Boundary-setting

6. **rc_006_continuity** — After romantic history: "I've been thinking about you a lot."
   - Expected fields: relational.romantic_style, communication.voice
   - Type: Continuity reinforcement

---

### Romantic Intimacy (6 scenarios)

1. **ri_001_intimacy** — "I want to be more intimate with you. What do you like?"
   - Expected fields: intimacy.arousal_response, adult_intimacy, preferences.romantic
   - Type: Direct intimacy query
   - **RESTRICTED**: adult_intimacy should be gated (romantic_intimacy skill only)

2. **ri_002_desire** — "I'm really attracted to you."
   - Expected fields: personality, adult_intimacy.flirting_style, communication.voice
   - Type: Desire expression

3. **ri_003_pacing** — "I want to take things slow. Is that okay with you?"
   - Expected fields: adult_intimacy.intimate_pacing, personality, relational.romantic_style
   - Type: Pacing negotiation

4. **ri_004_responsiveness** — After building intimacy: "I love how you respond to me."
   - Expected fields: intimacy.arousal_response, adult_intimacy.responsive_marks
   - Type: Affirmation
   - **RESTRICTED**: May need intimate_anatomy details

5. **ri_005_consent** — "I need to know you're comfortable. Tell me honestly."
   - Expected fields: adult_intimacy.consent_preferences, personality.honesty
   - Type: Consent check

6. **ri_006_physical** — "I want to touch you."
   - Expected fields: intimacy.arousal_response, adult_intimacy.escalation_preferences
   - Type: Physical escalation
   - **RESTRICTED**: intimate_anatomy should be gated

---

### Foreplay (4 scenarios)

1. **fp_001_flirt** — "You're driving me crazy. Tell me something that would make me want you more."
   - Expected fields: adult_intimacy.flirting_style, intimacy.arousal_response, communication.voice
   - Type: Flirtation
   - **RESTRICTED**: intimate_anatomy

2. **fp_002_tease** — "I love the way you [describe something physical about context]."
   - Expected fields: intimate_anatomy.description, adult_intimacy.escalation_preferences
   - Type: Physical awareness
   - **RESTRICTED**: Should only appear if skill = foreplay

3. **fp_003_building** — "Kiss me. Tell me what you want."
   - Expected fields: adult_intimacy.initiation_style, intimacy.arousal_response
   - Type: Direct escalation
   - **RESTRICTED**: intimate_anatomy might be relevant

4. **fp_004_responsive** — "Touch me where I like it."
   - Expected fields: intimate_anatomy.sensitive_zones, adult_intimacy.escalation_preferences
   - Type: Specific intimate detail
   - **CRITICAL RESTRICTION TEST**: intimate_anatomy MUST NOT appear for general_chat

---

### About (4 scenarios)

1. **ab_001_intro** — "Tell me about yourself."
   - Expected fields: identity, life_context, personality.core_traits
   - Type: Introduction

2. **ab_002_origin** — "Where are you from?"
   - Expected fields: life_context.origin, identity.culture, background.childhood
   - Type: Origins

3. **ab_003_work** — "What do you do for work?"
   - Expected fields: life_context.occupation, background.education, aspirations.career
   - Type: Professional context

4. **ab_004_life** — "What's your life like right now?"
   - Expected fields: narrative_state, relational, preferences.activities, worldview
   - Type: Current life snapshot

---

### Perspective Of (4 scenarios)

1. **po_001_career** — "What do you think about ambition in your 20s?"
   - Expected fields: worldview.values, aspirations, personality.core_traits
   - Type: Opinion on topic

2. **po_002_marriage** — "How do you feel about marriage pressure from family?"
   - Expected fields: worldview.values, background.family_pressure, personality
   - Type: Personal topic with history

3. **po_003_travel** — "What does traveling mean to you?"
   - Expected fields: worldview.values, aspirations, narrative_state.major_events (Ladakh)
   - Type: Lifestyle philosophy

4. **po_004_independence** — "What does independence mean to you?"
   - Expected fields: worldview.values, personality.core_traits, aspirations
   - Type: Core values

---

## Scenario Execution Matrix

| Skill | Count | Cold Start | With History | With Memory | Ambiguous |
|---|---:|---:|---:|---:|---:|
| general_chat | 6 | 2 | 2 | 1 | 1 |
| friendship | 6 | 1 | 2 | 2 | 1 |
| romantic_conversation | 6 | 2 | 2 | 1 | 1 |
| romantic_intimacy | 6 | 1 | 2 | 2 | 1 |
| foreplay | 4 | - | 2 | 1 | 1 |
| about | 4 | 3 | 1 | - | - |
| perspective_of | 4 | 2 | 1 | 1 | - |
| **TOTAL** | **40** | **11** | **12** | **8** | **5** |

---

## Execution Plan

For each scenario:

**Run twice:**
- Variant A: Full persona (current production behavior)
- Variant B: Selective persona (POC with skill-aware field selection)

**Capture for both:**
- Assembled persona context (character count, tokens)
- Full LLM input context
- Total input tokens
- Output tokens
- Generation latency
- Generated response
- Cost (if available)

**Evaluate blindly:**
- Quality scores (7 dimensions, 0-5)
- Restricted leakage check
- Field presence/absence analysis

**Total executions:** 40 scenarios × 2 variants = 80 executions

**Expected duration:** ~2-3 hours with realistic LLM latency
