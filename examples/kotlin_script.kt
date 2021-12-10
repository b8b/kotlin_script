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

///DEP=org.cikit:kotlin_script:1.6.0.0
///DEP=com.github.ajalt.clikt:clikt-jvm:3.3.0

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import kotlin_script.KotlinScript
import kotlin_script.loadScript
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

private const val defaultMavenRepoUrl = "https://repo1.maven.org"

private object KotlinScriptCommand: CliktCommand(
    name = "kotlin_script",
    help = "kotlin_script command-line interface"
) {
    private val mavenRepoUrl by option("-R", "--m2-central-repo",
        help = "url to public maven repository ($defaultMavenRepoUrl)"
    ).default(System.getenv("M2_CENTRAL_REPO") ?: defaultMavenRepoUrl)

    private val mavenRepoCache by option("--m2-local-mirror",
        help = "path to local read-only mirror for maven artifacts"
    ).file(canBeFile = false)

    private val localRepo by option("--m2-local-repo",
        help = "path to local maven repository to store fetched artifacts"
    ).file(canBeFile = false)

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
    ).file()

    private val destinationJar by option("-d",
        help = "store destination jar (stored in local repo by default)"
    ).file()

    private val scriptFile by argument(name = "SCRIPT").file()
    private val scriptArgs by argument(name = "ARG").multiple()

    private fun defaultLocalRepo() = System.getProperty("user.home")?.let { p ->
        Paths.get(p, ".m2/repository")
    } ?: error("user.home system property not set")

    override fun run() {
        val localRepoPath = localRepo?.toPath()
            ?: System.getenv("M2_LOCAL_REPO")?.let { p -> Paths.get(p) }
            ?: defaultLocalRepo()
        val ks = KotlinScript(
            mavenRepoUrl = mavenRepoUrl,
            mavenRepoCache = mavenRepoCache?.toPath()
                ?: System.getenv("M2_LOCAL_MIRROR")?.let { Paths.get(it) },
            localRepo = localRepoPath,
            progress = progress,
            trace = trace,
            force = force,
        )
        val script = loadScript(scriptFile.toPath())
        val metaData = ks.compile(script)
        storeMetaData?.let { f ->
            metaData.storeToFile(f.toPath())
        } ?: metaData.storeToRepo(localRepoPath)
        val compiledJar = ks.jarCachePath(metaData)
        val finalJar = destinationJar?.let { f ->
            val p = f.toPath()
            Files.copy(compiledJar, p, StandardCopyOption.REPLACE_EXISTING)
            p
        } ?: compiledJar
        if (run) {
            TODO("run $finalJar with $scriptArgs")
        }
    }
}

fun main(args: Array<String>) = KotlinScriptCommand.main(args)
