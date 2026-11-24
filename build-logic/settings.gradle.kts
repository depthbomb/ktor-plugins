dependencyResolutionManagement {
    @Suppress("UnstableApiUsage") // Gradle still marks centralized repository declarations as incubating.
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
