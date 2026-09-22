package com.pinkdreams.storage

class InMemoryObjectStorage : ObjectStorage {
    private val storage = mutableMapOf<String, StorageObject>()

    override fun store(key: String, content: ByteArray, contentType: String): StorageMetadata {
        val obj = StorageObject(
            key = key,
            content = content.copyOf(),
            contentType = contentType,
            size = content.size.toLong()
        )
        storage[key] = obj
        return StorageMetadata(key, contentType, obj.size)
    }

    override fun retrieve(key: String): StorageObject? {
        return storage[key]?.copy(content = storage[key]!!.content.copyOf())
    }

    override fun delete(key: String): Boolean {
        return storage.remove(key) != null
    }

    override fun exists(key: String): Boolean {
        return storage.containsKey(key)
    }

    override fun probeReadiness(): StorageReadiness {
        val key = ".health-probe/inmem"
        val payload = byteArrayOf(9, 8, 7)
        store(key, payload, "application/octet-stream")
        val got = retrieve(key)
        delete(key)
        val ok = got != null && got.content.contentEquals(payload)
        return StorageReadiness(
            mode = "memory",
            rootLabel = null,
            readable = true,
            writable = true,
            probeOk = ok,
            detail = "In-memory storage is single-process only; not multi-instance safe",
        )
    }

    fun clear() {
        storage.clear()
    }

    fun getSize(): Int {
        return storage.size
    }
}
