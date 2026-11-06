package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private val version = FileVersion(1722331200123L, 456L, "987654", "cafebabe0123456789")

/** The JSON of files and their content goes over the protocol and comes back the same. */
class FileCodecsTest {
    @Test
    fun `a file version travels with its stat, its file key and the hash of its bytes`() {
        assertEquals(version, version.toJson().versionOrNull())
        // A file with no file key (Windows, most non-POSIX disks) is sent without one, and comes back without one.
        val withoutKey = version.copy(fileKey = null)
        assertEquals(withoutKey, withoutKey.toJson().versionOrNull())
        // Nothing known (an absent field) is no version either; mistyped fields fail, as every field does.
        assertNull(JsonNull.versionOrNull())
        assertFailsWith<IllegalArgumentException> { JsonObject(emptyMap()).versionOrNull() }
    }

    @Test
    fun `a file content carries the version of the file it was read from`() {
        val text = FileContent.Text(listOf("one", "two"), "\r\n", version)
        assertEquals(text, text.toJson().content())
        val binary = FileContent.Binary(version)
        assertEquals(binary, binary.toJson().content())
        // A content that has no version (too large, failed, or an old server) comes back without one.
        val tooLarge: FileContent = FileContent.TooLarge(33_554_432L)
        assertEquals(tooLarge, tooLarge.toJson().content())
        assertNull(tooLarge.toJson().content().version)
        assertNull(FileContent.Error("no such file").toJson().content().version)
        val withoutVersion =
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("text"),
                    "lineSeparator" to JsonPrimitive("\n"),
                    "lines" to listOf("one").toJson(),
                ),
            )
        assertNull(withoutVersion.content().version)
    }

    @Test
    fun `a content of the next version is decoded with the fields it keeps and without the unknown ones`() {
        // A field this (or an older) version of the protocol does not talk of changes nothing.
        val text = JsonObject(
            mapOf(
                "type" to JsonPrimitive("text"),
                "lineSeparator" to JsonPrimitive("\n"),
                "lines" to listOf("one").toJson(),
                "versionChangedYesterday" to version.toJson(),
            ),
        )
        assertEquals(FileContent.Text(listOf("one"), "\n"), text.content())
        val binary = JsonObject(mapOf("type" to JsonPrimitive("binary"), "fileOf" to version.toJson()))
        assertEquals(FileContent.Binary(), binary.content())
    }
}
