package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.Conversations
import com.pinkdreams.persistence.database.MemoryFacts
import com.pinkdreams.persistence.database.Messages
import com.pinkdreams.persistence.database.Skills
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

/**
 * Module 05 — Dashboard. Read-only COUNT(*) aggregates over `conversations`
 * and `messages` so the Dashboard's activity cards show REAL numbers instead
 * of a fabricated placeholder.
 *
 * Deliberately narrow and cheap: every query here is a single `COUNT(*)` with
 * at most an equality + a `created_at >= ?` range predicate — no joins, no row
 * materialisation, no percentile/statistics logic (that remains exclusively
 * PerformanceMetricsRepository's job and is not duplicated here). Nothing in
 * this class writes, and no existing repository's query shape is touched.
 */
class AdminStatsRepository(private val db: Database) {

    data class ActivityCounts(
        val totalConversations: Long,
        val productionConversations: Long,
        val testConversations: Long,
        val conversationsLast24h: Long,
        val totalMessages: Long,
        val messagesLast24h: Long,
    )

    /**
     * @param now injected so a test can pin the 24h window deterministically;
     *   production always uses the same clock helper every other repository uses.
     */
    fun activityCounts(now: LocalDateTime = defaultNow()): ActivityCounts = transaction(db) {
        val since = now.minusHours(24)
        ActivityCounts(
            totalConversations = Conversations.selectAll().count(),
            productionConversations = Conversations
                .select { Conversations.executionMode eq "PRODUCTION" }.count(),
            testConversations = Conversations
                .select { Conversations.executionMode eq "TEST" }.count(),
            conversationsLast24h = Conversations
                .select { Conversations.createdAt greaterEq since }.count(),
            totalMessages = Messages.selectAll().count(),
            messagesLast24h = Messages
                .select { Messages.createdAt greaterEq since }.count(),
        )
    }

    /** Messages in the last 24h broken down by role — same cheap COUNT shape. */
    fun messagesLast24hByRole(role: String, now: LocalDateTime = defaultNow()): Long = transaction(db) {
        Messages
            .select { (Messages.role eq role) and (Messages.createdAt greaterEq now.minusHours(24)) }
            .count()
    }

    // ------------------------------------------------------------------
    // Additional Dashboard / list KPIs. Same discipline as above: every
    // query below is either a single COUNT(*) with at most one equality
    // predicate, or ONE `GROUP BY` over a single table. Nothing here joins,
    // and nothing here is issued per row — the grouped queries deliberately
    // return the WHOLE map in one round trip precisely so a caller rendering
    // a list never degenerates into an N+1 loop.
    // ------------------------------------------------------------------

    data class MemoryFactCounts(
        val totalFacts: Long,
        val openFacts: Long,
    )

    /** Two COUNT(*)s over `memory_facts`. No joins, no row materialisation. */
    fun memoryFactCounts(): MemoryFactCounts = transaction(db) {
        MemoryFactCounts(
            totalFacts = MemoryFacts.selectAll().count(),
            openFacts = MemoryFacts.select { MemoryFacts.status eq "open" }.count(),
        )
    }

    data class SkillStageCounts(
        /** Skill versions currently serving production traffic (one per key). */
        val production: Long,
        /** Published but NOT active — ready to try in Test Chat, live for nobody. */
        val testing: Long,
        val draft: Long,
    )

    /** Three COUNT(*)s over `skills` — mirrors the Personas list's own Production/Testing wording. */
    fun skillStageCounts(): SkillStageCounts = transaction(db) {
        SkillStageCounts(
            production = Skills.select { Skills.isActive eq true }.count(),
            testing = Skills.select { (Skills.status eq "published") and (Skills.isActive eq false) }.count(),
            draft = Skills.select { Skills.status eq "draft" }.count(),
        )
    }

    /**
     * Conversation count per persona — ONE grouped query for every persona at
     * once, never one query per row. Personas with zero conversations are
     * simply absent from the map (the caller renders them as 0).
     */
    fun conversationCountsByPersona(): Map<UUID, Long> = transaction(db) {
        val counter = Conversations.id.count()
        Conversations
            .slice(Conversations.personaId, counter)
            .selectAll()
            .groupBy(Conversations.personaId)
            .associate { it[Conversations.personaId] to it[counter] }
    }

    data class UserActivity(
        val conversations: Long,
        /** Most recent `last_message_at` across all of this user's conversations; null if they never sent one. */
        val lastActiveAt: LocalDateTime?,
    )

    /**
     * Conversation count AND last-activity timestamp per user, in ONE grouped
     * query over `conversations` for the whole user list.
     */
    fun activityByUser(): Map<UUID, UserActivity> = transaction(db) {
        val counter = Conversations.id.count()
        val latest = Conversations.lastMessageAt.max()
        Conversations
            .slice(Conversations.userId, counter, latest)
            .selectAll()
            .groupBy(Conversations.userId)
            .associate { it[Conversations.userId] to UserActivity(it[counter], it[latest]) }
    }
}
