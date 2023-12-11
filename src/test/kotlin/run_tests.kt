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
v=1.9.21.19
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
  "425beb05a5896b09ee916c5754e8262a837e15b4c40d6e9802f959b37210928e "*) ;;
  *) echo "error: failed to verify kotlin_script.sh" >&2
     rm -f "$kotlin_script_sh"; exit 1;;
  esac
fi
. "$kotlin_script_sh"; exit 2
*/

///DEP=org.apache.bcel:bcel:6.7.0
///DEP=org.apache.commons:commons-lang3:3.12.0

///DEP=com.willowtreeapps.assertk:assertk-jvm:0.28.0
///DEP=com.willowtreeapps.opentest4k:opentest4k-jvm:1.3.0
///DEP=org.opentest4j:opentest4j:1.3.0

///INC=TestBasic.kt
///INC=TestCachePath.kt
///INC=TestNoFetchTool.kt
///INC=TestInvalidHome.kt

import org.apache.bcel.classfile.ClassParser
import java.io.IOException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlin.streams.asSequence
import kotlin.system.exitProcess

@Target(AnnotationTarget.FUNCTION)
annotation class Test

data class ShellScript(
    val name: String,
    val sha256: String,
    val lines: List<String>,
    val shellFunctions: Map<String, Int>
)

fun cleanup(dir: Path) {
    Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
        override fun postVisitDirectory(dir: Path, exc: IOException?) =
            FileVisitResult.CONTINUE.also { Files.delete(dir) }

        override fun visitFile(file: Path, attrs: BasicFileAttributes) =
            FileVisitResult.CONTINUE.also { Files.delete(file) }
    })
}

val readme = Path("README.md").readText()

val embeddedInstaller = ".*```Sh(.*?__kotlin_script_installer__.*?)```.*"
    .toRegex(RegexOption.DOT_MATCHES_ALL)
    .matchEntire(readme)?.groupValues?.get(1)?.trim()
    ?: error("error extracting embedded installer from README.md")

val v = Regex(".*\\nv=(.*?)\\n.*", RegexOption.DOT_MATCHES_ALL)
    .matchEntire(embeddedInstaller)?.groupValues?.get(1)?.trim()
    ?: error("error extracting kotlin_script version from embedded installer in README.md")

val homeDir = Path(System.getProperty("user.home"))
val realLocalRepo = homeDir / ".m2/repository"
val libsDir = realLocalRepo / "org/cikit/kotlin_script/$v"

//+test.kt:3>
val locPattern = "^\\+(.*?):(\\d+)> .*\$".toRegex()

val kotlinScript = "kotlin_script-$v.sh".let { fileName ->
    val (lines, sha256) = (libsDir / fileName).readBytes().let { data ->
        val sha256 = with(MessageDigest.getInstance("SHA-256")) {
            update(data)
            digest().joinToString("") { String.format("%02x", it) }
        }
        String(data).substringBefore("\nPK").trim().split("\n") to sha256
    }
    val shellFunctions = mutableMapOf<String, Int>()
    val funcRegex = """^[A-Za-z0-9_]+\(\)$""".toRegex()
    lines.forEachIndexed { index, line ->
        if (line.matches(funcRegex)) {
            shellFunctions[line.removeSuffix("()")] = index.inc()
        }
    }
    ShellScript(fileName, sha256, lines, shellFunctions)
}

val buildDir = Path("build")
val baseDir = buildDir / "t_$v"
val initialRepo = baseDir / "initial_repo"
val repo = baseDir /  "repo"
val binDir = baseDir / "bin"
val localRepo = baseDir / "local_repo"
val cache = localRepo / "org/cikit/kotlin_script_cache"
val linesCovered = mutableSetOf<Pair<String, Int>>()

val env = arrayOf(
    "PATH=${binDir.absolutePathString()}",
    "M2_CENTRAL_REPO=${repo.toUri()}",
    "M2_LOCAL_REPO=${localRepo.relativeTo(baseDir)}"
)

private fun <T> sh(cmd: String, onExit: (Int, String) -> T): T {
    val pb = ProcessBuilder("sh", "-c", cmd)
    pb.inheritIO()
    pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
    val p = pb.start()
    val out = p.inputStream.use { `in` -> String(`in`.readBytes()).trim() }
    val rc = p.waitFor()
    return onExit(rc, out)
}

