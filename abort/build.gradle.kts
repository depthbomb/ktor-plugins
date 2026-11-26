plugins {
    id("io.github.depthbomb.ktor-library")
}

version = "0.1.0"
description = "HTTP exceptions and conditional abort helpers for Ktor Server calls."

dependencies {
    api(libs.ktor.core)
    testImplementation(libs.ktor.status)
    testImplementation(libs.ktor.test)
}
