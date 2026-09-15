package com.pinkdreams.common.errors

import kotlinx.serialization.Serializable

@Serializable
data class ApiError(
    val code: ErrorCode,
    override val message: String,
    val requestId: String? = null,
) : RuntimeException(message) {
    val httpStatus: HttpStatus = when (code) {
        ErrorCode.VALIDATION_ERROR -> HttpStatus.BAD_REQUEST
        ErrorCode.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED
        ErrorCode.NOT_FOUND -> HttpStatus.NOT_FOUND
        ErrorCode.ENTITLEMENT_DENIED -> HttpStatus.FORBIDDEN
        ErrorCode.MODERATION_BLOCKED -> HttpStatus.BAD_REQUEST
        ErrorCode.GENERATION_FAILED -> HttpStatus.BAD_GATEWAY
        ErrorCode.VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY
        ErrorCode.PERSIST_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR
        ErrorCode.DELIVERY_FAILED -> HttpStatus.ACCEPTED
        ErrorCode.INTERNAL_SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
    }
}
