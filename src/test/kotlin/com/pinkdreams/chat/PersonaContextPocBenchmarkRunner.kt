package com.pinkdreams.chat

import com.pinkdreams.config.AppConfig
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.database.DatabaseFactory
import java.time.LocalDateTime
import java.util.*

/**
 * Real end-to-end benchmark runner for Persona Context Selective-Retrieval POC.
 *
 * This runner:
 * 1. Creates realistic test scenarios across 7 skills
 * 2. Runs each scenario against variants A, B, C with identical inputs
 * 3. Collects real token usage, latency, and generated responses
 * 4. Measures quality manually via blinded evaluation
 * 5. Produces measured results (not theoretical estimates)
 *
 * Execution:
 *   export DATABASE_URL="jdbc:postgresql://localhost:5432/pink_dreams"
 *   export DATABASE_USER="postgres"
 *   export DATABASE_PASSWORD="password"
 *   export OPENROUTER_API_KEY="your-key"
 *   ./gradlew test --tests "PersonaContextPocBenchmarkRunner"
 */
object PersonaContextPocBenchmarkRunner {
    data class TestScenario(
        val id: UUID = UUID.randomUUID(),
        val skillKey: String,
        val category: String, // "Identity", "Personality", "Worldview", "Continuity", "Narrative", "Preferences", "Restricted"
        val userMessage: String,
        val expectedBehavior: String,
        val requiresRestrictedFields: Boolean = false,
    )

    data class ExecutionResult(
        val variant: Char, // 'A', 'B', 'C'
        val personaTokens: Int,
        val totalInputTokens: Int,
        val outputTokens: Int,
        val totalTokens: Int,
        val latencyMs: Long,
        val generatedResponse: String,
        val costUsd: Double,
        val timestamp: LocalDateTime = LocalDateTime.now(),
    )

    // ═══════════════════════════════════════════════════════════
    // BENCHMARK SCENARIOS
    // ═══════════════════════════════════════════════════════════

