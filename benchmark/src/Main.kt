package org.example.kotlineditor.benchmark

import com.github.luben.zstd.Zstd
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.system.exitProcess

/** Command-line options of the benchmark. */
class Options(
    val repo: Path,
    val php: Path,
    val phpProject: Path?,
    val kotlinLibs: List<Path>,
    val dictDir: Path,
    val localDictDir: Path,
    val out: Path?,
    val iterations: Int,
    val train: Boolean,
)

/** One measured codec on one payload set. */
class BenchResult(
    val codec: String,
    val rawBytes: Long,
    val wireBytes: Long,
    val compressedPayloads: Int,
    val encodeNanos: Double,
    val decodeNanos: Double,
    val payloadCount: Int,
)

/** A named set of bodies, measured together as one workload. */
class PayloadSet(val title: String, val payloads: List<Payload>)

fun main(args: Array<String>) {
    val options = parse(args)
    if (options.train) {
        train(options)
    } else {
        benchmark(options)
    }
}

private fun parse(args: Array<String>): Options {
    var repo = Paths.get("").toAbsolutePath().normalize()
    var php = Paths.get("/usr/share/php")
    var phpProject: Path? = null
    val kotlinLibs = ArrayList<Path>()
    var dictDir: Path? = null
    var localDictDir: Path? = null
    var out: Path? = null
    var iterations = 30
    var train = false
    for (arg in args) {
        val key = if (arg.contains('=')) arg.substringBefore('=') else arg
        val value = if (arg.contains('=')) arg.substringAfter('=') else ""
        when (key) {
            "--repo" -> repo = Paths.get(value).toAbsolutePath().normalize()
            "--php" -> php = Paths.get(value)
            "--php-project" -> phpProject = Paths.get(value).toAbsolutePath().normalize()
            "--kotlin-libs" -> value.split(',').filter { it.isNotBlank() }.forEach { kotlinLibs.add(Paths.get(it)) }
            "--dict-dir" -> dictDir = Paths.get(value)
            "--local-dict-dir" -> localDictDir = Paths.get(value)
            "--out" -> out = Paths.get(value)
            "--iterations" -> iterations = value.toInt()
            "--train" -> train = true
            "--help", "-h" -> {
                printHelp()
                exitProcess(0)
            }
            else -> error("Unknown argument: $arg")
        }
    }
    return Options(
        repo = repo,
        php = php,
        phpProject = phpProject,
        kotlinLibs = kotlinLibs,
        dictDir = dictDir ?: repo.resolve("benchmark/dicts"),
        localDictDir = localDictDir ?: repo.resolve("benchmark/dicts-local"),
        out = out,
        iterations = iterations,
        train = train,
    )
}

private fun printHelp() {
    println(
        """
        Usage: ./kotlin run -m benchmark -- [options]

          --train                   Train the dictionaries, then exit.
          --repo=<dir>              Project whose Kotlin files are measured (default: current directory).
          --php=<dir>               Public PHP corpus for the generic dictionary (default: /usr/share/php).
          --php-project=<dir>       PHP project used for the project dictionary and the PHP tests.
          --kotlin-libs=<dirs>      Comma-separated Kotlin source trees for the generic dictionary.
          --dict-dir=<dir>          Public dictionaries (default: benchmark/dicts).
          --local-dict-dir=<dir>    Project dictionaries, never committed (default: benchmark/dicts-local).
          --out=<file>              Also write the Markdown report to this file.
          --iterations=<n>          Timed iterations per codec (default: 30).
        """.trimIndent(),
    )
}

