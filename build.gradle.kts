import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    val kotlinVersion = "1.4.10"

    kotlin("jvm") version kotlinVersion
    id("org.jetbrains.dokka") version kotlinVersion
    `maven-publish`
}

group = "org.cikit.kotlin_script"
version = "1.4.10.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_7
    targetCompatibility = JavaVersion.VERSION_1_7
}

repositories {
    mavenCentral()
    jcenter()
}

sourceSets {
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
    testImplementation("junit:junit:4.13")

    examplesImplementation("com.pi4j:pi4j-core:1.2")
    examplesImplementation("org.apache.sshd:sshd-netty:2.4.0")
    examplesImplementation("io.vertx:vertx-core:3.9.0")
    examplesImplementation("com.fasterxml.jackson.core:jackson-databind:2.11.0")
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
    from(tasks["dokkaJavadoc"])
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
        attributes["Implementation-Version"] = version
        attributes["Implementation-Vendor"] = "cikit.org"
        attributes["Main-Class"] = "kotlin_script.KotlinScript"
        attributes["Class-Path"] = "kotlin-compiler-${getKotlinPluginVersion()}/kotlinc/lib/kotlin-stdlib.jar"
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
    group = "build"
    description = "copy runtime dependencies into build directory"
    destinationDir = File(buildDir, "libs/kotlin-compiler-${getKotlinPluginVersion()}/kotlinc/lib")
    from(configurations.kotlinCompilerClasspath)
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
