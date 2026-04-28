plugins {
    kotlin("jvm") version "2.2.21"
    application
}

kotlin {
    jvmToolchain(24)
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("uk.me.cormack.lighting7.launcher.LauncherMainKt")
}

dependencies {
    // Pure JDK only — `java.awt.SystemTray`, `java.net.http`, `java.awt.Desktop` cover
    // every responsibility (process spawning, readiness polling, browser open, tray menu).
    // Keeping this lean matters for jpackage in Phase 3: every extra dep grows the
    // installer.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

// Single-shot dev run: build the backend + compiler-server fat jars in the root project
// and pass their absolute paths to the launcher via system properties. Without these,
// LauncherMain falls back to looking for siblings of its own JAR (the jpackage layout).
tasks.named<JavaExec>("run") {
    dependsOn(":shadowJar", ":assembleCompilerServer")
    // Property name = JAR filename, so LauncherMain's resolveJar() can look up either
    // child by passing the bare filename through `System.getProperty`.
    systemProperty("lighting7.jar", rootProject.layout.buildDirectory.file("libs/lighting7.jar").get().asFile.absolutePath)
    systemProperty("kotlin-compiler-server.jar", rootProject.layout.buildDirectory.file("distributions/kotlin-compiler-server.jar").get().asFile.absolutePath)
}
