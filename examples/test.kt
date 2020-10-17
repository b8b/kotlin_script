#!/bin/sh

/*__kotlin_script_installer__/ 2>/dev/null
#
#    _         _   _ _                       _       _
#   | |       | | | (_)                     (_)     | |
#   | | _____ | |_| |_ _ __    ___  ___ _ __ _ _ __ | |_
#   | |/ / _ \| __| | | '_ \  / __|/ __| '__| | '_ \| __|
#   |   < (_) | |_| | | | | | \__ \ (__| |  | | |_) | |_
#   |_|\_\___/ \__|_|_|_| |_| |___/\___|_|  |_| .__/ \__|
#                         ______              | |
#                        |______|             |_|
v=1.4.10.0
artifact=org/cikit/kotlin_script/kotlin_script/"$v"/kotlin_script-"$v".sh
if ! [ -e "${local_repo:=$HOME/.m2/repository}"/"$artifact" ]; then
  fetch_s="$(command -v fetch) -aAqo" || fetch_s="$(command -v curl) -fSso"
  mkdir -p "$local_repo"/org/cikit/kotlin_script/kotlin_script/"$v"
  tmp_f="$(mktemp "$local_repo"/"$artifact"~XXXXXXXXXXXXXXXX)" || exit 1
  if ! ${fetch_cmd:="$fetch_s"} "$tmp_f" \
      "${repo:=https://repo1.maven.org/maven2}"/"$artifact"; then
    echo "error: failed to fetch kotlin_script" >&2
    rm -f "$tmp_f"; exit 1
  fi
  case "$(openssl dgst -sha256 -r < "$tmp_f")" in
  "5c745d2793ee85fa929c76c02969dfb072c82b33fa833cced65571f506831b9b "*)
    mv -f "$tmp_f" "$local_repo"/"$artifact" ;;
  *)
    echo "error: failed to validate kotlin_script" >&2
    rm -f "$tmp_f"; exit 1 ;;
  esac
fi
. "$local_repo"/"$artifact"
exit 2
*/

///MAIN=TestKt

import java.io.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.ZipInputStream
import kotlin.reflect.KClass
import kotlin.system.exitProcess

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

