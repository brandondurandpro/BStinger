package org.example.kotlineditor.acp

import kotlinx.serialization.json.JsonObject

/** The `_meta.claudeCode` object of an update: what the Claude adapter adds to the standard fields, null for others. */
internal fun JsonObject.claudeMeta(): JsonObject? = get("_meta").obj()?.get("claudeCode").obj()

/** The report a sub-agent handed back (`_meta.claudeCode.toolResponse.handbackReport.text`), null when absent. */
internal fun JsonObject.handbackReport(): String? {
    val response = claudeMeta()?.get("toolResponse").obj()
    return response?.get("handbackReport").obj()?.text("text")
}
