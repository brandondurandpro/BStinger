package org.example.kotlineditor.e2e

import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.AbstractCommandSupport
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.example.kotlineditor.rpc.Protocol
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/**
 * A real SSH server and a real server bundle for the remote development tests.
 *
 * The "remote" is this machine: MINA runs the POSIX commands the client sends, with `$HOME` pointed at a temporary
 * directory, while the client is the system OpenSSH (`ssh`). The whole flow is therefore real — host keys,
 * known hosts, key authentication, upload, extraction, daemon, tunnel — without needing a second machine.
 */
object RemoteTestEnvironment {
    /** Windows cannot host the POSIX remote used by these tests; the feature's Windows target comes later. */
    val available: Boolean = !System.getProperty("os.name").lowercase().contains("win")

    private const val MODE_EXECUTABLE = 0b111_101_101

    private val root: Path by lazy { Files.createTempDirectory("remote-development") }
    val home: Path get() = root.resolve("home").also { Files.createDirectories(it) }
    val ssh: RemoteSshServer by lazy { RemoteSshServer(home) }
    val bundle: Path by lazy { buildBundle() }

    // Manual-connection tests must not read the developer's real ~/.ssh/config (nor wait for its ssh -G runs).
    private val emptySshConfig: Path by lazy {
        root.resolve("ssh-config-empty").also { Files.writeString(it, "# isolated test config\n") }
    }

    init {
        if (available) {
            Runtime.getRuntime().addShutdownHook(Thread { cleanup() })
        }
    }

    fun prepare() {
        if (!available) return
        ssh.ensureStarted()
        // A chaos test may have changed the host key; trust the one the server has now.
        ssh.trustCurrentHostKey()
        System.setProperty("kotlin.editor.remote.bundle", bundle.toString())
        System.setProperty("kotlin.editor.ssh.knownHosts", ssh.knownHosts.toString())
        System.setProperty("kotlin.editor.ssh.config", emptySshConfig.toString())
    }

    fun cleanup() {
        if (!available) return
        stopDaemon()
        runCatching { ssh.close() }
        root.toFile().deleteRecursively()
    }

    /** Process id of the daemon the installer started, or null when there is none. */
    fun daemonPid(): Long? {
        val state = home.resolve(".kotlin-editor/server/state.properties")
        return runCatching {
            Properties().apply { Files.newInputStream(state).use { load(it) } }.getProperty("pid")?.toLong()
        }.getOrNull()
    }

    /** Kills the daemon the installer started, so its JVM does not outlive the tests. */
    fun stopDaemon() {
        val pid = daemonPid() ?: return
        ProcessHandle.of(pid).ifPresent {
            it.destroy()
            runCatching { it.onExit().get(5, TimeUnit.SECONDS) }
            if (it.isAlive) it.destroyForcibly()
        }
    }

    /** Text of the server log, for the assertion message when a remote connection fails. */
    fun diagnostics(): String =
        runCatching { Files.readString(home.resolve(".kotlin-editor/server/server.log")) }.getOrDefault("")

    private fun buildBundle(): Path {
        val start =
            checkNotNull(RemoteTestEnvironment::class.java.getResourceAsStream("/remote/start.sh")) {
                "remote/start.sh is missing from the test classpath"
            }.use { it.readBytes() }
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val classpath =
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .joinToString(":") { File(it).absolutePath }
        val wrapper =
            """
            #!/bin/sh
            exec "$java" "${'$'}@"
            """.trimIndent() + "\n"
        val files =
            listOf(
                TestTarFile("runtime/", ByteArray(0), MODE_EXECUTABLE, directory = true),
                TestTarFile("runtime/bin/", ByteArray(0), MODE_EXECUTABLE, directory = true),
                TestTarFile("VERSION", "${Protocol.APP_VERSION}\n".toByteArray(), 0b110_100_100),
                TestTarFile("start.sh", start, MODE_EXECUTABLE),
                TestTarFile("classpath", classpath.toByteArray(), 0b110_100_100),
                TestTarFile("runtime/bin/java", wrapper.toByteArray(), MODE_EXECUTABLE),
            )
        val out = Files.createTempFile(root, "server-${Protocol.APP_VERSION}-linux-x86_64", ".tar.gz")
        writeBundleTarGz(out, files)
        return out
    }
}

