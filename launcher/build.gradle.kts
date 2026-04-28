plugins {
    kotlin("jvm") version "2.2.21"
    application
    id("com.gradleup.shadow") version "8.3.5"
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

// Phase 3 packaging: jpackage's --input wants exactly one launcher.jar. The shadow
// plugin merges kotlin-stdlib (the only non-JDK dep) into a single self-contained
// jar so the install layout stays to three flat jars. mergeServiceFiles() mirrors
// the root shadowJar config — kept in sync if launcher ever picks up SPI deps.
tasks.shadowJar {
    archiveFileName.set("launcher.jar")
    mergeServiceFiles()
}

// LauncherMain.ensureDefaultConfig() reads `/default-local.conf` from the launcher
// classpath on first install. Generate it from the canonical example.local.conf
// at the repo root so the two files can't drift.
val stageDefaultConfig = tasks.register<Copy>("stageDefaultConfig") {
    from(rootProject.file("example.local.conf"))
    into(layout.buildDirectory.dir("generated/resources/default-config"))
    rename { "default-local.conf" }
}

sourceSets.main {
    resources.srcDir(stageDefaultConfig)
}
