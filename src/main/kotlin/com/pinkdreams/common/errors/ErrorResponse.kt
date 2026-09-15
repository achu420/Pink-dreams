package com.pinkdreams.common.errors

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: ApiError,
)
