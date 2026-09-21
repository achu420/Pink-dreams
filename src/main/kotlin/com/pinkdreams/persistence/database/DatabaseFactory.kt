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
            // Schema might already exist or initialization might have failed.
            // Continue anyway (the application will fail later if tables/columns
            // are actually missing), but this must never fail silently — an
            // exception here means the running schema can now diverge from what
            // the code expects (e.g. a new column silently never gets added),
            // which otherwise only surfaces later as a confusing, unrelated 500.
            System.err.println("DATABASE_SCHEMA_INIT: createMissingTablesAndColumns failed, continuing with existing schema: ${e.message}")
            e.printStackTrace()
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
            Users,
            ConversationEngines,
            Skills,
            MemoryEngines,
            IntentEngines,
            AiSettings,
            AdminAllowlist,
            PersonaIdentity,
            PersonaVisualVersions,
            PersonaVisualWardrobeItems,
            PersonaVisualReferenceImages,
            ImageJobs,
            GeneratedCandidates,
            Personas,
            PersonaCoreVersions,
            UserProfiles,
            MemoryFacts,
            SensitivePreferences,
            Entitlements,
            Conversations,
            Messages,
            ChatRequestExecutions,
            LlmExchanges,
        )
    }
}

// Owned by the partner's auth system in production (see CLAUDE.md); this is a
// read-only reference to the existing table (id UUID PRIMARY KEY), used only to
// check whether an authenticated user id is real before creating a conversation
// for it. No FK constraint is added elsewhere in this schema for that column —
// doing so would require every existing test/fixture across the codebase to
// pre-register a user row, which is out of scope for this fix.
object Users : Table("users") {
    val id = uuid("id")

    override val primaryKey = PrimaryKey(id)
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

// Phase D — Memory Engine: an independently-versioned prompt/configuration
// controlling memory-maintenance LLM behavior, structurally identical to
// ConversationEngines (single global active row) — a different concern
// (memory maintenance vs. universal conversational rules), never merged.
object MemoryEngines : Table("memory_engines") {
    val id = uuid("id")
    val version = integer("version").uniqueIndex()
    val content = text("content")
    val status = text("status").default("draft")
    val isActive = bool("is_active").default(false)
    val changelogNote = text("changelog_note").nullable()
    val createdBy = text("created_by").nullable()
    val createdAt = datetime("created_at")
    // Operational configuration travels WITH the versioned engine rather than
    // living in free-form prompt text or as scattered constants: activating a
    // Memory Engine version activates its batch/target settings atomically.
    // Defaults match the documented baseline (10 / 20). RELEVANT_MEMORY_TARGET
    // is a target the engine aims for, never a hard cap enforced in SQL.
    val batchSize = integer("batch_size").default(10)
    val relevantMemoryTarget = integer("relevant_memory_target").default(20)

    override val primaryKey = PrimaryKey(id)
}

// Phase ADMIN-2 — Intent Engine: the versioned prompt/configuration that
// governs how the current interaction intent is identified (which Skill is
// selected). Structurally identical to MemoryEngines/ConversationEngines
// (single global active row). It is a CONTROL-PLANE prompt: it is never
// injected into response generation, and it never contains the skill
// catalogue — the active candidate keys are supplied by the runtime.
object IntentEngines : Table("intent_engines") {
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

// Phase ADMIN-2 — AI runtime settings. Deliberately a SINGLE-ROW table
// (SINGLETON_ID) rather than a versioned entity: unlike the engines, these are
// not reviewed-and-promoted prompt content but live operational knobs, and the
// existing lifecycle (draft/publish/activate) would add ceremony without
// meaning. Every column is NULLABLE on purpose — null means "not configured
// here", which falls through to the environment variable and then the
// application default, so an empty table reproduces today's behavior exactly.
object AiSettings : Table("ai_settings") {
    val id = uuid("id")
    val model = text("model").nullable()
    val temperature = double("temperature").nullable()
    val maxOutputTokens = integer("max_output_tokens").nullable()
    // Task 9 — Admin AI Runtime Controls. Same nullable-means-"not configured
    // here, fall back to the code default" convention as the three columns
    // above; see AiRuntimeSettings.resolve() for the single authoritative
    // resolution path these feed into.
    val intentModel = text("intent_model").nullable()
    val intentJsonMode = bool("intent_json_mode").nullable()
    val intentMaxOutputTokens = integer("intent_max_output_tokens").nullable()
    val generationProviderSort = text("generation_provider_sort").nullable()
    val updatedAt = datetime("updated_at")
    val updatedBy = text("updated_by").nullable()

