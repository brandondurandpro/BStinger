package org.example.kotlineditor.server

import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.DirectoryChangeListener
import io.methvin.watcher.DirectoryWatcher
import io.methvin.watcher.visitor.FileTreeVisitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import org.example.kotlineditor.model.FileVersion
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

/**
 * The open files of one answer given a version: beyond them the paths travel without one, and the client reads
 * and decides them one by one, as when no version travels at all. Internal so the tests name this very cap.
 */
internal const val MAX_VERSIONED_OPEN_FILES = 32

/** A version computed less than this long ago is found again without hashing the file (see [VersionCache]). */
internal const val VERSION_CACHE_TTL_MS = 2_000L

// Most versions the cache keeps: the open files of the projects, and no more.
private const val VERSION_CACHE_CAPACITY = 256

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

    // The watches being created, by root; guarded by [lock], held only to look them up (see #155).
    private val starting = HashMap<String, Deferred<RootWatch>>()

    // The roots that could not be watched, with the failure and when it happened; guarded by [lock].
    private val failures = HashMap<String, Pair<IOException, Long>>()
    private val lock = Mutex()

    // Creates the watches apart from the polls that asked for them: a poll cancelled meanwhile leaves neither the
    // others waiting for nothing nor a watch open for nobody.
    private val creations = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Each watch runs its event loop on a thread of its own, blocked on the watch service.
    private val loops =
        Executors.newCachedThreadPool { task -> Thread(task, "disk-watch").apply { isDaemon = true } }

    @Volatile private var closed = false

    override suspend fun changes(root: String, since: DiskCursor, open: List<String>): DiskChanges =
        watchOf(root).changesAfter(since, open)

    /**
     * How many files the recent answers hashed (the cache of [VersionCache] spared the repeats): the tests watch
     * it, so that a burst hashes only what is open.
     */
    fun computedVersions(): Long = synchronized(watches) { watches.values.sumOf { it.hashes } }

    // Registering a large tree holds up the polls of that project only: the lock covers the lookup, not the creation.
    private suspend fun watchOf(root: String): RootWatch {
        val running = runningWatch(root)
        if (running != null) return running
        val creation = lock.withLock { creationOf(root) }
        return creation.await()
    }

    // Under [lock]: the watch of [root] being created, else a new creation, unless [root] failed a moment ago.
    private fun creationOf(root: String): Deferred<RootWatch> {
        // Created since the caller looked: a second watch would replace it without closing it.
        val running = runningWatch(root)
        val known = starting[root] ?: running?.let { CompletableDeferred(it) }
        return known ?: newCreation(root)
    }

    // Under [lock]: starts creating the watch of [root], unless it failed a moment ago.
    private fun newCreation(root: String): Deferred<RootWatch> {
        val failure = failures[root]
        val recent = failure != null && System.currentTimeMillis() - failure.second < settings.retryFailedMs
        if (failure != null && recent) throw failure.first
        val creation = creations.async { create(root) }
        starting[root] = creation
        return creation
    }

    // Registers every directory before answering, so that nothing that changes afterwards is missed.
    private suspend fun create(root: String): RootWatch {
        val outcome = runCatching { RootWatch(Paths.get(root), settings, onChanged, loops) }
        val started = outcome.getOrNull()
        if (started != null) publish(root, started)
        lock.withLock {
            starting.remove(root)
            val failed = outcome.exceptionOrNull() as? IOException
            if (failed != null) failures[root] = failed to System.currentTimeMillis()
            if (started != null) failures.remove(root)
        }
        check(!closed) { "The server is stopping" }
        return outcome.getOrThrow()
    }

    // A watch created while the server stops is closed at once.
    private fun publish(root: String, started: RootWatch) {
        synchronized(watches) {
            if (closed) started.close() else watches[root] = started
        }
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

    /** Whether a change of [path] reaches [onChanged] through a running watch: its writer need not tell it again. */
    fun reports(path: String): Boolean {
        val file = Paths.get(path)
        val roots = synchronized(watches) { watches.filterValues { it.isOpen }.keys.toList() }
        return roots.any { root -> isReported(Paths.get(root), file) }
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

/**
 * The versions the recent answers computed, by path; they carry the (mtime, size) they describe and when they were
 * hashed. A path asked again whose (mtime, size) is theirs, within the window [VERSION_CACHE_TTL_MS], is found
 * again without reading or hashing it: a burst may repeat a path across answers, and its bytes did not move
 * (nothing between two answers but the events themselves).
 *
 * The window bounds what it can answer wrong: a file rewritten in place to its own size, with its time restored
 * (`touch -r`), keeps the (mtime, size) of the version cached while holding other bytes. Rare, and bounded: the
 * next real change of the file moves its (mtime, size), and a save checked on the server stats it again.
 */
private class VersionCache {
    private val computedCount = AtomicInteger()

    // Insertion-ordered: past the capacity, the eldest entry goes.
    private val entries = LinkedHashMap<String, Cached>()

    /** Files hashed so far (the cache hits are not here). */
    val computed: Long get() = computedCount.get().toLong()

    /** The version [path] holds now; `null` when there is no file (deleted, a directory) or the disk refuses it. */
    @Suppress("ReturnCount") // every step of a version answers at once that there is none to give
    fun recent(path: Path): FileVersion? {
        // The stat decides on the cache before any byte is read: a hit hashes nothing.
        val attributes = attributesOf(path) ?: return null
        val mtime = attributes.lastModifiedTime().toMillis()
        val size = attributes.size()
        val hit = cached(path.toString(), mtime, size)
        if (hit != null) return hit
        val bytes =
            try {
                Files.readAllBytes(path)
            } catch (_: IOException) {
                return null
            }
        val version = versionOf(path, bytes) ?: return null
        computedCount.incrementAndGet()
        return store(path.toString(), Cached(version.mtimeMs, version.size, version))
    }

    // Found again when [path] holds the (mtime, size) of a version computed within the window, as it does when
    // nothing between two answers wrote it.
    @Synchronized private fun cached(path: String, mtime: Long, size: Long): FileVersion? {
        val cached = entries[path] ?: return null
        val fresh = System.currentTimeMillis() - cached.hashedAt <= VERSION_CACHE_TTL_MS
        val sameBytes = cached.mtimeMs == mtime && cached.size == size
        return if (fresh && sameBytes) cached.version else null
    }

    // Stored with the (mtime, size) of the version returned, [path]'s own: another write within the window moves
    // its stat to another entry, and nothing of a reported version is trusted once the window is past.
    @Synchronized private fun store(path: String, cached: Cached): FileVersion {
        entries[path] = cached
        while (entries.size > VERSION_CACHE_CAPACITY) {
            val eldest = entries.keys.firstOrNull() ?: break
            entries.remove(eldest)
        }
        return cached.version
    }

    private class Cached(val mtimeMs: Long, val size: Long, val version: FileVersion) {
        val hashedAt = System.currentTimeMillis()
    }

    private fun attributesOf(path: Path) = realTargetOf(path)?.let { statOf(it) }
}

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

    // The versions of the open files computed recently, so that a burst that repeats a path does not hash it twice.
    private val versions = VersionCache()

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

    /** Files the version cache hashed (the repeats found again were not): the tests watch it. */
    val hashes: Long get() = versions.computed

    fun isIdle(): Boolean = polls.get() == 0 && System.currentTimeMillis() - lastPoll > settings.idleMs

    fun close() {
        runCatching { watcher.close() }
    }

    suspend fun changesAfter(since: DiskCursor, open: List<String>): DiskChanges {
        polls.incrementAndGet()
        try {
            val sameWatch = since.watch == id
            if (sameWatch && since.version == version.value) awaitChange(since.version)
            val answer = changesSince(since.version, sameWatch)
            return withVersions(answer, open)
        } finally {
            lastPoll = System.currentTimeMillis()
            polls.decrementAndGet()
        }
    }

    // The parts of an answer that a version travels with: the paths of it to hash, `null` when none of it is
    // known or no open file is named among them.
    private fun versionPaths(answer: DiskChanges, open: List<String>): List<String>? {
        val openInAnswer = if (!answer.complete) emptyList() else answer.paths.filter { it in open.toHashSet() }
        if (openInAnswer.isEmpty()) return null
        return openInAnswer.take(MAX_VERSIONED_OPEN_FILES)
    }

    // The versioned answer: named open files hashed off the watch on the IO dispatcher, so that a burst whose
    // open files are few answers in a bounded time whatever it wrote (the rest travels without a version).
    private suspend fun withVersions(answer: DiskChanges, open: List<String>): DiskChanges {
        val planned = versionPaths(answer, open) ?: return answer
        val answerVersions = withContext(Dispatchers.IO) { versionsOf(planned) }
        return DiskChanges(answer.cursor, answer.paths, answer.complete, answerVersions)
    }

    // Reads [paths] off the watch's lock; a path the disk cannot stat (deleted while written, a directory) is
    // answered without a version, as when the client read it instead.
    private fun versionsOf(paths: List<String>): Map<String, FileVersion> {
        val versioned =
            paths.mapNotNull { path ->
                versions.recent(Paths.get(path))?.let { version -> path to version }
            }
        return versioned.toMap()
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
