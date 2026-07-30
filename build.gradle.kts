import java.io.ByteArrayOutputStream

val kotlin_version: String by project
val logback_version: String by project

val sqlite_version: String by project
val exposed_version: String by project
val hikaricp_version: String by project

val lightingReactPath: String by project
val kotlinCompilerServerPath: String by project

plugins {
    // Kotlin floor is 2.2.x: ktmidi-jvm-desktop's transitive stdlib needs it (dropping
    // below 2.2 reintroduces the type-resolution regressions recorded in
    // docs/plans/completed/control-surface-plan.md).
    //
    // We sit at 2.4.10 rather than 2.2.21 because of a *compiler bug*, not a Ktor
    // requirement — io.ktor.plugin 3.5.x does not itself demand 2.4. On 2.2.21 the newer
    // Ktor toolchain trips an internal compiler error in the IR const-evaluation
    // interpreter (`InterpreterMethodNotFoundError: Unknown function: toUByte(kotlin.Int)`)
    // while compiling this project's test sources — plain `0.toUByte()` assertions in
    // EasingCurveTest. If that is fixed in a later 2.2.x/2.3.x patch, 2.4 stops being a
    // floor; don't read it as one.
    //
    // The in-app script editor's kotlin-compiler-server fork tracks this version: keep the
    // fork checked out on its matching upstream branch and `compilerServerKotlinVersion` in
    // gradle.properties in step with the version here. Kotlin only reads metadata from its own
    // minor plus one, so a fork more than one minor behind rejects every Lighting7 symbol in the
    // editor ("compiled with an incompatible version of Kotlin … binary version 2.4.0, expected
    // 2.1.0") even though the same scripts compile and run correctly in the app itself.
    kotlin("jvm")
    id("io.ktor.plugin") version "3.5.1"
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.github.node-gradle.node") version "7.1.0"
    id("com.gradleup.shadow")
    id("maven-publish")
}

group = "uk.me.cormack"
version = "0.0.1"

// Kotlin 2.4.10 supports JVM target 24. ktmidi-jvm-desktop (LibreMidiAccess) uses the Java 22+
// Foreign Function & Memory API, so we need ≥ 22. Target 24 (= non-LTS) compiles and the app runs
// happily on the LTS JDK 25.
//
// Held in a val because the kotlin-compiler-server patch below has to compile scripts at this same
// target — Kotlin won't inline a function from a module built for a higher target than the current
// one, which would break every `inline`/`reified` helper the scripting DSL exposes.
val jvmToolchainVersion = 24

kotlin {
    jvmToolchain(jvmToolchainVersion)
}

application {
    mainClass.set("uk.me.cormack.lighting7.ApplicationKt")
}

repositories {
    mavenCentral()
}

