package org.example.kotlineditor.harness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.example.kotlineditor.acp.httpGet
import org.example.kotlineditor.lsp.provision.TarArchive
import org.example.kotlineditor.model.AgentSkill
import org.example.kotlineditor.model.CatalogSkill
import org.example.kotlineditor.model.SkillCatalogListing
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

// A repository is not downloaded bigger than this once unpacked: a repository of skills is text, a few megabytes.
private const val MAX_UNPACKED_BYTES = 200L * 1024 * 1024

// How deep in a repository a skill may be: `skills/engineering/tdd/SKILL.md` is found.
private const val MAX_DEPTH = 6

// The folders of a repository that hold no skill an agent is given: its own tooling and dependencies.
private val SKIPPED_FOLDERS = setOf("node_modules", "dist", "build")

private const val LICENSE_PREFIX = "LICENSE"
private const val SEPARATOR_IN_NAME = "--"

/**
 * The popular repositories of skills ([sources], `owner/name`), downloaded from [archiveUrl] (`{repo}` is the name of
 * the repository) and kept unpacked in `skill-catalog` of [dataDirectory], so that listing again needs no network. A
 * skill is installed by copying its folder into `skills` of [dataDirectory], where [SkillDiscovery] finds it for the
 * agents of a team, with the licence of its repository beside it. Nothing here touches a project.
 */
