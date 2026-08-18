import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

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
    // Test-only, and the arrow points the right way: the launcher gains nothing, so its
    // "pure JDK, zero dependencies" invariant is untouched. This exists so the update-apply
    // handshake can be round-tripped in one JVM — the backend's UpdateMarkerWriter through the
    // launcher's UpdateMarker reader. They are two independently written parsers of the same
    // file format, and agreeing only by inspection is precisely how that breaks in the field.
    testImplementation(project(":launcher"))
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

    // Password hashing for desk-local user accounts (auth/Passwords.kt, multi-user-auth
    // plan session 1). Cost 12 (~250 ms/verify) is a deliberate login throttle; tests pin
    // `auth.bcryptCost=4`. One transitive: at.favre.lib:bytes.
    implementation("at.favre.lib:bcrypt:0.10.2")
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

// `Sync`, not `Copy`: Vite emits content-hashed filenames (`index-<hash>.js`), so every
// frontend rebuild writes a NEW file here and a `Copy` never removes the old one. Left
// unchecked that grows without bound — this was found at 123 MB across 44 files (~22 stale
// `index-*.js` at ~4 MB each), which inflated the thin `jar` to 45 MB, the editor jar staged
// into the compiler-server fork to 23 MB, and every locally built .pkg/.msi with them. CI
// never saw it because a fresh checkout has no history to accumulate.
//
// `Sync` therefore OWNS this directory and deletes anything it did not put there. That is
// acceptable only because the directory is entirely machine-generated and gitignored (and
// `clean` already deletes it below) — do not hand-place a file here expecting it to survive
// a build.
val copyFrontend = tasks.register<Sync>("copyFrontend") {
    description = "Mirror the built React bundle into src/main/resources/static/, pruning stale chunks."
    group = "build"
    dependsOn(buildFrontend)
    from(lightingReactDir.resolve("dist"))
    into(frontendStaticDir)
    // Require an actual entry point — a bare empty `dist/` (e.g. after a vite failure) means
    // the bundle is broken; serving the previous classpath copy is preferable to copying nothing.
    // Note this skips the *pruning* as well as the copy, which is the intended pairing: a failed
    // Vite run leaves the last good bundle in place rather than emptying the directory.
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

// ─── Build-time identity (version / channel / commit) ──────────────────
// Until this existed the running app could not report its own version at all: `project.version`
// never reaches a manifest, and `jpackageAppVersion` was a Gradle property consumed only by
// jpackage. The update checker needs to compare "what am I" against "what is on GitHub", so one
// generated resource carries the answer into BOTH shipped jars.
//
// It is a *resource*, not generated Kotlin like BundledOAuthCredentials, precisely because two
// modules need it. Generated Kotlin would have to be emitted into two source sets in two
// packages — the duplication this is trying to avoid. One properties file lands in both jars
// from one task, and `launcher/build.gradle.kts` wires its `resources.srcDir` to this same task
// output. That is a task-output dependency, not a classpath one: the launcher's zero-dependency
// invariant is intact.
//
// Note what is deliberately NOT here: whether this is a packaged install. `stageJpackageInput`
// stages the very same lighting7.jar that `./gradlew run` executes, so packaged-ness is not
// knowable at build time. It is detected at runtime by the launcher. See LauncherBuildInfo.
val buildChannel: String =
    (findProperty("buildChannel") as String?)?.takeIf { it.isNotBlank() } ?: "dev"

require(buildChannel in setOf("dev", "release")) {
    "buildChannel must be 'dev' or 'release', got '$buildChannel'."
}

// Deliberately NOT derived from `git rev-parse`. Shelling out at configuration time would fork a
// process on every single Gradle invocation, and would make the sha a task input that changes on
// every commit — so a `git commit` touching nothing but a doc would force a full Kotlin recompile
// and shadowJar. CI passes them explicitly; local builds get blanks and don't care.
val buildCommitSha: String = (findProperty("buildCommitSha") as String?).orEmpty()

// The COMMITTER DATE of the built commit, not the time of the build. A wall-clock value here
// would make every build dirty and defeat both up-to-date checks and the CI build cache; a
// committer date is constant for a given commit, so rebuilding a tag reproduces the same file.
val buildCommitTimestamp: String = (findProperty("buildCommitTimestamp") as String?).orEmpty()

val buildInfoGenDir = layout.buildDirectory.dir("generated/resources/build-info")

val generateBuildInfo = tasks.register("generateBuildInfo") {
    description = "Emit lighting7-build-info.properties (version, channel, commit) for both shipped jars."
    group = "build"

    // Unhashed, unlike generateBundledOAuthCredentials: that task hashes because its inputs are
    // secrets that must not appear in --info output. These are meant to be visible.
    inputs.property("version", jpackageAppVersion)
    inputs.property("channel", buildChannel)
    inputs.property("commitSha", buildCommitSha)
    inputs.property("commitTimestamp", buildCommitTimestamp)
    outputs.dir(buildInfoGenDir)

    doLast {
        // The `lighting7-` prefix is not decoration. This lands at the ROOT of a fat jar built
        // with `failOnDuplicateEntries.set(true)`, and a generic `/build-info.properties` is a
        // name a dependency could plausibly ship too — which would fail the build with a message
        // pointing at Shadow rather than at this task.
        buildInfoGenDir.get().asFile
            .apply { mkdirs() }
            .resolve("lighting7-build-info.properties")
            .writeText(
                "# Generated by :generateBuildInfo — do not edit.\n" +
                    "version=$jpackageAppVersion\n" +
                    "channel=$buildChannel\n" +
                    "commitSha=$buildCommitSha\n" +
                    "commitTimestamp=$buildCommitTimestamp\n"
            )
    }
}

sourceSets["main"].resources.srcDir(generateBuildInfo)

// ─── Host / target OS ──────────────────────────────────────────────────
// Declared here rather than down with the jlink/jpackage block that also uses them:
// `shadowJar` below keys its native-payload excludes off the target OS, and a Kotlin DSL
// script is evaluated top to bottom, so a `val` declared 400 lines later is not in scope.
val hostOs = org.gradle.internal.os.OperatingSystem.current()
val runtimeOsLabel = when {
    hostOs.isMacOsX -> "mac"
    hostOs.isWindows -> "windows"
    else -> "linux"
}

// Which OS's native binaries `shadowJar` keeps. Defaults to the host, because the fat jar's only
// shipping consumer is a host-matched installer (`packageWindows` / `packageMac` are both
// host-only). Five dependencies ship natives for every platform they support, which cost ~17 MB
// of the installer for binaries the target can never load.
//
// A *target* property rather than plain host-keying, for one specific reason: it makes the Windows
// jar reproducible from a Mac. `./gradlew shadowJar -PnativePayloadOs=windows` produces exactly
// what CI stages, so the installer's size can be measured and diffed without a Windows host —
// which is the only way most of this is checkable during development.
//
// `all` restores the old everything-everywhere jar. Use it when you need a portable
// `lighting7.jar`, because the default no longer is one: a Mac-built jar copied to Linux fails at
// the first DB connection with `No native library found for os.name=Linux`. Tests and `run` are
// unaffected — both resolve against `runtimeClasspath`, not the fat jar.
val nativePayloadOs: String = ((findProperty("nativePayloadOs") as String?) ?: runtimeOsLabel).also {
    // Validated eagerly at configuration time, in the same spirit as requireMsiCompatibleVersion
    // below: a typo would otherwise silently exclude EVERY native payload (nothing matches the
    // target, so nothing is kept) and produce a jar that builds green and cannot open its own
    // database.
    require(it in setOf("windows", "mac", "linux", "all")) {
        "-PnativePayloadOs=$it is not recognised. Expected one of: windows, mac, linux, all."
    }
}

// Every per-platform native payload in the dependency set, as (Ant exclude pattern, entry-name
// prefix, owning target OS). `null` means no target we build for owns it, so it is always dropped
// unless `nativePayloadOs=all`.
//
// Listed explicitly rather than swept with `**/*.{so,dll,dylib}`. A blanket glob would silently
// strip the host's natives out of any dependency added later — a build-green, runtime-dead
// failure — and it cannot tell `com/sun/jna/win32-x86-64/` (a payload directory) from
// `com/sun/jna/win32/` (a Java package of .class files). Note the trailing hyphens in the JNA
// prefixes; they are what draws that line.
//
// Only binary payloads are listed: the CLASSES always ship. coremidi4j is referenced statically
// by `midi/MidiDeviceRegistry.kt` and both jne and javacpp decide what to load from `os.name` at
// runtime, so removing their classes would break compilation or throw where today they simply
// report no devices. `META-INF/services/javax.sound.midi.spi.MidiDeviceProvider` stays too — it
// is merged by `mergeServiceFiles()` below, and off-Mac the provider just enumerates nothing.
//
// Keyed by OS only, never by architecture: the Windows jar keeps all four sqlite arches (3.7 MB)
// though the MSI is x64. Arch-keying is another ~2.9 MB and a second axis — see
// FU-DIST-NATIVE-ARCH.
//
// `jne/windows/` is kept even though it is provably dead weight today: `midi/KtmidiAccessSource.kt`
// routes Windows to `JvmMidiAccess`, so libremidi's DLL is never loaded there. It is 0.35 MB, and
// dropping it would plant a trap that springs only when someone fixes the LLP64 ABI bug and flips
// that branch back — a MIDI subsystem that works in dev and not in the installer.
val nativePayloads: List<Triple<String, String, String?>> = listOf(
    Triple("org/sqlite/native/Windows/**", "org/sqlite/native/Windows/", "windows"),
    Triple("org/sqlite/native/Mac/**", "org/sqlite/native/Mac/", "mac"),
    Triple("org/sqlite/native/Linux/**", "org/sqlite/native/Linux/", "linux"),
    Triple("org/sqlite/native/Linux-Musl/**", "org/sqlite/native/Linux-Musl/", "linux"),
    Triple("org/sqlite/native/FreeBSD/**", "org/sqlite/native/FreeBSD/", null),
    Triple("jne/windows/**", "jne/windows/", "windows"),
    Triple("jne/macos/**", "jne/macos/", "mac"),
    Triple("jne/linux/**", "jne/linux/", "linux"),
    // alsa-javacpp's payload sits at the archive root, not under a package, which makes this the
    // one entry here that is not namespaced to an owning library. Only alsa ships a top-level
    // `linux-x86_64/` across the current runtime classpath, so there is no collision today — but a
    // second dependency shipping files at that same literal root path would be swept in or out
    // along with alsa's, and the verifier below would NOT catch it: it asks "does anything exist
    // under this prefix", not "does the right library own it". Re-check this line if another
    // javacpp-style native dependency is ever added.
    Triple("linux-x86_64/**", "linux-x86_64/", "linux"),
    Triple(
        "uk/co/xfactorylibrarians/coremidi4j/libCoreMidi4J.dylib",
        "uk/co/xfactorylibrarians/coremidi4j/libCoreMidi4J.dylib",
        "mac",
    ),
    Triple("com/sun/jna/win32-*/**", "com/sun/jna/win32-", "windows"),
    Triple("com/sun/jna/darwin-*/**", "com/sun/jna/darwin-", "mac"),
    Triple("com/sun/jna/linux-*/**", "com/sun/jna/linux-", "linux"),
    Triple("com/sun/jna/aix-*/**", "com/sun/jna/aix-", null),
    Triple("com/sun/jna/freebsd-*/**", "com/sun/jna/freebsd-", null),
    Triple("com/sun/jna/openbsd-*/**", "com/sun/jna/openbsd-", null),
    Triple("com/sun/jna/sunos-*/**", "com/sun/jna/sunos-", null),
)

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

    // Drop every native payload the target OS cannot load. Filtering happens in the CopySpec,
    // i.e. before the transformers and before duplicate detection, so excluded entries simply
    // never reach either — this cannot disturb the merging above.
    //
    // The hazard it *could* introduce is subtler and is why the verifier below exists: an exclude
    // broad enough to remove ALL copies of an entry handled by PreserveFirstFoundResourceTransformer
    // would quietly retire one of those `include(...)` lines, and `failOnDuplicateEntries` would
    // never notice the guard had gone decorative. None of the patterns above overlaps a transformer
    // include today (they are all anchored on payload directories); re-check that if you add one.
    if (nativePayloadOs != "all") {
        nativePayloads.filter { (_, _, owner) -> owner != nativePayloadOs }
            .forEach { (pattern, _, _) -> exclude(pattern) }
    }

    // An Ant exclude that matches nothing fails SILENTLY, in both directions: a renamed payload
    // directory upstream means either 30 MB quietly returns to the installer, or the one native the
    // target actually needs quietly leaves it. Neither shows up as a build failure and both are
    // invisible until the app misbehaves on a user's machine, so assert the resulting jar rather
    // than trusting the patterns.
    //
    // Note the limit of this check: it is presence-by-prefix, not ownership. It proves the target's
    // payload directories survived and the others went, not that the *expected library* put them
    // there — so it cannot detect two dependencies sharing one prefix (see `linux-x86_64/` above).
    doLast {
        val jarFile = archiveFile.get().asFile
        val problems = mutableListOf<String>()
        ZipFile(jarFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            nativePayloads.forEach { (_, prefix, owner) ->
                val present = names.any { it.startsWith(prefix) }
                val expected = nativePayloadOs == "all" || owner == nativePayloadOs
                if (expected && !present) {
                    problems += "MISSING  $prefix — expected for nativePayloadOs=$nativePayloadOs " +
                        "(owner=${owner ?: "none"}). An upstream rename, or a too-broad exclude."
                }
                if (!expected && present) {
                    problems += "UNEXPECTED  $prefix — should have been excluded for " +
                        "nativePayloadOs=$nativePayloadOs (owner=${owner ?: "none"})."
                }
            }
        }
        require(problems.isEmpty()) {
            "Native payload verification failed for ${jarFile.name} (nativePayloadOs=$nativePayloadOs):\n" +
                problems.joinToString("\n") { "  - $it" } +
                "\nUpdate the `nativePayloads` table next to `nativePayloadOs` to match the dependency set."
        }
        logger.lifecycle("Native payloads verified for ${jarFile.name} (nativePayloadOs=$nativePayloadOs).")
    }
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
// `isNotBlank` matters as much as the null check: a blank version makes the first allowlist entry
// below resolve to the fork's own root directory, which `assembleCompilerServer` would then copy
// recursively into build/distributions — gigabytes of `.git/` and `build/`.
val compilerServerKotlinVersion: String =
    (findProperty("compilerServerKotlinVersion") as String?)?.takeIf { it.isNotBlank() }
        ?: error("compilerServerKotlinVersion is not set or is blank — declare it in gradle.properties.")

// The ONLY two of the fork's six `<version>*/` library directories that ship.
//
// The fork emits six — `<v>`, `-js`, `-wasm`, `-compose-wasm`, `-compose-wasm-compiler-plugins`,
// `-compiler-plugins` — because upstream's playground compiles for Kotlin/JS, Kotlin/Wasm and
// Compose-for-Web. This desk compiles for the JVM and nothing else:
// `lighting-react/src/components/scripts/ScriptEditor.tsx` mounts kotlin-playground with
// `mode="kotlin"` and no target-platform prop, so only the JVM target is ever requested. The four
// unused dirs were 37.2 MB of the installer (`-compose-wasm` alone is 28.6 MB) for compiler backends
// nothing can reach.
//
// Shipping only these two is safe because the fork tolerates the others being absent, by design
// rather than by luck: `src/main/kotlin/com/compiler/server/compiler/components/KotlinEnvironment.kt`
// reads five of the six as `listFiles()?.toList() ?: emptyList()` and escalates only `jvm` to
// `error("No kotlin libraries found in: …")`, after which
// `common/src/main/kotlin/com/compiler/server/common/components/KotlinEnvironment.kt` filters each
// list through `isJsKlib`/`isWasmKlib` — both happy with an empty list. So a missing `-js/` yields
// an editor that cannot compile Kotlin/JS, which is exactly what we want, and a missing `<v>/`
// still fails loudly at compiler-server startup.
val compilerServerLibDirNames: List<String> = listOf(
    compilerServerKotlinVersion,
    "$compilerServerKotlinVersion-compiler-plugins",
)

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

// The Lighting7 jar the compiler-server fork puts on the *script* classpath, so the embedded
// editor can resolve and complete against this project's own API. It needs class files and
// `META-INF/uk.me.cormack_Lighting7.kotlin_module` (that resource is what makes top-level
// declarations resolvable); it has no use whatsoever for the built React bundle in `static/`,
// which was 122 MB of the ordinary `jar`'s 143 MB uncompressed and rode into the installer as a
// SECOND copy of the frontend already inside lighting7.jar.
//
// A dedicated `Jar` task rather than the two more obvious alternatives:
//   - `exclude("static/**")` on the `Copy` below does nothing. That Copy's source is a single jar
//     FILE, and Ant patterns filter the file tree containing the archive, not entries inside it.
//   - excluding it from the `jar` task itself would be wrong: that task's contract is "the app's
//     own thin jar", and a Lighting7 jar that cannot serve its own UI is a landmine for anything
//     that later runs it directly.
//
// `static/**` only, deliberately — not classes-only. `routes/kotlinCompilerServer.kt` proxies
// `{action}` as a wildcard, so `/compiler/run` is reachable and the fork can *execute* against
// this jar rather than merely resolving symbols against it. `fx/`, `application.conf` and
// `logback.xml` total ~30 KB, so keeping them costs nothing and avoids guessing what execution
// needs. See FU-DIST-EDITOR-JAR-CLASSES-ONLY.
val compilerServerLightingJar = tasks.register<Jar>("compilerServerLightingJar") {
    description = "Build the Lighting7 jar staged onto the compiler server's script classpath (no frontend bundle)."
    group = "build"
    archiveFileName.set(compilerServerLightingJarName)
    // Its own directory: the archive name collides with the ordinary `jar` task's output in
    // build/libs/, and two tasks writing one path is an implicit-dependency error waiting to fire.
    destinationDirectory.set(layout.buildDirectory.dir("compiler-server-libs"))
    from(sourceSets["main"].output) {
        exclude("static/**")
    }
}

val stageCompilerServerLightingJar = tasks.register<Copy>("stageCompilerServerLightingJar") {
    description = "Copy the frontend-less Lighting7 jar into the fork's lighting-libs/ for the patched dependency."
    group = "build"
    onlyIf { compilerServerDir.exists() }
    // No `rename {}` — compilerServerLightingJar already emits the expected filename, which
    // `applyCompilerServerPatches` bakes into the fork's `kotlinDependency(files(...))` line.
    from(compilerServerLightingJar)
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
        // Only the dirs we actually copy. A wildcard here would keep the task's up-to-date check
        // depending on the four dirs the fork still generates but we no longer ship, so it would
        // re-run for changes that cannot affect its output.
        compilerServerLibDirNames.forEach { include("$it/**") }
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

        // Copy only the allowlisted library dirs (see `compilerServerLibDirNames`). Named
        // explicitly rather than scanned by prefix: a `startsWith` scan also matched the four
        // targets we don't ship, and it matched the fork's *own* version dirs (e.g. `2.3.0/`)
        // when those happened to share the prefix.
        //
        // Checked per name, not with the old `require(libDirs.isNotEmpty())`. That guard was
        // satisfied by ANY matching directory, so a fork that had emitted only `2.4.10-js/`
        // passed it and produced an installer whose editor had no JVM classpath at all — a
        // failure that surfaced as "completion returns only stdlib", never as a build error.
        compilerServerLibDirNames.forEach { name ->
            val src = compilerServerDir.resolve(name)
            require(src.isDirectory) {
                "Expected `$name/` in $compilerServerDir after bootJar — not found. " +
                    "Either the fork's :dependencies:copy* tasks did not run, or the fork is checked out on a different Kotlin " +
                    "version — in which case update `compilerServerKotlinVersion` in gradle.properties to match its branch."
            }
            val dest = File(outputFile.parentFile, name)
            dest.deleteRecursively()
            src.copyRecursively(dest)
            logger.lifecycle("Copied $name/ → ${dest.relativeTo(rootDir)}/")
        }

        // Delete any `<version>*/` dir we staged before the allowlist existed. Nothing else does:
        // the loop above only `deleteRecursively()`s the dirs it is about to write, so a
        // `2.4.10-compose-wasm/` left by an earlier build would sit in build/distributions
        // indefinitely — and, because `stageJpackageInput` reads this directory, ride into the
        // installer as the 37.2 MB this change exists to remove.
        outputFile.parentFile.listFiles { f: File ->
            f.isDirectory &&
                f.name.startsWith(compilerServerKotlinVersion) &&
                f.name !in compilerServerLibDirNames
        }?.forEach { stale ->
            stale.deleteRecursively()
            logger.lifecycle("Removed stale ${stale.name}/ from ${outputFile.parentFile.relativeTo(rootDir)}/")
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
            // Six flags, two shipped directories — see the matching comment in LauncherMain.kt
            // for why the four absent ones are still named rather than omitted.
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
// merged with a generous safety baseline.
//
// This is deliberately NOT refined into an explicit module list, and the reason is a
// measurement rather than caution. Replacing `java.se` with the exact 19-module closure the
// app needs was measured at **1 MB** of the image (51 → 50 MB on JDK 26): everything the
// surgery can remove is `java.se`'s small tail — java.xml.crypto 0.66, java.security.jgss
// 0.55, java.rmi 0.22, java.sql.rowset 0.20, java.management.rmi 0.08 MB — and it is
// all-or-nothing, because `java.se` `requires transitive` each of them so none can be
// dropped while the aggregate stays. Against that, a module this list gets wrong surfaces as
// a NoClassDefFoundError at the first use of one feature, potentially mid-show, and the
// modules most easily missed are exactly the ones jdeps cannot see (Spring Boot and logback
// reach java.naming / java.management reflectively). 1 MB is not worth that. The real runtime
// saving came from `--include-locales` in buildRuntime below, which is 11 MB. See
// FU-DIST-JLINK-MODULES if this ever needs revisiting.

val jlinkModules = listOf(
    "java.se",
    "jdk.crypto.ec",
    "jdk.unsupported",
    "jdk.zipfs",
    // Kept as a module, then filtered down to the locales we ship by `--include-locales` in
    // buildRuntime. Do not "optimise" this by deleting the module instead — see the comment there.
    "jdk.localedata",
)

// Locales retained from `jdk.localedata`. en-GB is the desk's own locale; en-US is kept because
// it is the JDK's fallback and costs almost nothing.
val jlinkLocales = "en-GB,en-US"

// `hostOs` / `runtimeOsLabel` are declared up by the fat-jar packaging block instead of
// here, because `shadowJar`'s native-payload excludes need them and a Kotlin DSL script is
// evaluated in source order.
val runtimeOutputDir = layout.buildDirectory.dir("runtime-$runtimeOsLabel")
val jpackageInputDir = layout.buildDirectory.dir("jpackage-input")
val installersDir = layout.buildDirectory.dir("installers")

val javaHome = System.getProperty("java.home")
val jdkBin = { exe: String -> "$javaHome/bin/" + if (hostOs.isWindows) "$exe.exe" else exe }

// Windows Installer compares ONLY the first three fields of ProductVersion, and bounds them:
// major and minor to 255, build to 65535. A fourth field is accepted and then *ignored*, so
// 1.2.3.4 and 1.2.3.3 compare EQUAL and will not upgrade each other — a silent no-op you would
// only discover on a user's machine. Non-numeric components (1.2.3-rc1) are not a legal
// ProductVersion at all.
//
// Validated eagerly, on every platform rather than only on Windows: the MSI rules are strictly
// tighter than macOS CFBundleVersion's, so one validated number is correct for both installers,
// and failing at configuration time beats failing 40 minutes into a CI run.
private val msiVersionPattern = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,5})$""")

fun requireMsiCompatibleVersion(v: String): String {
    val match = msiVersionPattern.matchEntire(v) ?: error(
        "jpackageAppVersion '$v' is not a legal Windows Installer ProductVersion.\n" +
            "Required form: MAJOR.MINOR.PATCH — decimal, exactly three fields.\n" +
            "  - four fields (1.2.3.4): the fourth is IGNORED by Windows Installer, so the MSI\n" +
            "    would install but never upgrade a 1.2.3.x predecessor.\n" +
            "  - suffixes (1.2.3-rc1): not a ProductVersion. Releases must be plain semver;\n" +
            "    ship release candidates as workflow artifacts, never as a tagged release.\n" +
            "See the windowsUpgradeUuid comment below for why this matters."
    )
    val (majorRaw, minorRaw, patchRaw) = match.destructured
    listOf("major" to majorRaw, "minor" to minorRaw, "patch" to patchRaw).forEach { (label, raw) ->
        require(raw == "0" || !raw.startsWith("0")) {
            "jpackageAppVersion '$v' has a leading zero in the $label field. Windows Installer " +
                "reads '$raw' as ${raw.toInt()}, so the installed version would silently differ " +
                "from the tag and from the filename."
        }
    }
    require(majorRaw.toInt() in 1..255) {
        "jpackageAppVersion '$v': major must be 1..255 (jpackage requires >= 1; MSI caps at 255)."
    }
    require(minorRaw.toInt() in 0..255) { "jpackageAppVersion '$v': minor must be 0..255 (MSI cap)." }
    require(patchRaw.toInt() in 0..65535) { "jpackageAppVersion '$v': patch must be 0..65535 (MSI cap)." }
    return v
}

// jpackage requires the major in --app-version to be ≥ 1, so we can't pass the project's
// in-development `0.0.1` directly. Override via `-PjpackageAppVersion=...` for releases.
//
// `1.0.0` is RESERVED for unversioned local builds — never cut a release with it. A hand-built
// `./gradlew packageWindows` MSI is a genuinely installed product, and if a real release also
// shipped 1.0.0 the two would be indistinguishable by ProductVersion. The first real release is
// v1.1.0. (The update checker has a second, independent guard for this: `buildChannel`.)
val jpackageAppVersion: String =
    requireMsiCompatibleVersion((findProperty("jpackageAppVersion") as String?) ?: "1.0.0")

// ─── Windows Installer UpgradeCode ─────────────────────────────────────
// THIS VALUE MUST NEVER CHANGE. Not for a rename, not for a major version, not ever.
//
// Windows Installer identifies a *product line* by its UpgradeCode and an individual installed
// package by its ProductCode. A "major upgrade" — the only upgrade mechanism jpackage MSIs
// support — requires the new package to share the old package's UpgradeCode, carry a different
// ProductCode, and declare a strictly greater ProductVersion. RemoveExistingProducts then
// uninstalls the old ProductCode and installs the new one, leaving one entry in Add/Remove
// Programs.
//
// jpackage does not require --win-upgrade-uuid. When it is absent, WinMsiBundler defaults
// UPGRADE_UUID to a fresh UUID.randomUUID() *per build*, so every MSI we produced before this
// line declared a different product line. That does not "fail to upgrade" — it installs the new
// package SIDE BY SIDE with the old one, into the same directory, and uninstalling either leaves
// the other broken. The build stays green throughout. That was the bug this fixes.
//
// Changing this constant is equivalent to shipping a brand-new product under the same name:
// every existing user would end up with two overlapping installs. If you ever genuinely need a
// new product line, change --name and the install directory too.
//
// It lives here as a literal rather than in gradle.properties on purpose. gradle.properties
// values are overridable — a stray `-PwindowsUpgradeUuid=…` or a line in
// ~/.gradle/gradle.properties would orphan every install with a green build. This has exactly
// one correct value for the lifetime of the product, and changing it should require a diff.
//
// Generated once with `uuidgen`. `verifyWindowsInstallerSources` (below) asserts that the WiX
// jpackage actually generated carries it, because the failure mode is otherwise invisible.
val windowsUpgradeUuid = "E18F67A5-5765-4626-935C-0B39FEBF78B3"

val buildRuntime = tasks.register<Exec>("buildRuntime") {
    description = "Run jlink to produce a trimmed JRE for the host OS at build/runtime-<os>/."
    group = "distribution"

    val jmodsDir = file("$javaHome/jmods")
    require(jmodsDir.isDirectory) {
        "Expected jmods/ at $jmodsDir; jlink needs the host JDK's modules. Run with a JDK, not a JRE."
    }

    inputs.property("modules", jlinkModules.joinToString(","))
    inputs.property("locales", jlinkLocales)
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
        // 11 MB of the 59 MB runtime was `jdk.localedata` — CLDR data for ~800 locales, of which
        // this desk uses two. Measured on JDK 26 with these exact flags: 62 MB → 51 MB.
        //
        // A locale FILTER rather than dropping the `jdk.localedata` module, which is the obvious
        // and wrong version of this change: `FormatData_en_GB` lives in jdk.localedata, not
        // java.base, so removing the module would silently switch every server-side en_GB date and
        // number to US forms. Verified byte-identical output across filtered and unfiltered images
        // (`18/08/2026`, `Tuesday, 18 August 2026`).
        //
        // The failure mode if a needed locale is missing from this list is WRONG FORMATTING, not an
        // exception — nothing will throw to tell you. Add the tag here if the desk ever needs another.
        "--include-locales=$jlinkLocales",
    )
}

// `Sync`, not `Copy`: jpackage stages this directory wholesale, and a `Copy` never removes files
// that have disappeared from its source. After a `compilerServerKotlinVersion` bump the previous
// version's `<old>*/` library dirs would still be sitting here from an earlier run — the include
// pattern simply stops matching them — and would ride along into the installer as tens of MB of
// dead weight the runtime never reads, in a deliverable whose whole point is a trimmed bundle.
//
// That same property is why the `compilerServerLibDirNames` allowlist is applied HERE as well as
// in `assembleCompilerServer`. Narrowing it there stops new dirs being staged; narrowing it here
// is what guarantees the four dropped ones don't ride along for anyone whose build/distributions
// predates the change.
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
        compilerServerLibDirNames.forEach { include("$it/**") }
    }
    into(jpackageInputDir)
}

tasks.named<Delete>("clean") {
    delete(layout.buildDirectory.dir("runtime-mac"))
    delete(layout.buildDirectory.dir("runtime-windows"))
    delete(layout.buildDirectory.dir("runtime-linux"))
    delete(jpackageInputDir)
    delete(installersDir)
    delete(layout.buildDirectory.dir("jpackage-temp"))
}

// jpackage deletes its scratch directory on the way out. --temp keeps it, which is the only
// supported way to see the WiX sources it generated — and reading them back is how
// `verifyWindowsInstallerSources` turns "we passed --win-upgrade-uuid" into "the installer
// actually carries it".
val jpackageTempDir = layout.buildDirectory.dir("jpackage-temp")

fun registerJpackageTask(
    name: String,
    type: String,
    hostMatches: Boolean,
    iconFile: File,
    extraArgs: List<String>,
    // Windows only: keep jpackage's scratch dir and assert against the generated WiX.
    verifyGeneratedSources: Boolean = false,
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

        val installerExtension = type

        doFirst {
            // The staged lighting7.jar carries natives for ONE OS (see `nativePayloadOs`). An
            // installer built with a mismatched override is the worst kind of broken: jpackage
            // succeeds, the package installs, and the app dies at its first database connection
            // with `No native library found for os.name=…`. Nothing upstream of here can catch
            // it, because from Gradle's point of view the jar is perfectly valid.
            require(nativePayloadOs == runtimeOsLabel) {
                "$name would package a lighting7.jar built for nativePayloadOs=$nativePayloadOs " +
                    "on a $runtimeOsLabel host. Drop the -PnativePayloadOs override (or set it to " +
                    "$runtimeOsLabel) — it exists for measuring another platform's jar, not for " +
                    "building another platform's installer."
            }
            installersDir.get().asFile.mkdirs()
            // packageWindows and packageMac declare the SAME outputs.dir, and overlapping
            // outputs disable Gradle's stale-output cleanup. Without this, building 1.2.0 and
            // then 1.2.1 locally leaves both installers sitting there and the release
            // upload glob would match two files. Delete only this task's own extension —
            // the sibling task's artifact lives in the same directory.
            installersDir.get().asFile
                .listFiles { f -> f.isFile && f.name.endsWith(".$installerExtension") }
                ?.forEach { it.delete() }
            if (verifyGeneratedSources) {
                // jpackage requires --temp to name a new or empty directory.
                jpackageTempDir.get().asFile.deleteRecursively()
            }
        }

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
        if (verifyGeneratedSources) {
            args += listOf("--temp", jpackageTempDir.get().asFile.absolutePath)
        }
        commandLine(args)

        if (verifyGeneratedSources) {
            doLast { verifyWindowsInstallerSources(jpackageTempDir.get().asFile) }
        }
    }
}

/**
 * Read back the WiX sources jpackage generated and assert they carry the pinned UpgradeCode and
 * the version we asked for.
 *
 * This exists because a wrong or missing UpgradeCode is *completely invisible* at build time —
 * the MSI builds, installs, and works. The damage only shows up on the second install, on
 * someone else's machine, as a duplicate entry in Add/Remove Programs. Anything that can only
 * be caught months later is worth catching in the build.
 */
fun verifyWindowsInstallerSources(tempDir: File) {
    require(tempDir.isDirectory) {
        "jpackage --temp directory $tempDir is missing; cannot verify the generated WiX sources."
    }
    val wixSources = tempDir.walkTopDown()
        .filter { it.isFile && (it.extension == "wxs" || it.extension == "wxi") }
        .toList()
    require(wixSources.isNotEmpty()) {
        "No .wxs/.wxi sources found under $tempDir. jpackage's scratch layout has changed — " +
            "re-check what --temp produces before trusting the UpgradeCode is pinned."
    }
    val combined = wixSources.joinToString("\n") { it.readText() }
    require(combined.contains(windowsUpgradeUuid, ignoreCase = true)) {
        "The WiX sources jpackage generated do not contain the pinned UpgradeCode " +
            "$windowsUpgradeUuid. Without it every MSI is its own product line and installs " +
            "side by side instead of upgrading. Inspect $tempDir and check that " +
            "--win-upgrade-uuid is still being passed and still honoured by this JDK's jpackage."
    }
    logger.lifecycle("Verified generated WiX carries UpgradeCode $windowsUpgradeUuid (version $jpackageAppVersion).")
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
    extraArgs = listOf(
        "--win-menu",
        "--win-shortcut",
        "--win-dir-chooser",
        // See the windowsUpgradeUuid declaration. Without this the MSI cannot upgrade
        // anything, because jpackage mints a random UpgradeCode per build.
        "--win-upgrade-uuid", windowsUpgradeUuid,
    ),
    verifyGeneratedSources = true,
)

tasks.test {
    // Gradle defaults a Test task to 512m, which this suite outgrew: ~1300 tests in one JVM, and
    // every integration test constructs a `State` — which builds a Kotlin scripting host and walks
    // the build tree to fingerprint the script cache. Past the limit the failure is an
    // OutOfMemoryError thrown from whichever `State.<init>` happens to run next, so it surfaces as
    // a handful of *unrelated* tests failing differently on each run rather than as anything that
    // points at memory. Raise it explicitly so that stays diagnosable.
    maxHeapSize = "2g"

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
