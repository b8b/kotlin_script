#!/bin/sh

/*__kotlin_script_installer__/ 2>&-
# vim: syntax=kotlin
#    _         _   _ _                       _       _
#   | |       | | | (_)                     (_)     | |
#   | | _____ | |_| |_ _ __    ___  ___ _ __ _ _ __ | |_
#   | |/ / _ \| __| | | '_ \  / __|/ __| '__| | '_ \| __|
#   |   < (_) | |_| | | | | | \__ \ (__| |  | | |_) | |_
#   |_|\_\___/ \__|_|_|_| |_| |___/\___|_|  |_| .__/ \__|
#                         ______              | |
#                        |______|             |_|
v=1.3.72.0
artifact=org/cikit/kotlin_script/kotlin_script/"$v"/kotlin_script-"$v".sh
repo=${repo:-https://repo1.maven.org/maven2}
if ! [ -e "${local_repo:=$HOME/.m2/repository}"/"$artifact" ]; then
  fetch_s="$(command -v fetch) -aAqo" || fetch_s="$(command -v curl) -fSso"
  mkdir -p "$local_repo"/org/cikit/kotlin_script/kotlin_script/"$v"
  tmp_f="$(mktemp "$local_repo"/"$artifact"~XXXXXXXXXXXXXXXX)" || exit 1
  if ! ${fetch_cmd:="$fetch_s"} "$tmp_f" "$repo"/"$artifact"; then
    echo "error: failed to fetch kotlin_script" >&2
    rm -f "$tmp_f"; exit 1
  fi
  case "$(openssl dgst -sha256 -r < "$tmp_f")" in
  "175648b97df5b0410c177a379f58aca8f029b3da705ecfda87b542133ba0ac2d "*)
    mv -f "$tmp_f" "$local_repo"/"$artifact" ;;
  *)
    echo "error: failed to validate kotlin_script" >&2
    rm -f "$tmp_f"; exit 1 ;;
  esac
fi
. "$local_repo"/"$artifact"
exit 2
*/

import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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

fun main(args: Array<String>) {
    val mainJar = Paths.get(args.single())

    val home = Paths.get(System.getProperty("user.home"))
    val repo = home.resolve(".m2/repository")

    val tmp = Files.createTempFile("kotlin_script", ".jar")

    ZipFile(mainJar.toFile()).use { z ->
        FileOutputStream(tmp.toFile()).use { out ->
            ZipOutputStream(out).use { zout ->
                for (entry in z.entries()) {
                    if (entry.name == "META-INF/MANIFEST.MF") {
                        val manifest = z.getInputStream(entry).use { `in` -> Manifest(`in`) }
                        val kotlinVersion = manifest.mainAttributes.getValue("Kotlin-Compiler-Version")
                                ?: error("no Kotlin-Compiler-Version in manifest")
                        val compilerLibDir = mainJar.parent.resolve("kotlin-compiler-$kotlinVersion/kotlinc/lib")
                        updateManifest(manifest, compilerLibDir)
                        val data = ByteArrayOutputStream().use { tmp ->
                            manifest.write(tmp)
                            tmp.toByteArray()
                        }
                        entry.size = data.size.toLong()
                        entry.compressedSize = -1
                        zout.putNextEntry(entry)
                        zout.write(data)
                    } else {
                        zout.putNextEntry(entry)
                        z.getInputStream(entry).use { `in` ->
                            `in`.copyTo(zout)
                        }
                    }
                }
            }
        }
    }

    val manifest = ZipFile(mainJar.toFile()).use { z ->
        z.getInputStream(z.getEntry("META-INF/MANIFEST.MF")).use { `in` -> Manifest(`in`) }
    }

    val pom = ZipFile(mainJar.toFile()).use { z ->
        z.getInputStream(z.getEntry("META-INF/maven/org.cikit.kotlin_script/kotlin_script/pom.xml")).use { `in` -> String(`in`.readBytes()) }
    }

    val kotlinVersion = manifest.mainAttributes.getValue("Kotlin-Compiler-Version")
    val kotlinScriptVersion = manifest.mainAttributes.getValue("Implementation-Version")
    val compilerLibDir = mainJar.parent.resolve("kotlin-compiler-$kotlinVersion/kotlinc/lib")

    val repoKotlinScript = repo.resolve("org/cikit/kotlin_script/kotlin_script/$kotlinScriptVersion")
    Files.createDirectories(repoKotlinScript)

    repoKotlinScript.resolve("kotlin_script-$kotlinScriptVersion.pom").toFile().writeText(pom)

    val mainJarTgt = repoKotlinScript.resolve("kotlin_script-$kotlinScriptVersion.jar")
    Files.move(tmp, mainJarTgt, StandardCopyOption.REPLACE_EXISTING)

    for (item in listOf(
            "kotlin_script-$kotlinScriptVersion-javadoc.jar",
            "kotlin_script-$kotlinScriptVersion-sources.jar"
    )) {
        Files.copy(mainJar.parent.resolve(item), repoKotlinScript.resolve(item), StandardCopyOption.REPLACE_EXISTING)
    }

    val scriptTgt = repoKotlinScript.resolve("kotlin_script-$kotlinScriptVersion.sh")
    FileReader("kotlin_script.sh").use { r ->
        Files.newBufferedWriter(scriptTgt).use { w ->
            r.useLines { lines ->
                for (line in lines) {
                    w.write(line.replace("@stdlib_ver@", kotlinVersion)
                            .replace("@stdlib_sha256@", compilerLibDir.resolve("kotlin-stdlib.jar").sha256())
                            .replace("@ks_jar_ver@", kotlinScriptVersion)
                            .replace("@ks_jar_sha256@", mainJarTgt.sha256()))
                    w.write("${'\n'}")
                }
            }
        }
    }

    val readme = File("README.md").readText()
            .replace(Regex("^v=[^\n]+", RegexOption.MULTILINE), "v=$kotlinScriptVersion")
            .replace(Regex("\"[0-9a-f]{64} \"\\*\\)"), "\"${scriptTgt.sha256()} \"*)")
    File("README.md").writeText(readme)
}
