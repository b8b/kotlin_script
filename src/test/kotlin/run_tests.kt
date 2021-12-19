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
v=1.6.0.0
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
  "c7710288e71855c0ae05004fae38be70f7368f0432f6d660530205026e9bbfbd "*) ;;
  *) echo "error: failed to verify kotlin_script.sh" >&2
     rm -f "$kotlin_script_sh"; exit 1;;
  esac
fi
. "$kotlin_script_sh"; exit 2
*/

///DEP=org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.6.0
///DEP=org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.6.0

///DEP=org.apache.bcel:bcel:6.5.0

///DEP=com.willowtreeapps.assertk:assertk-jvm:0.25
///DEP=com.willowtreeapps.opentest4k:opentest4k-jvm:1.2.2
///DEP=org.opentest4j:opentest4j:1.2.0

///INC=TestBasic.kt
///INC=TestCachePath.kt
///INC=TestNoFetchTool.kt
///INC=TestInvalidHome.kt

import org.apache.bcel.classfile.ClassParser
import java.io.IOException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
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

val readme = Paths.get("README.md").readText()

val embeddedInstaller = ".*```Sh(.*?__kotlin_script_installer__.*?)```.*"
    .toRegex(RegexOption.DOT_MATCHES_ALL)
    .matchEntire(readme)?.groupValues?.get(1)?.trim()
    ?: error("error extracting embedded installer from README.md")

val v = Regex(".*\\nv=(.*?)\\n.*", RegexOption.DOT_MATCHES_ALL)
    .matchEntire(embeddedInstaller)?.groupValues?.get(1)?.trim()
    ?: error("error extracting kotlin_script version from embedded installer in README.md")

val homeDir = Paths.get(System.getProperty("user.home"))
val realLocalRepo = homeDir.resolve(".m2/repository")
val libsDir = realLocalRepo.resolve("org/cikit/kotlin_script/$v")

//+test.kt:3>
val locPattern = "^\\+(.*?):(\\d+)> .*\$".toRegex()

val kotlinScript = "kotlin_script-$v.sh".let { fileName ->
    val md = MessageDigest.getInstance("SHA-256")
    val lines = Files.newInputStream(libsDir.resolve(fileName)).use { `in` ->
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
    ShellScript(fileName, sha256, lines, shellFunctions)
}

val buildDir = Paths.get("build")
val baseDir = buildDir.resolve("t_$v")
val repo = baseDir.resolve( "repo")
val binDir = baseDir.resolve("bin")
val localRepo = baseDir.resolve("local_repo")
val cache = localRepo.resolve("org/cikit/kotlin_script_cache")
val linesCovered = mutableSetOf<Pair<String, Int>>()

val env = arrayOf(
    "PATH=${binDir.toAbsolutePath()}",
    "M2_CENTRAL_REPO=${repo.toUri()}",
    "M2_LOCAL_REPO=${baseDir.toAbsolutePath().relativize(
        localRepo.toAbsolutePath())}"
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
    if (Files.exists(binDir)) cleanup(binDir)
    Files.createDirectories(binDir)
    listOf(
        "rm", "mv", "cp", "mkdir", "mktemp",
        *(if (fetch == null) emptyArray() else arrayOf("fetch")),
        *(if (curl == null) emptyArray() else arrayOf("curl")),
        "openssl", "java"
    ).forEach { tool ->
        val outFile = binDir.resolve(tool)
        Files.newOutputStream(outFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING).use { out ->
            out.write((
                    "#!/bin/sh\n" +
                            "read -r line << '__EOF__'\n" +
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
                outFile,
                permissions.value())
        } catch (_: UnsupportedOperationException) {
        }
    }
}

fun setupLocalRepo() {
    if (Files.exists(localRepo)) cleanup(localRepo)
}

fun setupRepo() {
    if (Files.exists(repo)) cleanup(repo)

    //copy kotlin_script
    val ksSubdir = repo.resolve("org/cikit/kotlin_script/$v")
    Files.createDirectories(ksSubdir)
    Files.copy(
        libsDir.resolve("kotlin_script-$v.sh"),
        ksSubdir.resolve("kotlin_script-$v.sh"),
        StandardCopyOption.REPLACE_EXISTING)
    Files.copy(
        libsDir.resolve("kotlin_script-$v.jar"),
        ksSubdir.resolve("kotlin_script-$v.jar"),
        StandardCopyOption.REPLACE_EXISTING)

    //read compiler class-path
    var manifest: Manifest? = null
    ksSubdir.resolve("kotlin_script-$v.jar").inputStream().use { `in` ->
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
        val target = repo.resolve(subPath)
        Files.createDirectories(target.parent)
        Files.copy(
            realLocalRepo.resolve(subPath),
            target, StandardCopyOption.REPLACE_EXISTING)
    }
}

fun setupScripts() {
    Files.createDirectories(baseDir)
    baseDir.resolve("inc.kt").writeText("fun myFunc() = 1\n")
    baseDir.resolve("test.kt").writer().use { w ->
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
        .redirectOutput(ProcessBuilder.Redirect.to(
            baseDir.resolve(logFileName).toFile()
        ))
        .redirectErrorStream(true)
        .start()
    p.outputStream.close()
    val rc = p.waitFor()
    val result = mutableListOf<String>()
    baseDir.resolve(logFileName).useLines { lines ->
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
    if (rc != 0) error("command failed with exit code $rc:\n" +
            result.joinToString("\n"))
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
        val lines = if (fileName == "kotlin_script-$v.sh") {
            kotlinScript.lines
        } else {
            val f = baseDir.resolve(fileName)
            if (Files.exists(f)) {
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
        baseDir.resolve("${fileName}.cov").writer().use { w ->
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
            val p = Paths.get(r.toURI())
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
    val classLoader = Thread.currentThread().contextClassLoader
    val testClasses = classLoader.findClasses { className, fileName ->
        className.substringAfterLast(".").startsWith("Test") &&
                (args.isEmpty() || className.substringAfterLast('.') in args ||
                        args.any { arg -> Paths.get(arg).fileName.toString() == fileName })
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
