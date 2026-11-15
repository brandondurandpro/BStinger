package org.example.kotlineditor.e2e

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.example.kotlineditor.state.AppVersion
import org.example.kotlineditor.state.InstallEnvironment
import org.example.kotlineditor.state.PackageKind
import org.example.kotlineditor.state.UpdateOs
import org.example.kotlineditor.state.UpdatePlanner
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The system the tests run on, as the updater names it: Windows, else Linux (macOS tests use the Linux layout). */
val updateTestOs: UpdateOs =
    if (UpdateOs.fromName(
            System.getProperty("os.name"),
        ) == UpdateOs.WINDOWS
    ) {
        UpdateOs.WINDOWS
    } else {
        UpdateOs.LINUX
    }

/** The architecture label of the assets of the machine the tests run on. */
val updateTestArch: String = UpdateOs.archLabel(System.getProperty("os.arch"))

private const val OLD_DATE_MILLIS = 1_577_836_800_000L
private const val SHELL_MARKER = "launched.txt"

/**
 * A fake install of the IDE in a temporary directory, laid out like the jpackage app image of the system the tests
 * run on (a tar.gz extract on Linux, a zip extract on Windows), whose launcher writes [launchMarker] when it starts.
 * Never the real install: the updater only ever sees this directory.
 */
class FakeInstall private constructor(val base: File, val root: File, val launcher: File) : AutoCloseable {
    val environment: InstallEnvironment = InstallEnvironment(updateTestOs, updateTestArch, launcher, appImage = null)

    /** The file the launcher writes in the install when it is started. */
    val launchMarker: File get() = File(root, SHELL_MARKER)

    /** The version written in the install by whoever built it (`old`, or the version of the release). */
    fun version(): String = File(root, FakeRelease.VERSION_FILE).readText().trim()

    override fun close() {
        base.deleteRecursively()
    }

    companion object {
        fun create(): FakeInstall {
            val base = Files.createTempDirectory("fake-install").toFile()
            val root = File(base, if (updateTestOs == UpdateOs.WINDOWS) "Kotlin Editor" else "kotlin-editor")
            FakeRelease.writeTree(root, "old")
            val launcherPath =
                if (updateTestOs ==
                    UpdateOs.WINDOWS
                ) {
                    FakeRelease.WINDOWS_LAUNCHER
                } else {
                    FakeRelease.UNIX_LAUNCHER
                }
            return FakeInstall(base, root, File(root, launcherPath))
        }
    }
}

/** Builds the archive of a release of the fake install, with the name the updater expects for this system. */
object FakeRelease {
    /** The folder of the jars: `lib/app` in the app image of Linux, `app` in that of Windows. */
    val appDirectory: String = if (updateTestOs == UpdateOs.WINDOWS) "app" else "lib/app"
    val VERSION_FILE: String = "$appDirectory/VERSION"
    const val UNIX_LAUNCHER = "bin/kotlin-editor"
    private const val JAR_BYTES = 2_048
    const val WINDOWS_LAUNCHER = "Kotlin Editor.cmd"

    /** The kind of archive of the system the tests run on. */
    val kind: PackageKind = if (updateTestOs == UpdateOs.WINDOWS) PackageKind.ZIP else PackageKind.TAR_GZ

    /** The name of the asset of [version] (`0.3.0`) for this system and [kind]. */
    fun assetName(version: String, assetKind: PackageKind = kind): String {
        val parsed = checkNotNull(AppVersion.parse(version))
        return UpdatePlanner.assetName(parsed, updateTestOs, updateTestArch, assetKind)
    }

    /** The tree of an install in [root]: a launcher that writes a marker, the jars, a file telling the [version]. */
    fun writeTree(root: File, version: String, padBytes: Int = 0, withLauncher: Boolean = true) {
        File(root, appDirectory).mkdirs()
        File(root, VERSION_FILE).writeText(version)
        val jar = File(root, "$appDirectory/app.jar")
        jar.writeBytes(ByteArray(JAR_BYTES) { it.toByte() })
        jar.setLastModified(OLD_DATE_MILLIS)
        if (padBytes > 0) {
            File(
                root,
                "$appDirectory/padding.bin",
            ).writeBytes(kotlin.random.Random(1).nextBytes(padBytes))
        }
        if (!withLauncher) return
        if (updateTestOs == UpdateOs.WINDOWS) writeWindowsLauncher(root) else writeUnixLauncher(root)
    }

