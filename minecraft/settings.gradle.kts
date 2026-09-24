pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Allows a clean checkout to provision the Java 25 toolchain that paper-api 26.2 requires,
    // even on machines that only have an older JDK installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
    }
}

rootProject.name = "medieval"

// Modules are added as they gain real content: the Geyser and resource-pack
// modules land with the Bedrock and asset-pipeline phases.
include("medieval-api")
include("medieval-core")
include("medieval-paper")