/** One file or directory of the test server bundle. */
private class TestTarFile(val name: String, val bytes: ByteArray, val mode: Int, val directory: Boolean = false)

private fun writeBundleTarGz(dest: Path, files: List<TestTarFile>) {
    GZIPOutputStream(Files.newOutputStream(dest)).use { out ->
        for (file in files) {
            out.write(tarHeader(file.name, file.bytes.size, file.mode, file.directory))
            out.write(file.bytes)
            val padding = (512 - file.bytes.size % 512) % 512
            if (padding > 0) out.write(ByteArray(padding))
        }
        out.write(ByteArray(1024))
    }
}

private fun tarHeader(name: String, size: Int, mode: Int, directory: Boolean): ByteArray {
    val header = ByteArray(512)
    putAscii(header, 0, name, 100)
    putOctal(header, 100, 8, mode.toLong())
    putOctal(header, 108, 8, 0)
    putOctal(header, 116, 8, 0)
    putOctal(header, 124, 12, size.toLong())
    putOctal(header, 136, 12, 0)
    header[156] = (if (directory) '5' else '0').code.toByte()
    putAscii(header, 257, "ustar", 5)
    header[262] = 0
    putAscii(header, 263, "00", 2)
    putAscii(header, 265, "tester", 32)
    putAscii(header, 297, "tester", 32)
    putAscii(header, 148, "        ", 8)
    var checksum = 0
    for (byte in header) checksum += byte.toInt() and 0xff
    putAscii(header, 148, "%06o".format(checksum), 6)
    header[154] = 0
    header[155] = ' '.code.toByte()
    return header
}

private fun putAscii(target: ByteArray, offset: Int, value: String, length: Int) {
    val bytes = value.toByteArray()
    bytes.copyInto(target, offset, 0, minOf(bytes.size, length))
}

private fun putOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
    val text = value.toString(8).padStart(length - 1, '0')
    putAscii(target, offset, text, length - 1)
    target[offset + length - 1] = 0
}

/** SSH server the client talks to, running the same POSIX shell commands a Linux host would. */
class RemoteSshServer(private val home: Path) : Closeable {
    private val root: Path = Files.createTempDirectory("remote-ssh")
    private val hostKey: Path = root.resolve("hostkey")
    val identity: Path = root.resolve("id_rsa")
    val knownHosts: Path = root.resolve("known_hosts")

    var port: Int = 0
        private set

    private var sshd: SshServer? = null
    private var hostKeys: SimpleGeneratorHostKeyProvider? = null
    private val clientPublic: java.security.PublicKey

    init {
        Files.createDirectories(home)
        generateClientKey()
        clientPublic = readClientPublicKey()
    }

    fun ensureStarted() {
        if (sshd == null) start()
    }

    fun start(requestedPort: Int = port, trust: Boolean = true) {
        val server = SshServer.setUpDefaultServer()
        server.host = "127.0.0.1"
        server.port = requestedPort
        val keys =
            SimpleGeneratorHostKeyProvider(hostKey).apply {
                algorithm = "RSA"
                keySize = 2048
            }
        server.keyPairProvider = keys
        server.publickeyAuthenticator =
            org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator {
                    username: String,
                    key: java.security.PublicKey,
                    _: ServerSession?,
                ->
                username == "tester" && key.encoded.contentEquals(clientPublic.encoded)
            }
        server.passwordAuthenticator = org.apache.sshd.server.auth.password.RejectAllPasswordAuthenticator.INSTANCE
        server.commandFactory = ShellCommandFactory(home)
        server.subsystemFactories = listOf(SftpSubsystemFactory())
        server.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        server.start()
        sshd = server
        port = server.port
        hostKeys = keys
        if (trust) writeKnownHosts(keys.loadKeys(null).first().public)
    }

