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
}

fun buildStorageKey(personaIdentityId: UUID, visualVersionId: UUID, referenceId: UUID): String {
    return "personas/$personaIdentityId/visual-versions/$visualVersionId/references/$referenceId"
}
