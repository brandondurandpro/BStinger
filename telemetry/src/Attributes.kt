package org.example.kotlineditor.telemetry

/**
 * The only attributes a measure may carry, each with a bounded set of values: never a path, a file name, a content,
 * a search query nor a branch name (docs/telemetry.md lists them). [allowed] is the closed set of values, null for
 * names from a catalog of the code (methods, languages), which [Attributes] still checks the shape of.
 */
enum class Attribute(val key: String, val allowed: Set<String>? = null) {
    /** The protocol method of an RPC (`fs/read`), or the LSP method of a language server request. */
    Method("method"),

    /** Whether the request survived a reconnection (sent again on the new connection). */
    Retried("retried", setOf("true", "false")),

    /** The language of the file (`php`, `kotlin`), from the IDE's catalog of languages. */
    Language("language"),

    /** A size bucket, see [sizeBucket]. */
    Size("size", SIZE_BUCKETS.map { it.second }.toSet() + LARGEST_BUCKET),

    /** Whether the project is on this machine or on a remote one. */
    Location("location", setOf("local", "remote")),

    /** What an operation found, when it can find nothing. */
    Result("result", setOf("found", "empty", "partial")),
}

private val SIZE_BUCKETS =
    listOf(
        1_000L to "<1k",
        10_000L to "<10k",
        100_000L to "<100k",
        1_000_000L to "<1M",
        10_000_000L to "<10M",
    )
private const val LARGEST_BUCKET = ">=10M"

/** The bucket of [size] (bytes, lines, files or results): `<1k`, `<10k`, `<100k`, `<1M`, `<10M` or `>=10M`. */
fun sizeBucket(size: Long): String = SIZE_BUCKETS.firstOrNull { size < it.first }?.second ?: LARGEST_BUCKET

// A catalog name: short, of letters, digits and a few separators, with at most one slash (`lsp/hover`), so that a
// path or a sentence cannot pass for one.
private val NAME = Regex("[A-Za-z][A-Za-z0-9_.$-]{0,30}(/[A-Za-z0-9_.$-]{1,30})?")

/** What a value that is not allowed becomes. */
const val REDACTED = "other"

/** The attributes of a measure, checked against [Attribute]: a value out of its set becomes [REDACTED]. */
class Attributes private constructor(private val values: Map<Attribute, String>) {
    /** The value of [attribute], null when absent. */
    operator fun get(attribute: Attribute): String? = values[attribute]

    /** Each attribute and its value. */
    fun forEach(action: (Attribute, String) -> Unit) = values.forEach(action)

    /** These attributes and [other]'s, [other] winning. */
    operator fun plus(other: Attributes): Attributes =
        when {
            other.values.isEmpty() -> this
            values.isEmpty() -> other
            else -> Attributes(values + other.values)
        }

    override fun equals(other: Any?): Boolean = other is Attributes && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = values.entries.joinToString(prefix = "{", postfix = "}") { "${it.key.key}=${it.value}" }

    companion object {
        /** No attribute. */
        val NONE = Attributes(emptyMap())

        /** [pairs] checked: a value out of the set of its attribute, or not shaped like a name, becomes [REDACTED]. */
        fun of(vararg pairs: Pair<Attribute, String>): Attributes =
            if (pairs.isEmpty()) NONE else Attributes(pairs.associate { (attribute, value) -> attribute to checked(attribute, value) })

        /** Whether [value] may be sent as the value of [attribute]. */
        fun allows(attribute: Attribute, value: String): Boolean {
            val allowed = attribute.allowed
            return if (allowed != null) value in allowed else NAME.matches(value)
        }

        private fun checked(attribute: Attribute, value: String): String = if (allows(attribute, value)) value else REDACTED
    }
}
