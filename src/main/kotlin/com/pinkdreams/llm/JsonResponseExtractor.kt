package com.pinkdreams.llm

/**
 * Extracts the JSON object out of a model response for the structured
 * side-channel calls (memory extraction, intent discovery, memory-engine
 * maintenance, continuity summarization).
 *
 * Why this exists: reasoning-capable models do not reliably confine their
 * reasoning to the provider's `reasoning` field. They frequently prepend it to
 * `content` as plain prose and then emit the JSON, e.g.
 *
 *   `...This is a temporary state. So nothing to remember. {"facts": []}`
 *
 * Requiring the ENTIRE response to be valid JSON therefore threw away
 * perfectly good answers, and — because every one of those parsers treats a
 * parse failure as "nothing found" — did so silently. That is the root cause
 * of memory extraction appearing to do nothing while reporting no error.
 *
 * Deliberately narrow: this only locates the JSON span. It does not parse,
 * validate, repair, or reshape it — each caller still decodes into its own DTO
 * and applies its own validation, so a genuinely malformed response still
 * fails at the caller exactly as before.
 */
object JsonResponseExtractor {

    /**
     * Returns the LAST balanced top-level JSON object in [raw], or null if
     * there is none.
     *
     * Last, not first: when a model narrates its reasoning it may mention a
     * candidate object mid-sentence before committing to its final answer, and
     * the final answer is what it emits last.
     *
     * Brace matching is string- and escape-aware, so a `{` or `}` inside a JSON
     * string value cannot throw the scan off.
     */
    fun extractJsonObject(raw: String): String? {
        val text = stripMarkdownFences(raw)
        var lastMatch: String? = null
        var index = 0
        while (index < text.length) {
            if (text[index] != '{') {
                index++
                continue
            }
            val end = findMatchingBrace(text, index)
            if (end == -1) {
                // Unterminated from here on — nothing further can balance either.
                break
            }
            lastMatch = text.substring(index, end + 1)
            index = end + 1
        }
        return lastMatch
    }

    /** Index of the `}` closing the `{` at [start], or -1 if unbalanced. */
    private fun findMatchingBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun stripMarkdownFences(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.startsWith("```")) {
            trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        } else {
            trimmed
        }
    }
}
