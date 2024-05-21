plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka") version "1.9.20"
    `maven-publish`
}

group = "org.cikit"
version = "2.0.0.23"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

kotlin {
    jvmToolchain(8)
}

repositories {
    mavenCentral()
}

sourceSets {
    create("launcher") {
        java {
            srcDir("launcher")
        }
    }
    create("examples") {
        kotlin {
            srcDir("examples")
        }
    }
}

configurations {
    named("examplesImplementation") {
        extendsFrom(implementation.get())
    }
}

fun DependencyHandler.examplesImplementation(dependencyNotation: Any): Dependency? =
        add("examplesImplementation", dependencyNotation)

val main by sourceSets
val launcher by sourceSets
val examples by sourceSets

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.github.ajalt.mordant:mordant-jvm:2.+")

    testImplementation("org.apache.bcel:bcel:6.+")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:latest.release")
    testImplementation("org.junit.jupiter:junit-jupiter:5.+")

    examplesImplementation(main.runtimeClasspath)
    examplesImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    examplesImplementation("org.apache.commons:commons-compress:1.+")
    examplesImplementation("org.eclipse.jdt:ecj:3.33.0")
    examplesImplementation("com.pi4j:pi4j-core:1.2")
    examplesImplementation("org.apache.sshd:sshd-netty:2.+")
    examplesImplementation("org.apache.sshd:sshd-git:2.+")
    examplesImplementation("net.i2p.crypto:eddsa:0.3.0")
    examplesImplementation("org.bouncycastle:bcpkix-jdk18on:1.+")
    examplesImplementation("io.vertx:vertx-core:4.5.+")
    examplesImplementation("com.github.ajalt.clikt:clikt-jvm:4.+")
    examplesImplementation("org.apache.james:apache-mime4j-core:latest.release")
    examplesImplementation("de.erichseifert.vectorgraphics2d:VectorGraphics2D:0.+")
}

dependencyLocking {
    lockAllConfigurations()
}

val kotlinSourcesJar by tasks

val dokkaJar by tasks.creating(Jar::class) {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    description = "Assembles Kotlin docs with Dokka"
    archiveClassifier.set("javadoc")
    from(tasks["dokkaJavadoc"])
}

val mainJar by tasks.named<Jar>("jar")

tasks.jar {
    dependsOn("generatePomFileForMavenJavaPublication")
    into("META-INF/maven/${project.group}/${project.name}") {
        from(layout.buildDirectory.dir("publications/mavenJava")) {
            include("pom-default.xml")
        }
        rename(".*", "pom.xml")
    }
    manifest {
        attributes["Implementation-Title"] = "kotlin_script"
        attributes["Implementation-Version"] = archiveVersion
        attributes["Implementation-Vendor"] = "cikit.org"
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.create<JavaExec>("updateMainSources") {
    group = "Execution"
    description = "update sources with dependency information"
    classpath = examples.runtimeClasspath
    mainClass = "InstallKt"
    val compilerClassPath = configurations.kotlinCompilerClasspath.get()
        .resolvedConfiguration
        .resolvedArtifacts
        .filterNot { it.moduleVersion.id.name == "kotlin-stdlib-common" }
    args = listOf(
        "update-main-sources",
        "--kotlin-script-version",
        version.toString(),
        "--kotlin-compiler-dependencies",
        compilerClassPath.joinToString(" ") { a ->
            "${a.moduleVersion.id.group}:${a.moduleVersion.id.name}:" +
                    "${a.moduleVersion.id.version}:" + when (a.classifier) {
                null -> ""
                else -> a.classifier
            } + when (a.type) {
                "jar" -> ""
                else -> "@${a.type}"
            }
        },
        "--kotlin-compiler-class-path",
        compilerClassPath.joinToString(":") { it.file.absolutePath },
        sourceSets.main.get().kotlin.files.first {
            it.invariantSeparatorsPath.endsWith("/kotlin_script/KotlinScript.kt")
        }.path
    )
}

tasks.create<JavaExec>("updateLauncherSources") {
    group = "Execution"
    description = "update sources with dependency information"
    classpath = examples.runtimeClasspath
    mainClass = "InstallKt"
    val classPath = configurations.runtimeClasspath.get()
        .resolvedConfiguration
        .resolvedArtifacts
        .filterNot {
            it.moduleVersion.id.name.startsWith("kotlin-stdlib-") ||
                    it.moduleVersion.id.name == "annotations"
        }
        .toSet()
    args = listOf(
        "update-launcher-sources",
        "--kotlin-script-version",
        version.toString(),
        "--kotlin-script-dependencies",
        classPath.joinToString(" ") { a ->
            "${a.moduleVersion.id.group}:${a.moduleVersion.id.name}:" +
                    "${a.moduleVersion.id.version}:" + when (a.classifier) {
                null -> ""
                else -> a.classifier
            } + when (a.type) {
                "jar" -> ""
                else -> "@${a.type}"
            }
        },
        "--kotlin-script-class-path",
        classPath.joinToString(":") { it.file.absolutePath },
        launcher.java.files.first {
            it.invariantSeparatorsPath.endsWith("/kotlin_script/Launcher.java")
        }.path
    )
}

tasks.create<JavaExec>("installMainJar") {
    dependsOn(mainJar, dokkaJar, kotlinSourcesJar)
    group = "Execution"
    description = "install kotlin_script to local repository"
    classpath = examples.runtimeClasspath
    mainClass = "InstallKt"
    args = listOf(
        "install-main-jar",
        mainJar.outputs.files.singleFile.toString()
    )
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
            artifact(kotlinSourcesJar)
            artifact(dokkaJar)
            versionMapping {
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }
            pom {
                name = "kotlin_script"
                description = "Lightweight build system for kotlin/jvm"
                url = "https://github.com/b8b/kotlin_script"
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
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
                    connection = "scm:git:https://github.com/b8b/kotlin_script.git"
                    developerConnection = "scm:git:ssh://github.com/b8b/kotlin_script.git"
                    url = "https://github.com/b8b/kotlin_script.git"
                }
            }
        }
    }
}