private fun train(options: Options) {
    val repoFiles = loadSources(options.repo, "kt")
    val genericPhpFiles = loadSources(options.php, "php")
    val projectPhpFiles = options.phpProject?.let { loadSources(it, "php") }
    val libs = options.kotlinLibs.flatMap { loadSources(it, "kt") }
    require(libs.size > 10) { "Need at least 11 Kotlin library files for the generic dictionary; got ${libs.size}" }
    val (repoTrain, _) = split(repoFiles)
    val genericKotlin = libs.map { it.readBody() }
    val genericPhp = genericPhpFiles.map { it.readBody() }
    val projectKotlin = repoTrain.map { it.readBody() }
    val projectPhp = projectPhpFiles?.let { split(it).first.map { file -> file.readBody() } }
    val mixed = genericKotlin + genericPhp
    Files.createDirectories(options.dictDir)
    Files.createDirectories(options.localDictDir)
    for (size in listOf(32768, 65536, 112640)) {
        writeDict(options.dictDir.resolve("kotlin-generic-${size / 1024}k.dict"), trainDictionary(genericKotlin, size))
        writeDict(options.dictDir.resolve("php-${size / 1024}k.dict"), trainDictionary(genericPhp, size))
        writeDict(options.dictDir.resolve("mixed-${size / 1024}k.dict"), trainDictionary(mixed, size))
        writeDict(options.localDictDir.resolve("kotlin-project-${size / 1024}k.dict"), trainDictionary(projectKotlin, size))
        if (projectPhp != null) {
            writeDict(options.localDictDir.resolve("php-project-${size / 1024}k.dict"), trainDictionary(projectPhp, size))
        }
    }
    println("Public dictionaries: ${options.dictDir}")
    println("Project dictionaries: ${options.localDictDir}")
}

private fun trainDictionary(samples: List<ByteArray>, size: Int): ByteArray {
    require(samples.size > 10) { "Need at least 11 samples, got ${samples.size}" }
    val buffer = ByteArray(size)
    val result = Zstd.trainFromBuffer(samples.toTypedArray(), buffer, false, 3)
    check(!Zstd.isError(result)) { "zstd dictionary training failed: ${Zstd.getErrorName(result)}" }
    return buffer.copyOf(result.toInt())
}

private fun writeDict(path: Path, dictionary: ByteArray) {
    Files.write(path, dictionary)
    println("${path.fileName}: ${dictionary.size} bytes")
}

