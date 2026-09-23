package com.pinkdreams.imaging.benchmark

object ProviderFailureClassifier {
    fun classify(message: String?): String {
        val m = message?.lowercase().orEmpty()
        if (m.contains("unsupported") || m.contains("does not support") || m.contains("maximum") && m.contains("candidate")) {
            return "UNSUPPORTED_PARAMETER"
        }
        if (m.contains("model") && (m.contains("not found") || m.contains("unavailable") || m.contains("unknown model"))) {
            return "MODEL_UNAVAILABLE"
        }
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
            return "PROVIDER_REJECTED"
        }
        return "TECHNICAL_FAILURE"
    }
}
