import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    val kotlinVersion = "1.3.11"

    kotlin("jvm") version kotlinVersion
    id("org.jetbrains.dokka") version "0.9.17"

    signing
    `maven-publish`
    id("maven-publish-auth") version "2.0.1"
}

group = "org.cikit.kotlin_script"
version = project.properties["version"]
        ?.takeUnless { it == "unspecified" } ?: "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_1_7
    targetCompatibility = JavaVersion.VERSION_1_7
}

repositories {
    mavenCentral()
}

dependencies {
    compile(kotlin("stdlib"))
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "1.6"
}

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "1.6"
}
