val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project

val sqlite_version: String by project
val exposed_version: String by project
val hikaricp_version: String by project

val lightingReactPath: String by project

plugins {
    // Kotlin 2.2.x is required by ktmidi-jvm-desktop's transitive stdlib. Upgrading from
    // 2.1.21 to 2.2.21 may regress kotlin-compiler-server (empty responses from completion
    // / highlight endpoints); user accepted that short-term risk in exchange for a clean
    // compile path against ktmidi and a Java 22+ toolchain.
    kotlin("jvm") version "2.2.21"
    id("io.ktor.plugin") version "3.3.3"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21"
    id("com.github.node-gradle.node") version "7.1.0"
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
