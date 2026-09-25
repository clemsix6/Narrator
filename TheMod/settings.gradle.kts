pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version")
        id("org.jetbrains.kotlin.jvm") version providers.gradleProperty("kotlin_version")
        id("org.jetbrains.kotlin.plugin.serialization") version providers.gradleProperty("kotlin_version")
    }
}

rootProject.name = "narrator2"