    // The launcher writes what the options of the JVM are, in a marker next to the install: the tests read it.
    // It ends with `exit`: `start` runs a batch file in a `cmd /k`, which would otherwise stay open for ever, holding
    // the output of the test run (the real launcher is an .exe, which just ends).
    private fun writeWindowsLauncher(root: File) {
        val script =
            """
            @echo off
            echo [%JAVA_TOOL_OPTIONS%]>"%~dp0$SHELL_MARKER"
            exit
            """.trimIndent()
        File(root, WINDOWS_LAUNCHER).writeText(script.replace("\n", "\r\n") + "\r\n")
    }

    private fun writeUnixLauncher(root: File) {
        val launcher = File(root, UNIX_LAUNCHER)
        launcher.parentFile.mkdirs()
        val script =
            """
            #!/bin/sh
            echo "[${'$'}{JAVA_TOOL_OPTIONS-unset}]" > "${'$'}(dirname "${'$'}0")/../$SHELL_MARKER"
            """.trimIndent()
        launcher.writeText(script + "\n")
        launcher.setExecutable(true)
    }

    /** The archive of [version] in [directory]; [padBytes] of incompressible data make it as large as wanted. */
    fun archive(
        version: String,
        directory: File,
        padBytes: Int = 0,
        assetKind: PackageKind = kind,
        withLauncher: Boolean = true,
    ): File {
        val file = File(directory, assetName(version, assetKind))
        when (assetKind) {
            PackageKind.ZIP -> zip(file, version, padBytes, withLauncher)
            PackageKind.TAR_GZ -> tar(file, version, padBytes, withLauncher)
            else -> file.writeText("#!/bin/sh\necho appimage $version\n")
        }
        return file
    }

