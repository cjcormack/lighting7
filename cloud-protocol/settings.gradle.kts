// The wire contract between the desk and Lighting7 Cloud (docs/cloud-protocol.md). Its own build so
// the cloud can consume it from a sparse checkout of this directory and the repo's root files. The
// desk includes it with `includeBuild("cloud-protocol")`.
pluginManagement {
    // The desk's root gradle.properties is the one place the Kotlin version is set, so this build
    // cannot drift from the desk's. A sparse checkout in cone mode always includes the root files.
    val deskProperties = providers.fileContents(layout.rootDirectory.file("../gradle.properties")).asText.orNull
        ?: error("cloud-protocol reads kotlin_version from ../gradle.properties, the desk's; check out the repo's root files too")
    val kotlinVersion = java.util.Properties().apply { load(deskProperties.reader()) }.getProperty("kotlin_version")
        ?: error("../gradle.properties has no kotlin_version")
    plugins {
        id("org.jetbrains.kotlin.jvm") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.serialization") version kotlinVersion
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "cloud-protocol"
