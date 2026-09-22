package com.pinkdreams.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * Durable filesystem-backed [ObjectStorage].
 *
 * Keys are restricted to relative path segments (no `..`, no absolute paths)
 * to prevent path traversal outside [rootDirectory].
 */
class LocalFileObjectStorage(
    rootDirectory: Path,
    private val maxBytes: Long = 20L * 1024 * 1024,
) : ObjectStorage {

    private val root: Path = rootDirectory.toAbsolutePath().normalize().also {
        it.createDirectories()
    }

    override fun store(key: String, content: ByteArray, contentType: String): StorageMetadata {
        require(content.isNotEmpty()) { "Content cannot be empty" }
        require(content.size.toLong() <= maxBytes) {
            "Content exceeds max size of $maxBytes bytes"
        }
        require(contentType.isNotBlank()) { "contentType cannot be blank" }
        validateContentType(contentType)

        val target = resolveSafe(key)
        target.parent?.createDirectories()
        val tmp = Files.createTempFile(target.parent, ".upload-", ".tmp")
        try {
            Files.write(
                tmp,
                content,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                Files.move(
                    tmp,
                    target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
        // Sidecar for content type (simple, no external metadata store required)
        Files.writeString(
            contentTypeSidecar(target),
            contentType.trim(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return StorageMetadata(key = normalizeKey(key), contentType = contentType.trim(), size = content.size.toLong())
    }

    override fun retrieve(key: String): StorageObject? {
        val target = resolveSafe(key)
        if (!target.exists() || !target.isRegularFile()) return null
        val bytes = Files.readAllBytes(target)
        val ct = contentTypeSidecar(target).takeIf { it.exists() }?.let { Files.readString(it).trim() }
            ?: "application/octet-stream"
        return StorageObject(key = normalizeKey(key), content = bytes, contentType = ct, size = bytes.size.toLong())
    }

    override fun delete(key: String): Boolean {
        val target = resolveSafe(key)
        val sidecar = contentTypeSidecar(target)
        var deleted = false
        if (target.exists()) {
            Files.delete(target)
            deleted = true
        }
        if (sidecar.exists()) {
            Files.delete(sidecar)
        }
        return deleted
    }

    override fun exists(key: String): Boolean {
        val target = resolveSafe(key)
        return target.exists() && target.isRegularFile()
    }

    fun listKeysWithPrefix(prefix: String): List<String> {
        val normalizedPrefix = normalizeKey(prefix)
        if (!Files.exists(root)) return emptyList()
        val results = mutableListOf<String>()
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && !it.fileName.toString().endsWith(".contentType") }
                .forEach { path ->
                    val relative = root.relativize(path).toString().replace('\\', '/')
                    if (relative.startsWith(normalizedPrefix)) {
                        results.add(relative)
                    }
                }
        }
        return results
    }

    private fun contentTypeSidecar(file: Path): Path =
        file.resolveSibling(file.fileName.toString() + ".contentType")

    private fun normalizeKey(key: String): String =
        key.trim().trimStart('/').replace('\\', '/')

    private fun resolveSafe(key: String): Path {
        val normalized = normalizeKey(key)
        require(normalized.isNotBlank()) { "Storage key cannot be blank" }
        require(!normalized.contains("..")) { "Storage key must not contain '..'" }
        require(normalized.none { it.code < 32 }) { "Storage key contains control characters" }
        val resolved = root.resolve(normalized).normalize()
        require(resolved.startsWith(root)) { "Storage key escapes root directory" }
        return resolved
    }

    private fun validateContentType(contentType: String) {
        val allowed = setOf(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/webp",
            "image/gif",
            "application/octet-stream",
        )
        val base = contentType.trim().lowercase().substringBefore(';').trim()
        require(base in allowed) { "Unsupported content type: $contentType" }
    }

    companion object {
        fun fromEnv(defaultDir: String = "data/object-storage"): LocalFileObjectStorage {
            val dir = System.getenv("IMAGE_STORAGE_DIR") ?: defaultDir
            val max = System.getenv("IMAGE_STORAGE_MAX_BYTES")?.toLongOrNull() ?: (20L * 1024 * 1024)
            return LocalFileObjectStorage(Path.of(dir), maxBytes = max)
        }
    }
}