    override val primaryKey = PrimaryKey(id)

    /** The one and only row. Fixed so the table can never grow a second. */
    val SINGLETON_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a151")
}

// DB-backed admin allowlist. Purely ADDITIVE alongside the ADMIN_USER_IDS
// environment variable, which keeps working exactly as before: AdminAuthorization
// Provider takes the UNION of the two, so an empty table reproduces today's
// behavior for every existing deployment. A brand-new table, so nothing
// existing is altered.
object AdminAllowlist : Table("admin_allowlist") {
    val userId = uuid("user_id")
    val note = text("note").nullable()
    val addedBy = text("added_by").nullable()
    val addedAt = datetime("added_at")

    override val primaryKey = PrimaryKey(userId)
}

// Phase B — Skill Foundation. Unlike ConversationEngines (one global active
// row), Skills are versioned PER KEY: multiple distinct keys (e.g. "flirting",
// "friendship") can each independently have their own active version at the
// same time. Uniqueness is therefore (key, version), not version alone.
object Skills : Table("skills") {
    val id = uuid("id")
    val key = text("key")
    val version = integer("version")
    val content = text("content")
    val status = text("status").default("draft")
    val isActive = bool("is_active").default(false)
    val changelogNote = text("changelog_note").nullable()
    val author = text("author").nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(key, version)
    }
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

object GeneratedCandidates : Table("generated_candidates") {
    val id = uuid("id")
    val imageJobId = uuid("image_job_id")
    val storageKey = varchar("storage_key", 1024)
    val contentType = varchar("content_type", 100)
    val fileSize = long("file_size")
    val widthPx = integer("width_px").nullable()
    val heightPx = integer("height_px").nullable()
    val checksum = varchar("checksum", 256)
    val candidateIndex = integer("candidate_index")
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
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
    // Phase ADMIN-2 — persona PROFILE metadata: descriptive information about
    // the persona, deliberately separate from the Persona Core (which defines
    // who the character is and how she behaves) and never injected as prompt
    // text by these columns alone. gender/orientation/apparentAge already exist
    // above and are NOT duplicated here.
    val bio = text("bio").nullable()
    val city = varchar("city", 255).nullable()
    val occupation = varchar("occupation", 255).nullable()
    val interests = text("interests").nullable()
    // Stored as a JSON array of strings in one column rather than a join table:
    // tags are a small, display-oriented, wholly-replaced list, and a linked
    // table would add a repository and lifecycle for no query we actually make.
    val tags = text("tags").default("[]")
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

