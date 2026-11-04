package org.example.kotlineditor.server

import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.DirectoryChangeListener
import io.methvin.watcher.DirectoryWatcher
import io.methvin.watcher.visitor.FileTreeVisitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.lsp.DiskChange
import org.example.kotlineditor.model.DiskChanges
import org.example.kotlineditor.model.DiskCursor
import org.example.kotlineditor.model.DiskWatch
import org.example.kotlineditor.model.IGNORED_DIRECTORIES
import org.slf4j.helpers.NOPLogger
import java.io.Closeable
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val GIT = ".git"
private const val REFS = "refs"

// The files of `.git` that say the branch, the index or the commits changed; its other files are Git's own business.
private val GIT_FILES = setOf("HEAD", "index", "packed-refs")

/** Tuning of [ProjectWatches]. */
class WatchSettings(
    /** Longest wait of a poll for a change, before it answers that nothing changed. */
    val holdMs: Long = 25_000,
    /** Wait after a change before answering, so that the changes of a burst (a checkout, a build) go together. */
    val settleMs: Long = 100,
    /** Most changes kept for a late poll; one that missed more is told everything may have changed. */
    val maxKept: Int = 10_000,
    /** A project nobody polled for this long is no longer watched. */
    val idleMs: Long = 120_000,
    /**
     * A project that could not be watched (more directories than inotify allows) is tried again after this long;
     * meanwhile its polls fail at once instead of walking its tree again.
     */
    val retryFailedMs: Long = 60_000,
)

/**
 * Watches the projects the clients poll ([DiskWatch]), with `directory-watcher`: inotify on Linux, a single recursive
 * watch on Windows (one per directory would keep them from being renamed or deleted), FSEvents on macOS. The language
 * servers are told what changed ([onChanged]), as when the IDE itself changes the disk.
 *
 * The output and caches of tools (`build`, `node_modules`, `.gradle`... see [IGNORED_DIRECTORIES]) are watched for
 * their own entries only, not below; in `.git`, only what says the branch, the index or the commits changed.
 */
class ProjectWatches(
    private val settings: WatchSettings = WatchSettings(),
    private val onChanged: (List<Pair<String, DiskChange>>) -> Unit = {},
) : DiskWatch,
    Closeable {
    private val watches = HashMap<String, RootWatch>()

    // The roots that could not be watched, with the failure and when it happened; guarded by [lock].
    private val failures = HashMap<String, Pair<IOException, Long>>()
    private val lock = Mutex()

    // Each watch runs its event loop on a thread of its own, blocked on the watch service.
    private val loops =
        Executors.newCachedThreadPool { task -> Thread(task, "disk-watch").apply { isDaemon = true } }

    @Volatile private var closed = false

    override suspend fun changes(root: String, since: DiskCursor): DiskChanges = watchOf(root).changesAfter(since)

    private suspend fun watchOf(root: String): RootWatch =
        lock.withLock {
            val running = runningWatch(root)
            if (running != null) return running
            val failure = failures[root]
            val recent = failure != null && System.currentTimeMillis() - failure.second < settings.retryFailedMs
            if (failure != null && recent) throw failure.first
            // Registers every directory before answering, so that nothing that changes afterwards is missed.
            val started =
                try {
                    withContext(Dispatchers.IO) { RootWatch(Paths.get(root), settings, onChanged, loops) }
                } catch (failed: IOException) {
                    failures[root] = failed to System.currentTimeMillis()
                    throw failed
                }
            failures.remove(root)
            synchronized(watches) {
                if (closed) started.close() else watches[root] = started
            }
            check(!closed) { "The server is stopping" }
            started
        }

    // The open watch of [root], once the idle ones are closed.
    private fun runningWatch(root: String): RootWatch? =
        synchronized(watches) {
            check(!closed) { "The server is stopping" }
            val idle = watches.filterValues { it.isIdle() || !it.isOpen }
            idle.forEach { (path, watch) ->
                watch.close()
                watches.remove(path)
            }
            watches[root]
        }

    override fun close() {
        closed = true
        synchronized(watches) {
            watches.values.forEach { it.close() }
            watches.clear()
        }
        loops.shutdownNow()
    }
}

// The watch ids: unique per server process and different from those of the previous process, so that a client that
// polls a restarted server with its old cursor gets told it missed everything.
private val nextWatchId = AtomicLong(System.currentTimeMillis() * 1_000)

private class Change(val version: Long, val path: String)

