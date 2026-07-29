plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow")
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
// This subproject declares no `version`, so the plain `jar` task's default output name is
// also `launcher.jar` — the same path shadowJar writes. Whichever task ran last won, so
// `stageJpackageInput` could stage the *thin* jar (no kotlin-stdlib, no Main-Class) and
// produce an installer whose tray launcher dies with NoClassDefFoundError on Intrinsics.
// Give the thin jar a classifier so only shadowJar owns `launcher.jar`.
tasks.jar {
    archiveClassifier.set("thin")
}

tasks.shadowJar {
    archiveFileName.set("launcher.jar")
    // See the root build's shadowJar for why INCLUDE is needed and why preserve-first is
    // paired with it. This module only bundles kotlin-stdlib, so there is nothing to
    // preserve-first today — mergeServiceFiles() is kept in sync with the root config in
    // case launcher ever picks up SPI deps, and failOnDuplicateEntries will flag it if so.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    failOnDuplicateEntries.set(true)
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