// Two legacy API jars ship the same `javax.*` packages as their maintained `jakarta.*`
// successors, which already resolve here — so the fat jar would contain two byte-different
// copies of ~220 classes and the winner would be decided by classpath order:
//
//   javax.validation:validation-api:1.1.0.Final  (via io.swagger:swagger-core, from
//       io.github.smiley4:ktor-openapi)  is superseded by jakarta.validation-api:2.0.2
//   javax.xml.bind:jaxb-api:2.3.0  (via ch.bildspur:artnet4j)  is superseded by
//       jakarta.xml.bind:jakarta.xml.bind-api:2.3.3
//
// Both jakarta artifacts still use the `javax.*` namespace and are API-compatible supersets
// (2.0.2 adds valueextraction, ClockProvider, @NotEmpty/@Email), so the consumers above keep
// linking. Dropping the legacy pair is what makes shadowJar's `failOnDuplicateEntries`
// achievable — see the fat-jar section below.
configurations.configureEach {
    exclude(group = "javax.validation", module = "validation-api")
    exclude(group = "javax.xml.bind", module = "jaxb-api")
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-resources")
    implementation("io.ktor:ktor-server-host-common")
    implementation("io.github.smiley4:ktor-openapi:5.7.0")
    implementation("io.github.smiley4:ktor-swagger-ui:5.7.0")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-server-status-pages")
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
    implementation("dev.atsushieno:ktmidi-jvm:0.12.0")
    implementation("dev.atsushieno:ktmidi-jvm-desktop:0.12.0")
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

    implementation("org.jetbrains.kotlin:kotlin-scripting-common")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host")
    implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies")

    // mDNS (Bonjour) advertisement so iPad / LAN clients reach the backend at
    // `lighting7-<hostname>.local:8413` without entering an IP. See MdnsService.kt.
    implementation("org.jmdns:jmdns:3.6.3")

    // JGit for the cloud-sync per-project working tree (phase 3 of plans/cloud-sync.md).
    // Previously pinned to 6.10.0 on the grounds that 7.x carried a larger transitive
    // footprint for jlink runtimes. That turned out not to be true: 7.7.1 resolves the same
    // three transitives as 6.10.0 (JavaEWAH, slf4j-api, commons-codec), with only
    // commons-codec moving 1.17.0 -> 1.22.0. Pin lifted.
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.1.202607240634-r")

    // Cross-platform OS-keychain access for storing GitHub PATs (cloud-sync phase 4).
    // Wraps macOS Security framework, libsecret, and Windows Credential Manager via JNA.
    implementation("com.github.javakeyring:java-keyring:1.0.4")
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

// ─── Build-time bundled OAuth credentials ──────────────────────────────
// Installer distributions (Windows .msi built by GitHub Actions) can't ship a
// `local.conf` with secrets — the workflow runs in the public repo. Instead we
// inject `-PghOauthClientId` / `-PghOauthClientSecret` from GitHub Actions
// repository secrets and compile them into a generated Kotlin object that
// `State.oauthGitHubClient` falls back to when `local.conf` doesn't supply
// values. Local builds leave the props empty and the fallback is inert.
//
// The credentials end up in the shadow jar (and therefore the .msi). That's
// the standard "public client" trade-off for OAuth desktop apps — a leaked
// secret is rotated by changing the GitHub App's secret + re-running this
// workflow + shipping a new installer. GitHub Apps are scoped per-install, so
// a leak can't pivot beyond what each user has explicitly authorised.

val ghOauthClientId: String = (findProperty("ghOauthClientId") as String?).orEmpty()
val ghOauthClientSecret: String = (findProperty("ghOauthClientSecret") as String?).orEmpty()

val bundledOAuthGenDir = layout.buildDirectory.dir("generated/source/oauth/main/kotlin")

val generateBundledOAuthCredentials = tasks.register("generateBundledOAuthCredentials") {
    description = "Emit BundledOAuthCredentials.kt with values from -PghOauthClientId / -PghOauthClientSecret (empty by default)."
    group = "build"

    // Hash inputs so the secret never appears in build logs / Gradle's --info.
    inputs.property("clientIdHash", ghOauthClientId.hashCode())
    inputs.property("clientSecretHash", ghOauthClientSecret.hashCode())
    inputs.property("clientIdEmpty", ghOauthClientId.isEmpty())
    inputs.property("clientSecretEmpty", ghOauthClientSecret.isEmpty())
    outputs.dir(bundledOAuthGenDir)

    doLast {
        val outFile = bundledOAuthGenDir.get().asFile
            .resolve("uk/me/cormack/lighting7/sync/auth/oauth/BundledOAuthCredentials.kt")
        outFile.parentFile.mkdirs()

        fun escape(s: String): String = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\$", "\\\$")

        outFile.writeText(
            """
            // Generated by `generateBundledOAuthCredentials`. DO NOT EDIT.
            package uk.me.cormack.lighting7.sync.auth.oauth

            internal object BundledOAuthCredentials {
                const val GITHUB_CLIENT_ID: String = "${escape(ghOauthClientId)}"
                const val GITHUB_CLIENT_SECRET: String = "${escape(ghOauthClientSecret)}"
            }
            """.trimIndent() + "\n"
        )
    }
}

kotlin.sourceSets["main"].kotlin.srcDir(bundledOAuthGenDir)

tasks.named("compileKotlin") {
    dependsOn(generateBundledOAuthCredentials)
}

// ─── Fat-jar packaging ─────────────────────────────────────────────────
// `shadowJar` produces a single self-contained `lighting7.jar` that the launcher
// (and Phase 3 jpackage) spawns directly. `mergeServiceFiles()` matters — without
// it, Logback's StaticLoggerBinder, Exposed's dialect SPI, and CoreMIDI4J's
// MidiDeviceProvider service entries collide and only one provider wins.

tasks.shadowJar {
    archiveFileName.set("lighting7.jar")

    // Shadow 9 applies DuplicatesStrategy.EXCLUDE by default, which drops duplicate
    // entries *before* the transformers below run — that would defeat both
    // mergeServiceFiles() (the SPI merging described above) and the built-in
    // KotlinModuleMetadataTransformer. INCLUDE lets every copy reach the transformers,
    // which is what actually merges them into one entry.
    //
    // INCLUDE alone is NOT safe, because several dependencies genuinely ship the same
    // entry. Within a single jar, ZipFile/JarFile builds its lookup map from the central
    // directory and a later duplicate OVERWRITES an earlier one, so `getEntry` returns the
    // LAST copy — the opposite of the first-found copy that EXCLUDE used to keep. Verified
    // empirically: with plain INCLUDE, `javax/validation/Configuration.class` resolved to
    // the 1723-byte validation-api 1.1.0.Final copy instead of the 2117-byte
    // jakarta.validation-api 2.0.2 one, silently downgrading the effective Bean Validation
    // API to a mixed 1.1/2.0 surface that swagger-core (behind `GET /openapi`) can fail on
    // with NoSuchMethodError.
    //
    // Duplicate *classes* are handled at the dependency level (see the `exclude`s near
    // `repositories` above) — Shadow's resource transformers never see `.class` entries, so
    // they cannot fix that case. What remains here is duplicated *resources*:
    // `kotlin/**/*.kotlin_builtins` (which the scripting host reads), a couple of JSON
    // schema files, and licence/notice text. PreserveFirstFoundResourceTransformer keeps
    // first-found for those while leaving service-file and kotlin_module merging intact.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    transform(com.github.jengelman.gradle.plugins.shadow.transformers.PreserveFirstFoundResourceTransformer::class.java) {
        include("kotlin/*.kotlin_builtins")
        include("kotlin/**/*.kotlin_builtins")
        include("draftv3/schema")
        include("draftv4/schema")
        include("jnijavacpp.cpp")
        // Licence/notice text duplicated across dependencies — keep the first and move on.
        include("META-INF/LICENSE*")
        include("META-INF/NOTICE*")
        include("META-INF/AL2.0")
        include("META-INF/ASL-2.0.txt")
        include("META-INF/LGPL2.1")
        include("META-INF/LGPL-3.0.txt")
        include("META-INF/DEPENDENCIES")
        include("META-INF/thirdparty-LICENSE")
        include("META-INF/FastDoubleParser-*")
        include("META-INF/io.netty.versions.properties")
    }
    // Guard: with INCLUDE, any *new* duplicate a future dependency bump introduces would
    // otherwise be resolved silently by classpath order. Fail the build instead so the
    // pattern list above stays honest.
    failOnDuplicateEntries.set(true)
    mergeServiceFiles()
}