/** One project root: its watcher, and the last changes, numbered. */
private class RootWatch(
    private val root: Path,
    private val settings: WatchSettings,
    private val onChanged: (List<Pair<String, DiskChange>>) -> Unit,
    loops: Executor,
) {
    init {
        if (!Files.isDirectory(root)) throw IOException("Not a directory: $root")
    }

    val id = nextWatchId.incrementAndGet()
    private val kept = ArrayDeque<Change>()
    private var latest = 0L

    // Versions up to this one were dropped (too many changes, or the watch service overflowed).
    private var floor = 0L
    private val version = MutableStateFlow(0L)

    // Language server notifications of a burst, sent once the watcher has nothing more to read.
    private val unsent = ArrayList<Pair<String, DiskChange>>()
    private val polls = AtomicInteger()

    @Volatile private var lastPoll = System.currentTimeMillis()

    private val watcher: DirectoryWatcher =
        DirectoryWatcher
            .builder()
            .path(root)
            .listener(Listener())
            .fileHashing(false)
            .fileTreeVisitor(WatchedTree(root))
            .logger(NOPLogger.NOP_LOGGER)
            .build()

    init {
        // It registers the directories before it returns, and keeps a failure (too many inotify watches) in the future.
        val loop = watcher.watchAsync(loops)
        if (loop.isCompletedExceptionally) {
            close()
            val failure = runCatching { loop.join() }.exceptionOrNull()
            throw IOException("Cannot watch $root: ${failure?.cause?.message ?: failure?.message}", failure)
        }
    }

    val isOpen: Boolean get() = !watcher.isClosed

    fun isIdle(): Boolean = polls.get() == 0 && System.currentTimeMillis() - lastPoll > settings.idleMs

    fun close() {
        runCatching { watcher.close() }
    }

    suspend fun changesAfter(since: DiskCursor): DiskChanges {
        polls.incrementAndGet()
        try {
            val sameWatch = since.watch == id
            if (sameWatch && since.version == version.value) awaitChange(since.version)
            return changesSince(since.version, sameWatch)
        } finally {
            lastPoll = System.currentTimeMillis()
            polls.decrementAndGet()
        }
    }

    private suspend fun awaitChange(since: Long) {
        val changed = withTimeoutOrNull(settings.holdMs) { version.first { it > since } }
        if (changed != null) delay(settings.settleMs)
    }

    private fun changesSince(since: Long, sameWatch: Boolean): DiskChanges =
        synchronized(kept) {
            val cursor = DiskCursor(id, latest)
            val known = sameWatch && since in floor..latest
            if (!known) return DiskChanges(cursor, emptyList(), complete = false)
            val paths = kept.filter { it.version > since }.mapTo(LinkedHashSet()) { it.path }
            DiskChanges(cursor, paths.toList(), complete = true)
        }

    private fun record(path: Path) {
        val now =
            synchronized(kept) {
                latest++
                kept.addLast(Change(latest, path.toString()))
                if (kept.size > settings.maxKept) floor = kept.removeFirst().version
                latest
            }
        version.value = now
    }

    // The watch service lost events: whoever polls next is told anything may have changed.
    private fun overflowed() {
        val now =
            synchronized(kept) {
                latest++
                kept.clear()
                floor = latest
                latest
            }
        version.value = now
    }

    private inner class Listener : DirectoryChangeListener {
        override fun onEvent(event: DirectoryChangeEvent) {
            if (event.eventType() == EventType.OVERFLOW) {
                overflowed()
                return
            }
            val path = event.path()
            if (path == null || !isReported(root, path)) return
            record(path)
            val change = languageServerChange(event)
            if (change != null) synchronized(unsent) { unsent += change }
        }

        override fun onIdle(count: Int) {
            val burst =
                synchronized(unsent) {
                    val all = unsent.toList()
                    unsent.clear()
                    all
                }
            if (burst.isNotEmpty()) runCatching { onChanged(burst) }
        }
    }
}

// What the language servers are told about [event]: files only, and nothing of `.git`.
private fun languageServerChange(event: DirectoryChangeEvent): Pair<String, DiskChange>? {
    val path = event.path().toString()
    val change =
        when (event.eventType()) {
            EventType.CREATE -> DiskChange.Created.takeUnless { event.isDirectory }
            EventType.MODIFY -> DiskChange.Changed.takeUnless { event.isDirectory }
            EventType.DELETE -> DiskChange.Deleted
            else -> null
        }
    val first = event.rootPath().relativize(event.path()).firstOrNull()
    val inGit = first?.toString() == GIT
    return if (change == null || inGit) null else path to change
}

// The names of the directories from [root] to [path], [path] included.
private fun segmentsOf(root: Path, path: Path): List<String> {
    if (!path.startsWith(root)) return emptyList()
    return root.relativize(path).map { it.toString() }.filter { it.isNotEmpty() }
}

// Whether a directory is watched: the root, the directories of `.git/refs`, and those not below a tool's directory
// (which itself is watched, for its own entries).
private fun isWatched(root: Path, directory: Path): Boolean {
    val segments = segmentsOf(root, directory)
    val inGit = segments.firstOrNull() == GIT
    return when {
        directory == root -> true
        inGit -> segments.size == 1 || segments[1] == REFS
        else -> segments.dropLast(1).none { it in IGNORED_DIRECTORIES }
    }
}

// Whether the directories below [directory] may be watched (see [isWatched]).
private fun descendsInto(root: Path, directory: Path): Boolean {
    val segments = segmentsOf(root, directory)
    val inGit = segments.firstOrNull() == GIT
    return inGit || segments.none { it in IGNORED_DIRECTORIES }
}

// Whether a change of [path] is reported: an entry of a watched directory, or in `.git` what moves the branch, the
// index or the commits. Windows watches the whole tree at once: this also filters what it reports below.
private fun isReported(root: Path, path: Path): Boolean {
    val segments = segmentsOf(root, path)
    val name = segments.lastOrNull() ?: return false
    val inGit = segments.firstOrNull() == GIT
    return when {
        !inGit -> segments.dropLast(2).none { it in IGNORED_DIRECTORIES }
        segments.size == 2 -> name in GIT_FILES
        else -> segments[1] == REFS && !name.endsWith(".lock")
    }
}

/** Visits only the directories the watch registers (see [isWatched]), when it starts and when one is created. */
private class WatchedTree(private val root: Path) : FileTreeVisitor {
    override fun recursiveVisitFiles(
        file: Path,
        onDirectory: FileTreeVisitor.Callback,
        onFile: FileTreeVisitor.Callback,
    ) {
        Files.walkFileTree(
            file,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!isWatched(root, dir)) return FileVisitResult.SKIP_SUBTREE
                    onDirectory.call(dir)
                    return if (descendsInto(root, dir)) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (isReported(root, file)) onFile.call(file)
                    return FileVisitResult.CONTINUE
                }

                // A directory that cannot be read, or that was deleted meanwhile, is simply not watched.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
    }
}
