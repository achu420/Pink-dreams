package com.pinkdreams.imaging.orchestration

import java.time.LocalDateTime
import java.util.UUID

data class GeneratedCandidate(
    val id: UUID,
    val imageJobId: UUID,
    val storageKey: String,
    val contentType: String,
    val fileSize: Long,
    val widthPx: Int?,
    val heightPx: Int?,
    val checksum: String,
    val candidateIndex: Int,
    val createdAt: LocalDateTime,
    val status: CandidateStatus = CandidateStatus.GENERATED,
    val adminRemark: String? = null,
)
