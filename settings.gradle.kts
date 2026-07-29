// Single source of truth for the plugin versions that must match across the root build and
// the `launcher` subproject. Declaring them once here means both projects can apply the
// plugins WITHOUT a version literal — previously Kotlin was repeated three times and Shadow
// twice, and missing one site failed configuration with the opaque "plugin [id: '…'] was
// already on the classpath with a different version".
pluginManagement {
    val kotlinVersion = providers.gradleProperty("kotlin_version").get()
    val shadowVersion = providers.gradleProperty("shadow_version").get()
    plugins {
        id("org.jetbrains.kotlin.jvm") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.serialization") version kotlinVersion
        id("com.gradleup.shadow") version shadowVersion
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Lighting7"

include("launcher")
