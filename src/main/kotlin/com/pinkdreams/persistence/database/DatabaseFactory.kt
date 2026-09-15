package com.pinkdreams.persistence.database

import com.pinkdreams.config.DatabaseConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

object DatabaseFactory {
    fun connect(config: DatabaseConfig): Database {
        val db = Database.connect(
            url = config.jdbcUrl,
            driver = when {
                config.jdbcUrl.startsWith("jdbc:h2:") -> "org.h2.Driver"
                config.jdbcUrl.startsWith("jdbc:postgresql:") -> "org.postgresql.Driver"
                else -> "org.postgresql.Driver"
            },
            user = config.username,
            password = config.password,
        )
        try {
            initializeSchema(db)
        } catch (e: Exception) {
            // Schema might already exist or initialization might have failed
            // Continue anyway as the application will fail later if tables don't exist
        }
        return db
    }

    fun connectInMemory(): Database = Database.connect(
        url = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_UPPER=FALSE",
        driver = "org.h2.Driver",
        user = "sa",
        password = "",
    )

    fun initializeSchema(db: Database) = transaction(db) {
        SchemaUtils.createMissingTablesAndColumns(
            ConversationEngines,
            PersonaIdentity,
            PersonaVisualVersions,
            PersonaVisualWardrobeItems,
            PersonaVisualReferenceImages,
            ImageJobs,
            Personas,
            PersonaCoreVersions,
            UserProfiles,
            MemoryFacts,
            Entitlements,
            Conversations,
            Messages,
            ChatRequestExecutions,
        )
    }
}

