package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.Conversations
import com.pinkdreams.persistence.database.Messages
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

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
}