private fun benchmark(options: Options) {
    val repoFiles = loadSources(options.repo, "kt")
    val phpFiles = options.phpProject?.let { loadSources(it, "php") } ?: loadSources(options.php, "php")
    val (_, repoTest) = split(repoFiles)
    val (_, phpTestAll) = split(phpFiles)
    val phpTest = sampleEvenly(phpTestAll, 200)

    val kotlinDict = loadDict(options.dictDir, "kotlin-generic-64k.dict")
    val phpDict = loadDict(options.dictDir, "php-64k.dict")
    val mixedDict = loadDict(options.dictDir, "mixed-64k.dict")
    val kotlinProjectDict = loadDict(options.localDictDir, "kotlin-project-64k.dict")
    val phpProjectDict = loadDict(options.localDictDir, "php-project-64k.dict")

    val kotlinRead = PayloadSet("Kotlin : lecture de fichiers (${repoFiles.size} fichiers)", repoFiles.map { it.payload() })
    val kotlinProjectRead = PayloadSet("Kotlin : lecture de fichiers (${repoTest.size} fichiers de test)", repoTest.map { it.payload() })
    val phpRead = PayloadSet("PHP : lecture de fichiers (${phpTest.size} fichiers, échantillon)", phpTest.map { it.payload() })
    val phpFull = PayloadSet("PHP : corpus de test complet (${phpTestAll.size} fichiers)", phpTestAll.map { it.payload() })
    val kotlinSmall = PayloadSet("Kotlin : petites requêtes et réponses", smallPayloads(repoFiles, "fun "))
    val phpSmall = PayloadSet("PHP : petites requêtes et réponses", smallPayloads(phpTest, "function "))
    val kotlinProject = PayloadSet("Kotlin : requêtes de projet", projectPayloads(repoFiles, "fun ", includeSymbols = true))
    val phpProject = PayloadSet("PHP : requêtes de projet", projectPayloads(phpTest, "function ", includeSymbols = false))
    val kotlinSession = PayloadSet("Kotlin : session réaliste", sessionPayloads(repoFiles, "fun ", includeSymbols = true))
    val phpSession = PayloadSet("PHP : session réaliste", sessionPayloads(phpTest, "function ", includeSymbols = false))
    val large = PayloadSet("Kotlin : fichier synthétique de 200 000 lignes", listOf(largePayload(repoFiles)))

    val report = StringBuilder()
    report.appendLine("# Benchmark zstd + dictionnaire de code")
    report.appendLine()
    report.appendLine("Machine : ${System.getProperty("os.arch")}, JVM ${System.getProperty("java.version")}, zstd-jni 1.5.6-10.")
    report.appendLine("Corpus Kotlin : ${repoFiles.size} fichiers, ${bytes(repoFiles.sumOf { it.readBody().size.toLong() })} octets de corps.")
    report.appendLine("Corpus PHP : ${phpFiles.size} fichiers, ${bytes(phpFiles.sumOf { it.readBody().size.toLong() })} octets de corps; ${phpTest.size} fichiers échantillonnés pour les mesures de temps.")
    report.appendLine()

    appendTable(report, kotlinRead, runSet(kotlinRead, kotlinCodecs(kotlinDict), options.iterations))
    appendSizeBuckets(report, repoFiles, kotlinDict, options.iterations)
    appendTable(report, kotlinSmall, runSet(kotlinSmall, kotlinCodecs(kotlinDict), options.iterations))
    appendTable(report, kotlinProject, runSet(kotlinProject, kotlinCodecs(kotlinDict), options.iterations))
    appendTable(report, kotlinSession, runSet(kotlinSession, kotlinCodecs(kotlinDict, mixedDict, hybrid = true), options.iterations))
    appendTable(report, phpRead, runSet(phpRead, phpCodecs(phpDict, phpProjectDict), options.iterations))
    appendTable(report, phpFull, sizeOnly(phpFull, phpCodecs(phpDict, phpProjectDict)), timing = false)
    appendTable(report, phpSmall, runSet(phpSmall, phpCodecs(phpDict, phpProjectDict), options.iterations))
    appendTable(report, phpProject, runSet(phpProject, phpCodecs(phpDict, phpProjectDict), options.iterations))
    appendTable(report, phpSession, runSet(phpSession, phpCodecs(phpDict, phpProjectDict, mixedDict, hybrid = true), options.iterations))
    appendTable(report, large, runSet(large, kotlinCodecs(kotlinDict), 10))

    if (kotlinProjectDict != null) {
        appendTable(
            report,
            kotlinProjectRead,
            runSet(
                kotlinProjectRead,
                listOf(CurrentDeflate(), ZstdCodec("zstd-3 + dict Kotlin générique", 3, kotlinDict), ZstdCodec("zstd-3 + dict projet Kotlin", 3, kotlinProjectDict)),
                options.iterations,
            ),
        )
    }

    appendDictionarySizes(report, options, kotlinRead, phpRead, kotlinDict, phpDict)
    appendCrossLanguage(report, kotlinRead, phpRead, kotlinDict, phpDict)

    val text = report.toString()
    println(text)
    options.out?.let { path ->
        Files.createDirectories(path.toAbsolutePath().parent)
        Files.writeString(path, text)
        println("Report written to $path")
    }
}

private fun kotlinCodecs(dictionary: ByteArray?, mixed: ByteArray? = null, hybrid: Boolean = false): List<Codec> {
    val codecs = ArrayList<Codec>()
    codecs += CurrentDeflate()
    for (level in listOf(1, 3, 9)) codecs += ZstdCodec("zstd-$level sans dict", level)
    if (dictionary != null) for (level in listOf(1, 3, 9)) codecs += ZstdCodec("zstd-$level + dict Kotlin", level, dictionary)
    if (mixed != null) codecs += ZstdCodec("zstd-3 + dict mixte", 3, mixed)
    if (hybrid && dictionary != null) codecs += HybridZstdCodec("zstd-3 hybride (dict sur le code)", 3, dictionary)
    return codecs
}

private fun phpCodecs(generic: ByteArray?, project: ByteArray?, mixed: ByteArray? = null, hybrid: Boolean = false): List<Codec> {
    val codecs = ArrayList<Codec>()
    codecs += CurrentDeflate()
    for (level in listOf(1, 3, 9)) codecs += ZstdCodec("zstd-$level sans dict", level)
    if (generic != null) codecs += ZstdCodec("zstd-3 + dict PHP générique", 3, generic)
    if (project != null) {
        codecs += ZstdCodec("zstd-3 + dict PHP projet", 3, project)
        codecs += ZstdCodec("zstd-9 + dict PHP projet", 9, project)
    }
    if (mixed != null) codecs += ZstdCodec("zstd-3 + dict mixte", 3, mixed)
    val hybridDictionary = project ?: generic
    if (hybrid && hybridDictionary != null) codecs += HybridZstdCodec("zstd-3 hybride (dict sur le code)", 3, hybridDictionary)
    return codecs
}