object ConversationEngines : Table("conversation_engines") {
    val id = uuid("id")
    val version = integer("version").uniqueIndex()
    val content = text("content")
    val status = text("status").default("draft")
    val isActive = bool("is_active").default(false)
    val changelogNote = text("changelog_note").nullable()
    val createdBy = text("created_by").nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

object PersonaIdentity : Table("persona_identity") {
    val id = uuid("id")
    val activeVisualVersionId = uuid("active_visual_version_id").nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

object PersonaVisualVersions : Table("persona_visual_versions") {
    val id = uuid("id")
    val personaIdentityId = uuid("persona_identity_id")
    val version = integer("version")
    val physicalGuide = text("physical_guide").default("{}")
    val styleConstraints = text("style_constraints").default("{}")
    val status = varchar("status", 50).default("draft")
    val changelogNote = text("changelog_note").nullable()
    val author = text("author").nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

object PersonaVisualWardrobeItems : Table("persona_visual_wardrobe_items") {
    val id = uuid("id")
    val personaVisualVersionId = uuid("persona_visual_version_id")
    val category = varchar("category", 100)
    val subcategory = varchar("subcategory", 100)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val color = varchar("color", 100).nullable()
    val material = varchar("material", 100).nullable()
    val fit = varchar("fit", 100).nullable()
    val pattern = varchar("pattern", 100).nullable()
    val seasonTags = text("season_tags").default("{}") // stored as JSON array
    val styleTags = text("style_tags").default("{}") // stored as JSON array
    val accessories = text("accessories").default("{}") // stored as JSON array
    val isAvailable = bool("is_available").default(true)
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

object PersonaVisualReferenceImages : Table("persona_visual_reference_images") {
    val id = uuid("id")
    val personaVisualVersionId = uuid("persona_visual_version_id")
    val storageKey = varchar("storage_key", 500).uniqueIndex()
    val contentType = varchar("content_type", 100)
    val fileSize = long("file_size")
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val checksum = varchar("checksum", 128).nullable()
    val role = varchar("role", 50).default("GENERAL_IDENTITY")
    val status = varchar("status", 50).default("UPLOADED")
    val referenceSource = varchar("source", 50)
    val notes = text("notes").nullable()
    val createdAt = datetime("created_at")
    val finalizedAt = datetime("finalized_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object ImageJobs : Table("image_jobs") {
    val id = uuid("id")
    val personaVisualVersionId = uuid("persona_visual_version_id")
    val jobType = varchar("job_type", 50).default("IMAGE_GENERATION")
    val status = varchar("status", 50).default("QUEUED")
    val idempotencyKey = varchar("idempotency_key", 255)
    val requestPayload = text("request_payload")
    val attemptCount = integer("attempt_count").default(0)
    val maxAttempts = integer("max_attempts").default(3)
    val availableAt = datetime("available_at")
    val claimedByWorker = varchar("claimed_by_worker", 255).nullable()
    val claimedAt = datetime("claimed_at").nullable()
    val lastError = text("last_error").nullable()
    val createdAt = datetime("created_at")
    val startedAt = datetime("started_at").nullable()
    val completedAt = datetime("completed_at").nullable()

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(personaVisualVersionId, idempotencyKey)
    }
}

object Personas : Table("personas") {
    val id = uuid("id")
    val slug = varchar("slug", 255).uniqueIndex()
    val displayName = varchar("display_name", 255)
    val status = varchar("status", 50).default("draft")
    val gender = varchar("gender", 50)
    val orientation = varchar("orientation", 50)
    val apparentAge = integer("apparent_age")
    val languageProfile = text("language_profile").default("{}")
    val activeCoreVersionId = uuid("active_core_version_id").nullable()
    val personaIdentityId = uuid("persona_identity_id").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object PersonaCoreVersions : Table("persona_core_versions") {
    val id = uuid("id")
    val personaId = uuid("persona_id")
    val version = integer("version")
    val content = text("content")
    val status = varchar("status", 50).default("draft")
    val changelogNote = text("changelog_note").nullable()
    val author = text("author").nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

object UserProfiles : Table("user_profiles") {
    val userId = uuid("user_id")
    val displayName = varchar("display_name", 255).nullable()
    val preferredLanguage = varchar("preferred_language", 50).nullable()
    val communicationStyle = varchar("communication_style", 255).nullable()
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

object MemoryFacts : Table("memory_facts") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val personaId = uuid("persona_id")
    val fact = text("fact")
    val factType = varchar("fact_type", 30)
    val criticality = varchar("criticality", 20).default("medium")
    val criticalityRank = short("criticality_rank").nullable()
    val tier = varchar("tier", 10).default("hot")
    val status = varchar("status", 20).default("open")
    val factSource = varchar("source", 20).default("llm_extracted")
    val learnedAt = datetime("learned_at")
    val lastReferencedAt = datetime("last_referenced_at").nullable()
    val evictedAt = datetime("evicted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object Entitlements : Table("entitlements") {
    val userId = uuid("user_id")
    val state = varchar("state", 20)
    val trialStartedAt = datetime("trial_started_at").nullable()
    val stateChangedAt = datetime("state_changed_at")
    val extendedDaysRemaining = integer("extended_days_remaining").default(0)
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

object Conversations : Table("conversations") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val personaId = uuid("persona_id")
    val state = varchar("state", 20).default("active")
    val lastMessageAt = datetime("last_message_at").nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

object Messages : Table("messages") {
    val id = uuid("id")
    val conversationId = uuid("conversation_id")
    val role = varchar("role", 20)
    val content = text("content")
    val engineVersionId = uuid("engine_version_id").nullable()
    val personaCoreVersionId = uuid("persona_core_version_id").nullable()
    val clientMessageId = uuid("client_message_id").nullable()
    val requestId = uuid("request_id").nullable()
    val metadata = text("metadata").default("{}")
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

object ChatRequestExecutions : Table("chat_request_executions") {
    val conversationId = uuid("conversation_id")
    val clientMessageId = uuid("client_message_id")
    val requestId = uuid("request_id")
    val status = varchar("status", 20).default("processing")
    val assistantMessageId = uuid("assistant_message_id").nullable()
    val errorCode = varchar("error_code", 255).nullable()
    val createdAt = datetime("created_at")
    val completedAt = datetime("completed_at").nullable()

    override val primaryKey = PrimaryKey(conversationId, clientMessageId)
}

fun defaultNow(): LocalDateTime = LocalDateTime.now()
