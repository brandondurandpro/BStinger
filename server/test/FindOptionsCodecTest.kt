package org.example.kotlineditor.server

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.rpc.findOptions
import org.example.kotlineditor.rpc.putParams
import kotlin.test.Test
import kotlin.test.assertEquals

/** The directories of a Find in Files cross the protocol as a list, and an older client's single one still reads. */
class FindOptionsCodecTest {
    private fun roundTrip(options: FindOptions) = buildJsonObject { putParams(options, 10) }.findOptions()

    @Test
    fun `several scopes survive the protocol`() {
        val options = FindOptions(text = "x", scopes = listOf("src", "docs/api"))
        assertEquals(options.scopes, roundTrip(options).scopes)
    }

    @Test
    fun `no scope stays the whole project`() {
        assertEquals(emptyList(), roundTrip(FindOptions(text = "x")).scopes)
    }

    @Test
    fun `the single scope of an older client is read as a list`() {
        val params =
            buildJsonObject {
                put("query", "x")
                put("ignoreCase", false)
                put("wholeWord", false)
                put("regex", false)
                put("includeIgnored", false)
                put("scope", "src")
            }
        assertEquals(listOf("src"), params.findOptions().scopes)
    }
}
