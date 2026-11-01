package org.example.kotlineditor.lsp.memory

import java.nio.charset.StandardCharsets

// Parsers of the `/proc` files the memory sampler reads, straight on the raw bytes: no regex, no split, no
// line list, nothing allocated beyond the result. They run for every process of every tracked tree every two
// seconds, and each /proc file is one small read (a few µs), so parsing must not cost more than the read.
//
// Bytes are compared to constants directly: /proc content is ASCII, and the one field that may carry
// arbitrary bytes (the command name in `stat`) is skipped by position, never interpreted.

private const val KIB = 1024L

private const val START_TIME_FIELD = 22

private const val FIRST_FIELD_AFTER_NAME = 3

private const val LINE_FEED: Byte = 10
private const val SPACE: Byte = 32
private const val COLON: Byte = 58
private const val DIGIT_ZERO: Byte = 48
private const val PAREN_CLOSE: Byte = 41

private const val VM_RSS = "VmRSS:"

private const val RSS_ANON = "RssAnon:"

/**
 * `VmRSS` and `RssAnon` from the content of `/proc/<pid>/status`: the resident set and its anonymous part,
 * turned into bytes. Null when either line is missing: the status of a process that exited has no `Vm` lines
 * at all, and a kernel older than 4.5 has no `RssAnon`, so the private metric cannot be read.
 */
internal fun parseProcStatus(bytes: ByteArray): ProcessMemory? {
    var residentKib = -1L
    var privateKib = -1L
    var lineStart = 0
    while (lineStart < bytes.size) {
        var lineEnd = lineStart
        while (lineEnd < bytes.size && bytes[lineEnd] != LINE_FEED) lineEnd++
        when {
            matchesAt(bytes, lineStart, VM_RSS) -> residentKib = kbValue(bytes, lineStart + VM_RSS.length, lineEnd)
            matchesAt(bytes, lineStart, RSS_ANON) -> privateKib = kbValue(bytes, lineStart + RSS_ANON.length, lineEnd)
        }
        lineStart = lineEnd + 1
    }
    return if (residentKib >= 0 && privateKib >= 0) ProcessMemory(privateKib * KIB, residentKib * KIB) else null
}

/**
 * The start time of a process, in clock ticks since boot, from the content of `/proc/<pid>/stat`: field 22.
 * The command name in field 2 can contain spaces and parentheses, so the scan starts after the LAST `)`: no
 * field after the name contains one, while the name itself may. Null when the content is too short.
 */
internal fun parseProcStatStartTime(bytes: ByteArray): Long? {
    val nameEnd = lastIndexOf(bytes, PAREN_CLOSE)
    if (nameEnd < 0) return null
    var index = nameEnd + 1
    repeat(START_TIME_FIELD - FIRST_FIELD_AFTER_NAME) {
        index = endOfToken(bytes, skipToToken(bytes, index))
    }
    index = skipToToken(bytes, index)
    return asciiLong(bytes, index, endOfToken(bytes, index))
}

/**
 * Every `Name:  value kB` line of `/proc/<pid>/smaps_rollup` — `Pss`, `Private_Dirty`, `Private_Clean`,
 * `Rss`, `Swap` among others — turned into bytes, in file order. Lines without the `kB` unit (such as
 * `VmFlags` and `ProtectionKey`) are skipped.
 */
internal fun parseSmapsRollup(bytes: ByteArray): Map<String, Long> {
    val values = LinkedHashMap<String, Long>()
    var lineStart = 0
    while (lineStart < bytes.size) {
        var lineEnd = lineStart
        while (lineEnd < bytes.size && bytes[lineEnd] != LINE_FEED) lineEnd++
        rollupValue(bytes, lineStart, lineEnd)?.let { (name, value) -> values[name] = value }
        lineStart = lineEnd + 1
    }
    return values
}

// The `Name: value kB` pair of one rollup line, or null when the line does not have that shape.
private fun rollupValue(bytes: ByteArray, start: Int, end: Int): Pair<String, Long>? {
    var colon = start
    while (colon < end && bytes[colon] != COLON) colon++
    val longEnough = end - start >= KB_SUFFIX
    val endsWithKb = longEnough && bytes[end - 1] == UPPER_B && bytes[end - 2] == LOWER_K
    val hasName = colon > start
    val hasValue = colon < end && endsWithKb
    if (!hasName || !hasValue) return null
    val name = String(bytes, start, colon - start, StandardCharsets.US_ASCII)
    val value = kbValue(bytes, colon + 1, end)
    return if (value >= 0) name to value * KIB else null
}

private const val KB_SUFFIX = 2
private const val LOWER_K: Byte = 107
private const val UPPER_B: Byte = 66

// Whether [prefix] is at [index] in [bytes].
private fun matchesAt(bytes: ByteArray, index: Int, prefix: String): Boolean =
    index + prefix.length <= bytes.size && prefix.indices.all { bytes[index + it] == prefix[it].code.toByte() }

// The value in KiB written after the colon of a /proc line, or -1 when there is no number there.
private fun kbValue(bytes: ByteArray, from: Int, end: Int): Long {
    var index = from
    while (index < end && bytes[index] in BLANKS) index++
    var value = 0L
    while (index < end && bytes[index] in DIGIT_ZERO..DIGIT_NINE) {
        value = value * DECIMAL + (bytes[index] - DIGIT_ZERO)
        index++
    }
    return if (index == from) -1L else value
}

private const val TAB: Byte = 9
private val BLANKS = byteArrayOf(SPACE, TAB)
private val TOKEN_ENDS = byteArrayOf(SPACE, LINE_FEED)
private const val DECIMAL = 10L
private const val DIGIT_NINE: Byte = 57

// The index of the first token character at or after [index] (tokens are separated by single spaces).
private fun skipToToken(bytes: ByteArray, index: Int): Int {
    var token = index
    while (token < bytes.size && bytes[token] == SPACE) token++
    return token
}

// The index just past the token that starts at [index].
private fun endOfToken(bytes: ByteArray, index: Int): Int {
    var end = index
    while (end < bytes.size && bytes[end] !in TOKEN_ENDS) end++
    return end
}

// The number made of the digits between [from] and [to], or null when there is none.
private fun asciiLong(bytes: ByteArray, from: Int, to: Int): Long? {
    val digitsOnly = from < to && (from until to).all { bytes[it] in DIGIT_ZERO..DIGIT_NINE }
    if (!digitsOnly) return null
    var value = 0L
    for (index in from until to) value = value * DECIMAL + (bytes[index] - DIGIT_ZERO)
    return value
}

// The last index of [byte] in [bytes], or -1.
private fun lastIndexOf(bytes: ByteArray, byte: Byte): Int {
    for (index in bytes.indices.reversed()) {
        if (bytes[index] == byte) return index
    }
    return -1
}
