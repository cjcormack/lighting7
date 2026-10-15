plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow")
}

kotlin {
    jvmToolchain(24)

    // As in the root build: a warning fails the build rather than accumulating.
    compilerOptions {
        allWarningsAsErrors = true
    }
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

// Single-shot dev run: build the backend fat jar in the root project and pass its absolute path
// to the launcher via a system property. Without it, LauncherMain falls back to looking for a
// sibling of its own JAR (the jpackage layout).
//
// It runs when asked for as this project's: `./gradlew :launcher:run` (or an abbreviation of it), or
// `run` from this directory or with `-p launcher`. A bare `./gradlew run` at the root is the desk's
// alone; were it this one's too, a desk quit from its own menu would end that `run` and the build
// would go on to start the launcher, which spawns a desk of its own.
val startedHere = (gradle.startParameter.projectDir ?: gradle.startParameter.currentDir).canonicalFile == projectDir.canonicalFile
val launcherRunNamed = startedHere || gradle.startParameter.taskNames.any { name ->
    val path = name.trimStart(':').split(':')
    path.size == 2 && path[0].isNotEmpty() && "launcher".startsWith(path[0]) && path[1].isNotEmpty() && "run".startsWith(path[1])
}
tasks.named<JavaExec>("run") {
    if (launcherRunNamed) dependsOn(":shadowJar")
    onlyIf("run as ./gradlew :launcher:run") { launcherRunNamed }
    // Property name = JAR filename, so LauncherMain's resolveJar() can look the child up by
    // passing the bare filename through `System.getProperty`.
    systemProperty("lighting7.jar", rootProject.layout.buildDirectory.file("libs/lighting7.jar").get().asFile.absolutePath)
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
    // Consume the ROOT project's generateBuildInfo output rather than regenerating it here.
    // The launcher and the backend are separate jars inside one installer and MUST agree on the
    // version byte-for-byte — a mismatch would surface as the app reporting one version while
    // the updater compares another, i.e. an update that reinstalls itself forever.
    //
    // Regenerating it locally (the stageCompilerServerVersion shape above) would make the Gradle
    // *property* the source of truth and fork the derivation logic — the defaulting, the MSI
    // version validation, the channel check — into two build files. One task, two consumers.
    //
    // This is a dependency on a task's output *file*, not on the root project's classpath.
    // Nothing new lands on the launcher's compile or runtime classpath, so the pure-JDK
    // invariant this module is built around is untouched.
    resources.srcDir(rootProject.tasks.named("generateBuildInfo"))
}
