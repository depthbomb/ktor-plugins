plugins {
    id("io.github.depthbomb.ktor-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

version = "0.1.0"
description = "Signed URL generation and validation for Ktor Server."

dependencies {
    api(libs.ktor.core)
    compileOnly(libs.ktor.resources)
    testImplementation(libs.ktor.resources)
    testImplementation(libs.ktor.test)
}
