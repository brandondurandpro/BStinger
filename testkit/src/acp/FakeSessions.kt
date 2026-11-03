package org.example.kotlineditor.testkit.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** One prompt of a session of the [FakeAgent] and its answer. */
class FakeTurn(val prompt: String, val reply: StringBuilder = StringBuilder())

/** A session of the [FakeAgent], with its history, as a real agent keeps it. */
class FakeSession(val id: String, val cwd: String) {
    @Volatile
    var title: String? = null

    @Volatile
    var updatedAt: String = Instant.now().toString()

    /** Its model and its mode ([FakeConfig]). */
    @Volatile
    var model: String = FakeConfig.DEFAULT_MODEL

    @Volatile
    var mode: String = "ask"
    val turns: MutableList<FakeTurn> = java.util.Collections.synchronizedList(mutableListOf())
}

/**
 * The sessions of the [FakeAgent]: in memory, or in [store] (one JSON file each) so that they outlive the process,
 * as the sessions of Claude Code outlive the server that started it.
 */
class FakeSessions(private val store: File?) {
    private val sessions = ConcurrentHashMap<String, FakeSession>()
    private var created = 0

    init {
        store?.mkdirs()
        store?.listFiles { file -> file.extension == "json" }.orEmpty().forEach { file ->
            runCatching { read(file) }.getOrNull()?.let { sessions[it.id] = it }
        }
        created = sessions.size
    }

    /** A new session working in [cwd]; its id is unique in the store. */
    fun create(cwd: String): FakeSession {
        val id = synchronized(this) { "session-${++created}" }
        return FakeSession(id, cwd).also {
            sessions[id] = it
            save(it)
        }
    }

    operator fun get(id: String): FakeSession? = sessions[id]

    /** The sessions working in [cwd] (all of them when null), newest first. */
    fun list(cwd: String?): List<FakeSession> =
        sessions.values.filter { cwd == null || it.cwd == cwd }.sortedByDescending { it.updatedAt }

    /** Deletes [id]; false when there is none. */
    fun delete(id: String): Boolean {
        val removed = sessions.remove(id) ?: return false
        store?.let { File(it, "${removed.id}.json").delete() }
        return true
    }

    /** Writes [session] to the store, when there is one. */
    fun save(session: FakeSession) {
        session.updatedAt = Instant.now().toString()
        val directory = store ?: return
        val json =
            buildJsonObject {
                put("id", session.id)
                put("cwd", session.cwd)
                put("title", session.title?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                put("updatedAt", session.updatedAt)
                put("model", session.model)
                putJsonArray("turns") {
                    session.turns.toList().forEach { turn ->
                        add(
                            buildJsonObject {
                                put("prompt", turn.prompt)
                                put("reply", turn.reply.toString())
                            },
                        )
                    }
                }
            }
        File(directory, "${session.id}.json").writeText(json.toString())
    }

    private fun read(file: File): FakeSession {
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        val session = FakeSession(json.text("id").orEmpty(), json.text("cwd").orEmpty())
        session.title = json.text("title")
        session.updatedAt = json.text("updatedAt") ?: session.updatedAt
        session.model = json.text("model") ?: session.model
        (json["turns"] as? JsonArray).orEmpty().forEach { element ->
            val turn = element.jsonObject
            session.turns.add(FakeTurn(turn.text("prompt").orEmpty(), StringBuilder(turn.text("reply").orEmpty())))
        }
        return session
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
