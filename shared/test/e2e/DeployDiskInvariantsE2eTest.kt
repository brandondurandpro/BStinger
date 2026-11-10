package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A deployment reads the project and writes the destination: the project, its Git repository and what its links point
 * to stay as they were, byte for byte; the destination gets the files as they are, with their line endings,
 * encodings, executable bit and links.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class DeployDiskInvariantsE2eTest {
    private val project = SampleProject.withUnusualFiles().apply { commitAll() }
    private val scratch = Files.createTempDirectory("kotlin-editor-deploy-invariants")
    private val target = scratch.resolve("site")

    @AfterTest
    fun cleanup() {
        project.unlockUnusualFiles()
        project.delete()
        scratch.deleteRecursively()
    }

    @Test
    fun `deploying unusual files changes nothing in the project, and the destination gets them as they are`() =
        runComposeUiTest {
            val before = DiskSnapshot.take(project)
            launchWithProject(project)
            deployFromTree("unusual", target.toString())
            // The deployment is not one of the files it reads: nothing is excepted.
            before.assertUnchanged(project)
            for (name in listOf("crlf.txt", "no-final-newline.txt", "bom.txt", "latin1.php", "données é.txt")) {
                assertContentEquals(
                    project.root.resolve("unusual/$name").readBytes(),
                    target.resolve("unusual/$name").readBytes(),
                    name,
                )
            }
        }

    @Test
    fun `the executable bit and the links of the project are kept in the destination`() =
        runComposeUiTest {
            // Windows: no POSIX permissions, and no link without a privilege.
            if (!WINDOWS_DISK) {
                launchWithProject(project)
                deployFromTree("unusual", target.toString())
                val script = target.resolve("unusual/run.sh")
                assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(script))
                val link = target.resolve("unusual/link.txt")
                assertTrue(Files.isSymbolicLink(link))
                assertEquals("target.txt", Files.readSymbolicLink(link).toString())
                assertTrue(Files.exists(project.root.resolve("unusual/link.txt"), LinkOption.NOFOLLOW_LINKS))
            }
        }

    @Test
    fun `a link to a directory outside the project is deployed as a link and what it points to is untouched`() =
        runComposeUiTest {
            if (project.hasOutsideLink()) {
                val before = DiskSnapshot.take(project)
                launchWithProject(project)
                deployFromTree("linked", target.toString())
                before.assertUnchanged(project)
                assertTrue(Files.isSymbolicLink(target.resolve(OUTSIDE_LINK)))
            }
        }
}
