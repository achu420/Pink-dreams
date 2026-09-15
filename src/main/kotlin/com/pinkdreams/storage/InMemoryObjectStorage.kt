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

    fun clear() {
        storage.clear()
    }

    fun getSize(): Int {
        return storage.size
    }
}
