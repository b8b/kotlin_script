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
v=1.9.21.21
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
  "72fdbaf8238e40303a9115b2c3177c6717a928b60520ccdde7e42b265164f494 "*) ;;
  *) echo "error: failed to verify kotlin_script.sh" >&2
     rm -f "$kotlin_script_sh"; exit 1;;
  esac
fi
. "$kotlin_script_sh"; exit 2
*/

///DEP=org.cikit:kotlin_script:1.9.21.21
///DEP=com.github.ajalt.mordant:mordant-jvm:2.2.0
///DEP=com.github.ajalt.colormath:colormath-jvm:3.3.1
///DEP=org.jetbrains:markdown-jvm:0.5.2
///DEP=it.unimi.dsi:fastutil-core:8.5.12
///DEP=net.java.dev.jna:jna:5.13.0

///DEP=com.github.ajalt.clikt:clikt-jvm:4.2.1

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import kotlin_script.KotlinScript
import kotlin_script.loadScript
import java.io.File
import kotlin.io.path.*
import kotlin.system.exitProcess

private const val DEFAULT_MAVEN_REPO_URL = "https://repo1.maven.org"

private object KotlinScriptCommand: CliktCommand(
    name = "kotlin_script",
    help = "kotlin_script command-line interface"
) {
    private val mavenRepoUrl by option("-R", "--m2-central-repo",
        help = "url to public maven repository ($DEFAULT_MAVEN_REPO_URL)"
    ).default(System.getenv("M2_CENTRAL_REPO") ?: DEFAULT_MAVEN_REPO_URL)

    private val mavenRepoCache by option("--m2-local-mirror",
        help = "path to local read-only mirror for maven artifacts"
    ).path(canBeFile = false)

    private val localRepo by option("--m2-local-repo",
        help = "path to local maven repository to store fetched artifacts"
    ).path(canBeFile = false)

    private val progress by option("-P", "--show-progress",
        help = "write progress messages to stderr"
    ).flag()

    private val trace by option("-x", "--trace",
        help = "write trace messages to stderr"
    ).flag()

    private val force by option("-f", "--force",
        help = "force recompilation of cached target jar"
    ).flag()

    private val run by option("--run",
        help = "compile and run script"
    ).flag()

    private val storeMetaData by option("-M", "--metadata",
        help = "store metadata file (stored into local repo by default)"
    ).path()

    private val destinationJar by option("-d",
        help = "store destination jar (stored in local repo by default)"
    ).path()

    private val nativeImage by option(
        help = "build native image"
    ).flag()

    private val scriptFile by argument(name = "SCRIPT").path()
    private val scriptArgs by argument(name = "ARG").multiple()

    private fun defaultLocalRepo() = System.getProperty("user.home")?.let { p ->
        Path(p) / ".m2" / "repository"
    } ?: error("user.home system property not set")

    private fun trace(vararg msg: String) {
        if (trace) {
            val terminal = if (progress) Terminal() else null
            terminal?.let { t ->
                t.print(TextStyles.bold.invoke("++"), stderr = true)
                t.print(" ", stderr = true)
                msg.forEachIndexed { index, arg ->
                    val part = if (arg.startsWith("/")) {
                        if (":" in arg) {
                            val separator = TextStyles.bold.invoke(":")
                            arg.split(":").joinToString(separator) {
                                TextStyles.italic.invoke(it)
                            }
                        } else {
                            TextStyles.italic.invoke(arg)
                        }
                    } else if (arg.startsWith("-")) {
                        TextStyles.bold.invoke(arg)
                    } else {
                        arg
                    }
                    t.print(part, stderr = true)
                    if (index < msg.size - 1) {
                        t.print(" ", stderr = true)
                    }
                }
                t.println(stderr = true)
            } ?: System.err.println("++ ${msg.joinToString(" ")}")
        }
    }

    override fun run() {
        val localRepoPath = localRepo
            ?: System.getenv("M2_LOCAL_REPO")?.let { p -> Path(p) }
            ?: defaultLocalRepo()
        val ks = KotlinScript(
            mavenRepoUrl = mavenRepoUrl,
            mavenRepoCache = mavenRepoCache
                ?: System.getenv("M2_LOCAL_MIRROR")?.let { Path(it) },
            localRepo = localRepoPath,
            progress = progress,
            trace = trace,
            force = force,
        )
        val script = loadScript(scriptFile)
        val metaData = ks.compile(script)
        storeMetaData
            ?.let { f -> metaData.storeToFile(f) }
            ?: metaData.storeToRepo(localRepoPath)
        val compiledJar = ks.jarCachePath(metaData)
        val finalJar = destinationJar?.let { f ->
            compiledJar.copyTo(f, true)
            f
        } ?: compiledJar

        val cp = sequenceOf(finalJar) + metaData.dep.asSequence().map { d ->
            localRepoPath / d.subPath
        }

        if (nativeImage) {
            val exe = finalJar.pathString.removeSuffix(".jar") + when {
                "win" in System.getProperty("os.name").lowercase() -> ".exe"
                else -> ""
            }
            val exeFile = Path(exe)
            val runNativeImage = force ||
                    !exeFile.exists() ||
                    exeFile.getLastModifiedTime().toMillis() <
                    finalJar.getLastModifiedTime().toMillis()
            if (runNativeImage) {
                val args = listOf(
                    "native-image",
                    "--verbose",
                    "--initialize-at-build-time",
                    "--no-fallback",
                    "--strict-image-heap",
                    "--gc=serial",
                    //"--static",
                    "-O3",
                    "-cp", cp.joinToString(File.pathSeparator) { it.pathString },
                    metaData.main,
                    "-o", exe
                )
                trace(*args.toTypedArray())
                val rc = ProcessBuilder(args).inheritIO().start().waitFor()
                if (rc != 0) {
                    exitProcess(rc)
                }
            }
            if (run) {
                val runArgs = listOf(exe) + scriptArgs
                trace(*runArgs.toTypedArray())
                val rcRun = ProcessBuilder(runArgs).inheritIO().start().waitFor()
                exitProcess(rcRun)
            }
        }

        if (run) {
            val javaCmd = System.getenv("java_cmd") ?: "java"
            val args = javaCmd.split(Regex("""\s+""")) + listOf(
                "-cp", cp.joinToString(File.pathSeparator),
                metaData.main,
                *scriptArgs.toTypedArray()
            )
            trace(*args.toTypedArray())
            val rc = ProcessBuilder(args).inheritIO().start().waitFor()
            exitProcess(rc)
        }
    }
}

fun main(args: Array<String>) = KotlinScriptCommand.main(args)
