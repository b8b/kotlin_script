#!/bin/sh

/*/ __kotlin_script_installer__ 2>&-
# vim: syntax=kotlin
#    _         _   _ _                       _       _
#   | |       | | | (_)                     (_)     | |
#   | | _____ | |_| |_ _ __    ___  ___ _ __ _ _ __ | |_
#   | |/ / _ \| __| | | '_ \  / __|/ __| '__| | '_ \| __|
#   |   < (_) | |_| | | | | | \__ \ (__| |  | | |_) | |_
#   |_|\_\___/ \__|_|_|_| |_| |___/\___|_|  |_| .__/ \__|
#                         ______              | |
#                        |______|             |_|
v=1.8.10.18
p=org/cikit/kotlin_script/"$v"/kotlin_script-"$v".sh
url="${M2_CENTRAL_REPO:=https://repo1.maven.org/maven2}"/"$p"
kotlin_script_sh="${M2_LOCAL_REPO:-"$HOME"/.m2/repository}"/"$p"
if ! [ -r "$kotlin_script_sh" ]; then
  kotlin_script_sh="$(mktemp)" || exit 1
  fetch_cmd="$(command -v curl) -kfLSso" || \
    fetch_cmd="$(command -v fetch) --no-verify-peer -aAqo" || \
    fetch_cmd="wget --no-check-certificate -qO"
  if ! $fetch_cmd "$kotlin_script_sh" "$url"; then
    echo "failed to fetch kotlin_script.sh from $url" >&2
    rm -f "$kotlin_script_sh"; exit 1
  fi
  dgst_cmd="$(command -v openssl) dgst -sha256 -r" || dgst_cmd=sha256sum
  case "$($dgst_cmd < "$kotlin_script_sh")" in
  "11ffc2591a99e21602953ba2ebda001237d5953fba547227748c4fdf4a5d4faf "*) ;;
  *) echo "error: failed to verify kotlin_script.sh" >&2
     rm -f "$kotlin_script_sh"; exit 1;;
  esac
fi
. "$kotlin_script_sh"; exit 2
*/

///DEP=org.cikit:kotlin_script:1.8.10.18

///DEP=com.github.ajalt.mordant:mordant-jvm:2.2.0
///DEP=com.github.ajalt.colormath:colormath-jvm:3.3.1
///DEP=org.jetbrains:markdown-jvm:0.5.2
///DEP=it.unimi.dsi:fastutil-core:8.5.12
///DEP=net.java.dev.jna:jna:5.13.0

///DEP=com.github.ajalt.clikt:clikt-jvm:4.2.1

///DEP=org.eclipse.jdt:ecj:3.33.0
///DEP=org.apache.commons:commons-compress:1.25.0

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import kotlin_script.parseDependency
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.eclipse.jdt.core.compiler.CompilationProgress
import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintWriter
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.jar.Manifest
import java.util.zip.ZipFile
import kotlin.io.path.*

private fun Path.sha256(): String {
    val md = MessageDigest.getInstance("SHA-256")
    this.inputStream().use { `in` ->
        val buffer = ByteArray(1024 * 4)
        while (true) {
            val r = `in`.read(buffer)
            if (r < 0) break
            md.update(buffer, 0, r)
        }
    }
    return md.digest().joinToString("") { String.format("%02x", it) }
}

private fun getTimeStamp(): OffsetDateTime {
    val p = ProcessBuilder("git", "log", "-1", "--format=%aI")
        .inheritIO()
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
    val output = p.inputStream.use { `in` -> String(`in`.readBytes()) }
    val rc = p.waitFor()
    if (rc != 0) error("git terminated with exit code $rc")
    return OffsetDateTime.parse(output.trim())
}

private fun gitLsTree(path: Path, baseDir: Path? = path.parent): String {
    val rel = if (baseDir == null) path else path.relativeTo(baseDir)
    val p = ProcessBuilder(
        "git", "ls-tree", "HEAD", rel.pathString)
        .apply {
            if (baseDir != null) {
                directory(baseDir.toFile())
            }
        }
        .inheritIO()
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
    val output = p.inputStream.use { `in` -> String(`in`.readBytes()) }
    val rc = p.waitFor()
    if (rc != 0) error("git terminated with exit code $rc")
    return output.trim()
}