    fun stop() {
        sshd?.stop(true)
        sshd = null
    }

    fun restart() {
        val previous = port
        stop()
        start(previous)
    }

    /** Restarts with a fresh host key while the client still trusts the old one, as a reinstalled host would. */
    fun restartWithNewHostKey() {
        val previous = port
        stop()
        Files.deleteIfExists(hostKey)
        start(previous, trust = false)
    }

    /** Writes the current host key to known_hosts, undoing [restartWithNewHostKey] for the next test. */
    fun trustCurrentHostKey() {
        val keys = hostKeys ?: return
        if (sshd != null) writeKnownHosts(keys.loadKeys(null).first().public)
    }

    override fun close() {
        stop()
        root.toFile().deleteRecursively()
    }

    private fun generateClientKey() {
        val keygen =
            runCatching {
                ProcessBuilder("ssh-keygen", "-q", "-t", "rsa", "-b", "2048", "-N", "", "-f", identity.toString())
                    .redirectErrorStream(true)
                    .start()
            }.getOrElse { throw IllegalStateException("Cannot run ssh-keygen: ${it.message}", it) }
        val output = keygen.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!keygen.waitFor(30, TimeUnit.SECONDS) || keygen.exitValue() != 0) {
            throw IllegalStateException("ssh-keygen failed: $output")
        }
        runCatching {
            Files.setPosixFilePermissions(
                identity,
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rw-------"),
            )
        }
    }

    private fun readClientPublicKey(): java.security.PublicKey {
        val line =
            Files
                .readString(Path.of("$identity.pub"))
                .trim()
                .split(Regex("\\s+"))
                .take(2)
                .joinToString(" ")
        val entry = PublicKeyEntry.parsePublicKeyEntry(line)
        return entry.resolvePublicKey(null, emptyMap(), PublicKeyEntryResolver.IGNORING)
    }

    private fun writeKnownHosts(hostPublic: java.security.PublicKey) {
        val encoded = PublicKeyEntry.toString(hostPublic)
        Files.writeString(knownHosts, "[127.0.0.1]:$port $encoded\n")
    }
}

private class ShellCommandFactory(private val home: Path) : CommandFactory {
    override fun createCommand(channel: ChannelSession, command: String): Command = ShellCommand(command, home)
}

/** Runs the command with /bin/sh, as a Linux SSH server does, with HOME pointed at the fake remote home. */
private class ShellCommand(command: String, private val home: Path) : AbstractCommandSupport(command, null) {
    @Volatile private var process: Process? = null

    override fun run() {
        try {
            val started =
                ProcessBuilder("/bin/sh", "-c", command)
                    .directory(home.toFile())
                    .apply { environment()["HOME"] = home.toAbsolutePath().toString() }
                    .start()
            process = started
            val output = getOutputStream()
            val error = getErrorStream()
            val input = getInputStream()
            val pumps =
                listOf(
                    pump(started.inputStream, output),
                    pump(started.errorStream, error),
                    pump(input, started.outputStream) { started.outputStream.close() },
                )
            val code = started.waitFor()
            pumps.forEach { it.join(1_000) }
            onExit(code)
        } catch (e: IOException) {
            onExit(127, e.message)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            onExit(130, e.message)
        }
    }

    override fun destroy(channel: ChannelSession) {
        process?.destroyForcibly()
        super.destroy(channel)
    }

    private fun pump(input: InputStream, output: OutputStream, done: () -> Unit = {}): Thread =
        thread(isDaemon = true) {
            runCatching {
                input.copyTo(output)
                output.flush()
            }.also { done() }
        }
}

/** Starts/returns the shared remote environment, or null when the test platform cannot host a POSIX remote. */
fun remoteEnvironment(): RemoteTestEnvironment? =
    if (RemoteTestEnvironment.available) {
        RemoteTestEnvironment.prepare()
        RemoteTestEnvironment
    } else {
        null
    }