// ─── kotlin-compiler-server bootJar packaging ──────────────────────────
// Patches the user's JetBrains fork in place (`-jvm-target`, lighting-libs
// kotlinDependency), runs `bootJar` there, copies the resulting fat JAR to
// build/distributions/kotlin-compiler-server.jar, then reverts the fork tree.
//
// The launcher spawns this JAR with `java -jar ... --server.port=8321 --server.address=127.0.0.1`.
// The fork lives at `kotlinCompilerServerPath` (default `../kotlin-compiler-server`); override
// via `-PkotlinCompilerServerPath=...` if it lives elsewhere.

// The Kotlin version the kotlin-compiler-server fork is checked out at. Names the
// `<version>[-js|-wasm]/` library directories the fork's `:dependencies:copy*` tasks emit and
// that its generated application.properties reads at runtime. Declared in gradle.properties;
// the launcher reads the same value from a generated resource, so the six
// `--libraries.folder.*` flags can no longer drift from it. See the plugins block for why it
// has to stay within one minor of this project's Kotlin version.
// `isNotBlank` matters as much as the null check: a blank version makes the `startsWith(version)`
// scan in assembleCompilerServer match *every* top-level directory in the fork (`.git/`, `build/`,
// …) and recursively copy gigabytes of them into build/distributions while still satisfying
// `require(libDirs.isNotEmpty())`.
val compilerServerKotlinVersion: String =
    (findProperty("compilerServerKotlinVersion") as String?)?.takeIf { it.isNotBlank() }
        ?: error("compilerServerKotlinVersion is not set or is blank — declare it in gradle.properties.")

