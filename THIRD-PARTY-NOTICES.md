# Third-party notices

Generated from `licences/` by `node scripts/licences.mjs --write`; do not edit by hand.
How the data is kept, and what the gate allows, is in [docs/licensing.md](docs/licensing.md).

Lighting7 ships the components below, each under its own licence. A licence written as
`A OR B` is offered both ways by its author, and Lighting7 takes it under the first allowed
one. Each identifier is an SPDX licence; its text is at `https://spdx.org/licenses/<id>.html`.

## Java libraries

In `lighting7.jar` (the desk) and `launcher.jar`.

| Module | Licence |
|---|---|
| `at.favre.lib:bcrypt` | Apache-2.0 |
| `at.favre.lib:bytes` | Apache-2.0 |
| `at.favre.lib:hkdf` | Apache-2.0 |
| `ch.qos.logback:logback-classic` | EPL-2.0 OR LGPL-2.1-only |
| `ch.qos.logback:logback-core` | EPL-2.0 OR LGPL-2.1-only |
| `com.charleskorn.kaml:kaml-jvm` | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-annotations` | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-core` | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-databind` | Apache-2.0 |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | Apache-2.0 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | Apache-2.0 |
| `com.fizzed:jne` | Apache-2.0 |
| `com.github.hypfvieh:dbus-java-core` | MIT |
| `com.github.hypfvieh:dbus-java-transport-native-unixsocket` | MIT |
| `com.github.java-json-tools:btf` | Apache-2.0 OR LGPL-3.0-or-later |
| `com.github.java-json-tools:jackson-coreutils` | Apache-2.0 OR LGPL-3.0-or-later |
| `com.github.java-json-tools:jackson-coreutils-equivalence` | Apache-2.0 OR LGPL-3.0-or-later |
| `com.github.java-json-tools:json-patch` | Apache-2.0 OR LGPL-3.0-or-later |
| `com.github.java-json-tools:json-schema-core` | Apache-2.0 OR LGPL-3.0-or-later |
| `com.github.java-json-tools:json-schema-validator` | Apache-2.0 OR LGPL-3.0-or-later |
| `com.github.java-json-tools:msg-simple` | Apache-2.0 OR LGPL-3.0-or-later |
| `com.github.java-json-tools:uri-template` | Apache-2.0 OR LGPL-3.0-or-later |
| `com.github.javakeyring:java-keyring` | BSD-3-Clause |
| `com.google.code.findbugs:jsr305` | Apache-2.0 |
| `com.google.errorprone:error_prone_annotations` | Apache-2.0 |
| `com.google.guava:failureaccess` | Apache-2.0 |
| `com.google.guava:guava` | Apache-2.0 |
| `com.google.guava:listenablefuture` | Apache-2.0 |
| `com.googlecode.javaewah:JavaEWAH` | Apache-2.0 |
| `com.googlecode.libphonenumber:libphonenumber` | Apache-2.0 |
| `com.squareup.okio:okio-jvm` | Apache-2.0 |
| `com.typesafe:config` | Apache-2.0 |
| `com.zaxxer:HikariCP` | Apache-2.0 |
| `commons-codec:commons-codec` | Apache-2.0 |
| `commons-io:commons-io` | Apache-2.0 |
| `commons-logging:commons-logging` | Apache-2.0 |
| `de.swiesend:secret-service` | MIT |
| `dev.atsushieno:alsa-javacpp` | MIT |
| `dev.atsushieno:ktmidi-jvm` | MIT |
| `dev.atsushieno:ktmidi-jvm-desktop-jvm` | MIT |
| `dev.atsushieno:libremidi-panama` | MIT |
| `dev.atsushieno:rtmidi-javacpp` | MIT |
| `io.github.oshai:kotlin-logging-jvm` | Apache-2.0 |
| `io.github.smiley4:ktor-openapi` | Apache-2.0 |
| `io.github.smiley4:ktor-swagger-ui` | Apache-2.0 |
| `io.github.smiley4:schema-kenerator-core` | Apache-2.0 |
| `io.github.smiley4:schema-kenerator-jsonschema` | Apache-2.0 |
| `io.github.smiley4:schema-kenerator-reflection` | Apache-2.0 |
| `io.github.smiley4:schema-kenerator-serialization` | Apache-2.0 |
| `io.github.smiley4:schema-kenerator-swagger` | Apache-2.0 |
| `io.ktor:ktor-client-cio-jvm` | Apache-2.0 |
| `io.ktor:ktor-client-content-negotiation-jvm` | Apache-2.0 |
| `io.ktor:ktor-client-core-jvm` | Apache-2.0 |
| `io.ktor:ktor-client-encoding-jvm` | Apache-2.0 |
| `io.ktor:ktor-client-logging-jvm` | Apache-2.0 |
| `io.ktor:ktor-events-jvm` | Apache-2.0 |
| `io.ktor:ktor-http-cio-jvm` | Apache-2.0 |
| `io.ktor:ktor-http-jvm` | Apache-2.0 |
| `io.ktor:ktor-io-jvm` | Apache-2.0 |
| `io.ktor:ktor-network-jvm` | Apache-2.0 |
| `io.ktor:ktor-network-tls-jvm` | Apache-2.0 |
| `io.ktor:ktor-openapi-schema-jvm` | Apache-2.0 |
| `io.ktor:ktor-resources-jvm` | Apache-2.0 |
| `io.ktor:ktor-serialization-jvm` | Apache-2.0 |
| `io.ktor:ktor-serialization-kotlinx-json-jvm` | Apache-2.0 |
| `io.ktor:ktor-serialization-kotlinx-jvm` | Apache-2.0 |
| `io.ktor:ktor-server-auth-api-key-jvm` | Apache-2.0 |
| `io.ktor:ktor-server-auth-jvm` | Apache-2.0 |
| `io.ktor:ktor-server-content-negotiation-jvm` | Apache-2.0 |
| `io.ktor:ktor-server-core-jvm` | Apache-2.0 |
| `io.ktor:ktor-server-csrf-jvm` | Apache-2.0 |
| `io.ktor:ktor-server-host-common-jvm` | Apache-2.0 |
| `io.ktor:ktor-server-netty-jvm` | Apache-2.0 |
| `io.ktor:ktor-server-resources-jvm` | Apache-2.0 |
| `io.ktor:ktor-server-routing-openapi-jvm` | Apache-2.0 |
| `io.ktor:ktor-server-sessions-jvm` | Apache-2.0 |
| `io.ktor:ktor-server-status-pages-jvm` | Apache-2.0 |
| `io.ktor:ktor-server-websockets-jvm` | Apache-2.0 |
| `io.ktor:ktor-sse-jvm` | Apache-2.0 |
| `io.ktor:ktor-utils-jvm` | Apache-2.0 |
| `io.ktor:ktor-websocket-serialization-jvm` | Apache-2.0 |
| `io.ktor:ktor-websockets-jvm` | Apache-2.0 |
| `io.netty:netty-buffer` | Apache-2.0 |
| `io.netty:netty-codec` | Apache-2.0 |
| `io.netty:netty-codec-base` | Apache-2.0 |
| `io.netty:netty-codec-compression` | Apache-2.0 |
| `io.netty:netty-codec-http` | Apache-2.0 |
| `io.netty:netty-codec-http2` | Apache-2.0 |
| `io.netty:netty-codec-marshalling` | Apache-2.0 |
| `io.netty:netty-codec-protobuf` | Apache-2.0 |
| `io.netty:netty-common` | Apache-2.0 |
| `io.netty:netty-handler` | Apache-2.0 |
| `io.netty:netty-resolver` | Apache-2.0 |
| `io.netty:netty-transport` | Apache-2.0 |
| `io.netty:netty-transport-classes-epoll` | Apache-2.0 |
| `io.netty:netty-transport-classes-kqueue` | Apache-2.0 |
| `io.netty:netty-transport-native-epoll` | Apache-2.0 |
| `io.netty:netty-transport-native-kqueue` | Apache-2.0 |
| `io.netty:netty-transport-native-unix-common` | Apache-2.0 |
| `io.sentry:sentry` | MIT |
| `io.sentry:sentry-kotlin-extensions` | MIT |
| `io.sentry:sentry-ktor-client` | MIT |
| `io.sentry:sentry-logback` | MIT |
| `io.swagger.core.v3:swagger-annotations` | Apache-2.0 |
| `io.swagger.core.v3:swagger-core` | Apache-2.0 |
| `io.swagger.core.v3:swagger-models` | Apache-2.0 |
| `io.swagger.parser.v3:swagger-parser` | Apache-2.0 |
| `io.swagger.parser.v3:swagger-parser-core` | Apache-2.0 |
| `io.swagger.parser.v3:swagger-parser-safe-url-resolver` | Apache-2.0 |
| `io.swagger.parser.v3:swagger-parser-v2-converter` | Apache-2.0 |
| `io.swagger.parser.v3:swagger-parser-v3` | Apache-2.0 |
| `io.swagger:swagger-annotations` | Apache-2.0 |
| `io.swagger:swagger-compat-spec-parser` | Apache-2.0 |
| `io.swagger:swagger-core` | Apache-2.0 |
| `io.swagger:swagger-models` | Apache-2.0 |
| `io.swagger:swagger-parser` | Apache-2.0 |
| `io.swagger:swagger-parser-safe-url-resolver` | Apache-2.0 |
| `it.krzeminski:snakeyaml-engine-kmp-jvm` | Apache-2.0 |
| `jakarta.activation:jakarta.activation-api` | BSD-3-Clause |
| `jakarta.validation:jakarta.validation-api` | Apache-2.0 |
| `jakarta.xml.bind:jakarta.xml.bind-api` | BSD-3-Clause |
| `joda-time:joda-time` | Apache-2.0 |
| `net.java.dev.jna:jna` | Apache-2.0 OR LGPL-2.1-or-later |
| `net.java.dev.jna:jna-platform` | Apache-2.0 OR LGPL-2.1-or-later |
| `net.sf.jopt-simple:jopt-simple` | MIT |
| `net.thauvin.erik.urlencoder:urlencoder-lib-jvm` | Apache-2.0 |
| `org.apache.commons:commons-lang3` | Apache-2.0 |
| `org.apache.httpcomponents:httpclient` | Apache-2.0 |
| `org.apache.httpcomponents:httpcore` | Apache-2.0 |
| `org.bytedeco:javacpp` | Apache-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0 |
| `org.checkerframework:checker-qual` | MIT |
| `org.eclipse.jetty.alpn:alpn-api` | Apache-2.0 OR EPL-1.0 |
| `org.eclipse.jgit:org.eclipse.jgit` | BSD-3-Clause |
| `org.jetbrains.exposed:exposed-core` | Apache-2.0 |
| `org.jetbrains.exposed:exposed-dao` | Apache-2.0 |
| `org.jetbrains.exposed:exposed-java-time` | Apache-2.0 |
| `org.jetbrains.exposed:exposed-jdbc` | Apache-2.0 |
| `org.jetbrains.exposed:exposed-json` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-build-tools-api` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-compiler-embeddable` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-daemon-embeddable` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-reflect` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-script-runtime` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-scripting-common` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-scripting-compiler-impl-embeddable` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-scripting-dependencies` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-scripting-ide-services` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-scripting-jvm` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-scripting-jvm-host` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7` | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-datetime-jvm` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-io-core-jvm` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-io-jvm` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` | Apache-2.0 |
| `org.jetbrains:annotations` | Apache-2.0 |
| `org.jmdns:jmdns` | Apache-2.0 |
| `org.mozilla:rhino` | MPL-2.0 |
| `org.slf4j:slf4j-api` | MIT |
| `org.webjars:swagger-ui` | Apache-2.0 |
| `org.xerial:sqlite-jdbc` | Apache-2.0 |
| `org.yaml:snakeyaml` | Apache-2.0 |
| `pt.davidafsilva.apple:jkeychain` | BSD-2-Clause |
| `tools.jackson.core:jackson-core` | Apache-2.0 |
| `tools.jackson.core:jackson-databind` | Apache-2.0 |
| `tools.jackson.module:jackson-module-kotlin` | Apache-2.0 |
| `uk.co.xfactory-librarians:coremidi4j` | EPL-1.0 |

## Go modules

In `lighting7-link`, the desk's WebRTC sidecar, built from `link/`.

| Module | Licence |
|---|---|
| `github.com/coder/websocket` | ISC |
| `github.com/google/uuid` | BSD-3-Clause |
| `github.com/pion/datachannel` | MIT |
| `github.com/pion/dtls/v3` | MIT |
| `github.com/pion/ice/v4` | MIT |
| `github.com/pion/interceptor` | MIT |
| `github.com/pion/logging` | MIT |
| `github.com/pion/mdns/v2` | MIT |
| `github.com/pion/randutil` | MIT |
| `github.com/pion/rtcp` | MIT |
| `github.com/pion/rtp` | MIT |
| `github.com/pion/sctp` | MIT |
| `github.com/pion/sdp/v3` | MIT |
| `github.com/pion/srtp/v3` | MIT |
| `github.com/pion/stun/v4` | MIT |
| `github.com/pion/transport/v5` | MIT |
| `github.com/pion/turn/v5` | MIT |
| `github.com/pion/webrtc/v4` | MIT |
| `github.com/wlynxg/anet` | BSD-3-Clause |
| `golang.org/x/crypto` | BSD-3-Clause |
| `golang.org/x/net` | BSD-3-Clause |
| `golang.org/x/sys` | BSD-3-Clause |
| `golang.org/x/time` | BSD-3-Clause |
| The Go standard library and runtime | BSD-3-Clause |

## Browser libraries

The frontend's production dependencies and everything they pull in, from
`frontend/package-lock.json`. Most are bundled into the desk's web UI; a few are build tools.

| Package | Licence |
|---|---|
| `@babel/runtime` | MIT |
| `@dimforge/rapier3d-compat` | Apache-2.0 |
| `@dnd-kit/accessibility` | MIT |
| `@dnd-kit/core` | MIT |
| `@dnd-kit/sortable` | MIT |
| `@dnd-kit/utilities` | MIT |
| `@emnapi/core` | MIT |
| `@emnapi/runtime` | MIT |
| `@emnapi/wasi-threads` | MIT |
| `@floating-ui/core` | MIT |
| `@floating-ui/dom` | MIT |
| `@floating-ui/react-dom` | MIT |
| `@floating-ui/utils` | MIT |
| `@jridgewell/gen-mapping` | MIT |
| `@jridgewell/remapping` | MIT |
| `@jridgewell/resolve-uri` | MIT |
| `@jridgewell/sourcemap-codec` | MIT |
| `@jridgewell/trace-mapping` | MIT |
| `@mediapipe/tasks-vision` | Apache-2.0 |
| `@monogrid/gainmap-js` | MIT |
| `@napi-rs/canvas` | MIT |
| `@napi-rs/canvas-android-arm64` | MIT |
| `@napi-rs/canvas-darwin-arm64` | MIT |
| `@napi-rs/canvas-darwin-x64` | MIT |
| `@napi-rs/canvas-linux-arm-gnueabihf` | MIT |
| `@napi-rs/canvas-linux-arm64-gnu` | MIT |
| `@napi-rs/canvas-linux-arm64-musl` | MIT |
| `@napi-rs/canvas-linux-riscv64-gnu` | MIT |
| `@napi-rs/canvas-linux-x64-gnu` | MIT |
| `@napi-rs/canvas-linux-x64-musl` | MIT |
| `@napi-rs/canvas-win32-arm64-msvc` | MIT |
| `@napi-rs/canvas-win32-x64-msvc` | MIT |
| `@napi-rs/wasm-runtime` | MIT |
| `@oxc-project/types` | MIT |
| `@radix-ui/number` | MIT |
| `@radix-ui/primitive` | MIT |
| `@radix-ui/react-alert-dialog` | MIT |
| `@radix-ui/react-arrow` | MIT |
| `@radix-ui/react-avatar` | MIT |
| `@radix-ui/react-collection` | MIT |
| `@radix-ui/react-compose-refs` | MIT |
| `@radix-ui/react-context` | MIT |
| `@radix-ui/react-context-menu` | MIT |
| `@radix-ui/react-dialog` | MIT |
| `@radix-ui/react-direction` | MIT |
| `@radix-ui/react-dismissable-layer` | MIT |
| `@radix-ui/react-dropdown-menu` | MIT |
| `@radix-ui/react-focus-guards` | MIT |
| `@radix-ui/react-focus-scope` | MIT |
| `@radix-ui/react-id` | MIT |
| `@radix-ui/react-label` | MIT |
| `@radix-ui/react-menu` | MIT |
| `@radix-ui/react-popover` | MIT |
| `@radix-ui/react-popper` | MIT |
| `@radix-ui/react-portal` | MIT |
| `@radix-ui/react-presence` | MIT |
| `@radix-ui/react-primitive` | MIT |
| `@radix-ui/react-roving-focus` | MIT |
| `@radix-ui/react-select` | MIT |
| `@radix-ui/react-separator` | MIT |
| `@radix-ui/react-slider` | MIT |
| `@radix-ui/react-slot` | MIT |
| `@radix-ui/react-tabs` | MIT |
| `@radix-ui/react-toggle` | MIT |
| `@radix-ui/react-toggle-group` | MIT |
| `@radix-ui/react-tooltip` | MIT |
| `@radix-ui/react-use-callback-ref` | MIT |
| `@radix-ui/react-use-controllable-state` | MIT |
| `@radix-ui/react-use-effect-event` | MIT |
| `@radix-ui/react-use-is-hydrated` | MIT |
| `@radix-ui/react-use-layout-effect` | MIT |
| `@radix-ui/react-use-rect` | MIT |
| `@radix-ui/react-use-size` | MIT |
| `@radix-ui/react-visually-hidden` | MIT |
| `@radix-ui/rect` | MIT |
| `@react-three/drei` | MIT |
| `@react-three/fiber` | MIT |
| `@reduxjs/toolkit` | MIT |
| `@remix-run/route-pattern` | MIT |
| `@rolldown/binding-android-arm-eabi` | MIT |
| `@rolldown/binding-android-arm64` | MIT |
| `@rolldown/binding-darwin-arm64` | MIT |
| `@rolldown/binding-darwin-x64` | MIT |
| `@rolldown/binding-freebsd-x64` | MIT |
| `@rolldown/binding-linux-arm-gnueabihf` | MIT |
| `@rolldown/binding-linux-arm64-gnu` | MIT |
| `@rolldown/binding-linux-arm64-musl` | MIT |
| `@rolldown/binding-linux-ppc64-gnu` | MIT |
| `@rolldown/binding-linux-s390x-gnu` | MIT |
| `@rolldown/binding-linux-x64-gnu` | MIT |
| `@rolldown/binding-linux-x64-musl` | MIT |
| `@rolldown/binding-openharmony-arm64` | MIT |
| `@rolldown/binding-win32-arm64-msvc` | MIT |
| `@rolldown/binding-win32-x64-msvc` | MIT |
| `@rolldown/pluginutils` | MIT |
| `@sentry/browser` | MIT |
| `@sentry/browser-utils` | MIT |
| `@sentry/conventions` | MIT |
| `@sentry/core` | MIT |
| `@sentry/feedback` | MIT |
| `@sentry/react` | MIT |
| `@sentry/replay` | MIT |
| `@sentry/replay-canvas` | MIT |
| `@standard-schema/spec` | MIT |
| `@standard-schema/utils` | MIT |
| `@tailwindcss/node` | MIT |
| `@tailwindcss/oxide` | MIT |
| `@tailwindcss/oxide-android-arm64` | MIT |
| `@tailwindcss/oxide-darwin-arm64` | MIT |
| `@tailwindcss/oxide-darwin-x64` | MIT |
| `@tailwindcss/oxide-freebsd-x64` | MIT |
| `@tailwindcss/oxide-linux-arm-gnueabihf` | MIT |
| `@tailwindcss/oxide-linux-arm64-gnu` | MIT |
| `@tailwindcss/oxide-linux-arm64-musl` | MIT |
| `@tailwindcss/oxide-linux-x64-gnu` | MIT |
| `@tailwindcss/oxide-linux-x64-musl` | MIT |
| `@tailwindcss/oxide-wasm32-wasi` | MIT |
| `@tailwindcss/oxide-win32-arm64-msvc` | MIT |
| `@tailwindcss/oxide-win32-x64-msvc` | MIT |
| `@tailwindcss/vite` | MIT |
| `@tanstack/react-virtual` | MIT |
| `@tanstack/virtual-core` | MIT |
| `@tweenjs/tween.js` | MIT |
| `@tybys/wasm-util` | MIT |
| `@types/draco3d` | MIT |
| `@types/offscreencanvas` | MIT |
| `@types/react` | MIT |
| `@types/react-dom` | MIT |
| `@types/react-reconciler` | MIT |
| `@types/stats.js` | MIT |
| `@types/three` | MIT |
| `@types/use-sync-external-store` | MIT |
| `@types/webxr` | MIT |
| `@use-gesture/core` | MIT |
| `@use-gesture/react` | MIT |
| `aria-hidden` | MIT |
| `base64-js` | MIT |
| `bidi-js` | MIT |
| `buffer` | MIT |
| `camera-controls` | MIT |
| `class-variance-authority` | Apache-2.0 |
| `clsx` | MIT |
| `cmdk` | MIT |
| `cookie-es` | MIT |
| `cross-env` | MIT |
| `cross-spawn` | MIT |
| `csstype` | MIT |
| `dequal` | MIT |
| `detect-gpu` | MIT |
| `detect-libc` | Apache-2.0 |
| `detect-node-es` | MIT |
| `draco3d` | Apache-2.0 |
| `enhanced-resolve` | MIT |
| `es-toolkit` | MIT |
| `fdir` | MIT |
| `fflate` | MIT |
| `fsevents` | MIT |
| `get-nonce` | MIT |
| `glsl-noise` | MIT |
| `graceful-fs` | ISC |
| `hls.js` | Apache-2.0 |
| `ieee754` | BSD-3-Clause |
| `immediate` | MIT |
| `immer` | MIT |
| `is-promise` | MIT |
| `isexe` | ISC |
| `its-fine` | MIT |
| `jiti` | MIT |
| `js-tokens` | MIT |
| `kotlin-playground` | Apache-2.0 |
| `lie` | MIT |
| `lightningcss` | MPL-2.0 |
| `lightningcss-android-arm64` | MPL-2.0 |
| `lightningcss-darwin-arm64` | MPL-2.0 |
| `lightningcss-darwin-x64` | MPL-2.0 |
| `lightningcss-freebsd-x64` | MPL-2.0 |
| `lightningcss-linux-arm-gnueabihf` | MPL-2.0 |
| `lightningcss-linux-arm64-gnu` | MPL-2.0 |
| `lightningcss-linux-arm64-musl` | MPL-2.0 |
| `lightningcss-linux-x64-gnu` | MPL-2.0 |
| `lightningcss-linux-x64-musl` | MPL-2.0 |
| `lightningcss-win32-arm64-msvc` | MPL-2.0 |
| `lightningcss-win32-x64-msvc` | MPL-2.0 |
| `loose-envify` | MIT |
| `lucide-react` | ISC |
| `maath` | MIT |
| `magic-string` | MIT |
| `make-cancellable-promise` | MIT |
| `make-event-props` | MIT |
| `merge-refs` | MIT |
| `meshline` | MIT |
| `meshoptimizer` | MIT |
| `nanoid` | MIT |
| `object-assign` | MIT |
| `path-key` | MIT |
| `pdfjs-dist` | Apache-2.0 |
| `picocolors` | ISC |
| `picomatch` | MIT |
| `postcss` | MIT |
| `potpack` | ISC |
| `promise-worker-transferable` | Apache-2.0 |
| `prop-types` | MIT |
| `qrcode-generator` | MIT |
| `react` | MIT |
| `react-colorful` | MIT |
| `react-dom` | MIT |
| `react-is` | MIT |
| `react-pdf` | MIT |
| `react-qr-code` | MIT |
| `react-redux` | MIT |
| `react-remove-scroll` | MIT |
| `react-remove-scroll-bar` | MIT |
| `react-router` | MIT |
| `react-style-singleton` | MIT |
| `react-use-measure` | MIT |
| `redux` | MIT |
| `redux-thunk` | MIT |
| `require-from-string` | MIT |
| `reselect` | MIT |
| `rolldown` | MIT |
| `scheduler` | MIT |
| `shebang-command` | MIT |
| `shebang-regex` | MIT |
| `sonner` | MIT |
| `source-map-js` | BSD-3-Clause |
| `stats-gl` | MIT |
| `stats.js` | MIT |
| `suspend-react` | MIT |
| `tailwind-merge` | MIT |
| `tailwindcss` | MIT |
| `tailwindcss-animate` | MIT |
| `tapable` | MIT |
| `three` | MIT |
| `three-mesh-bvh` | MIT |
| `three-stdlib` | MIT |
| `tiny-invariant` | MIT |
| `tinyglobby` | MIT |
| `troika-three-text` | MIT |
| `troika-three-utils` | MIT |
| `troika-worker-utils` | MIT |
| `tslib` | 0BSD |
| `tunnel-rat` | MIT |
| `use-callback-ref` | MIT |
| `use-sidecar` | MIT |
| `use-sync-external-store` | MIT |
| `utility-types` | MIT |
| `vite` | MIT |
| `warning` | MIT |
| `web-vitals` | Apache-2.0 |
| `webgl-constants` | MIT |
| `webgl-sdf-generator` | MIT |
| `which` | ISC |
| `zustand` | MIT |

## Native code and vendored files

| Component | Where | Licence |
|---|---|---|
| CoreMIDI4J's native library | uk/co/xfactorylibrarians/coremidi4j/libCoreMidi4J.dylib in lighting7.jar, from uk.co.xfactory-librarians:coremidi4j | EPL-1.0 |
| Gradle wrapper | gradle/wrapper/gradle-wrapper.jar, in the source tree only | Apache-2.0 |
| JNA's native dispatch library | com/sun/jna/<platform>/ in lighting7.jar, from net.java.dev.jna:jna | (Apache-2.0 OR LGPL-2.1-or-later) AND MIT |
| Jansi's native library | org/jetbrains/kotlin/org/fusesource/jansi/internal/native/ in lighting7.jar, shaded into org.jetbrains.kotlin:kotlin-compiler-embeddable | Apache-2.0 |
| SQLite and its JDBC bridge | org/sqlite/native/ in lighting7.jar, from org.xerial:sqlite-jdbc | Apache-2.0 AND blessing |
| alsa-javacpp's JNI bindings | linux-x86_64/ in lighting7.jar, from dev.atsushieno:alsa-javacpp | MIT |
| jkeychain's macOS keychain bridge | osxkeychain.so at the root of lighting7.jar, from pt.davidafsilva.apple:jkeychain | BSD-2-Clause |
| libremidi | jne/ in lighting7.jar, from dev.atsushieno:libremidi-panama | BSD-2-Clause AND MIT |
| lz4-java's native library | org/jetbrains/kotlin/net/jpountz/util/ in lighting7.jar, shaded into org.jetbrains.kotlin:kotlin-compiler-embeddable | Apache-2.0 AND BSD-2-Clause |
| react-kotlin-playground, modified | frontend/src/kotlinScript/, compiled into the web UI's script editor | MIT |
| shadcn/ui components | frontend/src/components/ui/ (all but form-fields.tsx), compiled into the web UI | MIT |

## Java runtime

The installers also carry a Java runtime that jlink builds from the Eclipse Temurin JDK, under
its own licence, `GPL-2.0-only WITH Classpath-exception-2.0`. The exception is what lets a
program under any licence run on it and ship with it; docs/licensing.md says why the gate does
not judge it.

## Licences

- `0BSD`: BSD Zero Clause License
- `Apache-2.0`: Apache License 2.0
- `BSD-2-Clause`: BSD 2-Clause "Simplified" License
- `BSD-3-Clause`: BSD 3-Clause "New" or "Revised" License (the Eclipse Distribution License 1.0 is this licence)
- `EPL-1.0`: Eclipse Public License 1.0, for a library used unmodified
- `EPL-2.0`: Eclipse Public License 2.0, for a library used unmodified
- `ISC`: ISC License
- `MIT`: MIT License
- `MPL-2.0`: Mozilla Public License 2.0, for a library used unmodified
- `blessing`: SQLite's public-domain dedication
