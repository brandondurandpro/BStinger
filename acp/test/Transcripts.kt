package org.example.kotlineditor.acp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentTranscript
import org.example.kotlineditor.model.AgentTurnState

private const val TIMEOUT_MS = 30_000L

/** What a client following a transcript has seen: every item, and the latest answer. */
class Followed(val items: List<AgentItem>, val last: AgentTranscript)

/** Follows the transcript of [conversation] like the IDE, from the start, until [done] holds. */
fun AgentService.follow(conversation: String, done: (AgentTurnState, List<AgentItem>) -> Boolean): Followed =
    runBlocking {
        withTimeout(TIMEOUT_MS) {
            val items = LinkedHashMap<String, AgentItem>()
            var since = 0L
            var last: AgentTranscript? = null
            while (last == null || !done(last.state, items.values.toList())) {
                val transcript = transcript(conversation, epoch, since)
                transcript.items.forEach { items[it.id] = it }
                since = transcript.version
                last = transcript
            }
            Followed(items.values.toList(), last)
        }
    }

/** Follows [conversation] until the agent is idle and [until] holds. */
fun AgentService.idle(conversation: String, until: (List<AgentItem>) -> Boolean = { true }): Followed =
    follow(conversation) { state, items -> state == AgentTurnState.Idle && until(items) }

/** The texts the agent answered, in order. */
fun List<AgentItem>.answers(): List<String> = filterIsInstance<AgentItem.AgentText>().map { it.text }
