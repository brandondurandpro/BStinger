package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.example.kotlineditor.model.DeployProfile
import org.example.kotlineditor.model.DeployProfiles
import org.example.kotlineditor.model.pathName
import org.example.kotlineditor.rpc.deployProfiles
import org.example.kotlineditor.rpc.toJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val UNSAFE_NAME = Regex("[^A-Za-z0-9._-]")

/**
 * [DeployProfiles] kept in [directory], one JSON file per project (the wire format of the profiles), written to a
 * temporary file then moved so that a crash never leaves half a file. A file that cannot be read is no deployments.
 */
class FileDeployProfiles(private val directory: File) : DeployProfiles {
    override suspend fun load(projectPath: String): List<DeployProfile> =
        withContext(Dispatchers.IO) {
            val file = fileFor(projectPath)
            runCatching { Json.parseToJsonElement(file.readText()).deployProfiles() }.getOrDefault(emptyList())
        }

    override suspend fun save(projectPath: String, profiles: List<DeployProfile>) {
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val file = fileFor(projectPath)
            val temporary = File(directory, file.name + ".tmp")
            temporary.writeText(profiles.toJson().toString())
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fileFor(projectPath: String): File {
        val hash = Integer.toHexString(projectPath.hashCode())
        val name = pathName(projectPath).replace(UNSAFE_NAME, "_")
        return File(directory, "$hash-$name.json")
    }
}
