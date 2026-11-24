plugins {
    id("io.github.depthbomb.ktor-library")
    `java-test-fixtures`
}

version = "0.1.0"
description = "Route and programmatic rate limiting with pluggable storage for Ktor Server."

dependencies {
    api(libs.ktor.core)
    implementation(libs.ktor.auth)
    testImplementation(libs.ktor.forwarded)
    testImplementation(libs.ktor.status)
    testImplementation(libs.ktor.test)
    testFixturesImplementation(kotlin("test"))
}
