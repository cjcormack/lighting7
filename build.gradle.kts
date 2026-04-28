import java.io.ByteArrayOutputStream

val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project

val sqlite_version: String by project
val exposed_version: String by project
val hikaricp_version: String by project

val lightingReactPath: String by project
val kotlinCompilerServerPath: String by project

plugins {
    // Kotlin 2.2.x is required by ktmidi-jvm-desktop's transitive stdlib. Upgrading from
    // 2.1.21 to 2.2.21 may regress kotlin-compiler-server (empty responses from completion
    // / highlight endpoints); user accepted that short-term risk in exchange for a clean
    // compile path against ktmidi and a Java 22+ toolchain.
    kotlin("jvm") version "2.2.21"
    id("io.ktor.plugin") version "3.3.3"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21"
    id("com.github.node-gradle.node") version "7.1.0"
    id("com.gradleup.shadow") version "8.3.5"
    id("maven-publish")
}

group = "uk.me.cormack"
version = "0.0.1"

kotlin {
    // Kotlin 2.2.21 supports up to JVM target 24. ktmidi-jvm-desktop (LibreMidiAccess)
    // uses the Java 22+ Foreign Function & Memory API, so we need ≥ 22. Target 24 (= non-LTS)
    // compiles and the app runs happily on the LTS JDK 25.
    jvmToolchain(24)
}

application {
    mainClass.set("uk.me.cormack.lighting7.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-resources")
    implementation("io.ktor:ktor-server-host-common")
    implementation("io.github.smiley4:ktor-openapi:5.4.0")
    implementation("io.github.smiley4:ktor-swagger-ui:5.4.0")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-json:$exposed_version")
    implementation("org.xerial:sqlite-jdbc:$sqlite_version")
    implementation("com.zaxxer:HikariCP:$hikaricp_version")
    implementation("io.ktor:ktor-server-websockets")
    implementation("io.ktor:ktor-server-netty")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-content-negotiation")
    testImplementation("io.ktor:ktor-client-websockets")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    implementation("ch.bildspur:artnet4j:0.6.2")

    // MIDI control-surface transport (Phase 0 of plans/completed/control-surface-plan.md).
    // ktmidi-jvm-desktop brings LibreMidiAccess (native libremidi via Panama FFM).
    implementation("dev.atsushieno:ktmidi-jvm:0.11.2")
    implementation("dev.atsushieno:ktmidi-jvm-desktop:0.11.2")
    // CoreMIDI4J — javax.sound.midi service provider for macOS that uses CoreMIDI directly
    // with proper hot-plug notifications. Built-in JVM sound API and libremidi both cache
    // the port list and miss disconnects; CoreMIDI4J registers notification callbacks and
    // reflects device changes live.
    implementation("uk.co.xfactory-librarians:coremidi4j:1.6")

    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-logging")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-client-encoding")

    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")

    implementation("org.jetbrains.kotlin:kotlin-scripting-common")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host")
    implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies")

    // mDNS (Bonjour) advertisement so iPad / LAN clients reach the backend at
    // `lighting7-<hostname>.local:8413` without entering an IP. See MdnsService.kt.
    implementation("org.jmdns:jmdns:3.5.12")
}

// ─── Frontend bundling ─────────────────────────────────────────────────
// The React app lives in a sibling repo (`../lighting-react` by default).
// `buildFrontend` runs `npm install && npm run build` against it, producing
// `dist/`. `copyFrontend` mirrors that into `src/main/resources/static/` so
// Ktor's `staticResources("/", "static")` serves it from the JAR classpath.

val lightingReactDir = file(lightingReactPath)
val frontendStaticDir = layout.projectDirectory.dir("src/main/resources/static")

node {
    // Download a pinned Node distribution into .gradle/ so the build doesn't depend on a
    // system Node install — gradle-daemon's sanitized PATH usually misses nvm anyway.
    download.set(true)
    version.set("24.10.0")
    nodeProjectDir.set(lightingReactDir)
}

val buildFrontend = tasks.register<com.github.gradle.node.npm.task.NpmTask>("buildFrontend") {
    description = "Run `npm install && npm run build` in the lighting-react repo."
    group = "build"
    workingDir.set(lightingReactDir)
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "build"))
    inputs.file(lightingReactDir.resolve("package.json"))
    inputs.file(lightingReactDir.resolve("package-lock.json"))
    inputs.file(lightingReactDir.resolve("vite.config.ts")).optional()
    inputs.file(lightingReactDir.resolve("tsconfig.json")).optional()
    inputs.file(lightingReactDir.resolve("eslint.config.js")).optional()
    inputs.file(lightingReactDir.resolve("index.html")).optional()
    inputs.dir(lightingReactDir.resolve("src")).withPropertyName("frontendSrc")
    outputs.dir(lightingReactDir.resolve("dist")).withPropertyName("frontendDist")
    onlyIf { lightingReactDir.exists() }
}

