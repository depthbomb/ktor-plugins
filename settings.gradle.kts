pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage") // Gradle still marks centralized repository declarations as incubating.
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ktor-server-plugins"
include("ratelimiter", "csrf", "signedurls", "abort")