private fun runSet(set: PayloadSet, codecs: List<Codec>, iterations: Int): List<BenchResult> {
    val raw = set.payloads.sumOf { it.body.size.toLong() }
    return codecs.map { codec ->
        codec.use {
            val encoded = set.payloads.map { payload -> codec.encode(payload.body, payload.kind) }
            val wire = set.payloads.indices.sumOf { index -> FRAME_HEADER + encoded[index].data.size.toLong() }
            val compressed = encoded.count { it.compressed }
            repeat(5) { for (payload in set.payloads) codec.decode(codec.encode(payload.body, payload.kind)) }
            val encodeTimes = LongArray(iterations)
            val decodeTimes = LongArray(iterations)
            for (iteration in 0 until iterations) {
                val start = System.nanoTime()
                val fresh = set.payloads.map { codec.encode(it.body, it.kind) }
                encodeTimes[iteration] = System.nanoTime() - start
                val decodeStart = System.nanoTime()
                fresh.forEach { codec.decode(it) }
                decodeTimes[iteration] = System.nanoTime() - decodeStart
            }
            BenchResult(codec.name, raw, wire, compressed, median(encodeTimes), median(decodeTimes), set.payloads.size)
        }
    }
}

/** Encodes each body once: used for the full corpus, where timing every file would take too long. */
private fun sizeOnly(set: PayloadSet, codecs: List<Codec>): List<BenchResult> {
    val raw = set.payloads.sumOf { it.body.size.toLong() }
    return codecs.map { codec ->
        codec.use {
            val encoded = set.payloads.map { payload -> codec.encode(payload.body, payload.kind) }
            val wire = set.payloads.indices.sumOf { index -> FRAME_HEADER + encoded[index].data.size.toLong() }
            BenchResult(codec.name, raw, wire, encoded.count { it.compressed }, 0.0, 0.0, set.payloads.size)
        }
    }
}

private fun appendTable(report: StringBuilder, set: PayloadSet, results: List<BenchResult>, timing: Boolean = true) {
    val baseline = results.first()
    report.appendLine("## ${set.title}")
    report.appendLine()
    if (timing) {
        report.appendLine("| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés | Encodage | Décodage |")
        report.appendLine("|---|---:|---:|---:|---:|---:|---:|")
    } else {
        report.appendLine("| Codec | Octets sur le fil | Gain vs actuel | Ratio | Corps compressés |")
        report.appendLine("|---|---:|---:|---:|---:|")
    }
    for (result in results) {
        val gain = (1.0 - result.wireBytes.toDouble() / baseline.wireBytes) * PERCENT
        val ratio = result.rawBytes.toDouble() / result.wireBytes
        val common = "| ${result.codec} | ${bytes(result.wireBytes)} | ${percent(gain)} | ${ratio(ratio)} | ${result.compressedPayloads}/${result.payloadCount} |"
        report.appendLine(if (timing) "$common ${millis(result.encodeNanos)} | ${millis(result.decodeNanos)} |" else common)
    }
    report.appendLine()
}

private fun appendSizeBuckets(report: StringBuilder, files: List<SourceFile>, dictionary: ByteArray?, iterations: Int) {
    val buckets = listOf(0 to 2048, 2048 to 4096, 4096 to 8192, 8192 to 16384, 16384 to Int.MAX_VALUE)
    report.appendLine("## Kotlin : effet du dictionnaire par taille de fichier")
    report.appendLine()
    report.appendLine("| Taille du corps | Fichiers | Actuel | zstd-3 + dict Kotlin | Gain | Ratio zstd |")
    report.appendLine("|---|---:|---:|---:|---:|---:|")
    for ((min, max) in buckets) {
        val payloads = files.map { it to it.readBody().size }.filter { it.second in min until max }.map { Payload(it.first.entry.name, it.first.readBody(), PayloadKind.CODE) }
        if (payloads.isEmpty()) continue
        val set = PayloadSet("", payloads)
        val codecs = listOf(CurrentDeflate(), ZstdCodec("zstd-3 + dict Kotlin", 3, dictionary))
        val results = runSet(set, codecs, iterations)
        val current = results[0]
        val zstd = results[1]
        val gain = (1.0 - zstd.wireBytes.toDouble() / current.wireBytes) * PERCENT
        val ratio = zstd.rawBytes.toDouble() / zstd.wireBytes
        val label = if (max == Int.MAX_VALUE) "≥ 16 Ko" else "${min / 1024}–${max / 1024} Ko"
        report.appendLine("| $label | ${payloads.size} | ${bytes(current.wireBytes)} | ${bytes(zstd.wireBytes)} | ${percent(gain)} | ${ratio(ratio)} |")
    }
    report.appendLine()
}