class SkillCatalog(
    private val dataDirectory: File,
    private val sources: List<String>,
    private val archiveUrl: String,
    private val fetch: (String) -> InputStream = ::httpGet,
) {
    private val cache = File(dataDirectory, "skill-catalog")
    private val installedBase = File(dataDirectory, "skills")
    private val discovery = SkillDiscovery(dataDirectory, sources)

    // One download of a repository at a time: two requests for the same one share it.
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * The skills of every source, downloading the ones not downloaded yet (all of them with [refresh]), in parallel. A
     * source that cannot be read is in the failures, the others are listed.
     */
    suspend fun list(refresh: Boolean): SkillCatalogListing =
        coroutineScope {
            val answers = sources.map { source -> async(Dispatchers.IO) { source to read(source, refresh) } }.awaitAll()
            val skills = answers.flatMap { (_, result) -> result.getOrNull().orEmpty() }
            val failures = answers.mapNotNull { (source, result) -> result.exceptionOrNull()?.let { source to messageOf(it) } }
            SkillCatalogListing(skills, failures.toMap())
        }

    /** Installs the skill at [path] of [source] (downloading the repository when it is not): replaced when it was. */
    fun install(source: String, path: String): AgentSkill {
        if (source !in sources) throw IOException("$source is not a repository of the catalog")
        val folder = synchronized(lockOf(source)) { unpackedOf(source, refresh = false) }
        val from = skillFolder(folder, path)
        val to = File(installedOf(source), installName(path))
        val staging = File(to.parentFile, ".installing-${to.name}")
        staging.deleteRecursively()
        from.copyRecursively(staging, overwrite = true)
        to.deleteRecursively()
        Files.move(staging.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        copyLicense(folder, installedOf(source))
        return discovery.describe(to.path) ?: throw IOException("$path of $source has no SKILL.md")
    }

    private fun read(source: String, refresh: Boolean): Result<List<CatalogSkill>> =
        try {
            val folder = synchronized(lockOf(source)) { unpackedOf(source, refresh) }
            Result.success(skillsOf(source, folder))
        } catch (failure: IOException) {
            Result.failure(failure)
        }

    private fun messageOf(failure: Throwable): String = failure.message ?: "unknown error"

    private fun lockOf(source: String): Any = locks.getOrPut(source) { Any() }

    // The unpacked repository, downloaded first when it is not there or when [refresh] says the user wants it again.
    private fun unpackedOf(source: String, refresh: Boolean): File {
        val folder = File(cache, folderName(source))
        if (refresh || !folder.isDirectory) download(source, folder)
        return folder
    }

    // Downloads and unpacks [source], keeping only its skills and its licence, swapped in once complete.
    private fun download(source: String, folder: File) {
        val url = archiveUrl.replace("{repo}", source)
        cache.mkdirs()
        val staging = Files.createTempDirectory(cache.toPath(), ".download-").toFile()
        try {
            val unpacked = File(staging, "unpacked")
            try {
                fetch(url).use { body -> TarArchive.extract(GZIPInputStream(Limited(body, MAX_UNPACKED_BYTES)), unpacked.toPath(), skipLinks = true) }
            } catch (failure: IOException) {
                throw IOException("Cannot download $source: ${failure.message}", failure)
            }
            // A repository archive holds one folder, named after the repository and the commit.
            val root = unpacked.listFiles()?.singleOrNull { it.isDirectory } ?: unpacked
            val kept = File(staging, "kept")
            keepSkills(root, root, kept)
            copyLicense(root, kept)
            folder.deleteRecursively()
            Files.move(kept.toPath(), folder.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            staging.deleteRecursively()
        }
    }

    // Copies the folders of [from] that hold a `SKILL.md` into [into], under their path from [root], and goes no deeper.
    private fun keepSkills(root: File, from: File, into: File, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        val children = from.listFiles { child -> child.isDirectory && !child.name.startsWith(".") && child.name !in SKIPPED_FOLDERS }
        for (child in children.orEmpty().sortedBy { it.name }) {
            if (File(child, SKILL_FILE).isFile) {
                child.copyRecursively(File(into, child.relativeTo(root).path), overwrite = true)
            } else {
                keepSkills(root, child, into, depth + 1)
            }
        }
    }

    private fun skillsOf(source: String, folder: File): List<CatalogSkill> {
        val found = ArrayList<CatalogSkill>()
        collect(source, folder, folder, found, 0)
        return found.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun collect(source: String, root: File, from: File, found: MutableList<CatalogSkill>, depth: Int) {
        if (depth > MAX_DEPTH) return
        for (child in from.listFiles { child -> child.isDirectory }.orEmpty().sortedBy { it.name }) {
            if (File(child, SKILL_FILE).isFile) {
                found += catalogSkill(source, root, child)
            } else {
                collect(source, root, child, found, depth + 1)
            }
        }
    }

    private fun catalogSkill(source: String, root: File, folder: File): CatalogSkill {
        val path = folder.relativeTo(root).invariantSeparatorsPath
        val skill = discovery.describe(folder.path) ?: AgentSkill(folder.path, folder.name, "", false)
        val installed = File(installedOf(source), installName(path)).isDirectory
        return CatalogSkill(source, path, skill.name, skill.description, installed)
    }

    // The folder of the skill at [path] of an unpacked repository; a path that leaves the repository, or holds no skill, fails.
    private fun skillFolder(repository: File, path: String): File {
        val folder = File(repository, path).canonicalFile
        val inside = folder.toPath().startsWith(repository.canonicalFile.toPath()) && folder != repository.canonicalFile
        if (!inside || !File(folder, SKILL_FILE).isFile) throw IOException("No skill at $path")
        return folder
    }

    private fun copyLicense(from: File, into: File) {
        val license = from.listFiles { file -> file.isFile && file.name.uppercase().startsWith(LICENSE_PREFIX) }?.minByOrNull { it.name }
        if (license != null) {
            into.mkdirs()
            license.copyTo(File(into, license.name), overwrite = true)
        }
    }

    private fun installedOf(source: String) = File(installedBase, folderName(source))

    // `skills/engineering/tdd` is installed as `engineering--tdd`: unique in its repository, and shallow for the discovery.
    private fun installName(path: String): String = path.removePrefix("skills/").replace("/", SEPARATOR_IN_NAME)

    private fun folderName(source: String) = source.replace('/', '-')
}

// Fails once more than [limit] bytes are read: an archive that unpacks to far more than text is refused.
private class Limited(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var read = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) count(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) count(count.toLong())
        return count
    }

    private fun count(bytes: Long) {
        read += bytes
        if (read > limit) throw IOException("The archive is too big")
    }
}
