import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    val kotlinVersion = "1.6.10"

    kotlin("jvm") version kotlinVersion
    id("org.jetbrains.dokka") version "1.6.0"
    `maven-publish`
}

group = "org.cikit"
version = "1.6.10.17"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
}

sourceSets {
    create("runner") {
        java {
            srcDir("runner")
        }
    }
    create("examples") {
        withConvention(KotlinSourceSet::class) {
            kotlin.setSrcDirs(listOf("examples"))
            Unit
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

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    testImplementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    testImplementation("org.apache.bcel:bcel:6.5.0")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.25")

    examplesImplementation("org.cikit:kotlin_script:$version")
    examplesImplementation("org.apache.commons:commons-compress:1.21")
    examplesImplementation("org.eclipse.jdt:ecj:3.16.0")
    examplesImplementation("com.pi4j:pi4j-core:1.2")
    examplesImplementation("org.apache.sshd:sshd-netty:2.8.0")
    examplesImplementation("org.apache.sshd:sshd-git:2.8.0")
    examplesImplementation("net.i2p.crypto:eddsa:0.3.0")
    examplesImplementation("org.bouncycastle:bcpkix-jdk15on:1.69")
    examplesImplementation("io.vertx:vertx-core:4.2.1")
    examplesImplementation("com.fasterxml.jackson.core:jackson-databind:2.13.0")
    examplesImplementation("com.github.ajalt.clikt:clikt-jvm:3.3.0")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jdkHome = properties["jdk.home"]?.toString()?.takeIf { it != "unspecified" }
    jvmTarget = "1.8"
}

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "1.8"
}

val main by sourceSets
val runner by sourceSets

val sourcesJar by tasks.creating(Jar::class) {
    group = "build"
    archiveClassifier.set("sources")
    from(main.allSource)
}

val dokkaJar by tasks.creating(Jar::class) {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    description = "Assembles Kotlin docs with Dokka"
    archiveClassifier.set("javadoc")
    from(tasks["dokkaJavadoc"])
}

val runnerJar by tasks.creating(Jar::class) {
    group = "build"
    archiveClassifier.set("runner")
    from(runner.output)
    manifest {
        attributes["Implementation-Title"] = "kotlin_script"
        attributes["Implementation-Version"] = archiveVersion
        attributes["Implementation-Vendor"] = "cikit.org"
        attributes["Main-Class"] = "kotlin_script.Runner"
    }
}

tasks.named<Jar>("jar") {
    dependsOn("generatePomFileForMavenJavaPublication")
    into("META-INF/maven/${project.group}/${project.name}") {
        from(File(buildDir, "publications/mavenJava"))
        rename(".*", "pom.xml")
    }
    val compilerClassPath = configurations.kotlinCompilerClasspath.get().resolvedConfiguration.resolvedArtifacts
    manifest {
        attributes["Implementation-Title"] = "kotlin_script"
        attributes["Implementation-Version"] = archiveVersion
        attributes["Implementation-Vendor"] = "cikit.org"
        attributes["Main-Class"] = "kotlin_script.KotlinScript"
        attributes["Class-Path"] = "../../../jetbrains/kotlin/kotlin-stdlib/${getKotlinPluginVersion()}/kotlin-stdlib-${getKotlinPluginVersion()}.jar"
        attributes["Kotlin-Script-Version"] = archiveVersion
        attributes["Kotlin-Compiler-Version"] = getKotlinPluginVersion()
        attributes["Kotlin-Compiler-Class-Path"] = compilerClassPath.joinToString(" ") { a ->
            "${a.moduleVersion.id.group}:${a.moduleVersion.id.name}:" +
                    "${a.moduleVersion.id.version}:" + when (a.classifier) {
                null -> ""
                else -> a.classifier
            } + when (a.type) {
                "jar" -> ""
                else -> "@${a.type}"
            }
        }
    }
}

val copyDependencies by tasks.registering(Copy::class) {
    val kotlinVersion = getKotlinPluginVersion()
    group = "build"
    description = "copy runtime dependencies into build directory"
    destinationDir = File(buildDir, "libs/kotlin-compiler-$kotlinVersion/kotlinc/lib")
    from(configurations.kotlinCompilerClasspath)
    rename { f ->
        if (kotlinVersion in f) {
            f.replace("-$kotlinVersion", "")
        } else {
            f.replace(Regex("""-\d.*(\.jar)$"""), "\$1")
        }
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
            pom {
                name.set("kotlin_script")
                description.set("Lightweight build system for kotlin/jvm")
                url.set("https://github.com/b8b/kotlin_script")
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
