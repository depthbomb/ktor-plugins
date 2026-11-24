plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    `maven-publish`
    id("org.jetbrains.dokka")
    signing
}

group = "io.github.depthbomb.ktor"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    testImplementation(kotlin("test-junit"))
}

java {
    withSourcesJar()
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

val apiDocsJar = tasks.register<Jar>("apiDocsJar") {
    description = "Packages the generated API documentation in the Javadoc JAR."
    archiveClassifier.set("javadoc")
    dependsOn(tasks.named("dokkaGeneratePublicationHtml"))
    from(dokka.dokkaPublications.named("html").flatMap { it.outputDirectory })
}

val githubUrl = "https://github.com/depthbomb/ktor-plugins"
val library = publishing.publications.create<MavenPublication>("library") {
    from(components["java"])
    artifact(apiDocsJar)
    pom {
        name.set(project.name)
        description.set(provider { project.description })
        url.set(githubUrl)
        licenses {
            license {
                name.set("MIT License")
                url.set("$githubUrl/blob/master/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("depthbomb")
                name.set("depthbomb")
                url.set("https://github.com/depthbomb")
            }
        }
        scm {
            url.set(githubUrl)
            connection.set("scm:git:$githubUrl.git")
            developerConnection.set("scm:git:ssh://git@github.com/depthbomb/ktor-plugins.git")
        }
    }
}

// Keep release signing and staging separate from ordinary builds and local development.
if (providers.gradleProperty("centralRelease").isPresent) {
    signing {
        useGpgCmd()
        sign(library)
    }

    val staging = layout.buildDirectory.dir("central-staging")
    publishing.repositories.maven {
        name = "CentralStaging"
        url = uri(staging)
    }
    val clearStaging = tasks.register<Delete>("clearCentralStaging") {
        description = "Clears the local Central staging repository before publishing."
        delete(staging)
    }
    tasks.withType<PublishToMavenRepository>().configureEach {
        if (name == "publishLibraryPublicationToCentralStagingRepository") {
            dependsOn(clearStaging)
        }
    }

    tasks.register<Zip>("centralBundle") {
        group = "publishing"
        description = "Builds a signed ZIP for a manual Central Portal upload."
        dependsOn("publishLibraryPublicationToCentralStagingRepository")
        val coordinatePath = "${library.groupId.replace('.', '/')}/${library.artifactId}/${library.version}"
        from(staging) {
            include("$coordinatePath/**")
            exclude("**/*.asc.*", "**/maven-metadata*")
        }
        archiveFileName.set("${library.artifactId}-${library.version}-central.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    }
}
