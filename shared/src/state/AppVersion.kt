package org.example.kotlineditor.state

import java.math.BigInteger

/**
 * A version such as `1.2.3`, or `1.2.3-rc1`: what the tags `v1.2.3` of the GitHub releases carry. Everything it was
 * read from stays available: the [text] as given, its [numbers] and its [preRelease] suffix.
 *
 * Two versions are equal when they compare equal, whatever their text: `1.2` and `v1.2.0` are the same version.
 */
class AppVersion private constructor(
    /** The text this version was read from, as given: `v1.2.3-rc1`. */
    val text: String,
    /** The dot separated numbers: `[1, 2, 3]` for `1.2.3-rc1`. */
    val numbers: List<Int>,
    /** What follows the dash (`rc1`), null for a release. */
    val preRelease: String?,
) : Comparable<AppVersion> {
    /** Whether this is a pre-release (`1.2.3-rc1`): older than `1.2.3`. */
    val isPreRelease: Boolean get() = preRelease != null

    override fun compareTo(other: AppVersion): Int {
        val byNumbers = compareNumbers(numbers, other.numbers)
        if (byNumbers != 0) return byNumbers
        return comparePreReleases(preRelease, other.preRelease)
    }

    override fun equals(other: Any?): Boolean = other is AppVersion && compareTo(other) == 0

    // Equal versions hash alike: trailing zeros do not count, nor how a pre-release is written (rc01 is rc1).
    override fun hashCode(): Int {
        val significant = numbers.dropLastWhile { it == 0 }
        return significant.hashCode() * HASH_FACTOR + isPreRelease.hashCode()
    }

    /** The version as shown to the user: the text without the `v` of a tag. */
    override fun toString(): String = text.removePrefix("v")

    companion object {
        private const val MAX_NUMBERS = 4
        private val PATTERN = Regex("""v?(\d{1,9}(?:\.\d{1,9}){0,${MAX_NUMBERS - 1}})(?:-([0-9A-Za-z.]{1,32}))?""")
        private const val HASH_FACTOR = 31

        // Digits or not digits: `rc10` is `rc`, `10`.
        private val CHUNK = Regex("""\d+|\D+""")

        /** The version written in [text], with or without the `v` of a tag; null when it is not one. */
        fun parse(text: String): AppVersion? {
            val match = PATTERN.matchEntire(text) ?: return null
            val numbers = match.groupValues[1].split('.').map { it.toInt() }
            val preRelease = match.groups[2]?.value
            return AppVersion(text, numbers, preRelease)
        }

        // A missing number is a zero: 1.2 is 1.2.0.
        private fun compareNumbers(first: List<Int>, second: List<Int>): Int {
            val size = maxOf(first.size, second.size)
            for (index in 0 until size) {
                val mine = first.getOrElse(index) { 0 }
                val theirs = second.getOrElse(index) { 0 }
                if (mine != theirs) return mine.compareTo(theirs)
            }
            return 0
        }

        // A release is newer than its pre-releases; two pre-releases are compared naturally.
        private fun comparePreReleases(first: String?, second: String?): Int =
            when {
                first == second -> 0
                first == null -> 1
                second == null -> -1
                else -> compareNaturally(first, second)
            }

        // rc2 < rc10: the digits of a suffix are compared as numbers.
        private fun compareNaturally(first: String, second: String): Int {
            val mine = chunksOf(first)
            val theirs = chunksOf(second)
            for ((a, b) in mine.zip(theirs)) {
                val order = compareChunks(a, b)
                if (order != 0) return order
            }
            return mine.size.compareTo(theirs.size)
        }

        private fun chunksOf(text: String): List<String> = CHUNK.findAll(text).map { it.value }.toList()

        private fun compareChunks(first: String, second: String): Int {
            val firstNumber: BigInteger? = first.toBigIntegerOrNull()
            val secondNumber: BigInteger? = second.toBigIntegerOrNull()
            if (firstNumber == null || secondNumber == null) return first.compareTo(second)
            return firstNumber.compareTo(secondNumber)
        }
    }
}
