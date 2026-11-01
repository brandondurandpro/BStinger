package org.example.kotlineditor.lsp.provision

import java.util.Base64
import java.util.HexFormat

/**
 * A checksum a pinned artifact must have before it is installed and before a copy is reused: the name of the
 * checksum function (`"SHA-256"` or `"SHA-512"`) and the value to expect. The value may be written in hexadecimal
 * or in base64 (npm publishes `dist.integrity` in base64); whichever reading yields the digest length of the
 * algorithm is the right one. A pin whose value cannot be read is rejected here, where a typo shows up, instead
 * of as a download that mysteriously never matches.
 */
class Digest(
    /** Name of the checksum function, as `java.security.MessageDigest` spells it. */
    val algorithm: String,
    /** The value to expect, hexadecimal or base64. */
    val value: String,
) {
    init {
        require(algorithm in LENGTHS) {
            "Unsupported digest algorithm $algorithm (use ${LENGTHS.keys.joinToString(" or ")})"
        }
        require(value.isNotBlank()) { "An empty checksum value cannot be verified" }
        decoded()
    }

    /** Whether the raw bytes of a freshly computed checksum are this one. */
    internal fun matches(computed: ByteArray): Boolean = computed.contentEquals(decoded())

    /** The expected checksum as raw bytes, decoded from its notation. */
    internal fun decoded(): ByteArray {
        val bytes =
            runCatching {
                if (hexadecimal()) HexFormat.of().parseHex(value) else Base64.getDecoder().decode(value)
            }.getOrNull()
        require(bytes != null && bytes.size == LENGTHS.getValue(algorithm)) {
            "The $algorithm checksum value is neither hexadecimal nor base64 of that length: $value"
        }
        return bytes
    }

    // Hexadecimal written without separators anywhere in the value; base64 rounds (like npm's) never are.
    private fun hexadecimal(): Boolean =
        value.isNotEmpty() && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** The checksum functions a [PinnedTool] may be verified with. */
    companion object {
        /** SHA-256, spelled like `MessageDigest` wants. */
        const val SHA_256: String = "SHA-256"

        /** SHA-512, the algorithm of npm's `dist.integrity`. */
        const val SHA_512: String = "SHA-512"

        // How many bytes (not characters) each accepted algorithm digests.
        private val LENGTHS: Map<String, Int> = mapOf(SHA_256 to SHA_256_BYTES, SHA_512 to SHA_512_BYTES)

        private const val SHA_256_BYTES = 32
        private const val SHA_512_BYTES = 64
    }
}
