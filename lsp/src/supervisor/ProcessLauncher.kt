package org.example.kotlineditor.lsp.supervisor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A server that cannot run here, and why. */
internal class Unavailable(override val message: String, cause: Throwable) : Exception(message, cause)

/** Starts the processes of the server of [target]: its command, provisioned first, run in its root. */
internal class ProcessLauncher(private val target: ServerTarget) {
    /** The command line, once the server is provisioned (downloaded, its runtime found); throws [Unavailable]. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun resolveCommand(): List<String> =
        try {
            withContext(Dispatchers.IO) { target.spec.command() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Any failure of the provisioning (missing runtime, failed download...) is shown, never thrown.
            throw Unavailable(failure.message ?: failure.toString(), failure)
        }

    /** Runs [command] in the root; throws [Unavailable] when it cannot run. Blocking. */
    fun start(command: List<String>): Process =
        try {
            ProcessBuilder(command).directory(File(target.root)).start()
        } catch (failure: IOException) {
            throw Unavailable("Cannot run ${command.first()}: ${failure.message}", failure)
        }
}
