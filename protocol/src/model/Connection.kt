package org.example.kotlineditor.model

/** State of the link with the server, shown in the status bar. */
enum class ConnectionStatus {
    /** Requests go through. */
    Connected,

    /** The link was lost; requests wait and are retried once it is back. */
    Reconnecting,

    /** The client was closed or rejected by the server. */
    Disconnected,
}

/** Tells the server whether the user agreed to share anonymous performance measures. */
interface TelemetrySettings {
    /** The user agreed ([enabled]) as installation [instanceId], or refused (null id). */
    suspend fun configure(enabled: Boolean, instanceId: String?)

    /** No server to tell. */
    object None : TelemetrySettings {
        override suspend fun configure(enabled: Boolean, instanceId: String?) = Unit
    }
}
