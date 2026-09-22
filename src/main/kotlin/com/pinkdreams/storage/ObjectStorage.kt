package com.pinkdreams.storage

import java.util.UUID

data class StorageObject(
    val key: String,
    val content: ByteArray,
    val contentType: String,
    val size: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StorageObject) return false

        if (key != other.key) return false
        if (!content.contentEquals(other.content)) return false
        if (contentType != other.contentType) return false
        if (size != other.size) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + content.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + size.hashCode()
        return result
    }
}

data class StorageMetadata(
    val key: String,
    val contentType: String,
    val size: Long
)

interface ObjectStorage {
    fun store(key: String, content: ByteArray, contentType: String): StorageMetadata

    fun retrieve(key: String): StorageObject?

    fun delete(key: String): Boolean

    fun exists(key: String): Boolean

    /** Optional readiness probe for ops diagnostics. Default: unsupported. */
    fun probeReadiness(): StorageReadiness = StorageReadiness(
        mode = "unknown",
        rootLabel = null,
        readable = false,
        writable = false,
        probeOk = false,
        detail = "Probe not implemented for this storage backend",
    )
}

data class StorageReadiness(
    val mode: String,
    val rootLabel: String?,
    val readable: Boolean,
    val writable: Boolean,
    val probeOk: Boolean,
    val detail: String,
)

fun buildStorageKey(personaIdentityId: UUID, visualVersionId: UUID, referenceId: UUID): String {
    return "personas/$personaIdentityId/visual-versions/$visualVersionId/references/$referenceId"
}