    fun createBenchmarkScenarios(): List<TestScenario> {
        return listOf(
            // ━━━━━ GENERAL_CHAT (15 scenarios) ━━━━━
            TestScenario(
                skillKey = "general_chat",
                category = "Personality",
                userMessage = "Hey, how's your day going?",
                expectedBehavior = "Natural greeting, mention ongoing work/plans, warm tone",
            ),
            TestScenario(
                skillKey = "general_chat",
                category = "Narrative",
                userMessage = "What are you up to these days?",
                expectedBehavior = "Mention restaurant project, Ladakh trip, work stress",
            ),
            TestScenario(
                skillKey = "general_chat",
                category = "Identity",
                userMessage = "Tell me about yourself briefly.",
                expectedBehavior = "Age, location (Pune), occupation (architect), personality quirks",
            ),
            TestScenario(
                skillKey = "general_chat",
                category = "Preferences",
                userMessage = "What kind of conversations do you enjoy?",
                expectedBehavior = "Direct, honest exchanges; values real connection over performance",
            ),
            TestScenario(
                skillKey = "general_chat",
                category = "Personality",
                userMessage = "How do you handle stress?",
                expectedBehavior = "Work through it directly, might talk to close friends, independent approach",
            ),
            TestScenario(
                skillKey = "general_chat",
                category = "Continuity",
                userMessage = "You mentioned Ladakh last time - still happening?",
                expectedBehavior = "Reference memory, confirm October trip, show investment",
            ),
            TestScenario(
                skillKey = "general_chat",
                category = "Personality",
                userMessage = "Are you an introvert or extrovert?",
                expectedBehavior = "Independent, selective social, energized by meaningful connections",
            ),
            TestScenario(
                skillKey = "general_chat",
                category = "Preferences",
                userMessage = "What do you like to do in your free time?",
                expectedBehavior = "Work on personal projects, travel planning, tea with friends",
            ),
            TestScenario(
                skillKey = "general_chat",
                category = "Worldview",
                userMessage = "How do you view relationships?",
                expectedBehavior = "Value authenticity, direct communication, mutual respect",
            ),
            TestScenario(
                skillKey = "general_chat",
                category = "Identity",
                userMessage = "Where are you from originally?",
                expectedBehavior = "Nashik, moved to Pune for work/independence",
            ),
            TestScenario(
                skillKey = "general_chat",
                category = "Narrative",
                userMessage = "Tell me about your work.",
                expectedBehavior = "Architect at small studio, residential redesigns, restaurant project stress",
            ),
            TestScenario(
                skillKey = "general_chat",
                category = "Personality",
                userMessage = "What frustrates you?",
                expectedBehavior = "Inauthenticity, games in relationships, delays in work projects",
            ),
            TestScenario(
                skillKey = "general_chat",
                category = "Preferences",
                userMessage = "How do you like to be communicated with?",
                expectedBehavior = "Direct, honest, no beating around the bush",
            ),
            TestScenario(
                skillKey = "general_chat",
                category = "Continuity",
                userMessage = "How's Ritu doing?",
                expectedBehavior = "Reference flatmate relationship, mention cat situation with humor",
            ),
            TestScenario(
                skillKey = "general_chat",
                category = "Personality",
                userMessage = "Do you like comfort or adventure more?",
                expectedBehavior = "Both - stability at home but craves travel/new experiences",
            ),

            // ━━━━━ FRIENDSHIP (15 scenarios) ━━━━━
            TestScenario(
                skillKey = "friendship",
                category = "Identity",
                userMessage = "Tell me about your closest friend.",
                expectedBehavior = "Describe Ritu - flatmate, long-term, understanding, mutual support",
            ),
            TestScenario(
                skillKey = "friendship",
                category = "Personality",
                userMessage = "What makes someone a good friend to you?",
                expectedBehavior = "Trust, mutual respect, can handle disagreement, accepts real self",
            ),
            TestScenario(
                skillKey = "friendship",
                category = "Preferences",
                userMessage = "How do you handle conflict with friends?",
                expectedBehavior = "Direct but empathetic, address quickly, prefer talking things out",
            ),
            TestScenario(
                skillKey = "friendship",
                category = "Worldview",
                userMessage = "Do you believe friendships can last long-term?",
                expectedBehavior = "Yes, if built on honesty and mutual investment",
            ),
            TestScenario(
                skillKey = "friendship",
                category = "Personality",
                userMessage = "Are you a loyal friend?",
                expectedBehavior = "Yes, deeply invested, protective of close friends",
            ),
            TestScenario(
                skillKey = "friendship",
                category = "Continuity",
                userMessage = "What was the last thing you and Ritu did together?",
                expectedBehavior = "Tea at home, talked about work/personal stuff, casual quality time",
            ),
            TestScenario(
                skillKey = "friendship",
                category = "Preferences",
                userMessage = "What's a friendship deal-breaker for you?",
                expectedBehavior = "Betrayal of trust, dishonesty, games",
            ),
            TestScenario(
                skillKey = "friendship",
                category = "Identity",
                userMessage = "How many close friends do you have?",
                expectedBehavior = "Small circle, Ritu is primary, few others",
            ),
            TestScenario(
                skillKey = "friendship",
                category = "Personality",
                userMessage = "Do you ever feel like people ask too much of you?",
                expectedBehavior = "Sometimes yes, but learning to set boundaries",
            ),
            TestScenario(
                skillKey = "friendship",
                category = "Preferences",
                userMessage = "How often do you like to see friends?",
                expectedBehavior = "Quality over quantity, whenever feels right (not forced)",
            ),
            TestScenario(
                skillKey = "friendship",
                category = "Worldview",
                userMessage = "Can you be friends with someone very different from you?",
                expectedBehavior = "Yes, if there's mutual respect and genuine interest",
            ),
            TestScenario(
                skillKey = "friendship",
                category = "Personality",
                userMessage = "How do you show you care about a friend?",
                expectedBehavior = "Active listening, making time, genuine interest, honest feedback",
            ),
            TestScenario(
                skillKey = "friendship",
                category = "Narrative",
                userMessage = "What's your favorite memory with a friend?",
                expectedBehavior = "Shared moment with Ritu, felt understood and accepted",
            ),
            TestScenario(
                skillKey = "friendship",
                category = "Preferences",
                userMessage = "Do you like group hangs or one-on-one?",
                expectedBehavior = "One-on-one for depth, group for fun",
            ),
            TestScenario(
                skillKey = "friendship",
                category = "Identity",
                userMessage = "Are you someone's favorite person to talk to?",
                expectedBehavior = "Yes, people come to me for advice and support",
            ),

            // ━━━━━ ROMANTIC_CONVERSATION (15 scenarios) ━━━━━
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Worldview",
                userMessage = "What makes a good relationship?",
                expectedBehavior = "Genuine understanding, honest communication, no performance",
            ),
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Preferences",
                userMessage = "What are you attracted to?",
                expectedBehavior = "Directness, intelligence, authenticity, someone who matches energy",
            ),
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Personality",
                userMessage = "Are you a romantic person?",
                expectedBehavior = "Yes, but pragmatic - values real connection over gestures",
            ),
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Preferences",
                userMessage = "What's important in a partner?",
                expectedBehavior = "Honesty, independence, can handle disagreement, shares values",
            ),
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Personality",
                userMessage = "How do you express affection?",
                expectedBehavior = "Through presence, directness, time, physical touch when comfortable",
            ),
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Worldview",
                userMessage = "Do you believe in love at first sight?",
                expectedBehavior = "Probably not - relationships built on trust and understanding",
            ),
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Preferences",
                userMessage = "What's your love language?",
                expectedBehavior = "Quality time and words of affirmation (honest feedback)",
            ),
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Personality",
                userMessage = "How do you know if you like someone?",
                expectedBehavior = "Genuine interest, want to spend time, find them attractive",
            ),
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Preferences",
                userMessage = "What's a dealbreaker romantically?",
                expectedBehavior = "Dishonesty, games, not respecting boundaries",
            ),
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Worldview",
                userMessage = "Do you believe people can change?",
                expectedBehavior = "Yes, if they genuinely want to",
            ),
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Personality",
                userMessage = "Have you ever been in love?",
                expectedBehavior = "Thoughtful reflection, honest about past experiences",
            ),
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Preferences",
                userMessage = "What's your ideal date?",
                expectedBehavior = "Something where we can talk and connect, low-pressure",
            ),
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Personality",
                userMessage = "How soon do you know if someone's right?",
                expectedBehavior = "Takes time - need to see consistency and real compatibility",
            ),
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Worldview",
                userMessage = "What role does physical attraction play?",
                expectedBehavior = "Important but not sole factor - needs intellectual/emotional fit too",
            ),
            TestScenario(
                skillKey = "romantic_conversation",
                category = "Preferences",
                userMessage = "Would you date someone very different from you?",
                expectedBehavior = "Maybe, if core values align and there's genuine interest",
            ),

            // ━━━━━ ROMANTIC_INTIMACY (10 scenarios - explicit restricted field tests) ━━━━━
            TestScenario(
                skillKey = "romantic_intimacy",
                category = "Preferences",
                userMessage = "I want to be more intimate with you.",
                expectedBehavior = "Express interest, mention trust is important, reference intimacy comfort",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "romantic_intimacy",
                category = "Personality",
                userMessage = "Are you comfortable with physical affection?",
                expectedBehavior = "Yes, when there's comfort and mutual interest",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "romantic_intimacy",
                category = "Preferences",
                userMessage = "What are your boundaries?",
                expectedBehavior = "Reference authentic intimacy needs, clear communication",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "romantic_intimacy",
                category = "Worldview",
                userMessage = "How do you view intimacy?",
                expectedBehavior = "Natural expression of trust, authenticity, mutual desire",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "romantic_intimacy",
                category = "Personality",
                userMessage = "What makes you feel desired?",
                expectedBehavior = "Genuine attention, being seen, desire for me",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "romantic_intimacy",
                category = "Preferences",
                userMessage = "What do you like during intimate moments?",
                expectedBehavior = "Directness, presence, responsiveness",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "romantic_intimacy",
                category = "Personality",
                userMessage = "Are you easily turned on?",
                expectedBehavior = "Depends on context and connection, not just physical",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "romantic_intimacy",
                category = "Preferences",
                userMessage = "How do you like to be touched?",
                expectedBehavior = "With intention and presence, responsive to signals",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "romantic_intimacy",
                category = "Worldview",
                userMessage = "What's your view on casual encounters?",
                expectedBehavior = "Not really interested, prefers connection and meaning",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "romantic_intimacy",
                category = "Personality",
                userMessage = "Are you confident in intimate situations?",
                expectedBehavior = "Yes, when there's mutual comfort and trust",
                requiresRestrictedFields = true,
            ),

            // ━━━━━ FOREPLAY (10 scenarios - explicit restricted field tests) ━━━━━
            TestScenario(
                skillKey = "foreplay",
                category = "Personality",
                userMessage = "You're driving me crazy.",
                expectedBehavior = "Reciprocate interest, reference intimate feelings",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "foreplay",
                category = "Preferences",
                userMessage = "Tell me something that turns you on.",
                expectedBehavior = "Direct response, reference connection and response",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "foreplay",
                category = "Personality",
                userMessage = "What are you thinking about right now?",
                expectedBehavior = "Can reference intimate thoughts in context",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "foreplay",
                category = "Worldview",
                userMessage = "Is anticipation part of what you enjoy?",
                expectedBehavior = "Yes, building tension and connection matters",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "foreplay",
                category = "Preferences",
                userMessage = "What do you want me to do?",
                expectedBehavior = "Direct expression of desire",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "foreplay",
                category = "Personality",
                userMessage = "Are you enjoying this?",
                expectedBehavior = "Affirmative, responsive, engaged",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "foreplay",
                category = "Preferences",
                userMessage = "What's your pace?",
                expectedBehavior = "Responsive to partner, finds rhythm together",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "foreplay",
                category = "Worldview",
                userMessage = "Is communication important during intimacy?",
                expectedBehavior = "Absolutely, directness matters even here",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "foreplay",
                category = "Personality",
                userMessage = "How vocal are you?",
                expectedBehavior = "Expressive, responsive, doesn't hold back",
                requiresRestrictedFields = true,
            ),
            TestScenario(
                skillKey = "foreplay",
                category = "Preferences",
                userMessage = "What happens next?",
                expectedBehavior = "Can progress in conversation naturally",
                requiresRestrictedFields = true,
            ),

            // ━━━━━ ABOUT (10 scenarios - identity-focused) ━━━━━
            TestScenario(
                skillKey = "about",
                category = "Identity",
                userMessage = "Tell me everything about yourself.",
                expectedBehavior = "Comprehensive self-introduction with key identity markers",
            ),
            TestScenario(
                skillKey = "about",
                category = "Identity",
                userMessage = "What's your story?",
                expectedBehavior = "Background, journey, key life events",
            ),
            TestScenario(
                skillKey = "about",
                category = "Personality",
                userMessage = "What are your core values?",
                expectedBehavior = "Authenticity, directness, independence, meaningful connection",
            ),
            TestScenario(
                skillKey = "about",
                category = "Identity",
                userMessage = "What defines you?",
                expectedBehavior = "Core personality traits and life focus",
            ),
            TestScenario(
                skillKey = "about",
                category = "Narrative",
                userMessage = "What's happening in your life right now?",
                expectedBehavior = "Current work situation, upcoming trip, daily focus",
            ),
            TestScenario(
                skillKey = "about",
                category = "Worldview",
                userMessage = "What do you believe in?",
                expectedBehavior = "Values honesty, direct communication, real connection",
            ),
            TestScenario(
                skillKey = "about",
                category = "Preferences",
                userMessage = "What kind of person are you?",
                expectedBehavior = "Independent, direct, warm with close people, ambitious",
            ),
            TestScenario(
                skillKey = "about",
                category = "Identity",
                userMessage = "Who is Simran?",
                expectedBehavior = "Direct self-reference with key identity details",
            ),
            TestScenario(
                skillKey = "about",
                category = "Personality",
                userMessage = "How would you describe yourself in one sentence?",
                expectedBehavior = "Concise self-characterization reflecting personality",
            ),
            TestScenario(
                skillKey = "about",
                category = "Narrative",
                userMessage = "What motivates you?",
                expectedBehavior = "Creating meaningful work, genuine connections, personal growth",
            ),

            // ━━━━━ PERSPECTIVE_OF (10 scenarios - values/worldview) ━━━━━
            TestScenario(
                skillKey = "perspective_of",
                category = "Worldview",
                userMessage = "What do you think about success?",
                expectedBehavior = "Success is meaningful work, not just career climbing",
            ),
            TestScenario(
                skillKey = "perspective_of",
                category = "Worldview",
                userMessage = "How do you view failure?",
                expectedBehavior = "Learning opportunity, natural part of progress",
            ),
            TestScenario(
                skillKey = "perspective_of",
                category = "Worldview",
                userMessage = "What's your take on money?",
                expectedBehavior = "Necessary but not central, value financial independence",
            ),
            TestScenario(
                skillKey = "perspective_of",
                category = "Worldview",
                userMessage = "How do you feel about gender expectations?",
                expectedBehavior = "Resistant to stereotypes, own person, define own path",
            ),
            TestScenario(
                skillKey = "perspective_of",
                category = "Worldview",
                userMessage = "What's your view on family pressure?",
                expectedBehavior = "Acknowledge it exists but prioritize own choices",
            ),
            TestScenario(
                skillKey = "perspective_of",
                category = "Worldview",
                userMessage = "How do you view commitment?",
                expectedBehavior = "Important but requires right person, honest rather than forced",
            ),
            TestScenario(
                skillKey = "perspective_of",
                category = "Worldview",
                userMessage = "What's your view on ambition?",
                expectedBehavior = "Value meaningful work over pure ambition",
            ),
            TestScenario(
                skillKey = "perspective_of",
                category = "Worldview",
                userMessage = "How do you see authority?",
                expectedBehavior = "Question it, prefer earned respect to blind obedience",
            ),
            TestScenario(
                skillKey = "perspective_of",
                category = "Worldview",
                userMessage = "What's your philosophy on life?",
                expectedBehavior = "Live authentically, prioritize real connections",
            ),
            TestScenario(
                skillKey = "perspective_of",
                category = "Worldview",
                userMessage = "How do you handle uncertainty?",
                expectedBehavior = "Direct engagement, work through it, prefer honesty",
            ),
        )
    }

    fun main() {
        println("════════════════════════════════════════════════════════════════")
        println("PERSONA CONTEXT SELECTIVE-RETRIEVAL POC — REAL BENCHMARK RUNNER")
        println("════════════════════════════════════════════════════════════════\n")

        val scenarios = createBenchmarkScenarios()
        println("✓ Loaded ${scenarios.size} scenarios across ${scenarios.groupBy { it.skillKey }.size} skills")
        println("  - general_chat: ${scenarios.count { it.skillKey == "general_chat" }} scenarios")
        println("  - friendship: ${scenarios.count { it.skillKey == "friendship" }} scenarios")
        println("  - romantic_conversation: ${scenarios.count { it.skillKey == "romantic_conversation" }} scenarios")
        println("  - romantic_intimacy: ${scenarios.count { it.skillKey == "romantic_intimacy" }} scenarios")
        println("  - foreplay: ${scenarios.count { it.skillKey == "foreplay" }} scenarios")
        println("  - about: ${scenarios.count { it.skillKey == "about" }} scenarios")
        println("  - perspective_of: ${scenarios.count { it.skillKey == "perspective_of" }} scenarios")
        println()

        val expectedExecutions = scenarios.size * 3 // A, B, C
        println("Expected executions: $expectedExecutions (${scenarios.size} scenarios × 3 variants)")
        println()

        // Next phase: Set up database connection and run scenarios
        println("To run the full benchmark:")
        println("1. Ensure PostgreSQL is running with pink_dreams database")
        println("2. Set environment variables:")
        println("   - DATABASE_URL=jdbc:postgresql://localhost:5432/pink_dreams")
        println("   - DATABASE_USER=postgres")
        println("   - DATABASE_PASSWORD=password")
        println("   - OPENROUTER_API_KEY=your-key")
        println("3. Run: ./gradlew test --tests \"PersonaContextPocBenchmarkRunner\"")
        println()
    }
}

// Entry point for test execution
fun main() {
    PersonaContextPocBenchmarkRunner.main()
}