val copyFrontend = tasks.register<Copy>("copyFrontend") {
    description = "Copy the built React bundle into src/main/resources/static/."
    group = "build"
    dependsOn(buildFrontend)
    from(lightingReactDir.resolve("dist"))
    into(frontendStaticDir)
    // Require an actual entry point — a bare empty `dist/` (e.g. after a vite failure) means
    // the bundle is broken; serving the previous classpath copy is preferable to copying nothing.
    onlyIf { lightingReactDir.resolve("dist/index.html").exists() }
}

tasks.named("processResources") {
    dependsOn(copyFrontend)
}

tasks.named<Delete>("clean") {
    delete(frontendStaticDir)
}

// ─── Fat-jar packaging ─────────────────────────────────────────────────
// `shadowJar` produces a single self-contained `lighting7.jar` that the launcher
// (and Phase 3 jpackage) spawns directly. `mergeServiceFiles()` matters — without
// it, Logback's StaticLoggerBinder, Exposed's dialect SPI, and CoreMIDI4J's
// MidiDeviceProvider service entries collide and only one provider wins.

tasks.shadowJar {
    archiveFileName.set("lighting7.jar")
    mergeServiceFiles()
}

// ─── kotlin-compiler-server bootJar packaging ──────────────────────────
// Mirrors build-kotlin-compiler-server.sh: applies the same patches the bash
// script applies (jvm-target 17, lighting-libs jarDependency), runs `bootJar`
// in the user's JetBrains fork, copies the resulting fat JAR to
// build/distributions/kotlin-compiler-server.jar, then reverts the fork tree.
//
// The launcher spawns this JAR with `java -jar ... --server.port=8321 --server.address=127.0.0.1`.
// The fork lives at `kotlinCompilerServerPath` (default `../kotlin-compiler-server`); override
// via `-PkotlinCompilerServerPath=...` if it lives elsewhere.

val compilerServerDir = file(kotlinCompilerServerPath)
val compilerServerOutput = layout.buildDirectory.file("distributions/kotlin-compiler-server.jar")
val compilerServerLightingJarName = "Lighting7-${project.version}.jar"

abstract class ApplyCompilerServerPatches : DefaultTask() {
    @get:org.gradle.api.tasks.InputDirectory
    abstract val forkDir: DirectoryProperty

    @TaskAction
    fun apply() {
        val dir = forkDir.get().asFile
        val kotlinEnv = dir.resolve("common/src/main/kotlin/component/KotlinEnvironment.kt")
        val depsBuild = dir.resolve("dependencies/build.gradle.kts")
        require(kotlinEnv.exists()) { "kotlin-compiler-server fork is missing $kotlinEnv" }
        require(depsBuild.exists()) { "kotlin-compiler-server fork is missing $depsBuild" }

        val anchor1 = "val additionalCompilerArguments: List<String> = listOf("
        val patch1 = "        \"-jvm-target\", \"17\","
        val envText = kotlinEnv.readText()
        require(envText.contains(anchor1)) {
            "Anchor not found in $kotlinEnv: `$anchor1`. The fork has drifted; update ApplyCompilerServerPatches."
        }
        if (!envText.contains(patch1)) {
            kotlinEnv.writeText(envText.replace(anchor1, "$anchor1\n$patch1"))
        }

        val anchor2 = "kotlinWasmDependency(libs.kotlin.stdlib.wasm.js)"
        val patch2 = "    kotlinDependency(files(\"/kotlin-compiler-server/lighting-libs/Lighting7-0.0.1.jar\"))"
        val depsText = depsBuild.readText()
        require(depsText.contains(anchor2)) {
            "Anchor not found in $depsBuild: `$anchor2`. The fork has drifted; update ApplyCompilerServerPatches."
        }
        if (!depsText.contains(patch2)) {
            depsBuild.writeText(depsText.replace(anchor2, "$anchor2\n\n$patch2"))
        }
    }
}

val checkCompilerServerClean = tasks.register<Exec>("checkCompilerServerClean") {
    description = "Bail if the kotlin-compiler-server fork has uncommitted changes (we'll patch and revert)."
    group = "build"
    workingDir = compilerServerDir
    commandLine("git", "status", "--porcelain")
    standardOutput = ByteArrayOutputStream()
    onlyIf { compilerServerDir.exists() }
    doLast {
        val out = (standardOutput as ByteArrayOutputStream).toString().trim()
        if (out.isNotEmpty()) {
            throw GradleException(
                "kotlin-compiler-server fork at ${compilerServerDir.absolutePath} has uncommitted changes:\n$out\n" +
                    "Commit or stash them first — assembleCompilerServer applies patches then reverts via `git checkout --`."
            )
        }
    }
}

val stageCompilerServerLightingJar = tasks.register<Copy>("stageCompilerServerLightingJar") {
    description = "Copy the lighting7 thin jar into the fork's lighting-libs/ for the patched dependency."
    group = "build"
    dependsOn(tasks.named("jar"))
    onlyIf { compilerServerDir.exists() }
    from(tasks.named("jar")) {
        rename { compilerServerLightingJarName }
    }
    into(compilerServerDir.resolve("lighting-libs"))
}

