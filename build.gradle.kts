plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka") version "1.9.10"
    `maven-publish`
}

group = "org.cikit"
version = "1.9.21.22"

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
    implementation("com.github.ajalt.mordant:mordant-jvm:2.2.0")

    testImplementation("org.apache.bcel:bcel:6.7.0")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")

    examplesImplementation(main.runtimeClasspath)
    examplesImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    examplesImplementation("org.apache.commons:commons-compress:1.25.0")
    examplesImplementation("org.eclipse.jdt:ecj:3.33.0")
    examplesImplementation("com.pi4j:pi4j-core:1.2")
    examplesImplementation("org.apache.sshd:sshd-netty:2.9.0")
    examplesImplementation("org.apache.sshd:sshd-git:2.9.0")
    examplesImplementation("net.i2p.crypto:eddsa:0.3.0")
    examplesImplementation("org.bouncycastle:bcpkix-jdk15on:1.70")
    examplesImplementation("io.vertx:vertx-core:4.5.0")
    examplesImplementation("com.fasterxml.jackson.core:jackson-databind:2.15.3")
    examplesImplementation("com.github.ajalt.clikt:clikt-jvm:4.2.1")
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