    init {
        // Server-computed version numbers (PersonaCoreVersionRepository.createNextVersion)
        // rely on this as the safety net under concurrent creation — see that method's
        // retry-on-conflict handling. Mirrors the existing composite-unique pattern
        // already used by ImageJobs (personaVisualVersionId, idempotencyKey).
        uniqueIndex(personaId, version)
    }
}

object UserProfiles : Table("user_profiles") {
    val userId = uuid("user_id")
    val displayName = varchar("display_name", 255).nullable()
    val preferredLanguage = varchar("preferred_language", 50).nullable()
    val communicationStyle = varchar("communication_style", 255).nullable()
    // Added for Admin Console Phase 2A (test-user creation). Free text.
    val gender = varchar("gender", 50).nullable()
    // Attraction/preference, NOT gender. Closed set enforced in UserProfileRepository: male/female/both.
    val interest = varchar("interest", 10).nullable()
    val city = varchar("city", 255).nullable()
    val age = integer("age").nullable()
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

object MemoryFacts : Table("memory_facts") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val personaId = uuid("persona_id")
    val fact = text("fact")
    // These five columns are TEXT (not VARCHAR) in the real production schema —
    // deliberately matched exactly here, not merely "close enough". A VARCHAR(N)
    // declaration that doesn't match the live column's actual type makes Exposed's
    // schema-diff believe a migration is needed and issue `ALTER COLUMN ... TYPE
    // VARCHAR(N)` on every startup. For `criticality` specifically this ALTER
    // fails outright — Postgres refuses to retype a column that a GENERATED
    // column (`criticality_rank`, computed from `criticality`) depends on — and
    // because the entire createMissingTablesAndColumns() call runs inside one
    // transaction, that single failure rolled back every other pending schema
    // change in the same run (including, historically, the UserProfiles
    // gender/interest/city/age columns never actually landing in production).
    val factType = text("fact_type")
    val criticality = text("criticality").default("medium")
    // Deliberately NOT declared here: `criticality_rank` is a Postgres
    // GENERATED ALWAYS AS (...) STORED column in production, computed from
    // `criticality`. Exposed has no concept of generated columns — declaring it
    // as a normal nullable column makes the schema-diff issue a plain `ALTER
    // COLUMN ... TYPE / DROP DEFAULT`, which Postgres always rejects for a
    // generated column. The app never needs to manage this column: it's
    // read-only, MemoryFactRepository never writes it, and MemoryService.rank()
    // already computes the identical mapping in Kotlin whenever it's absent.
    val tier = text("tier").default("hot")
    // "open" | "resolved" | "superseded" | "removed" — no enforced DB constraint
    // (there never was one for this column); "superseded"/"removed" are new
    // values introduced for Phase D's Memory Engine write path, additive only.
    val status = text("status").default("open")
    val factSource = text("source").default("llm_extracted")
    val learnedAt = datetime("learned_at")
    val lastReferencedAt = datetime("last_referenced_at").nullable()
    val evictedAt = datetime("evicted_at").nullable()
    // Phase D additions — all TEXT/nullable-with-default so an existing
    // production memory_facts table (which predates this column set, see the
    // criticality/criticality_rank note above) gets these ADDED via a plain
    // ADD COLUMN, never an ALTER COLUMN TYPE that could hit the same
    // generated-column class of failure.
    // "USER" | "PERSONA" — see MemoryService.OWNERS. Defaults to "USER" so
    // every pre-Phase-D row is unambiguously owned by the user, unchanged.
    val owner = text("owner").default("USER")
    val supersedesId = uuid("supersedes_id").nullable()
    val updatedAt = datetime("updated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

// Deliberately separate from MemoryFacts: sensitive/intimacy preferences need a
// clear semantic boundary (explicit-only writes, preference-vs-boundary-vs-history
// typing, easy selective/relevance-gated retrieval) that would otherwise mean
// overloading MemoryFacts' generic fact_type/tier machinery for a category of
// data that must never be silently inferred or mixed into ordinary memory.
object SensitivePreferences : Table("sensitive_preferences") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val personaId = uuid("persona_id")
    // "romantic" | "intimacy" | "sexual" — see SensitivePreferenceRepository.CATEGORIES.
    val category = varchar("category", 20)
    // "preference" | "boundary" | "history" — see SensitivePreferenceRepository.TYPES.
    val preferenceType = varchar("preference_type", 20)
    val content = text("content")
    // "explicit" | "inferred" — see SensitivePreferenceRepository.PROVENANCES. Only
    // "explicit" is ever written by any current code path (see Phase 4B report).
    val provenance = varchar("provenance", 10)
    val preferenceSource = varchar("source", 20).default("user_stated")
    // "active" | "superseded" | "deleted"
    val status = varchar("status", 20).default("active")
    val supersedesId = uuid("supersedes_id").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

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
    // Compact, bounded rolling summary of conversation content that has scrolled
    // outside the active recent-message window (see RepositoryContextAssembler's
    // message limit). Null/blank until a conversation actually exceeds that window.
    val continuitySummary = text("continuity_summary").nullable()
    // Count of messages (from the start of the conversation, chronological) already
    // folded into continuitySummary — lets continuity generation pick up exactly
    // where it left off instead of re-processing or duplicating prior content.
    val continuitySummaryCoveredCount = integer("continuity_summary_covered_count").default(0)
    // Phase D — Memory Engine batch cursor. Count of messages (from the start
    // of the conversation, chronological) already included in a completed
    // Memory Engine maintenance batch. Mirrors continuitySummaryCoveredCount's
    // exact pattern/purpose but is an independent cursor — the chat context's
    // "last 10 messages" and the Memory Engine's "next unprocessed batch" are
    // different concepts and must not share a cursor.
    val memoryEngineProcessedCount = integer("memory_engine_processed_count").default(0)

    // Phase ADMIN-3 — Test/Production execution mode + configuration snapshot.
    // "PRODUCTION" (the default, so every pre-existing row and every row created
    // by the normal /v1/conversations API is unaffected) or "TEST". A TEST
    // conversation carries its own immutable snapshot of exactly which versions
    // were used, so it keeps working identically even after production activates
    // different versions later. All snapshot columns are nullable and meaningful
    // ONLY when executionMode == "TEST" — a PRODUCTION conversation always
    // resolves the currently active version of everything, exactly as before
    // this phase.
    val executionMode = varchar("execution_mode", 20).default("PRODUCTION")
    val snapshotConversationEngineVersion = integer("snapshot_conversation_engine_version").nullable()
    val snapshotPersonaCoreVersion = integer("snapshot_persona_core_version").nullable()
    val snapshotIntentEngineVersion = integer("snapshot_intent_engine_version").nullable()
    val snapshotMemoryEngineVersion = integer("snapshot_memory_engine_version").nullable()
    // JSON object: skill key -> version, e.g. {"friendship":2,"flirting":4}. Only
    // the skills named here are candidates for the test conversation's Intent
    // Engine — never the full historical catalogue.
    val snapshotSkillVersionsJson = text("snapshot_skill_versions_json").nullable()
    // A real, hidden Personas row created for this TEST conversation alone —
    // required because memory_facts.persona_id carries a real foreign key to
    // personas(id) on PostgreSQL; a merely-derived UUID fails that constraint.
    // See MemoryScopeResolver.TestMemoryScope.
    val snapshotMemoryScopePersonaId = uuid("snapshot_memory_scope_persona_id").nullable()
    val snapshotModel = text("snapshot_model").nullable()
    val snapshotTemperature = double("snapshot_temperature").nullable()
    val snapshotMaxOutputTokens = integer("snapshot_max_output_tokens").nullable()
    // Intent Discovery Model Latency Investigation phase: a SEPARATE,
    // independent override from snapshotModel above. snapshotModel governs
    // primary generation and side-channel calls (via AiRuntimeSettings);
    // Intent Discovery never read that field and had no per-workload
    // override at all — see ChatEngineFactory. Null (the default) means
    // Intent Discovery keeps using the same model as everything else,
    // exactly as before this field existed.
    val snapshotIntentModel = text("snapshot_intent_model").nullable()
    // Intent Discovery Budget Investigation phase: independent from
    // snapshotMaxOutputTokens above, which governs primary generation only.
    val snapshotIntentMaxOutputTokens = integer("snapshot_intent_max_output_tokens").nullable()
    // Make Intent Discovery Fast + Reliable phase: independent from every
    // other snapshot field above.
    val snapshotIntentJsonMode = bool("snapshot_intent_json_mode").nullable()
    // Primary Generation Latency phase: governs PRIMARY generation's
    // provider-routing preference only — independent of every Intent-only
    // field above.
    val snapshotGenerationProviderSort = text("snapshot_generation_provider_sort").nullable()

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

// LLM Observability and Raw Exchange Capture phase — NEW BACKEND WORK. No
// prior table captured a raw LLM exchange for anything other than the
// primary generation call (and only inside messages.metadata, one row per
// TURN, not per LLM call). Intent, memory extraction, continuity, and
// memory-engine maintenance had no persisted record at all — only
// non-persisted, non-queryable System.err log lines. This table is the
// single, uniform record for EVERY LlmClient.generate() call across every
// workload, written by the ObservableLlmClient decorator (see
// com.pinkdreams.llm.observability), which wraps the shared LlmClient once
// in ChatEngineFactory.build() so no call site needs to change how it
// invokes the client.
object LlmExchanges : Table("llm_exchanges") {
    val id = uuid("id")
    // The ChatRequest.requestId shared by every LLM call made during one
    // turn (Intent + generation + async memory calls all reuse it) — this is
    // what lets exchanges be reconstructed per-turn, not just per-call.
    val turnRequestId = uuid("turn_request_id")
    val conversationId = uuid("conversation_id")
    val workload = varchar("workload", 64)
    val isTestChat = bool("is_test_chat").default(false)
    val model = text("model").nullable()
    val provider = varchar("provider", 128).nullable()
    val latencyMs = long("latency_ms")
    val promptTokens = integer("prompt_tokens").nullable()
    val completionTokens = integer("completion_tokens").nullable()
    val reasoningTokens = integer("reasoning_tokens").nullable()
    val totalTokens = integer("total_tokens").nullable()
    val finishReason = varchar("finish_reason", 64).nullable()
    val httpStatusCode = integer("http_status_code").nullable()
    // outcome distinguishes what the previous phase's log-only diagnostics
    // could only approximate: a genuine provider/network failure (isError
    // on the HTTP response, or an exception before any response arrived) is
    // NOT the same thing as a reasoning model exhausting its token budget
    // (OpenRouterBudgetExhaustionException), which is NOT the same thing as
    // a successful HTTP response containing text that fails to parse as the
    // expected shape (malformed output) — all three previously collapsed
    // into "no LlmResponse", indistinguishable from each other.
    val outcome = varchar("outcome", 32) // SUCCESS | BUDGET_EXHAUSTION | PROVIDER_ERROR | EXCEPTION
    val errorClass = varchar("error_class", 255).nullable()
    val errorMessage = text("error_message").nullable()
    // Raw bodies, exactly as exchanged — requestHeaders is captured
    // pre-redacted at the OpenRouterLlmClient call site (never contains the
    // Authorization header); see ProviderExchange's own doc comment, which
    // this table's writer relies on rather than re-implementing redaction.
    val requestBody = text("request_body").nullable()
    val responseBody = text("response_body").nullable()
    val createdAt = datetime("created_at")
    // Task 8 Part 4 — the smallest safe structured field needed to associate
    // an exchange with the skill SkillSelection actually chose for this
    // turn, sourced directly from the existing SkillSelection.Selected(key)
    // the pipeline already computes (LlmIntentDiscovery/ChatEngine) — never
    // inferred from free text, never a new routing decision. Null means
    // either SkillSelection.None (a genuine "no skill" outcome) or an
    // exchange recorded before this column existed; the two are
    // deliberately indistinguishable here (see the Task 8 report) — the
    // dashboard must treat both as "no attribution available", not invent a
    // difference the data doesn't support.
    val skillKey = varchar("skill_key", 128).nullable()

    override val primaryKey = PrimaryKey(id)
}

fun defaultNow(): LocalDateTime = LocalDateTime.now()
