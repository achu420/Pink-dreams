package com.pinkdreams.imaging.benchmark

object ProviderFailureClassifier {
    enum class FailureCategory {
        PROVIDER_SAFETY_REJECTED,
        INSUFFICIENT_CREDITS,
        INVALID_REQUEST,
        UNSUPPORTED_PARAMETER,
        TIMEOUT,
        TECHNICAL_FAILURE,
    }

    fun classify(message: String?): FailureCategory {
        val m = message?.lowercase().orEmpty()

        if (
            m.contains("safety") ||
            m.contains("moderat") ||
            m.contains("content policy") ||
            m.contains("rejected") ||
            m.contains("refused") ||
            m.contains("not allowed") ||
            m.contains("sexual") ||
            m.contains("policy")
        ) {
            return FailureCategory.PROVIDER_SAFETY_REJECTED
        }

        if (m.contains("insufficient") && m.contains("credit")) {
            return FailureCategory.INSUFFICIENT_CREDITS
        }

        if (m.contains("unsupported") || m.contains("does not support") || m.contains("maximum") && m.contains("candidate")) {
            return FailureCategory.UNSUPPORTED_PARAMETER
        }

        if (m.contains("model") && (m.contains("not found") || m.contains("unavailable") || m.contains("unknown model"))) {
            return FailureCategory.UNSUPPORTED_PARAMETER
        }

        if (m.contains("timeout") || m.contains("timed out") || m.contains("took too long")) {
            return FailureCategory.TIMEOUT
        }

        if (m.contains("invalid") || m.contains("validation") || m.contains("bad request")) {
            return FailureCategory.INVALID_REQUEST
        }

        return FailureCategory.TECHNICAL_FAILURE
    }

    fun classifyAsString(message: String?): String = classify(message).name
}
