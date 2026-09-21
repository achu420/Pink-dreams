package com.pinkdreams.api.admin

/**
 * Admin export CSV quoting, factored out so every admin CSV export uses the
 * SAME escaping rule rather than a naive comma-join that corrupts the file the
 * first time a user-generated value contains a comma, a quote or a newline.
 *
 * The rule is RFC-4180's: a field is wrapped in double quotes only when it
 * actually contains a delimiter, a quote or a line break, and an embedded
 * quote is doubled. This mirrors the quoting
 * [AdminObservabilityRoutes]'s pre-existing `/export/exchanges.csv` already
 * applies (that endpoint is deliberately left untouched); the only addition
 * here is that a lone carriage return also forces quoting, since memory facts
 * and message content are free text typed by real people and can contain one.
 */
internal object AdminCsv {

    fun escape(value: String?): String {
        val raw = value ?: return ""
        val needsQuoting = raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"" + raw.replace("\"", "\"\"") + "\"" else raw
    }

    fun row(values: List<Any?>): String = values.joinToString(",") { escape(it?.toString()) }

    /** Header + rows, CRLF-free (`\n`-separated), with every field escaped. */
    fun document(header: List<String>, rows: List<List<Any?>>): String =
        (listOf(row(header)) + rows.map { row(it) }).joinToString("\n")
}