    private fun tar(file: File, version: String, padBytes: Int, withLauncher: Boolean) {
        val work = Files.createTempDirectory("fake-release").toFile()
        try {
            writeTree(File(work, "kotlin-editor"), version, padBytes, withLauncher)
            val process =
                ProcessBuilder(
                    "tar",
                    "-czf",
                    file.path,
                    "-C",
                    work.path,
                    "kotlin-editor",
                ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "tar failed: $output" }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun zip(file: File, version: String, padBytes: Int, withLauncher: Boolean) {
        val work = Files.createTempDirectory("fake-release").toFile()
        try {
            val top = File(work, "Kotlin Editor")
            writeTree(top, version, padBytes, withLauncher)
            ZipOutputStream(file.outputStream()).use { zip -> addTree(zip, top, "Kotlin Editor") }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun addTree(zip: ZipOutputStream, directory: File, name: String) {
        val entry = ZipEntry("$name/")
        entry.lastModifiedTime = FileTime.fromMillis(OLD_DATE_MILLIS)
        zip.putNextEntry(entry)
        zip.closeEntry()
        for (child in directory.listFiles().orEmpty().sortedBy { it.name }) {
            if (child.isDirectory) {
                addTree(zip, child, "$name/${child.name}")
            } else {
                val file = ZipEntry("$name/${child.name}")
                file.lastModifiedTime = FileTime.fromMillis(child.lastModified())
                zip.putNextEntry(file)
                child.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}

/** Holds a download after some bytes, until [release] is called; [reached] is counted down when it holds. */
class DownloadGate {
    val reached = CountDownLatch(1)
    private val open = CountDownLatch(1)

    fun release() = open.countDown()

    internal fun hold() {
        reached.countDown()
        open.await(HOLD_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val HOLD_SECONDS = 60L
    }
}

/**
 * A stand-in of the downloads of GitHub releases, on loopback: serves the files [publish]ed under `/<tag>/<name>` and
 * a `SHA256SUMS.txt` per tag, and records every request. Never the real network.
 */
class FakeReleaseServer : AutoCloseable {
    private val files = HashMap<String, File>()
    private val sums = HashMap<String, MutableMap<String, String>>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val executor = Executors.newCachedThreadPool()

    /** What was requested, as `<tag>/<name>`. */
    val requests = CopyOnWriteArrayList<String>()

    /** Answers the download of assets (not the checksums) with this HTTP status instead, while set. */
    @Volatile
    var assetStatus: Int? = null

    @Volatile
    private var gate: DownloadGate? = null

    @Volatile
    private var gateAfter = 0L

    @Volatile
    private var sumsGate: DownloadGate? = null
    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/") { exchange -> answer(exchange) }
        server.start()
    }

    /** Serves [file] under [tag], with the checksum [checksum] in the sums (the real one by default). */
    fun publish(tag: String, file: File, checksum: String = sha256(file)) {
        synchronized(this) {
            files["$tag/${file.name}"] = file
            sums.getOrPut(tag) { LinkedHashMap() }[file.name] = checksum
        }
    }

    /** The next download of an asset stops after [afterBytes] bytes until the gate is released. */
    fun holdNextDownload(afterBytes: Long): DownloadGate {
        val created = DownloadGate()
        gateAfter = afterBytes
        gate = created
        return created
    }

    /** The next download of the checksums stops after 10 bytes until the gate is released. */
    fun holdNextSums(): DownloadGate {
        val created = DownloadGate()
        sumsGate = created
        return created
    }

    private fun answer(exchange: HttpExchange) {
        exchange.use {
            val key = exchange.requestURI.path.trimStart('/')
            requests += key
            val name = key.substringAfterLast('/')
            val status = assetStatus
            if (name == SUMS_NAME) {
                sendSums(exchange, key.substringBeforeLast('/'))
            } else if (status != null) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                sendFile(exchange, synchronized(this) { files[key] })
            }
        }
    }

    private fun sendSums(exchange: HttpExchange, tag: String) {
        val text = synchronized(this) { sums[tag]?.entries?.joinToString("") { "${it.value}  ${it.key}\n" } }
        if (text == null) {
            exchange.sendResponseHeaders(NOT_FOUND, -1)
            return
        }
        val bytes = text.toByteArray()
        exchange.sendResponseHeaders(OK, bytes.size.toLong())
        val held = sumsGate.also { sumsGate = null }
        val first = if (held == null) bytes.size else minOf(SUMS_HOLD_AFTER, bytes.size)
        exchange.responseBody.write(bytes, 0, first)
        if (held != null) {
            exchange.responseBody.flush()
            held.hold()
            exchange.responseBody.write(bytes, first, bytes.size - first)
        }
    }

    private fun sendFile(exchange: HttpExchange, file: File?) {
        if (file == null) {
            exchange.sendResponseHeaders(NOT_FOUND, -1)
            return
        }
        exchange.sendResponseHeaders(OK, file.length())
        val held = gate.also { gate = null }
        try {
            file.inputStream().use { input -> copy(input, exchange, held) }
        } catch (ignored: IOException) {
            // The client went away (cancelled, stalled): nothing more to send.
        }
    }

    private fun copy(input: InputStream, exchange: HttpExchange, held: DownloadGate?) {
        val buffer = ByteArray(CHUNK)
        var sent = 0L
        var waited = held == null
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            exchange.responseBody.write(buffer, 0, read)
            sent += read
            if (!waited && sent >= gateAfter) {
                waited = true
                exchange.responseBody.flush()
                held?.hold()
            }
        }
    }

    override fun close() {
        gate?.release()
        sumsGate?.release()
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        private const val SUMS_NAME = "SHA256SUMS.txt"
        private const val OK = 200
        private const val SUMS_HOLD_AFTER = 10
        private const val NOT_FOUND = 404
        private const val CHUNK = 16 * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