val compilerServerDir = file(kotlinCompilerServerPath)
val compilerServerOutput = layout.buildDirectory.file("distributions/kotlin-compiler-server.jar")
val compilerServerLightingJarName = "Lighting7-${project.version}.jar"

abstract class ApplyCompilerServerPatches : DefaultTask() {
    @get:org.gradle.api.tasks.InputDirectory
    abstract val forkDir: DirectoryProperty

    /**
     * JVM target for scripts the compiler server compiles. Must match the target the Lighting7
     * jar was built with: Kotlin refuses to inline a function from a module built for a *higher*
     * JVM target than the one currently being compiled, which would break every `inline`/`reified`
     * helper in the scripting DSL.
     */
    @get:Input
    abstract val jvmTarget: Property<Int>

    /**
     * Absolute path to the staged Lighting7 jar inside the fork's `lighting-libs/`, as a plain
     * `@Input` string rather than an `@InputFile`. The jar's *content* is already covered by the
     * [forkDir] snapshot (it is staged inside that tree); what needs tracking here is the **path**,
     * because it is baked into the generated `files("…")` literal — so bumping `project.version`
     * has to re-run the patch rather than leave the fork pointed at a stale jar name. Tracking it as
     * a String also sidesteps the missing-file validation an `@InputFile` would impose.
     */
    @get:Input
    abstract val lightingJarPath: Property<String>