val applyCompilerServerPatches = tasks.register<ApplyCompilerServerPatches>("applyCompilerServerPatches") {
    description = "Apply jvm-target 17 + lighting-libs kotlinDependency patches to the fork (mirrors build-kotlin-compiler-server.sh)."
    group = "build"
    dependsOn(checkCompilerServerClean, stageCompilerServerLightingJar)
    onlyIf { compilerServerDir.exists() }
    forkDir.set(compilerServerDir)
}

val runCompilerServerBootJar = tasks.register<Exec>("runCompilerServerBootJar") {
    description = "Run `./gradlew bootJar` in the kotlin-compiler-server fork."
    group = "build"
    dependsOn(applyCompilerServerPatches)
    workingDir = compilerServerDir
    onlyIf { compilerServerDir.exists() }
    val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
    commandLine(if (isWindows) "gradlew.bat" else "./gradlew", "bootJar")
}

val revertCompilerServerPatches = tasks.register<Exec>("revertCompilerServerPatches") {
    description = "Revert patches applied by applyCompilerServerPatches via `git checkout -- .` (also removes lighting-libs/)."
    group = "build"
    workingDir = compilerServerDir
    onlyIf { compilerServerDir.exists() }
    commandLine("git", "checkout", "--", ".")
    doLast {
        compilerServerDir.resolve("lighting-libs").deleteRecursively()
    }
}

// Make every patch-applying task finalised by the revert so the fork tree is clean even
// if bootJar fails. The revert runs in finalizer slot — Gradle guarantees it executes.
applyCompilerServerPatches.configure { finalizedBy(revertCompilerServerPatches) }
runCompilerServerBootJar.configure { finalizedBy(revertCompilerServerPatches) }
stageCompilerServerLightingJar.configure { finalizedBy(revertCompilerServerPatches) }

tasks.register("assembleCompilerServer") {
    description = "Build the kotlin-compiler-server fork's bootJar and copy it to build/distributions/kotlin-compiler-server.jar."
    group = "build"
    dependsOn(runCompilerServerBootJar)
    finalizedBy(revertCompilerServerPatches)
    onlyIf {
        if (!compilerServerDir.exists()) {
            logger.warn("Skipping assembleCompilerServer: ${compilerServerDir.absolutePath} not found. Set -PkotlinCompilerServerPath=... to point at your fork.")
            false
        } else {
            true
        }
    }

    val outputFile = compilerServerOutput.get().asFile
    outputs.file(outputFile)

    doLast {
        val libsDir = compilerServerDir.resolve("build/libs")
        require(libsDir.isDirectory) {
            "Expected ${libsDir} after bootJar; the fork did not produce a libs directory."
        }
        // Spring Boot's bootJar produces a single fat jar named `kotlin-compiler-server-*.jar`.
        // Pick the newest non-classifier jar (avoid `*-plain.jar` if Spring Boot ever generates one).
        val jar = libsDir.listFiles { f -> f.name.endsWith(".jar") && !f.name.endsWith("-plain.jar") }
            ?.maxByOrNull { it.lastModified() }
            ?: error("No bootJar output in ${libsDir}.")

        outputFile.parentFile.mkdirs()
        jar.copyTo(outputFile, overwrite = true)
        logger.lifecycle("Copied ${jar.name} → ${outputFile.relativeTo(rootDir)}")
    }
}

tasks.test {
    // Forward opt-in test flags to the forked test JVM. `fx.benchmark` gates the
    // FxEngineBenchmark harness; `dmx.benchmark` gates the DMX setValues benchmark;
    // `cueedit.profile` gates the cueEdit setProperty profile harness. All three are
    // skipped by default; the first two run for ~10 s when enabled, the cueEdit profile
    // for up to a few minutes (driving 6000 events through SQLite).
    val fxBenchmarkFlag = System.getProperty("fx.benchmark")
    val dmxBenchmarkFlag = System.getProperty("dmx.benchmark")
    val cueEditProfileFlag = System.getProperty("cueedit.profile")
    if (fxBenchmarkFlag != null) systemProperty("fx.benchmark", fxBenchmarkFlag)
    if (dmxBenchmarkFlag != null) systemProperty("dmx.benchmark", dmxBenchmarkFlag)
    if (cueEditProfileFlag != null) systemProperty("cueedit.profile", cueEditProfileFlag)
    if (fxBenchmarkFlag != null || dmxBenchmarkFlag != null || cueEditProfileFlag != null) {
        // Always rerun + stream stdout when a benchmark is requested, otherwise Gradle's
        // up-to-date check swallows the numbers and the test runner's default stdout policy
        // hides the println summary lines.
        outputs.upToDateWhen { false }
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
        }
    }
}