fun main(args: Array<String>) {
    val v = args.singleOrNull()?.removePrefix("-v")
            ?: error("usage: test.kt -v<version>")

    val homeDir = File(System.getProperty("user.home"))
    val realLocalRepo = File(homeDir, ".m2/repository")

    val libsDir = File(realLocalRepo, "org/cikit/kotlin_script/kotlin_script/$v")

    //+test.kt:3>
    val locPattern = "^\\+(.*?):(\\d+)> .*\$".toRegex()

    val kotlinScript = {
        val fileName = "kotlin_script-$v.sh"
        val md = MessageDigest.getInstance("SHA-256")
        val lines = FileInputStream(File(libsDir, fileName)).use { `in` ->
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
        Script(fileName, sha256, lines, shellFunctions)
    }()

    val buildDir = File("build")
    val baseDir = File(buildDir, "t_$v")
    val repo = File(baseDir, "repo")
    val binDir = File(baseDir, "bin")
    val localRepo = File(baseDir, "local_repo")
    val ksHome = File(baseDir, "ks_home")
    val linesCovered = mutableSetOf<Pair<String, Int>>()

    val env = arrayOf(
            "PATH=${binDir.absolutePath}",
            "repo=${repo.toURI()}",
            "local_repo=${baseDir.toPath().toAbsolutePath().relativize(
                    localRepo.toPath().toAbsolutePath())}",
            "ks_home=${baseDir.toPath().toAbsolutePath().relativize(
                    ksHome.toPath().toAbsolutePath())}"
    )

    val zsh = ProcessBuilder("sh", "-c", "command -v zsh").let { pb ->
        pb.inheritIO()
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
        val p = pb.start()
        val out = p.inputStream.use { `in` -> String(`in`.readBytes()).trim() }
        val rc = p.waitFor()
        if (rc != 0) error("cannot find location of zsh command")
        out
    }

    val fetch = ProcessBuilder("sh", "-c", "command -v fetch").let { pb ->
        pb.inheritIO()
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
        val p = pb.start()
        val out = p.inputStream.use { `in` -> String(`in`.readBytes()).trim() }
        val rc = p.waitFor()
        if (rc != 0) null else out
    }

    val curl = ProcessBuilder("sh", "-c", "command -v curl").let { pb ->
        pb.inheritIO()
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
        val p = pb.start()
        val out = p.inputStream.use { `in` -> String(`in`.readBytes()).trim() }
        val rc = p.waitFor()
        if (rc != 0) null else out
    }

    fun setupBin() {
        if (binDir.exists()) cleanup(binDir.toPath())
        Files.createDirectories(binDir.toPath())
        listOf(
                "rm", "mv", "cp", "mkdir", "mktemp",
                *(if (fetch == null) emptyArray() else arrayOf("fetch")),
                *(if (curl == null) emptyArray() else arrayOf("curl")),
                "openssl", "java"
        ).forEach { tool ->
            val outFile = File(binDir, tool)
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

    fun setupLocalRepo() {
        if (localRepo.exists()) cleanup(localRepo.toPath())
    }

    fun setupKsHome() {
        if (ksHome.exists()) cleanup(ksHome.toPath())
    }

    fun setupRepo() {
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
        compilerClassPath.split(' ').forEach { d ->
            val (groupId, artifactId, version) = d.split(':')
            val subPath = groupId.replace('.', '/') + "/$artifactId/$version/$artifactId-$version.jar"
            val target = File(repo, subPath)
            Files.createDirectories(target.toPath().parent)
            Files.copy(File(realLocalRepo, subPath).toPath(),
                    target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun setupScripts() {
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

    fun runScript(logFileName: String, vararg args: String): List<String> {
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
                result.joinToString("\n"))
        return result.toList()
    }

    fun setup() {
        setupScripts()
        setupRepo()
        setupBin()
        setupLocalRepo()
        setupKsHome()
    }

    fun reportCoverage() {
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

    fun compileOk() {
        try {
            runScript("test_compile_ok.out",
                    "env", *env, "script_file=test.kt",
                    zsh, "-xy", "test.kt")
        } catch (ex: IllegalStateException) {
            throw RuntimeException(ex)
        }
    }

    fun setupNoClean() {
        setupBin()
        setupRepo()
    }

    var rc = 0

    fun test(name: String, expectedException: KClass<out Exception>? = null, block: () -> Unit) {
        println("--> $name")
        try {
            setupNoClean()
            block()
            if (expectedException != null) {
                println("**** fail **** expected exception $expectedException")
                rc = 1
            }
        } catch (ex: Exception) {
            if (expectedException == null || !expectedException.isInstance(ex)) {
                println("**** fail **** $ex")
                rc = 1
            }
        }
    }

    setup()

    test("test1FromScratch") {
        compileOk()
    }

    test("testAllCached") {
        compileOk()
        runScript("test_all_cached.out",
                "env", *env, "script_file=test.kt",
                zsh, "-xy", "test.kt")
    }

    test("testCompileError", IllegalStateException::class) {
        FileWriter(File(baseDir, "test_err.kt")).use { w ->
            w.write("hello there\n")
        }
        runScript("test_compile_error.out",
                "env", *env, "script_file=test_err.kt",
                zsh, "-xy", "test.kt")
    }

    test("testInvInc", IllegalStateException::class) {
        FileWriter(File(baseDir, "test_inv_inc.kt")).use { w ->
            w.write("///INC=nowhere.kt\n")
        }
        runScript("test_compile_inv_inc.out",
                "env", *env, "script_file=test_inv_inc.kt",
                zsh, "-xy", "test.kt")
    }

    test("testInvDep", IllegalStateException::class) {
        FileWriter(File(baseDir, "test_inv_dep.kt")).use { w ->
            w.write("///DEP=nowhere:nothing:1.0\n")
        }
        runScript("test_inv_dep.out",
                "env", *env, "script_file=test_inv_dep.kt",
                zsh, "-xy", "test.kt")
    }

    test("testCopyFromLocalRepo") {
        compileOk()
        cleanup(ksHome.toPath())
        runScript("test_copy_from_local_repo.out",
                "env", *env, "script_file=test.kt",
                zsh, "-xy", "test.kt")
    }

    test("testBadLocalRepo") {
        compileOk()
        cleanup(ksHome.toPath())
        val subDir = "org/cikit/kotlin_script/kotlin_script/$v"
        FileOutputStream(File(localRepo, "$subDir/kotlin_script-$v.jar"))
                .use { out -> out.write("broken!".toByteArray()) }
        runScript("test_bad_local_repo.out",
                "env", *env, "script_file=test.kt",
                zsh, "-xy", "test.kt")
    }

    test("testNoFetchTool", IllegalStateException::class) {
        compileOk()
        cleanup(ksHome.toPath())
        val subDir = "org/cikit/kotlin_script/kotlin_script/$v"
        listOf(
                File(localRepo, "$subDir/kotlin_script-$v.jar"),
                File(binDir, "fetch"),
                File(binDir, "curl")
        ).forEach { f ->
            Files.deleteIfExists(f.toPath())
        }
        runScript("test_no_fetch_tool.out",
                "env", *env, "script_file=test.kt",
                zsh, "-xy", "test.kt")
    }

    println("--> done")
    reportCoverage()

    exitProcess(rc)
}