private fun gitCatFile(path: Path, baseDir: Path? = path.parent): String? {
    val sha = gitLsTree(path, baseDir)
        .split(Regex("""\s+"""))
        .getOrNull(2)
        ?: return null
    val p = with (ProcessBuilder("git", "cat-file", "blob", sha)) {
        if (baseDir != null) {
            directory(baseDir.toFile())
        }
        inheritIO()
        redirectOutput(ProcessBuilder.Redirect.PIPE)
        start()
    }
    val output = p.inputStream.use { `in` -> String(`in`.readBytes()) }
    val rc = p.waitFor()
    if (rc != 0) error("git terminated with exit code $rc")
    return output
}

private val trimRegex = Regex(
    """(^\s*).*(\s*)$""",
    setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
)

private fun replaceKeepWs(input: String, replace: String): String {
    return input.replace(trimRegex) { mr ->
        val (prefix, suffix) = mr.destructured
        "${prefix}${replace}$suffix"
    }
}

private fun canonicalizeManifest(data: ByteArray): ByteArray {
    fun OutputStream.writeManifestEntry(bytes: ByteArray) {
        var outIndex = 0
        for (i in bytes.indices) {
            write(bytes[i].toInt())
            outIndex++
            if (outIndex == 70) {
                write(byteArrayOf(0x0D, 0x0A))
                outIndex = 0
                if (i == bytes.indices.last) return
                write(0x20)
                outIndex++
            }
        }
        write(byteArrayOf(0x0D, 0x0A))
    }
    val entries = mutableMapOf<String, StringBuilder>()
    var currentEntry = ""
    for (line in String(data).split("\n").map { line -> line.removeSuffix("\r") }) {
        if (line.isEmpty()) continue
        if (line.first().isWhitespace()) {
            entries.getValue(currentEntry).append(line.substring(1))
            continue
        }
        currentEntry = line.substringBefore(':')
        entries[currentEntry] = StringBuilder(line)
    }
    val order = listOf(
        "Manifest-Version",
        "Implementation-Title",
        "Implementation-Vendor",
        "Implementation-Version",
        "Class-Path"
    )
    ByteArrayOutputStream().use { out ->
        for (k in order) {
            entries.remove(k)?.let {
                val bytes = it.toString().toByteArray()
                out.writeManifestEntry(bytes)
            }
        }
        for ((_, line) in entries.entries.sortedBy { it.key }) {
            val bytes = line.toString().toByteArray()
            out.writeManifestEntry(bytes)
        }
        out.write(byteArrayOf(0x0D, 0x0A))
        return out.toByteArray()
    }
}

private fun canonicalizeJar(
    input: Path,
    output: OutputStream,
    ts: Instant,
    transformManifest: (Manifest) -> Unit = {}
) {
    ZipFile(input.toFile()).use { zf ->
        val names = zf.entries().asSequence().map { it.name }.toMutableList()
        names.sort()
        ZipArchiveOutputStream(output).use { zout ->
            for (name in names) {
                val zeIn = zf.getEntry(name)
                val zeOut = ZipArchiveEntry(name)
                zeOut.creationTime = FileTime.from(ts)
                zeOut.lastModifiedTime = zeOut.creationTime
                zeOut.lastAccessTime = zeOut.creationTime
                if (name == "META-INF/MANIFEST.MF") {
                    val manifest = Manifest(zf.getInputStream(zeIn))
                    transformManifest(manifest)
                    val data = ByteArrayOutputStream().use { tmp ->
                        manifest.write(tmp)
                        canonicalizeManifest(tmp.toByteArray())
                    }
                    zeOut.size = data.size.toLong()
                    zeOut.compressedSize = -1
                    zout.putArchiveEntry(zeOut)
                    zout.write(data)
                } else {
                    zeOut.size = zeIn.size
                    zout.putArchiveEntry(zeOut)
                    zf.getInputStream(zeIn).copyTo(zout)
                }
                zout.closeArchiveEntry()
            }
        }
    }
}

private fun canonicalizeJar(
    input: Path, output: Path, ts: Instant,
    transformManifest: (Manifest) -> Unit = {}
) {
    output.outputStream().use { out ->
        canonicalizeJar(input, out, ts, transformManifest)
    }
}

