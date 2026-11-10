package org.example.kotlineditor.model

import kotlinx.coroutines.flow.Flow

/**
 * What a command run by the server printed since the previous chunk, or how it ended. A run is a stream of
 * chunks: [seq] orders them, so a client that reconnects asks for the ones after its last.
 */
data class RunChunk(
    /** Sequence number, from 1; the chunks of a run are numbered one after the other. */
    val seq: Long,
    /** Lines the command printed (output and error output merged), without their line break. */
    val lines: List<String> = emptyList(),
    /** Whether the command ended: the last chunk of a run. */
    val finished: Boolean = false,
    /** Exit code of the command, `null` while it runs, when it was stopped, or when it could not start. */
    val exitCode: Int? = null,
    /** Why the command could not start or run (program not found, directory gone), if it failed that way. */
    val error: String? = null,
)

/** A command running on the server: [chunks] delivers what it prints, and [id] resumes or stops it. */
class RunStream(
    /** Id of the stream: the id of the request that started it. */
    val id: Long,
    /** The chunks of the run, in order; the flow completes once the chunk that is [RunChunk.finished] arrived. */
    val chunks: Flow<RunChunk>,
)

/** Runs commands in a project, next to its files. */
interface ProjectRunner {
    /**
     * Starts [command] (the program, then its arguments, without a shell) in [directory], or returns `null` when
     * this server cannot run commands.
     */
    fun start(directory: String, command: List<String>): RunStream?

    /** Stops the run [stream] and the processes it started; nothing when it already ended. */
    fun stop(stream: RunStream)

    /** No server to run commands. */
    object None : ProjectRunner {
        override fun start(directory: String, command: List<String>): RunStream? = null

        override fun stop(stream: RunStream) = Unit
    }
}
