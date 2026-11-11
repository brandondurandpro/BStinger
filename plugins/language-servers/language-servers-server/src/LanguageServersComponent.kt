package org.example.kotlineditor.languageservers.server

import org.example.kotlineditor.lsp.DiskChange
import org.example.kotlineditor.lsp.LanguageServerService
import org.example.kotlineditor.model.FileOperation
import org.example.kotlineditor.model.OperatedFile
import org.example.kotlineditor.server.DiskObserver
import org.example.kotlineditor.server.LocalServices

/**
 * The language servers of a server, started on the first request that needs them. It tells them what changes on the
 * disk and what the IDE does to files once they run, and stops them with the services.
 */
internal class LanguageServersComponent(services: LocalServices) : DiskObserver {
    private val lazyService = lazy { LanguageServerService(services.languageServerSettings) { services.telemetry } }

    /** The language servers; the first use starts them. */
    val service: LanguageServerService by lazyService

    /** Whether [service] was ever used (closing it otherwise would start it). */
    val started: Boolean get() = lazyService.isInitialized()

    override val active: Boolean get() = started

    init {
        services.observe(this)
        services.closeWith { if (started) service.close() }
    }

    override fun diskChanged(changes: List<Pair<String, DiskChange>>) = service.diskChanged(changes)

    override fun filesOperated(operation: FileOperation, files: List<OperatedFile>) =
        service.filesOperated(operation, files)

    override fun sessionEnded(session: String) = service.release(session)
}

/** The language servers of [this], one for all the contributions. */
internal fun LocalServices.languageServersComponent(): LanguageServersComponent =
    component("language-servers") { LanguageServersComponent(this) }
