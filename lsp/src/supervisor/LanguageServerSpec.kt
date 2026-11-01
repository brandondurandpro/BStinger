package org.example.kotlineditor.lsp.supervisor

import kotlinx.serialization.json.JsonElement

/** How to run one language server; the supervisor runs one process per spec and root directory. */
class LanguageServerSpec(
    /** Name of the server (`phpactor`), part of the server id. */
    val name: String,
    /** Language identifier (`php`). */
    val language: String,
    /** `initializationOptions` of the `initialize` request. */
    val initializationOptions: JsonElement? = null,
    /**
     * The command line. Blocking, it may download the server first; an exception (runtime missing, failed
     * download) makes the server unavailable with the exception's message.
     */
    val command: () -> List<String>,
)

private const val FIVE_MINUTES = 300_000L

/** Delays of the supervisor, shortened by tests. */
class SupervisorTimings(
    /** A server nobody uses any more is stopped after this long (a project switched back and forth keeps it). */
    val idleStopMs: Long = FIVE_MINUTES,
    /** Longest wait for the answer to `initialize`. */
    val initializeTimeoutMs: Long = 30_000L,
    /** Longest wait for the answer to `shutdown`, then for the process to exit after `exit`. */
    val shutdownTimeoutMs: Long = 3_000L,
    /** Wait before the first restart after a crash; it doubles with each crash. */
    val firstBackoffMs: Long = 1_000L,
    /** This many crashes within [crashWindowMs] and the server stays down. */
    val maxCrashes: Int = 3,
    /** Window of [maxCrashes]. */
    val crashWindowMs: Long = FIVE_MINUTES,
    /** The leak detector judges each history this often. */
    val leakCheckMs: Long = 10_000L,
)
