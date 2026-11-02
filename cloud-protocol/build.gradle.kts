import java.util.Properties

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// The coordinates the desk depends on, `uk.me.cormack.lighting7:cloud-protocol`, which Gradle
// substitutes with this build.
group = "uk.me.cormack.lighting7"

val deskProperties = providers.fileContents(layout.projectDirectory.file("../gradle.properties")).asText.map { text ->
    Properties().apply { load(text.reader()) }
}
val deskKotlinVersion = deskProperties.map { it.getProperty("kotlin_version") }
val serializationVersion = deskProperties.get().getProperty("serialization_version")
    ?: error("../gradle.properties has no serialization_version")

kotlin {
    // The desk's toolchain, so a consumer runs on JVM 24 or later.
    jvmToolchain(24)

    // As in the desk's build: a warning fails the build.
    compilerOptions {
        allWarningsAsErrors = true
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // `api`: the public types carry `JsonObject` and take a `Json`.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    testImplementation(kotlin("test-junit"))
    testImplementation(kotlin("reflect"))
}

tasks.test {
    // ProtocolBuildTest holds the Kotlin this build compiled with to the desk's.
    systemProperty("desk.kotlinVersion", deskKotlinVersion.get())
}