    @TaskAction
    fun apply() {
        val dir = forkDir.get().asFile
        // Upstream moved this file into a package directory somewhere between 2.1 and 2.4; accept
        // either layout so a fork on an older branch still patches.
        val kotlinEnv = listOf(
            "common/src/main/kotlin/com/compiler/server/common/components/KotlinEnvironment.kt",
            "common/src/main/kotlin/component/KotlinEnvironment.kt",
        ).map(dir::resolve).firstOrNull { it.exists() }
            ?: error(
                "kotlin-compiler-server fork at $dir has no KotlinEnvironment.kt at either known path. " +
                    "The fork has drifted; update ApplyCompilerServerPatches."
            )
        val depsBuild = dir.resolve("dependencies/build.gradle.kts")
        require(depsBuild.exists()) { "kotlin-compiler-server fork is missing $depsBuild" }

        val anchor1 = "val additionalCompilerArguments: List<String> = listOf("
        val patch1 = "        \"-jvm-target\", \"${jvmTarget.get()}\","
        val envText = kotlinEnv.readText()
        require(envText.contains(anchor1)) {
            "Anchor not found in $kotlinEnv: `$anchor1`. The fork has drifted; update ApplyCompilerServerPatches."
        }
        if (!envText.contains(patch1)) {
            kotlinEnv.writeText(envText.replace(anchor1, "$anchor1\n$patch1"))
        }

        val anchor2 = "kotlinWasmDependency(libs.kotlin.stdlib.wasm.js)"
        // Absolute, and forward-slashed so the generated Kotlin string literal stays valid on
        // Windows. A relative path would resolve against the fork's `:dependencies` subproject
        // rather than its root, and silently contribute nothing: Gradle's `files()` tolerates
        // missing paths, and the fork's `Copy` task skips them — so the editor would come up with
        // no Lighting7 symbols at all rather than failing the build.
        //
        // Escaped because this lands inside a Kotlin string literal in the fork's build script: a
        // `$` in the checkout path would otherwise be read as a template expression and fail the
        // fork's *own* build script compilation with an "unresolved reference" in a file the
        // developer never touched.
        val jarPath = lightingJarPath.get()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
        val patch2 = "    kotlinDependency(files(\"$jarPath\"))"
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
    description = "Apply jvm-target + lighting-libs kotlinDependency patches to the kotlin-compiler-server fork."
    group = "build"
    dependsOn(checkCompilerServerClean, stageCompilerServerLightingJar)
    onlyIf { compilerServerDir.exists() }
    forkDir.set(compilerServerDir)
    jvmTarget.set(jvmToolchainVersion)
    lightingJarPath.set(
        compilerServerDir.resolve("lighting-libs/$compilerServerLightingJarName").invariantSeparatorsPath
    )
}

val runCompilerServerBootJar = tasks.register<Exec>("runCompilerServerBootJar") {
    description = "Run `./gradlew bootJar` in the kotlin-compiler-server fork."
    group = "build"
    dependsOn(applyCompilerServerPatches)
    workingDir = compilerServerDir
    onlyIf { compilerServerDir.exists() }
    val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
    commandLine(if (isWindows) "gradlew.bat" else "./gradlew", "bootJar")

    // The fork pins its daemon JVM to Java 17 via gradle/gradle-daemon-jvm.properties.
    // When the parent build runs on JDK 24+ (required for jpackage), the fork's daemon
    // resolution can't find a matching JDK on hosts where JDK 17 isn't on a default
    // search path (e.g. GitHub-hosted Windows runners). Let CI point this Exec at the
    // right JDK with `-PkotlinCompilerServerJavaHome=...`; locally, JAVA_HOME is fine.
    findProperty("kotlinCompilerServerJavaHome")?.toString()?.let { javaHomeOverride ->
        environment("JAVA_HOME", javaHomeOverride)
    }
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
    // The doLast block reads bootJar's output jar and the fork's `<kotlin>*/` lib dirs;
    // declare them as inputs so this task is properly UP-TO-DATE-checked and the 38 MB
    // recursive copy gets skipped when nothing has changed since the last run.
    inputs.files(fileTree(compilerServerDir) {
        include("build/libs/*.jar")
        include("$compilerServerKotlinVersion*/**")
    })
    // outputs.dir covers both the bootJar copy and the staged lib dirs.
    outputs.dir(outputFile.parentFile)

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

        // application.properties pins the runtime library dirs to
        // `$compilerServerKotlinVersion[-js|-wasm|…]`. Other
        // top-level `2.x.y/` directories in the fork (e.g. `2.3.0/` for the compiler-server's
        // own version) are build-time artefacts the runtime never reads — skip them.
        val libDirs = compilerServerDir.listFiles { f ->
            f.isDirectory && f.name.startsWith(compilerServerKotlinVersion)
        } ?: emptyArray()
        require(libDirs.isNotEmpty()) {
            "Expected `$compilerServerKotlinVersion[-js|-wasm|…]` library directories in $compilerServerDir after bootJar — none found. " +
                "Either the fork's :dependencies:copy* tasks did not run, or the fork is checked out on a different Kotlin " +
                "version — in which case update `compilerServerKotlinVersion` in gradle.properties to match its branch."
        }
        libDirs.forEach { src ->
            val dest = File(outputFile.parentFile, src.name)
            dest.deleteRecursively()
            src.copyRecursively(dest)
            logger.lifecycle("Copied ${src.name}/ → ${dest.relativeTo(rootDir)}/")
        }
    }
}

