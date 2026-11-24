plugins {
    id("io.github.depthbomb.ktor-library")
}

version = "0.1.0"
description = "Session-bound CSRF token generation and validation for Ktor Server."

dependencies {
    api(libs.ktor.core)
    testImplementation(libs.ktor.sessions)
    testImplementation(libs.ktor.test)
}