val zsh = sh("command -v zsh") { rc, out ->
    require(rc == 0) { "cannot find location of zsh command" }
    out
}
val fetch = sh("command -v fetch") { rc, out -> if (rc == 0) out else null }
val curl = sh("command -v curl") { rc, out -> if (rc == 0) out else null }

fun setupBin() {
    if (binDir.exists()) cleanup(binDir)
    binDir.createDirectories()
    val p700 = PosixFilePermissions.asFileAttribute(
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
        )
    ).value()
    listOf(
        "rm", "mv", "cp", "mkdir", "mktemp",
        *(if (fetch == null) emptyArray() else arrayOf("fetch")),
        *(if (curl == null) emptyArray() else arrayOf("curl")),
        "openssl",
        "java"
    ).forEach { tool ->
        val outFile = binDir / tool
        outFile.writeText(
            "#!/bin/sh\n" +
                    "read -r line << '__EOF__'\n" +
                    "${System.getenv("PATH")}\n" +
                    "__EOF__\n" +
                    "export PATH=\"\$line\"\n" +
                    "exec $tool \"\$@\"\n"
        )
        try {
            outFile.setPosixFilePermissions(p700)
        } catch (_: UnsupportedOperationException) {
        }
    }
}

fun setupLocalRepo() {
    if (localRepo.exists()) cleanup(localRepo)
    localRepo.createDirectories()
}

fun setupInitialRepo() {
    if (initialRepo.exists()) cleanup(initialRepo)

    //copy kotlin_script
    val ksSubdir = (initialRepo / "org/cikit/kotlin_script/$v")
        .createDirectories()
    for (f in listOf("kotlin_script-$v.sh", "kotlin_script-$v.jar")) {
        (libsDir / f).copyTo(ksSubdir / f, true)
    }

    baseDir.createDirectories()
    (baseDir / "test.kt").writer().use { w ->
        w.write(embeddedInstaller)
        w.write("""
                    |
                    |///DEP=org.slf4j:slf4j-api:1.7.36
                    |///RDEP=org.slf4j:slf4j-simple:1.7.36
                    |
                    |fun main() {
                    |    println("hello world!")
                    |}
                    |""".trimMargin())
    }
    runScript(
        "setup_repo.out",
        "env",
        "M2_LOCAL_REPO=${initialRepo.relativeTo(baseDir)}",
        "M2_LOCAL_MIRROR=${realLocalRepo.absolutePathString()}",
        "script_file=test.kt",
        zsh, "-xy", "test.kt"
    )
}

fun setupRepo() {
    if (repo.exists()) cleanup(repo)
    for (f in Files.walk(initialRepo)) {
        if (f.isDirectory()) {
            (repo / f.relativeTo(initialRepo)).createDirectories()
        } else {
            f.copyTo(repo / f.relativeTo(initialRepo))
        }
    }
}