// Dev convenience: run the compiler server in the foreground so `./gradlew run` (backend only,
// no launcher) has a script-editor backend to talk to. `:launcher:run` already spawns its own —
// use this when you want the backend under the Gradle `run` task or an IDE debugger instead.
//
// Passes the six `--libraries.folder.*` flags explicitly rather than leaning on the jar's
// CWD-relative application.properties. That costs a few lines but exercises the same flag wiring
// the packaged launcher uses, so a broken `compilerServerKotlinVersion` fails here in dev rather
// than only in an installer.
//
// `Exec` declares no outputs, so the `assembleCompilerServer` chain — including the fork's
// `bootJar` — re-runs on every invocation. It's incremental and adds only a few seconds, but for a
// tight edit/restart loop where the fork hasn't changed, `-PreuseStagedCompilerServer` drops the
// build chain entirely and just runs what is already staged in build/distributions/.
//
// Do NOT reach for `-x runCompilerServerBootJar` for that: `-x` also prunes
// `checkCompilerServerClean` (reachable only through the excluded task) while leaving
// `revertCompilerServerPatches` in the graph, so it would run `git checkout -- .` in the
// developer's fork with the uncommitted-changes guard gone — silently destroying work.
val reuseStagedCompilerServer = findProperty("reuseStagedCompilerServer") != null

tasks.register<Exec>("runCompilerServer") {
    description = "Run the bundled kotlin-compiler-server in the foreground on 127.0.0.1:8321 (Ctrl-C to stop)."
    group = "application"
    if (!reuseStagedCompilerServer) {
        dependsOn("assembleCompilerServer")
    }

    val jarFile = compilerServerOutput.get().asFile
    val libsDir = jarFile.parentFile
    // NOT `libsDir`: build/distributions is `assembleCompilerServer`'s declared `outputs.dir`, and
    // the server's bundled logback writes `./logs/spring-boot-logger.log` relative to its CWD.
    // Logging into that directory would permanently dirty the task's output snapshot — forcing a
    // 38 MB jar + multi-hundred-MB library re-copy on every later build — and that re-copy
    // `deleteRecursively()`s the very `$compilerServerKotlinVersion*` dirs a server running in
    // another terminal is reading from. Give it its own scratch dir instead; the explicit
    // `--libraries.folder.*` flags below are absolute, so CWD has no bearing on resolution.
    workingDir = layout.buildDirectory.dir("compiler-server-dev").get().asFile

    val port = (findProperty("compilerServerPort") as String?) ?: "8321"

    // Resolve java from the project's toolchain, not the Gradle JVM: scripts are compiled at
    // `-jvm-target $jvmToolchainVersion`, which needs a JDK at least that new. Gradle itself only
    // requires 21, so `System.getProperty("java.home")` could hand back a JDK too old to compile
    // anything and the failure would surface as a confusing per-script error at runtime.
    val launcher = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(jvmToolchainVersion))
    }

    doFirst {
        // Explicitly actionable, because every other compiler-server task just `onlyIf`-skips when
        // the fork is missing. Skipping an explicitly requested *run* task looks like a silent
        // success, and exec'ing anyway gives a bare "Unable to access jarfile" with the real cause
        // buried lines above.
        if (!jarFile.isFile) {
            throw GradleException(
                "No compiler-server jar at $jarFile.\n" +
                    if (reuseStagedCompilerServer) {
                        "-PreuseStagedCompilerServer skips the build chain, but nothing is staged yet. " +
                            "Run `./gradlew assembleCompilerServer` once first."
                    } else {
                        "assembleCompilerServer produced nothing — check out the kotlin-compiler-server " +
                            "fork at ${compilerServerDir.absolutePath} on the `$compilerServerKotlinVersion` " +
                            "branch, or point -PkotlinCompilerServerPath=... at it."
                    }
            )
        }
        workingDir.mkdirs()
        commandLine(
            launcher.get().executablePath.asFile.absolutePath,
            "-jar", jarFile.absolutePath,
            "--server.port=$port",
            "--server.address=127.0.0.1",
            "--libraries.folder.jvm=${libsDir.resolve(compilerServerKotlinVersion)}",
            "--libraries.folder.js=${libsDir.resolve("$compilerServerKotlinVersion-js")}",
            "--libraries.folder.wasm=${libsDir.resolve("$compilerServerKotlinVersion-wasm")}",
            "--libraries.folder.compose-wasm=${libsDir.resolve("$compilerServerKotlinVersion-compose-wasm")}",
            "--libraries.folder.compose-wasm-compiler-plugins=${libsDir.resolve("$compilerServerKotlinVersion-compose-wasm-compiler-plugins")}",
            "--libraries.folder.compiler-plugins=${libsDir.resolve("$compilerServerKotlinVersion-compiler-plugins")}",
        )
        logger.lifecycle("kotlin-compiler-server → http://127.0.0.1:$port/ (health: /health)")
    }
}

