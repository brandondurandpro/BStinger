package org.example.kotlineditor.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

// The agents of the ACP registry (https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json): how each one
// is distributed, an archive per platform or a package that npx or uvx runs. Unknown fields are ignored.

/** A package that `npx` or `uvx` runs: `@scope/name@1.2.3`, its arguments and its environment. */
internal data class RegistryPackage(val name: String, val arguments: List<String>, val environment: Map<String, String>)

/** The archive of an agent for one platform: where it is, its checksum, and the command it holds. */
internal data class RegistryBinary(
    val archive: String,
    /** Path of the program in the archive (`./bin/agent`). */
    val command: String,
    /** SHA-256 of the archive, hexadecimal; null when the registry gives none. */
    val sha256: String?,
    val arguments: List<String>,
    val environment: Map<String, String>,
)

/** An agent of the registry. */
internal data class RegistryAgent(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val npx: RegistryPackage?,
    val uvx: RegistryPackage?,
    /** Its archives, by platform (`linux-x86_64`, `darwin-aarch64`, `windows-x86_64`…). */
    val binaries: Map<String, RegistryBinary>,
)

/** The agents of a registry document; an agent that cannot be read is left out, a document that cannot fails. */
internal fun parseRegistry(text: String): List<RegistryAgent> {
    val root =
        runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw IOException("The agent registry is not a JSON object")
    return root["agents"].array().mapNotNull { it.obj()?.registryAgent() }
}

private fun JsonObject.registryAgent(): RegistryAgent? {
    val id = text("id")
    val distribution = get("distribution").obj()
    if (id == null || distribution == null) return null
    return RegistryAgent(
        id = id,
        name = text("name") ?: id,
        version = text("version").orEmpty(),
        description = text("description").orEmpty(),
        npx = distribution["npx"].obj()?.registryPackage(),
        uvx = distribution["uvx"].obj()?.registryPackage(),
        binaries = distribution["binary"].obj()?.binaries().orEmpty(),
    )
}

private fun JsonObject.registryPackage(): RegistryPackage? {
    val name = text("package") ?: return null
    return RegistryPackage(name, strings("args"), environment())
}

private fun JsonObject.binaries(): Map<String, RegistryBinary> =
    entries.mapNotNull { (platform, target) -> target.obj()?.binary()?.let { platform to it } }.toMap()

private fun JsonObject.binary(): RegistryBinary? {
    val archive = text("archive")
    val command = text("cmd")
    if (archive == null || command == null) return null
    return RegistryBinary(archive, command, text("sha256"), strings("args"), environment())
}

private fun JsonObject.strings(key: String): List<String> =
    get(key).array().mapNotNull { (it as? JsonPrimitive)?.content }

private fun JsonObject.environment(): Map<String, String> {
    val env = get("env").obj() ?: return emptyMap()
    return env.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.content?.let { name to it } }.toMap()
}

/** The platform of this machine as the registry names it (`linux-x86_64`), null for one it does not know. */
internal fun registryPlatform(
    osName: String = System.getProperty("os.name"),
    architecture: String = System.getProperty("os.arch"),
): String? {
    val os =
        when {
            osName.startsWith("Windows") -> "windows"
            osName.startsWith("Mac") -> "darwin"
            osName.startsWith("Linux") -> "linux"
            else -> null
        }
    val cpu =
        when (architecture) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> null
        }
    return if (os == null || cpu == null) null else "$os-$cpu"
}

/** The platform of this machine as the registry names it, for the registries of the tests. */
fun registryPlatformForTests(): String = checkNotNull(registryPlatform()) { "The tests run on an unknown platform" }
