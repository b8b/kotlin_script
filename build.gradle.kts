import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.*
import java.security.MessageDigest

plugins {
    val kotlinVersion = "1.3.21"

    kotlin("jvm") version kotlinVersion
    id("org.jetbrains.dokka") version "0.9.17"

    signing
    `maven-publish`
    id("maven-publish-auth") version "2.0.1"
}

group = "org.cikit.kotlin_script"
version = "1.3.21.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_7
    targetCompatibility = JavaVersion.VERSION_1_7
}

repositories {
    mavenCentral()
}

dependencies {
    compile("org.jetbrains.kotlin:kotlin-stdlib")
    testCompile("junit:junit:4.12")
    testCompile("com.pi4j:pi4j-core:1.1")
    testCompile("org.apache.sshd:sshd-netty:2.1.0")
    testCompile("io.vertx:vertx-core:3.6.0")
    testCompile("com.fasterxml.jackson.core:jackson-databind:2.9.8")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jdkHome = properties["jdk.home"]?.toString()?.takeIf { it != "unspecified" }
    jvmTarget = "1.6"
}

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "1.6"
}
compileTestKotlin.dependsOn.add("jar")
compileTestKotlin.dependsOn.add("buildKotlinScriptSh")

val main by sourceSets

val sourcesJar by tasks.creating(Jar::class) {
    group = "build"
    classifier = "sources"
    from(main.allSource)
}

val dokkaJar by tasks.creating(Jar::class) {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    description = "Assembles Kotlin docs with Dokka"
    classifier = "javadoc"
    from(tasks["dokka"])
}

fun File.sha256(): String {
    val md = MessageDigest.getInstance("SHA-256")
    FileInputStream(this).use { `in` ->
        val buffer = ByteArray(1024 * 4)
        while (true) {
            val r = `in`.read(buffer)
            if (r < 0) break
            md.update(buffer, 0, r)
        }
    }
    return md.digest().joinToString("") { String.format("%02x", it) }
}

val jar = tasks.named<Jar>("jar") {
    dependsOn("generatePomFileForMavenJavaPublication")
    into("META-INF/maven/${project.group}/${project.name}") {
        from(File(buildDir, "publications/mavenJava"))
        rename(".*", "pom.xml")
    }
    manifest.attributes.apply {
        val compilerConfiguration = configurations["kotlinCompilerClasspath"]
        val compilerClassPath = compilerConfiguration
                .resolvedConfiguration
                .resolvedArtifacts
        val classpath = configurations["compile"]
                .resolvedConfiguration
                .resolvedArtifacts
        val stdlib = classpath.single { a ->
            a.moduleVersion.id.group == "org.jetbrains.kotlin" &&
                    a.moduleVersion.id.name == "kotlin-stdlib" &&
                    a.classifier == null &&
                    a.extension == "jar"
        }
        val compilerDir = "kotlin-compiler-${stdlib.moduleVersion.id.version}/kotlinc/lib"
        put("Main-Class", "kotlin_script.KotlinScript")
        put("Class-Path", "$compilerDir/${stdlib.moduleVersion.id.name}.jar")
        put("Kotlin-Compiler-Class-Path", compilerClassPath.joinToString(" ") { a ->
            "${a.moduleVersion.id.group}:${a.moduleVersion.id.name}:" +
                    "${a.moduleVersion.id.version}:" + when (a.classifier) {
                null -> ""
                else -> a.classifier
            } + when (a.type) {
                "jar" -> ""
                else -> "@${a.type}"
            } + ":sha256=${a.file.sha256()}"
        })
    }
}.get()

val copyDependencies by tasks.registering(Copy::class) {
    group = "build"
    description = "copy runtime dependencies into build directory"

    val classpath = configurations["compile"]
            .resolvedConfiguration
            .resolvedArtifacts
    val stdlib = classpath.single { a ->
        a.moduleVersion.id.group == "org.jetbrains.kotlin" &&
                a.moduleVersion.id.name == "kotlin-stdlib" &&
                a.classifier == null &&
                a.extension == "jar"
    }
    val compilerDir = "kotlin-compiler-${stdlib.moduleVersion.id.version}/kotlinc/lib"

    destinationDir = File(buildDir, "libs/$compilerDir")
    val compilerConfiguration = configurations["kotlinCompilerClasspath"]
    from(compilerConfiguration)
    rename { f ->
        val iVersion = f.lastIndexOf('-')
        if (iVersion < 0) {
            f
        } else {
            val suffix = f.substring(iVersion + 1)
                    .substringAfterLast('.', "")
            f.substring(0, iVersion) + when (suffix) {
                "" -> ""
                else -> ".$suffix"
            }
        }
    }
}

val kotlinScriptShSourceFile = File(projectDir, "kotlin_script.sh")

val kotlinScriptShTargetFile = File(buildDir, "libs/kotlin_script-$version.sh")

val buildKotlinScriptSh by tasks.registering {
    group = "build"
    description = "build kotlin_script.sh"
    dependsOn("jar")
    doLast {
        val classpath = configurations["compile"]
                .resolvedConfiguration
                .resolvedArtifacts
        val stdlib = classpath.single { a ->
            a.moduleVersion.id.group == "org.jetbrains.kotlin" &&
                    a.moduleVersion.id.name == "kotlin-stdlib" &&
                    a.classifier == null &&
                    a.extension == "jar"
        }
        val stdlibSha256 = stdlib.file.sha256()
        val jarSha256 = jar.archivePath.sha256()

        FileReader(kotlinScriptShSourceFile).use { r ->
            FileWriter(kotlinScriptShTargetFile).use { w ->
                r.useLines { lines ->
                    for (line in lines) {
                        w.write(line.replace("@stdlib_ver@", stdlib.moduleVersion.id.version)
                                .replace("@stdlib_sha256@", stdlibSha256)
                                .replace("@ks_jar_ver@", "$version")
                                .replace("@ks_jar_sha256@", jarSha256))
                        w.write("${'\n'}")
                    }
                }
            }
        }
        Unit
    }
}

publishing {
    repositories {
        maven {
            name = "maven-central-cikit"
            url = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2")
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(sourcesJar)
            artifact(dokkaJar)
            artifact(kotlinScriptShTargetFile) {
                this.builtBy(buildKotlinScriptSh)
            }
            pom {
                name.set("kotlin_script")
                description.set("Lightweight build system for kotlin/jvm")
                url.set("https://github.com/b8b/kotlin_script.git")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("b8b@cikit.org")
                        name.set("b8b@cikit.org")
                        email.set("b8b@cikit.org")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/b8b/kotlin_script.git")
                    developerConnection.set("scm:git:ssh://github.com/b8b/kotlin_script.git")
                    url.set("https://github.com/b8b/kotlin_script.git")
                }
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}