// ─── Phase 3: jlink trimmed runtime + jpackage installers ──────────────
// Module list: we lean on the `java.se` aggregate plus a handful of jdk.* extras
// Spring Boot / JNDI / TLS need rather than auto-discovering with jdeps. jdeps
// doesn't walk Spring Boot's BOOT-INF/lib/ nested-jar layout in the
// kotlin-compiler-server fat jar, so any auto-discovery would still have to be
// merged with a generous safety baseline. Refining the module set is a follow-up
// if the runtime is too large in practice (currently ~59 MB compressed).

val jlinkModules = listOf(
    "java.se",
    "jdk.crypto.ec",
    "jdk.unsupported",
    "jdk.zipfs",
    "jdk.localedata",
)

val hostOs = org.gradle.internal.os.OperatingSystem.current()
val runtimeOsLabel = when {
    hostOs.isMacOsX -> "mac"
    hostOs.isWindows -> "windows"
    else -> "linux"
}
val runtimeOutputDir = layout.buildDirectory.dir("runtime-$runtimeOsLabel")
val jpackageInputDir = layout.buildDirectory.dir("jpackage-input")
val installersDir = layout.buildDirectory.dir("installers")

val javaHome = System.getProperty("java.home")
val jdkBin = { exe: String -> "$javaHome/bin/" + if (hostOs.isWindows) "$exe.exe" else exe }

// jpackage requires the major in --app-version to be ≥ 1, so we can't pass the project's
// in-development `0.0.1` directly. Override via `-PjpackageAppVersion=...` for releases.
val jpackageAppVersion: String = (findProperty("jpackageAppVersion") as String?) ?: "1.0.0"

val buildRuntime = tasks.register<Exec>("buildRuntime") {
    description = "Run jlink to produce a trimmed JRE for the host OS at build/runtime-<os>/."
    group = "distribution"

    val jmodsDir = file("$javaHome/jmods")
    require(jmodsDir.isDirectory) {
        "Expected jmods/ at $jmodsDir; jlink needs the host JDK's modules. Run with a JDK, not a JRE."
    }

    inputs.property("modules", jlinkModules.joinToString(","))
    inputs.property("javaHome", javaHome)
    outputs.dir(runtimeOutputDir)

    // jlink refuses to write into an existing directory. doFirst runs only when the
    // task actually executes (not when up-to-date), so this doesn't defeat caching.
    doFirst { runtimeOutputDir.get().asFile.deleteRecursively() }

    commandLine(
        jdkBin("jlink"),
        "--module-path", jmodsDir.absolutePath,
        "--add-modules", jlinkModules.joinToString(","),
        "--output", runtimeOutputDir.get().asFile.absolutePath,
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
        "--compress=zip-6",
    )
}

