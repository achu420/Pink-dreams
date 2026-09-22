package com.pinkdreams.imaging.storage

import com.pinkdreams.storage.LocalFileObjectStorage
import com.pinkdreams.storage.ObjectStorage
import com.pinkdreams.storage.StorageReadiness

/**
 * Operational view of image object storage for multi-instance readiness.
 * Never includes absolute filesystem paths or credentials.
 */
data class ImageStorageDiagnostics(
    val mode: String,
    val multiInstanceContract: String,
    val operatorDeclaredShared: Boolean,
    val probe: StorageReadiness,
    val guidance: String,
) {
    companion object {
        fun from(storage: ObjectStorage): ImageStorageDiagnostics {
            val probe = storage.probeReadiness()
            val declaredShared = System.getenv("IMAGE_STORAGE_SHARED")?.equals("true", ignoreCase = true) == true
            val mode = when (storage) {
                is LocalFileObjectStorage -> "local"
                else -> probe.mode
            }
            val contract = when (mode) {
                "local" -> "requires_shared_filesystem"
                "memory" -> "single_instance_only"
                else -> "unknown"
            }
            val guidance = when {
                mode == "memory" ->
                    "IMAGE_STORAGE=memory is for tests only. Multi-instance production requires local+shared IMAGE_STORAGE_DIR."
                mode == "local" && declaredShared ->
                    "Operator declared IMAGE_STORAGE_SHARED=true. All instances must mount the same IMAGE_STORAGE_DIR."
                mode == "local" ->
                    "Local file storage. Multi-instance is only safe when every instance shares the same IMAGE_STORAGE_DIR (NFS/SMB/volume). Set IMAGE_STORAGE_SHARED=true after verifying the mount."
                else ->
                    "Unknown storage mode; verify deployment configuration."
            }
            return ImageStorageDiagnostics(
                mode = mode,
                multiInstanceContract = contract,
                operatorDeclaredShared = declaredShared,
                probe = probe,
                guidance = guidance,
            )
        }
    }
}
