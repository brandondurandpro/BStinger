package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.DelegationStatus

// JSON of the delegations of a transcript: a task a conversation gave to another one.

internal fun JsonObjectBuilder.delegation(item: AgentItem.Delegation) {
    put("type", "delegation")
    put("member", item.member)
    put("conversation", item.conversation)
    put("task", item.task)
    put("status", item.status.name)
    put("result", item.result)
}

internal fun JsonObject.delegation(id: String, version: Long) =
    AgentItem.Delegation(
        id = id,
        version = version,
        member = string("member"),
        conversation = string("conversation"),
        task = string("task"),
        status = DelegationStatus.valueOf(string("status")),
        result = stringOrNull("result").orEmpty(),
    )