// `Sync`, not `Copy`: jpackage stages this directory wholesale, and a `Copy` never removes files
// that have disappeared from its source. After a `compilerServerKotlinVersion` bump the previous
// version's `<old>*/` library dirs would still be sitting here from an earlier run — the include
// pattern simply stops matching them — and would ride along into the installer as tens of MB of
// dead weight the runtime never reads, in a deliverable whose whole point is a trimmed bundle.
val stageJpackageInput = tasks.register<Sync>("stageJpackageInput") {
    description = "Stage launcher.jar + lighting7.jar + kotlin-compiler-server.jar (+ kotlin lib dirs) into build/jpackage-input/."
    group = "distribution"

    dependsOn(tasks.shadowJar, ":launcher:shadowJar", "assembleCompilerServer")

    from(tasks.shadowJar) { include("lighting7.jar") }
    from(rootProject.layout.buildDirectory.file("distributions/kotlin-compiler-server.jar"))
    from(project(":launcher").layout.buildDirectory.file("libs/launcher.jar"))
    // assembleCompilerServer stages <kotlin>/ (and friends) next to the jar in
    // build/distributions/. Forward them so the launcher's compiler-server child can
    // resolve them relative to its workingDir at runtime.
    from(rootProject.layout.buildDirectory.dir("distributions")) {
        include("$compilerServerKotlinVersion*/**")
    }
    into(jpackageInputDir)
}

tasks.named<Delete>("clean") {
    delete(layout.buildDirectory.dir("runtime-mac"))
    delete(layout.buildDirectory.dir("runtime-windows"))
    delete(layout.buildDirectory.dir("runtime-linux"))
    delete(jpackageInputDir)
    delete(installersDir)
}

fun registerJpackageTask(
    name: String,
    type: String,
    hostMatches: Boolean,
    iconFile: File,
    extraArgs: List<String>,
) {
    tasks.register<Exec>(name) {
        description = "Build a $type installer at build/installers/. Host-only — runs on the matching OS."
        group = "distribution"
        onlyIf {
            if (!hostMatches) {
                logger.warn("Skipping $name: jpackage --type $type only works on its target OS (current host: ${hostOs.name}).")
                false
            } else {
                true
            }
        }
        dependsOn(stageJpackageInput, buildRuntime)
        inputs.dir(jpackageInputDir)
        inputs.dir(runtimeOutputDir)
        outputs.dir(installersDir)

        val iconExists = iconFile.exists()
        if (iconExists) inputs.file(iconFile)

        doFirst { installersDir.get().asFile.mkdirs() }

        val args = mutableListOf(
            jdkBin("jpackage"),
            "--type", type,
            "--name", "lighting7",
            "--app-version", jpackageAppVersion,
            "--vendor", "Chris Cormack",
            "--input", jpackageInputDir.get().asFile.absolutePath,
            "--main-jar", "launcher.jar",
            "--main-class", "uk.me.cormack.lighting7.launcher.LauncherMainKt",
            "--runtime-image", runtimeOutputDir.get().asFile.absolutePath,
            "--dest", installersDir.get().asFile.absolutePath,
        )
        args += extraArgs
        if (iconExists) args += listOf("--icon", iconFile.absolutePath)
        commandLine(args)
    }
}

registerJpackageTask(
    name = "packageMac",
    type = "pkg",
    hostMatches = hostOs.isMacOsX,
    iconFile = rootProject.file("assets/lighting7.icns"),
    extraArgs = emptyList(),
)

registerJpackageTask(
    name = "packageWindows",
    type = "msi",
    hostMatches = hostOs.isWindows,
    iconFile = rootProject.file("assets/lighting7.ico"),
    extraArgs = listOf("--win-menu", "--win-shortcut", "--win-dir-chooser"),
)

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
