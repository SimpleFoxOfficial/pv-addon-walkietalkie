pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases")
    }
}

plugins {
    // Toolchain auto-provisioning (Java 21 for MC 1.21.1). Optional but convenient.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "pv-addon-walkietalkie"
