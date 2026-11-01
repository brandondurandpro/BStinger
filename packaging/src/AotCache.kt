package org.example.packaging

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText

/** Fixed date of the jars (1 January 2000), so that the AOT cache stays valid once installed. */
internal const val JAR_EPOCH_SECONDS = 946_684_800L

private const val MILLIS_PER_SECOND = 1000L
private const val FIRST_JDK_WITH_AOT_CACHE = 24
private const val SECONDS_TO_EXIT = 30L

/**
 * Adds an AOT cache (Project Leyden, Linux) to the application [image]: the application starts, runs
 * [trainingSeconds] seconds at most, then the cache is created from this training run. It must match exactly the
 * runtime and the jars shipped, hence its generation with each package. The JVM ignores a cache that does not
 * match, with a warning: the application still starts, only slower.
 */
internal fun addAotCache(app: App, image: Path, trainingSeconds: Int) {
    val skipped =
        when {
            Os.current != Os.LINUX -> "only made on Linux"
            Runtime.version().feature() < FIRST_JDK_WITH_AOT_CACHE -> "needs JDK $FIRST_JDK_WITH_AOT_CACHE+"
            else -> null
        }
    if (skipped != null) {
        println("[packaging] No AOT cache: $skipped")
        return
    }
    println("[packaging] AOT cache (Project Leyden), training for $trainingSeconds s at most")
    val conf = app.dist.resolve("app.aotconf")
    val cache = image.resolve("lib/app/app.aot")
    val launcher = image.resolve("bin/${app.name}").toString()
    // The JVM only accepts the cache if the jars have the dates of the training run: a fixed date here, given back
    // by the install scripts of the .deb and .rpm, since jpackage copies the jars without it.
    image.resolve("lib/app").listDirectoryEntries("*.jar").forEach {
        it.setLastModifiedTime(FileTime.fromMillis(JAR_EPOCH_SECONDS * MILLIS_PER_SECOND))
    }
    train(launcher, "-XX:AOTMode=record -XX:AOTConfiguration=$conf", trainingSeconds)
    if (conf.exists()) {
        train(
            launcher,
            "-XX:AOTMode=create -XX:AOTConfiguration=$conf -XX:AOTCache=$cache",
            trainingSeconds,
        )
    }
    if (!cache.exists()) {
        println("[packaging] No AOT cache: the training run recorded nothing (no display?)")
        return
    }
    // The cache is only read once created: it goes into the options of the JVM then.
    val cfg = image.resolve("lib/app/${app.name}.cfg")
    cfg.writeText(
        cfg.readText().replaceFirst("[JavaOptions]", "[JavaOptions]\njava-options=-XX:AOTCache=\$APPDIR/app.aot"),
    )
    println("[packaging] AOT cache: ${Files.size(cache) / BYTES_PER_MB} MB")
}

// Runs the application with the JVM [options], [seconds] at most. Output hidden: the training JVM prints dozens of
// harmless warnings. A SIGTERM lets the JVM write its AOT configuration before exiting.
private fun train(launcher: String, options: String, seconds: Int) {
    runCatching {
        val process =
            ProcessBuilder(launcher)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { environment()["JAVA_TOOL_OPTIONS"] = options }
                .start()
        if (!process.waitFor(seconds.toLong(), TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(SECONDS_TO_EXIT, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}
