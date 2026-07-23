plugins {
    kotlin("jvm") version "2.4.0" apply false
    id("net.fabricmc.fabric-loom") version "1.17.16" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "net.fabricmc.fabric-loom")

    version = rootProject.property("mod_version") as String
    group = rootProject.property("maven_group") as String

    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    tasks.named<ProcessResources>("processResources") {
        inputs.property("version", project.version)
        filesMatching("fabric.mod.json") {
            expand("version" to project.version)
        }
    }
}