private fun compileLauncher(
    sources: Path,
    output: OutputStream,
    ts: Instant,
    manifest: String,
    kotlinScriptVersion: String,
    mainJar: Path
) {
    UpdateLauncherCommand.main(
        listOf(
            "--kotlin-script-version", kotlinScriptVersion,
            "--update-jar-sha256", mainJar.sha256(),
            "--update-jar-size", mainJar.fileSize().toString(),
            (sources / "kotlin_script/Launcher.java").absolutePathString()
        )
    )
    val tmpDir = createTempDirectory("launcher")
    try {
        BatchCompiler.compile(
            arrayOf(
                "-d", tmpDir.toString(), "-encoding", "utf8",
                "-source", "1.8", "-target", "1.8", "-g", sources.toString()
            ),
            PrintWriter(System.out),
            PrintWriter(System.err),
            object : CompilationProgress() {
                override fun begin(remainingWork: Int) {
                }

                override fun done() {
                }

                override fun isCanceled(): Boolean = false
                override fun setTaskName(name: String?) {
                }

                override fun worked(workIncrement: Int, remainingWork: Int) {
                }
            }
        )
        val metaInf = (tmpDir / "META-INF").createDirectories()
        (metaInf / "MANIFEST.MF").outputStream().use { mfOut ->
            mfOut.write(canonicalizeManifest(manifest.toByteArray()))
        }
        val names = mutableListOf<String>()
        Files.walk(tmpDir).forEach { f ->
            if (f != tmpDir) {
                if (f.isDirectory()) {
                    names.add(tmpDir.relativize(f).toString() + "/")
                } else {
                    names.add(tmpDir.relativize(f).toString())
                }
            }
        }
        names.sort()
        val zOut = ZipArchiveOutputStream(output)
        for (name in names) {
            val zeOut = ZipArchiveEntry(name)
            zeOut.creationTime = FileTime.from(ts)
            zeOut.lastModifiedTime = zeOut.creationTime
            zeOut.lastAccessTime = zeOut.creationTime
            if (!name.endsWith("/")) {
                zeOut.method = ZipArchiveEntry.DEFLATED
                zOut.putArchiveEntry(zeOut)
                (tmpDir / name).inputStream().use { `in` ->
                    `in`.copyTo(zOut)
                }
            } else {
                zOut.putArchiveEntry(zeOut)
            }
            zOut.closeArchiveEntry()
        }
        zOut.close()
    } finally {
        // cleanup temp dir
        Files.walkFileTree(tmpDir, object : FileVisitor<Path> {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                file.deleteExisting()
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                dir.deleteExisting()
                return FileVisitResult.CONTINUE
            }
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                return FileVisitResult.CONTINUE
            }
        })
    }
}

