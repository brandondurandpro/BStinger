package org.example.kotlineditor.lsp.supervisor

import kotlinx.serialization.json.JsonElement
import org.example.kotlineditor.lsp.documents.DEFAULT_MAX_UNREAD_BYTES
import org.example.kotlineditor.lsp.documents.DocumentOutbox

/** How to run one language server; the supervisor runs one process per spec and root directory. */
class LanguageServerSpec(
    /** Name of the server (`phpactor`), part of the server id. */
    val name: String,
    /** Language identifier (`php`). */
    val language: String,
    /** `initializationOptions` of the `initialize` request. */
    val initializationOptions: JsonElement? = null,
    /** Why it may give no inlay hints, shown when it gives none; null: a generic sentence. */
    val withoutInlayHints: String? = null,
    /**
     * The command line. Blocking, it may download the server first; an exception (runtime missing, failed
     * download) makes the server unavailable with the exception's message.
     */
    val command: () -> List<String>,
)

private const val FIVE_MINUTES = 300_000L

/** Delays and bounds of the supervisor, shortened by tests. */
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
    /**
     * How long after `initialized` a server may still begin to index the project (intelephense begins once its
     * configuration is answered): the requests whose answer needs the index wait this long at first.
     */
    val indexingStartMs: Long = 500L,
    /**
     * A process given this many bytes of document notifications while it does not read the one being written to
     * it (alive, but hung or no longer reading its input) is restarted, its documents opened again in the next one;
     * see [DocumentOutbox].
     */
    val maxUnreadBytes: Long = DEFAULT_MAX_UNREAD_BYTES,
)