private class DictSweep(
    val label: String,
    val set: PayloadSet,
    val prefix: String,
    val fallback: ByteArray?,
)

private fun appendDictionarySizes(
    report: StringBuilder,
    options: Options,
    kotlinSet: PayloadSet,
    phpSet: PayloadSet,
    kotlinDict: ByteArray?,
    phpDict: ByteArray?,
) {
    val sizes = listOf(32, 64, 110)
    val sweeps =
        listOf(
            DictSweep("Kotlin", kotlinSet, "kotlin-generic", kotlinDict),
            DictSweep("PHP", phpSet, "php", phpDict),
        )
    for (sweep in sweeps) {
        val codecs = ArrayList<Codec>()
        codecs += CurrentDeflate()
        codecs += ZstdCodec("zstd-3 sans dict", 3)
        for (size in sizes) {
            val dictionary = loadDict(options.dictDir, "${sweep.prefix}-${size}k.dict") ?: sweep.fallback?.takeIf { size == 64 }
            if (dictionary != null) codecs += ZstdCodec("zstd-3 + dict ${size}K", 3, dictionary)
        }
        val titled = PayloadSet("${sweep.label} : tailles de dictionnaire", sweep.set.payloads)
        appendTable(report, titled, runSet(titled, codecs, 10))
    }
}

private fun appendCrossLanguage(
    report: StringBuilder,
    kotlinSet: PayloadSet,
    phpSet: PayloadSet,
    kotlinDict: ByteArray?,
    phpDict: ByteArray?,
) {
    if (kotlinDict != null && phpDict != null) {
        val kotlin = PayloadSet("Kotlin : dictionnaire d'une autre langue", kotlinSet.payloads)
        appendTable(
            report,
            kotlin,
            runSet(
                kotlin,
                listOf(
                    CurrentDeflate(),
                    ZstdCodec("zstd-3 sans dict", 3),
                    ZstdCodec("zstd-3 + dict Kotlin", 3, kotlinDict),
                    ZstdCodec("zstd-3 + dict PHP", 3, phpDict),
                ),
                10,
            ),
        )
        val php = PayloadSet("PHP : dictionnaire d'une autre langue", phpSet.payloads)
        appendTable(
            report,
            php,
            runSet(
                php,
                listOf(
                    CurrentDeflate(),
                    ZstdCodec("zstd-3 sans dict", 3),
                    ZstdCodec("zstd-3 + dict PHP", 3, phpDict),
                    ZstdCodec("zstd-3 + dict Kotlin", 3, kotlinDict),
                ),
                10,
            ),
        )
    }
}

private fun loadDict(dir: Path, name: String): ByteArray? {
    val path = dir.resolve(name)
    if (!Files.exists(path)) return null
    return Files.readAllBytes(path)
}

private fun sampleEvenly(files: List<SourceFile>, count: Int): List<SourceFile> {
    if (files.size <= count) return files
    val sorted = files.sortedBy { it.lines.size }
    val step = sorted.size.toDouble() / count
    return List(count) { index -> sorted[(index * step).toInt().coerceAtMost(sorted.lastIndex)] }
}

private fun bytes(value: Long): String = String.format(Locale.ROOT, "%,d", value)

private fun percent(value: Double): String = String.format(Locale.ROOT, "%+.1f %%", value)

private fun ratio(value: Double): String = String.format(Locale.ROOT, "%.2f×", value)

private fun millis(nanos: Double): String = String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000)

private const val PERCENT = 100.0
