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

///DEP=org.eclipse.jdt:ecj:3.33.0
///DEP=org.apache.commons:commons-compress:1.25.0

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.eclipse.jdt.core.compiler.CompilationProgress
import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.jar.Manifest
import java.util.zip.ZipFile
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes
import kotlin.io.path.useLines
import kotlin.io.path.writer

private fun Path.sha256(): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this).use { `in` ->
        val buffer = ByteArray(1024 * 4)
        while (true) {
            val r = `in`.read(buffer)
            if (r < 0) break
            md.update(buffer, 0, r)
        }
    }
    return md.digest().joinToString("") { String.format("%02x", it) }
}

private fun updateManifest(manifest: Manifest, compilerLibDir: Path): Manifest {
    val ccpAttribute = "Kotlin-Compiler-Class-Path"
    val compilerClassPath = manifest.mainAttributes.getValue(ccpAttribute) ?: error("no $ccpAttribute in manifest")
    manifest.mainAttributes.putValue(ccpAttribute, compilerClassPath.split(Regex("\\s+")).joinToString(" ") { spec ->
        val (groupId, artifactId, version, classifier) = spec.split(':')
        "$groupId:$artifactId:$version:$classifier:sha256=" + compilerLibDir.resolve("$artifactId.jar").sha256()
    })
    return manifest
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
    input: Path, output: OutputStream, ts: Instant,
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
    Files.newOutputStream(output).use { out ->
        canonicalizeJar(input, out, ts, transformManifest)
    }
}

private fun compileLauncher(
    sources: Path, output: OutputStream, ts: Instant,
    manifest: String,
    repo: Path, kotlinScriptDependencies: List<String>
) {
    val javaSource = sources.resolve("kotlin_script/Launcher.java")
    val updatedSource = mutableListOf<String>()
    javaSource.useLines { lines ->
        var st = 0
        for (line in lines) {
            when (st) {
                0 -> {
                    updatedSource += line
                    if (line.trim() == "private final String[] dependencies = new String[] {") {
                        for (subPath in kotlinScriptDependencies) {
                            updatedSource += "            \"$subPath\","
                        }
                        st = 1
                    }
                    if (line.trim() == "private final byte[][] checksums = new byte[][] {") {
                        for (subPath in kotlinScriptDependencies) {
                            val md = MessageDigest.getInstance("SHA-256")
                            md.update(repo.resolve(subPath).readBytes())
                            updatedSource += md.digest().joinToString(", ", "            new byte[]{", "},")
                        }
                        st = 1
                    }
                    if (line.trim() == "private final long[] sizes = new long[] {") {
                        for (subPath in kotlinScriptDependencies) {
                            updatedSource += "            ${repo.resolve(subPath).fileSize()}L,"
                        }
                        st = 1
                    }
                }
                1 -> if (line.trim() == "};") {
                    updatedSource += line
                    st = 0
                }
                else -> error("invalid st: $st")
            }
        }
    }
    javaSource.writer().use { w ->
        for (line in updatedSource) {
            w.appendLine(line)
        }
    }
    val tmpDir = Files.createTempDirectory("launcher")
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
        Files.createDirectories(tmpDir.resolve("META-INF"))
        Files.newOutputStream(tmpDir.resolve("META-INF/MANIFEST.MF")).use { mfOut ->
            mfOut.write(canonicalizeManifest(manifest.toByteArray()))
        }
        val names = mutableListOf<String>()
        Files.walk(tmpDir).forEach { f ->
            if (f != tmpDir) {
                if (Files.isDirectory(f)) {
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
                Files.newInputStream(tmpDir.resolve(name)).use { `in` ->
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
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.delete(dir)
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

fun main(args: Array<String>) {
    val ts = getTimeStamp().toInstant()
    println("--> git timestamp is $ts")

    val mainJar = Paths.get(args.single())

    val home = Paths.get(System.getProperty("user.home"))
    val repo = home.resolve(".m2/repository")

    val tmp = Files.createTempFile("kotlin_script", ".jar")
    canonicalizeJar(mainJar, tmp, ts) { manifest ->
        val kotlinVersion = manifest.mainAttributes.getValue("Kotlin-Compiler-Version")
            ?: error("no Kotlin-Compiler-Version in manifest")
        val compilerLibDir = mainJar.parent.resolve("kotlin-compiler-$kotlinVersion/kotlinc/lib")
        updateManifest(manifest, compilerLibDir)
    }

    val manifest = ZipFile(mainJar.toFile()).use { z ->
        z.getInputStream(z.getEntry("META-INF/MANIFEST.MF")).use { `in` -> Manifest(`in`) }
    }

    val pom = ZipFile(mainJar.toFile()).use { z ->
        z.getInputStream(z.getEntry("META-INF/maven/org.cikit/kotlin_script/pom.xml")).use { `in` -> String(`in`.readBytes()) }
    }

    val kotlinScriptVersion = manifest.mainAttributes.getValue("Implementation-Version")
    val kotlinScriptDependencies = listOf(
        "org/cikit/kotlin_script/$kotlinScriptVersion/kotlin_script-$kotlinScriptVersion.jar"
    ) + manifest.mainAttributes.getValue("Kotlin-Script-Class-Path").split(' ').map { d ->
        val (groupId, artifactId, version) = d.split(':')
        groupId.replace('.', '/') + "/$artifactId/$version/$artifactId-$version.jar"
    }

    val repoKotlinScript = repo.resolve("org/cikit/kotlin_script/$kotlinScriptVersion")
    Files.createDirectories(repoKotlinScript)

    repoKotlinScript.resolve("kotlin_script-$kotlinScriptVersion.pom").toFile().writeText(pom)

    val mainJarTgt = repoKotlinScript.resolve("kotlin_script-$kotlinScriptVersion.jar")
    Files.move(tmp, mainJarTgt, StandardCopyOption.REPLACE_EXISTING)

    for (item in listOf(
        "kotlin_script-$kotlinScriptVersion-javadoc.jar",
        "kotlin_script-$kotlinScriptVersion-sources.jar",
    )) {
        canonicalizeJar(mainJar.parent.resolve(item), repoKotlinScript.resolve(item), ts)
    }

    val scriptTgt = repoKotlinScript.resolve("kotlin_script-$kotlinScriptVersion.sh")
    FileReader("kotlin_script.sh").use { r ->
        Files.newOutputStream(scriptTgt).use { out ->
            val w = out.bufferedWriter()
            r.useLines { lines ->
                for (line in lines) {
                    w.write(line)
                    w.write("${'\n'}")
                }
            }
            w.flush()
            val mfStr = "Manifest-Version: 1.0\n" +
                    "Implementation-Title: kotlin_script\n" +
                    "Implementation-Version: $kotlinScriptVersion\n" +
                    "Implementation-Vendor: cikit.org\n" +
                    "Main-Class: kotlin_script.Launcher\n"
            compileLauncher(Paths.get("launcher"), out, ts, mfStr, repo, kotlinScriptDependencies)
        }
    }

    val readme = File("README.md").readText()
            .replace(Regex("^v=[^\n]+", RegexOption.MULTILINE), "v=$kotlinScriptVersion")
            .replace(Regex("\"[0-9a-f]{64} \"\\*\\)"), "\"${scriptTgt.sha256()} \"*)")
    File("README.md").writeText(readme)
}
