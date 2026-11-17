package org.example.kotlineditor.testkit.skills

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

private const val BLOCK = 512
private const val MODE = "0000644"

/**
 * Repositories of skills on a loopback HTTP server, served as the `.tar.gz` GitHub makes of a repository (one folder
 * named after the repository and a commit, holding the files), for the tests of the skill catalog: nothing reaches the
 * internet. A repository can be removed (404) or truncated.
 */
class SkillRepositories : AutoCloseable {
    private val archives = ConcurrentHashMap<String, ByteArray>()
    private val broken = ConcurrentHashMap.newKeySet<String>()
    private val asked = AtomicInteger()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/") { exchange ->
                asked.incrementAndGet()
                val repo = exchange.requestURI.path.removePrefix("/").removeSuffix(".tar.gz")
                val body = archives[repo]
                when {
                    body == null -> exchange.sendResponseHeaders(404, -1)
                    repo in broken -> {
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.write(body.copyOf(body.size / 2))
                    }
                    else -> {
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.write(body)
                    }
                }
                exchange.close()
            }
            start()
        }

    /** The template of the settings' `skillArchiveUrl`: `{repo}` is the `owner/name` of a repository. */
    val archiveUrl: String get() = "http://127.0.0.1:${server.address.port}/{repo}.tar.gz"

    /** How many archives were asked for. */
    val requests: Int get() = asked.get()

    /** Serves [repo] (`owner/name`) holding [files], by path in the repository; [links] are symbolic links, path to target. */
    fun add(repo: String, files: Map<String, String>, links: Map<String, String> = emptyMap()) {
        broken.remove(repo)
        archives[repo] = archive(repo, files, links)
    }

    /** Serves a skill folder of [repo]: its `SKILL.md` with the front matter [name] and [description], and [extra] files. */
    fun skill(repo: String, path: String, name: String, description: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val front = "---\nname: $name\ndescription: $description\n---\n\nBody of $name.\n"
        return mapOf("$path/SKILL.md" to front) + extra.mapKeys { "$path/${it.key}" }
    }

    /** Stops serving [repo]: the server answers 404. */
    fun remove(repo: String) {
        archives.remove(repo)
    }

    /** Serves a truncated archive of [repo] from now on. */
    fun truncate(repo: String) {
        broken.add(repo)
    }

    override fun close() = server.stop(0)

    private fun archive(repo: String, files: Map<String, String>, links: Map<String, String>): ByteArray {
        val root = "${repo.substringAfter('/')}-0123abc"
        val tar = ByteArrayOutputStream()
        for ((path, text) in files) {
            val content = text.toByteArray()
            tar.write(header("$root/$path", content.size.toLong(), '0'))
            tar.write(content.copyOf(((content.size + BLOCK - 1) / BLOCK) * BLOCK))
        }
        for ((path, target) in links) tar.write(header("$root/$path", 0, '2', target))
        tar.write(ByteArray(BLOCK * 2))
        val zipped = ByteArrayOutputStream()
        GZIPOutputStream(zipped).use { it.write(tar.toByteArray()) }
        return zipped.toByteArray()
    }

    // A ustar header; names longer than 100 bytes go in the prefix field, as `tar` writes them.
    private fun header(name: String, size: Long, type: Char, target: String = ""): ByteArray {
        val block = ByteArray(BLOCK)
        val split = if (name.length > 100) name.lastIndexOf('/', 100) else -1
        val prefix = if (split > 0) name.substring(0, split) else ""
        val shortName = if (split > 0) name.substring(split + 1) else name
        shortName.toByteArray().copyInto(block)
        (MODE + "\u0000").toByteArray().copyInto(block, 100)
        (size.toString(8).padStart(11, '0') + "\u0000").toByteArray().copyInto(block, 124)
        block[156] = type.code.toByte()
        target.toByteArray().copyInto(block, 157)
        "ustar".toByteArray().copyInto(block, 257)
        prefix.toByteArray().copyInto(block, 345)
        return block
    }
}