fun setupScripts() {
    baseDir.createDirectories()
    (baseDir / "inc.kt").writeText("fun myFunc() = 1\n")
    (baseDir / "test.kt").writer().use { w ->
        w.write(embeddedInstaller)
        w.write("""
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
        .directory(baseDir.toFile())
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(
            ProcessBuilder.Redirect.to((baseDir / logFileName).toFile())
        )
        .redirectErrorStream(true)
        .start()
    p.outputStream.close()
    val rc = p.waitFor()
    val result = mutableListOf<String>()
    (baseDir / logFileName).useLines { lines ->
        for (line in lines) {
            locPattern.matchEntire(line)?.let { mr ->
                val (name, ln) = mr.destructured
                val offs = kotlinScript.shellFunctions[name]
                val shName = "kotlin_script-$v.sh"
                linesCovered.add(when {
                    offs != null -> shName to (ln.toInt() + offs)
                    "tmp" in name -> shName to ln.toInt()
                    else -> name to ln.toInt()
                })
                Unit
            } ?: result.add(line)
        }
    }
    if (rc != 0) {
        error(
            "command failed with exit code $rc:\n${result.joinToString("\n")}"
        )
    }
    return result.toList()
}

fun setup() {
    setupScripts()
    setupRepo()
    setupBin()
    setupLocalRepo()
}

fun reportCoverage() {
    for (fileName in linesCovered.map { it.first }.toSet()) {
        val lines = if (Path(fileName).name == "kotlin_script-$v.sh") {
            kotlinScript.lines
        } else {
            val f = baseDir / fileName
            if (f.exists()) {
                try {
                    f.readLines()
                } catch (ex: IOException) {
                    println("warning: $f: $ex")
                    continue
                }
            } else {
                println("warning: $f not found")
                continue
            }
        }
        (baseDir / "${fileName}.cov").writer().use { w ->
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

var rc = 0

private fun test(instance: Any?, method: Method, name: String) {
    println("--> $name")
    try {
        setup()
        method.invoke(instance)
    } catch (ex: Exception) {
        rc = 1
        println("**** fail **** $ex")
        ex.printStackTrace()
    }
    System.out.flush()
    System.err.flush()
}

private fun ClassLoader.findClasses(
    predicate: (String, String) -> Boolean = { _, _ -> true }
): Sequence<String> {
    val allResources = getResources("").asSequence() +
            getResources("META-INF/MANIFEST.MF").asSequence()
    return allResources.flatMap { r ->
        val uri = r.toString()
        if (uri.startsWith("jar:file:")) {
            val jar = uri.substringBeforeLast("!").removePrefix("jar:file:")
            ZipFile(jar).use { zf ->
                val selectedEntries = mutableListOf<String>()
                for (ze in zf.entries().asSequence()) {
                    if (!ze.name.endsWith(".class")) continue
                    val cn = ze.name.split('/').filter { c ->
                        c.isNotBlank()
                    }.joinToString(".")
                        .removeSuffix(".class")
                    val sfn = ClassParser(zf.getInputStream(ze), ze.name)
                        .parse().sourceFileName
                    if (!predicate(cn, sfn)) continue
                    selectedEntries += cn
                }
                selectedEntries.asSequence()
            }
        } else if (uri.startsWith("file:")) {
            val p = r.toURI().toPath()
            Files.walk(p).asSequence().mapNotNull { f ->
                if (f.fileName.toString().endsWith(".class")) {
                    val cn = (p.nameCount until f.nameCount)
                        .joinToString(".") { i -> "${f.getName(i)}" }
                        .removeSuffix(".class")
                    val sfn = Files.newInputStream(f).use { `in` ->
                        ClassParser(`in`, f.toString())
                            .parse().sourceFileName
                    }
                    if (predicate(cn, sfn)) cn else null
                } else {
                    null
                }
            }
        } else {
            System.err.println("warning: skipped unsupported uri: $uri")
            emptySequence()
        }
    }
}

fun main(args: Array<String>) {
    setupInitialRepo()
    val classLoader = Thread.currentThread().contextClassLoader
    val testClasses = classLoader.findClasses { className, fileName ->
        className.substringAfterLast(".").startsWith("Test") &&
                (args.isEmpty() || className.substringAfterLast('.') in args ||
                        args.any { arg -> Path(arg).fileName.toString() == fileName })
    }
    for (className in testClasses.sorted()) {
        val clazz = Class.forName(className)
        val testMethods = mutableListOf<Method>()
        val staticTests = mutableListOf<Method>()
        for (m in clazz.declaredMethods.sortedBy { m -> m.name }) {
            if (!m.isAnnotationPresent(Test::class.java) ||
                m.returnType.typeName != "void" ||
                (m.modifiers and Modifier.PUBLIC) == 0) {
                continue
            }
            if ((m.modifiers and Modifier.STATIC) != 0) {
                staticTests += m
            } else {
                testMethods += m
            }
        }
        for (m in staticTests) {
            test(null, m, "${clazz.name}::${m.name}")
        }
        if (testMethods.isNotEmpty()) {
            val instance = clazz.newInstance()
            for (m in testMethods) {
                test(instance, m, "${clazz.name}.${m.name}")
            }
        }
    }
    System.out.flush()
    System.err.flush()
    println("--> reporting coverage")
    reportCoverage()
    System.out.flush()
    System.err.flush()
    println("--> done")
    exitProcess(rc)
}
