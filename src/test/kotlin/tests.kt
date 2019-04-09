import kotlin_script.parseDependency
import org.junit.Test
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.ZipInputStream

class Tests {

    private val buildDir = File("build")
    private val libsDir = File(buildDir, "libs")
    private val homeDir = File(System.getProperty("user.home"))
    private val realLocalRepo = File(homeDir, ".m2/repository")

    //+test.kt:3>
    private val locPattern = "^\\+(.*?):(\\d+)> .*\$".toRegex()

    private data class Script(
            val name: String,
            val sha256: String,
            val lines: List<String>,
            val shellFunctions: Map<String, Int>
    )

    private fun cleanup(dir: Path) {
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun postVisitDirectory(dir: Path, exc: IOException?) =
                    FileVisitResult.CONTINUE.also { Files.delete(dir) }

            override fun visitFile(file: Path, attrs: BasicFileAttributes) =
                    FileVisitResult.CONTINUE.also { Files.delete(file) }
        })
    }

    private fun setupBin(bin: File) {
        if (bin.exists()) cleanup(bin.toPath())
        Files.createDirectories(bin.toPath())
        listOf(
                "rm", "mv", "cp", "mkdir", "dirname", "mktemp",
                "fetch", "openssl", "java"
        ).forEach { tool ->
            val outFile = File(bin, tool)
            Files.newOutputStream(outFile.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                out.write((
                        "#!/bin/sh\n" +
                                "read -r line << __EOF__\n" +
                                "${System.getenv("PATH")}\n" +
                                "__EOF__\n" +
                                "export PATH=\"\$line\"\n" +
                                "exec $tool \"\$@\"\n"
                        ).toByteArray())
            }
            val permissions = PosixFilePermissions.asFileAttribute(setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            ))
            try {
                Files.setPosixFilePermissions(
                        outFile.toPath(),
                        permissions.value())
            } catch (_: UnsupportedOperationException) {
            }
        }
    }

    private fun setupLocalRepo(localRepo: File) {
        if (localRepo.exists()) cleanup(localRepo.toPath())
    }

    private fun setupKsHome(ksHome: File) {
        if (ksHome.exists()) cleanup(ksHome.toPath())
    }

    private fun setupRepo(repo: File, v: String) {
        if (repo.exists()) cleanup(repo.toPath())

        //copy kotlin_script
        val ksSubdir = File(repo, "org/cikit/kotlin_script/kotlin_script/$v")
        Files.createDirectories(ksSubdir.toPath())
        Files.copy(File(libsDir, "kotlin_script-$v.sh").toPath(),
                File(ksSubdir, "kotlin_script-$v.sh").toPath(),
                StandardCopyOption.REPLACE_EXISTING)
        Files.copy(File(libsDir, "kotlin_script-$v.jar").toPath(),
                File(ksSubdir, "kotlin_script-$v.jar").toPath(),
                StandardCopyOption.REPLACE_EXISTING)

        //read compiler class-path
        var manifest: Manifest? = null
        FileInputStream(File(ksSubdir, "kotlin_script-$v.jar")).use { `in` ->
            ZipInputStream(`in`).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (entry.name == "META-INF/MANIFEST.MF") {
                        manifest = Manifest(zin)
                    }
                }
            }
        }
        val compilerClassPath = manifest
                ?.mainAttributes
                ?.getValue("Kotlin-Compiler-Class-Path")
                ?: error("cannot get compiler class-path from manifest in kotlin_script-$v.jar")
        compilerClassPath.split(' ').map(::parseDependency).forEach { d ->
            val target = File(repo, d.subPath)
            Files.createDirectories(target.toPath().parent)
            Files.copy(File(realLocalRepo, d.subPath).toPath(),
                    target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun setupScripts(baseDir: File, v: String, kotlinScript: Script) {
        Files.createDirectories(baseDir.toPath())
        FileWriter(File(baseDir, "inc.kt")).use { w ->
            w.write("fun myFunc() = 1\n")
        }
        FileWriter(File(baseDir, "test.kt")).use { w ->
            w.write("""#!/bin/sh
                    |
                    |/*/ __kotlin_script_installer__ 2>/dev/null
                    |#
                    |#    _         _   _ _                       _       _
                    |#   | |       | | | (_)                     (_)     | |
                    |#   | | _____ | |_| |_ _ __    ___  ___ _ __ _ _ __ | |_
                    |#   | |/ / _ \| __| | | '_ \  / __|/ __| '__| | '_ \| __|
                    |#   |   < (_) | |_| | | | | | \__ \ (__| |  | | |_) | |_
                    |#   |_|\_\___/ \__|_|_|_| |_| |___/\___|_|  |_| .__/ \__|
                    |#                         ______              | |
                    |#                        |______|             |_|
                    |v=$v
                    |artifact=org/cikit/kotlin_script/kotlin_script/"${'$'}v"/kotlin_script-"${'$'}v".sh
                    |if ! [ -e "${'$'}{local_repo:=${'$'}HOME/.m2/repository}"/"${'$'}artifact" ]; then
                    |  fetch_s="${'$'}(command -v fetch) -aAqo" || fetch_s="${'$'}(command -v curl) -fSso"
                    |  mkdir -p "${'$'}local_repo"/org/cikit/kotlin_script/kotlin_script/"${'$'}v"
                    |  tmp_f="${'$'}(mktemp "${'$'}local_repo"/"${'$'}artifact"~XXXXXXXXXXXXXXXX)" || exit 1
                    |  if ! ${'$'}{fetch_cmd:="${'$'}fetch_s"} "${'$'}tmp_f" \
                    |      "${'$'}{repo:=https://repo1.maven.org/maven2}"/"${'$'}artifact"; then
                    |    echo "error: failed to fetch kotlin_script" >&2
                    |    rm -f "${'$'}tmp_f"; exit 1
                    |  fi
                    |  case "${'$'}(openssl dgst -sha256 -r < "${'$'}tmp_f")" in
                    |  "${kotlinScript.sha256} "*)
                    |    mv -f "${'$'}tmp_f" "${'$'}local_repo"/"${'$'}artifact" ;;
                    |  *)
                    |    echo "error: failed to validate kotlin_script" >&2
                    |    rm -f "${'$'}tmp_f"; exit 1 ;;
                    |  esac
                    |fi
                    |. "${'$'}local_repo"/"${'$'}artifact"
                    |exit 2
                    |*/
                    |
                    |///INC=inc.kt
                    |
                    |fun main() {
                    |    if (myFunc() == 1) println("hello world!")
                    |}
                    |""".trimMargin())
        }
    }

    private fun findVersions(): Map<String, Script> {
        val m = mutableMapOf<String, Script>()
        Files.newDirectoryStream(libsDir.toPath()).forEach { p ->
            val fileName = p.fileName.toString()
            val ext = fileName.substringAfterLast('.', "")
            if (fileName.startsWith("kotlin_script-") && ext == "sh") {
                val v = fileName
                        .removePrefix("kotlin_script-")
                        .substringBeforeLast('.')
                val md = MessageDigest.getInstance("SHA-256")
                val lines = FileInputStream(
                        File(libsDir, "kotlin_script-$v.sh")).use { `in` ->
                    val data = `in`.readBytes()
                    md.update(data)
                    String(data).split('\n')
                }
                val sha256 = md.digest().joinToString("") {
                    String.format("%02x", it)
                }
                val shellFunctions = mutableMapOf<String, Int>()
                lines.forEachIndexed { index, line ->
                    if (line.trimStart { ch ->
                                ch.isLetterOrDigit() || ch == '_'
                            } == "()") {
                        shellFunctions[line.removeSuffix("()")] =
                                index.inc()
                    }
                }
                m[v] = Script(fileName, sha256, lines, shellFunctions)
            }
        }
        return m.toMap()
    }

    private fun reportCoverage(baseDir: File,
                               v: String,
                               kotlinScript: Script,
                               linesCovered: Set<Pair<String, Int>>) {
        linesCovered.map { it.first }.toSet().forEach { fileName ->
            val lines = if (fileName == "kotlin_script-$v.sh") {
                kotlinScript.lines
            } else {
                FileReader(File(baseDir, fileName)).useLines { lines ->
                    lines.toList()
                }
            }
            FileWriter(File(baseDir, "$fileName.cov")).use { w ->
                lines.forEachIndexed { index, line ->
                    val ln = fileName to index.inc()
                    if (linesCovered.contains(ln) ||
                            line.isBlank() ||
                            line.trimStart().startsWith("#")) {
                        w.write("   ")
                    } else {
                        w.write("!  ")
                    }
                    w.write(line)
                    w.write("\n")
                }
            }
        }
    }

    private fun runScript(baseDir: File,
                          logFileName: String,
                          v: String,
                          kotlinScript: Script,
                          linesCovered: MutableSet<Pair<String, Int>>,
                          vararg args: String): List<String> {
        println("+ cd $baseDir && ${args.joinToString(" ")}")
        val p = ProcessBuilder(*args)
                .directory(baseDir)
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.to(
                        File(baseDir, logFileName)))
                .redirectErrorStream(true)
                .start()
        p.outputStream.close()
        val rc = p.waitFor()
        val result = mutableListOf<String>()
        FileReader(File(baseDir, logFileName)).useLines { lines ->
            for (line in lines) {
                locPattern.matchEntire(line)?.let { mr ->
                    val (name, ln) = mr.destructured
                    when (val offs = kotlinScript.shellFunctions[name]) {
                        null -> linesCovered.add(
                                File(name).name to ln.toInt()
                        )
                        else -> linesCovered.add(
                                "kotlin_script-$v.sh" to
                                        (ln.toInt() + offs)
                        )
                    }
                    Unit
                } ?: result.add(line)
            }
        }
        if (rc != 0) error("command failed with exit code $rc:\n" +
                "${result.joinToString("\n")}")
        return result.toList()
    }

    @Test
    fun testMain() {
        findVersions().forEach { v, script ->
            val baseDir = File(buildDir, "t_$v")
            setupScripts(baseDir, v, script)

            val repo = File(baseDir, "repo")
            setupRepo(repo, v)

            val binDir = File(baseDir, "bin")
            setupBin(binDir)

            val localRepo = File(baseDir, "local_repo")
            setupLocalRepo(localRepo)

            val ksHome = File(baseDir, "ks_home")
            setupKsHome(ksHome)

            val linesCovered = mutableSetOf<Pair<String, Int>>()

            val env = arrayOf(
                    "PATH=${binDir.absolutePath}",
                    "repo=${repo.toURI()}",
                    "local_repo=${baseDir.toPath().toAbsolutePath().relativize(
                            localRepo.toPath().toAbsolutePath())}",
                    "ks_home=${baseDir.toPath().toAbsolutePath().relativize(
                            ksHome.toPath().toAbsolutePath())}"
            )

            runScript(
                    baseDir, "test_01_from_scratch.out",
                    v, script,
                    linesCovered,
                    "env", *env, "script_file=test.kt",
                    "/usr/local/bin/zsh", "-xy", "test.kt")

            runScript(
                    baseDir, "test_02_all_cached.out",
                    v, script,
                    linesCovered,
                    "env", *env, "script_file=test.kt",
                    "/usr/local/bin/zsh", "-xy", "test.kt")

            FileWriter(File(baseDir, "test_err.kt")).use { w ->
                w.write("hello there\n")
            }
            try {
                runScript(
                        baseDir, "test_03_compile_error.out",
                        v, script,
                        linesCovered,
                        "env", *env, "script_file=test_err.kt",
                        "/usr/local/bin/zsh", "-xy", "test.kt")
            } catch (ex: Exception) {
                println(ex)
            }

            FileWriter(File(baseDir, "test_inv_inc.kt")).use { w ->
                w.write("///INC=nowhere.kt\n")
            }
            try {
                runScript(
                        baseDir, "test_04_compile_inv_inc.out",
                        v, script,
                        linesCovered,
                        "env", *env, "script_file=test_inv_inc.kt",
                        "/usr/local/bin/zsh", "-xy", "test.kt")
            } catch (ex: Exception) {
                println(ex)
            }

            FileWriter(File(baseDir, "test_inv_dep.kt")).use { w ->
                w.write("///DEP=nowhere:nothing:1.0\n")
            }
            try {
                runScript(
                        baseDir, "test_05_inv_dep.out",
                        v, script,
                        linesCovered,
                        "env", *env, "script_file=test_inv_dep.kt",
                        "/usr/local/bin/zsh", "-xy", "test.kt")
            } catch (ex: Exception) {
                println(ex)
            }

            reportCoverage(baseDir, v, script, linesCovered)
        }
    }
}