private object UpdateMainCommand : CliktCommand(
    name = "update-main-sources"
) {
    private val source by argument()
        .path(mustBeReadable = true, mustBeWritable = true)

    private val kotlinScriptVersion by option().required()
    private val kotlinCompilerDependencies by option().required()
    private val kotlinCompilerClassPath by option().required()

    override fun run() {
        val dependencies = kotlinCompilerDependencies.split(Regex("""\s+"""))
            .zip(kotlinCompilerClassPath.split(':'))
            .map { (spec, path) ->
                parseDependency(spec).copy(sha256 = Path(path).sha256())
            }

        val kotlinStdlib = dependencies.first { d -> d.artifactId == "kotlin-stdlib" }
        val kotlinScriptRev = kotlinScriptVersion.removePrefix("${kotlinStdlib.version}.")
        require(kotlinScriptRev.all { it.isDigit() })
        require(kotlinScriptRev.isNotBlank())

        val sourceText = gitCatFile(source)
        require(sourceText != null && sourceText == source.readText()) {
            "$source has been modified"
        }
        val modifiedSource = sourceText
            .replace(Regex("""KOTLIN_VERSION = ".*?"""")) { _ ->
                """KOTLIN_VERSION = "${kotlinStdlib.version}""""
            }
            .replace(Regex("""KOTLIN_SCRIPT_VERSION = ".*?"""")) { _ ->
                """KOTLIN_SCRIPT_VERSION = "${'$'}KOTLIN_VERSION.$kotlinScriptRev""""
            }
            .replace(
                Regex(
                    """(kotlinStdlibDependency = Dependency)\((.*?),(.*?),(.*?),(\s*sha256 = ".*?")""",
                    setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
                )
            ) { mr ->
                val (prefix, g, a, v, sha256) = mr.destructured
                buildString {
                    append(prefix)
                    append("(")
                    append(replaceKeepWs(g, "groupId = KOTLIN_GROUP_ID"))
                    append(",")
                    append(replaceKeepWs(a, "artifactId = \"kotlin-stdlib\""))
                    append(",")
                    append(replaceKeepWs(v, "version = KOTLIN_VERSION"))
                    append(",")
                    append(replaceKeepWs(sha256, "sha256 = \"${kotlinStdlib.sha256}\""))
                }
            }
            .replace(
                Regex(
                    """(// BEGIN_COMPILER_CLASS_PATH\s*).*(// END_COMPILER_CLASS_PATH)""",
                    setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
                )
            ) { mr ->
                val (prefix, suffix) = mr.destructured
                val indent = prefix.substringAfter("\n")
                buildString {
                    append(prefix)
                    append("kotlinStdlibDependency,\n")
                    append(indent)
                    for (d in dependencies) {
                        when {
                            d == kotlinStdlib -> {}
                            d.groupId == kotlinStdlib.groupId &&
                                    d.version == kotlinStdlib.version -> {
                                append("Dependency(\n")
                                append(indent)
                                append("    groupId = KOTLIN_GROUP_ID,\n")
                                append(indent)
                                append("    artifactId = \"")
                                append(d.artifactId)
                                append("\",\n")
                                append(indent)
                                append("    version = KOTLIN_VERSION,\n")
                                append(indent)
                                append("    sha256 = \"")
                                append(d.sha256)
                                append("\"\n")
                                append(indent)
                                append("),\n")
                                append(indent)
                            }

                            else -> {
                                append("Dependency(\n")
                                append(indent)
                                append("    groupId = \"")
                                append(d.groupId)
                                append("\",\n")
                                append(indent)
                                append("    artifactId = \"")
                                append(d.artifactId)
                                append("\",\n")
                                append(indent)
                                append("    version = \"")
                                append(d.version)
                                append("\",\n")
                                append(indent)
                                append("    sha256 = \"")
                                append(d.sha256)
                                append("\"\n")
                                append(indent)
                                append("),\n")
                                append(indent)
                            }
                        }
                    }
                    append(suffix)
                }
            }
        source.writeText(modifiedSource)
    }
}

private object UpdateLauncherCommand : CliktCommand(
    name = "update-launcher-sources"
) {
    private val source by argument()
        .path(mustBeReadable = true, mustBeWritable = true)

    private val kotlinScriptVersion by option().required()

    private val kotlinScriptDependencies by option()
    private val kotlinScriptClassPath by option()

    private val updateJarSha256 by option()
    private val updateJarSize by option().long()

    override fun run() {
        val dependencies = kotlinScriptDependencies
            ?.split(Regex("""\s+"""))
            ?.map { spec -> parseDependency(spec) }
        val classPath = kotlinScriptClassPath
            ?.split(':')
            ?.map { path -> Path(path) }

        val kotlinVersion = kotlinScriptVersion.substringBeforeLast('.')
        val kotlinScriptRev = kotlinScriptVersion.removePrefix("$kotlinVersion.")
        require(kotlinScriptRev.all { it.isDigit() })
        require(kotlinScriptRev.isNotBlank())

        val sourceText = gitCatFile(source)
        require(sourceText != null && sourceText == source.readText()) {
            "$source has been modified"
        }
        val modifiedSource = sourceText
            .replace(Regex("""kotlinVersion = ".*?"""")) { _ ->
                """kotlinVersion = "$kotlinVersion""""
            }
            .replace(Regex("""kotlinScriptVersion = kotlinVersion \+ ".*?"""")) { _ ->
                """kotlinScriptVersion = kotlinVersion + ".$kotlinScriptRev""""
            }
            .replace(
                Regex(
                    """(// BEGIN_KOTLIN_SCRIPT_DEPENDENCY_FILE_NAMES\s*)(.*?\n)(.*)(// END_KOTLIN_SCRIPT_DEPENDENCY_FILE_NAMES)""",
                    setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
                )
            ) { mr ->
                val (prefix, _, otherLines, suffix) = mr.destructured
                val indent = prefix.substringAfter("\n")
                buildString {
                    append(prefix)
                    append("\"org/cikit/kotlin_script/")
                    append(kotlinScriptVersion)
                    append("/kotlin_script-")
                    append(kotlinScriptVersion)
                    append(".jar\",\n")
                    if (dependencies == null) {
                        append(otherLines)
                    } else {
                        append(indent)
                        for (d in dependencies) {
                            append("\"")
                            append(d.subPath)
                            append("\",\n")
                            append(indent)
                        }
                    }
                    append(suffix)
                }
            }
            .replace(
                Regex(
                    """(// BEGIN_KOTLIN_SCRIPT_DEPENDENCY_CHECKSUMS\s*)(.*?\n)(.*)(// END_KOTLIN_SCRIPT_DEPENDENCY_CHECKSUMS)""",
                    setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
                )
            ) { mr ->
                val (prefix, firstLine, otherLines, suffix) = mr.destructured
                val indent = prefix.substringAfter("\n")
                buildString {
                    append(prefix)
                    updateJarSha256?.let { sha256 ->
                        append("new byte[]{")
                        append(sha256.chunked(2).joinToString(", ") { it.toInt(16).toByte().toString() })
                        append("},\n")
                    } ?: append(firstLine)
                    if (classPath == null) {
                        append(otherLines)
                    } else {
                        append(indent)
                        for (f in classPath) {
                            append("new byte[]{")
                            append(f.sha256().chunked(2).joinToString(", ") { it.toInt(16).toByte().toString() })
                            append("},\n")
                            append(indent)
                        }
                    }
                    append(suffix)
                }
            }
            .replace(
                Regex(
                    """(// BEGIN_KOTLIN_SCRIPT_DEPENDENCY_SIZES\s*)(.*?\n)(.*)(// END_KOTLIN_SCRIPT_DEPENDENCY_SIZES)""",
                    setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
                )
            ) { mr ->
                val (prefix, firstLine, otherLines, suffix) = mr.destructured
                val indent = prefix.substringAfter("\n")
                buildString {
                    append(prefix)
                    updateJarSize?.let { size ->
                        append(size)
                        append("L,\n")
                    } ?: append(firstLine)
                    if (classPath == null) {
                        append(otherLines)
                    } else {
                        append(indent)
                        for (f in classPath) {
                            append(f.fileSize())
                            append("L,\n")
                            append(indent)
                        }
                    }
                    append(suffix)
                }
            }
        source.writeText(modifiedSource)
    }
}

private object InstallMainJar : CliktCommand(
    name = "install-main-jar",
    help = "install precompiled kotlin_script to local repository"
) {
    private val mainJar by argument().path(mustBeReadable = true)

    override fun run() {
        val ts = getTimeStamp().toInstant()
        println("--> git timestamp is $ts")

        val home = Path(System.getProperty("user.home"))
        val repo = home / ".m2" / "repository"

        val tmp = createTempFile("kotlin_script", ".jar")
        canonicalizeJar(mainJar, tmp, ts)

        val manifest = ZipFile(mainJar.toFile()).use { z ->
            z.getInputStream(z.getEntry("META-INF/MANIFEST.MF")).use { `in` -> Manifest(`in`) }
        }

        val pom = ZipFile(mainJar.toFile()).use { z ->
            z.getInputStream(z.getEntry("META-INF/maven/org.cikit/kotlin_script/pom.xml"))
                .use { `in` -> String(`in`.readBytes()) }
        }

        val kotlinScriptVersion = manifest.mainAttributes.getValue("Implementation-Version")

        val repoKotlinScript = repo / "org/cikit/kotlin_script/$kotlinScriptVersion"
        repoKotlinScript.createDirectories()

        (repoKotlinScript / "kotlin_script-$kotlinScriptVersion.pom").writeText(pom)

        val mainJarTgt = repoKotlinScript / "kotlin_script-$kotlinScriptVersion.jar"
        tmp.moveTo(mainJarTgt, true)

        for (item in listOf(
            "kotlin_script-$kotlinScriptVersion-javadoc.jar",
            "kotlin_script-$kotlinScriptVersion-sources.jar",
        )) {
            canonicalizeJar(mainJar.parent / item, repoKotlinScript / item, ts)
        }

        val scriptTgt = repoKotlinScript / "kotlin_script-$kotlinScriptVersion.sh"
        Path("kotlin_script.sh").useLines { lines ->
            scriptTgt.outputStream().use { out ->
                val w = out.bufferedWriter()
                for (line in lines) {
                    w.write(line)
                    w.write("${'\n'}")
                }
                w.flush()
                val mfStr = "Manifest-Version: 1.0\n" +
                        "Implementation-Title: kotlin_script\n" +
                        "Implementation-Version: $kotlinScriptVersion\n" +
                        "Implementation-Vendor: cikit.org\n" +
                        "Main-Class: kotlin_script.Launcher\n"
                compileLauncher(
                    Path("launcher"),
                    out,
                    ts,
                    mfStr,
                    kotlinScriptVersion,
                    mainJarTgt
                )
            }
        }

        val readmePath = Path("README.md")
        val readme = readmePath.readText()
            .replace(Regex("^v=[^\n]+", RegexOption.MULTILINE), "v=$kotlinScriptVersion")
            .replace(Regex("\"[0-9a-f]{64} \"\\*\\)"), "\"${scriptTgt.sha256()} \"*)")
        readmePath.writeText(readme)
    }
}

private object InstallCommand: CliktCommand(
    name = "install",
    help = "install precompiled kotlin_script to local repository"
) {
    init {
        subcommands(UpdateMainCommand, UpdateLauncherCommand, InstallMainJar)
    }

    override fun run() {
    }
}

fun main(args: Array<String>) = InstallCommand.main(args)
