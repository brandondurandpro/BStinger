package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.kotlineditor.model.DeployFolder
import org.example.kotlineditor.model.DeployProfile
import org.example.kotlineditor.rpc.deployProfiles
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The deployments saved for a project are kept on the server's disk, a project at a time. */
@OptIn(ExperimentalPathApi::class)
class FileDeployProfilesTest {
    private val directory = Files.createTempDirectory("deploy-profiles")
    private val store = FileDeployProfiles(directory.resolve("deployments").toFile())

    private val site =
        DeployProfile(
            "Site",
            listOf(DeployFolder("web", "web"), DeployFolder("docs/api", "reference")),
            "web@example.com:/var/www",
            auto = true,
        )
    private val bin = DeployProfile("Bin", emptyList(), "/srv/bin")

    @AfterTest
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun `saved deployments come back as they were, with their mode and their order`() =
        runBlocking {
            store.save("/work/app", listOf(site, bin))
            assertEquals(
                listOf(site, bin),
                FileDeployProfiles(directory.resolve("deployments").toFile()).load("/work/app"),
            )
        }

    @Test
    fun `each project has its own deployments and an unknown project has none`() =
        runBlocking {
            store.save("/work/app", listOf(site))
            store.save("/work/other", listOf(bin))
            assertEquals(listOf(site), store.load("/work/app"))
            assertEquals(listOf(bin), store.load("/work/other"))
            assertEquals(emptyList(), store.load("/work/never"))
        }

    @Test
    fun `saving replaces the list and leaves no temporary file`() =
        runBlocking {
            store.save("/work/app", listOf(site, bin))
            store.save("/work/app", listOf(bin))
            assertEquals(listOf(bin), store.load("/work/app"))
            val files =
                directory
                    .resolve("deployments")
                    .toFile()
                    .list()
                    .orEmpty()
                    .toList()
            assertTrue(files.none { it.endsWith(".tmp") }, files.toString())
        }

    @Test
    fun `a file that cannot be read is no deployments, and one saved before the modes is manual`() =
        runBlocking {
            store.save("/work/app", listOf(site))
            directory
                .resolve("deployments")
                .toFile()
                .listFiles()
                .orEmpty()
                .forEach { it.writeText("{not json") }
            assertEquals(emptyList(), store.load("/work/app"))
            val old = """[{"name":"Old","sources":["a"],"destination":"/x"}]"""
            assertEquals(
                listOf(DeployProfile("Old", listOf(DeployFolder("a", "a")), "/x", auto = false)),
                Json.parseToJsonElement(old).deployProfiles(),
            )
        }
}
