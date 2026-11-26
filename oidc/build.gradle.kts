plugins {
    id("io.github.depthbomb.ktor-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

version = "0.1.0"
description = "Route-owned OpenID Connect browser login for Ktor Server."

dependencies {
    api(libs.ktor.core)
    api(libs.ktor.sessions)
    api(libs.kotlinx.json)
    implementation(libs.nimbus.oidc)
    testImplementation(libs.ktor.test)
}
