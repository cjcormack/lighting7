# Sentry — errors, traces, logs and metrics for the desk and its UI

> **Document status: COMPLETE — all five sessions shipped, as `df8c0b65`, `0fa1e6e4`, `ee704c36`,
> `fa8c5026` (2026-10-13) and `5aec25a9` (2026-10-14), with `a9899d6a` a fix to session 4's browser
> tracing.** Agreed with Chris on 2026-10-13, whose answers to §9's questions the decisions below
> record. SDK facts were checked that day against `sentry-java` 8.60.0 and `@sentry/react` 11.7.0.
> Where this plan and [`docs/observability-engineering.md`](../../observability-engineering.md)
> disagree, the engineering doc describes what shipped; §5 lists the departures. §6 and §7's
> follow-ups are in [`followups.md`](../followups.md), and the release workflow's first real run is
> `FU-MANUAL-SENTRY-RELEASE` in [`manual-validation.md`](../manual-validation.md).

## 1. Context

Chris's brief, 2026-10-13: add Sentry to both halves, with transactions and errors on each, session
replay on the frontend, and logs and metrics on the backend, the backend's transactions "rich with
DB/HTTP/etc. spans". Two Sentry projects exist, both in the EU region (`ingest.de.sentry.io`):

| Project | DSN |
|---|---|
| `lighting7-kotlin` | `https://0beb365f275ac2bbeb4f85b10cc50dc9@o4512248405164032.ingest.de.sentry.io/4512248434262096` |
| `lighting7-react` | `https://5ccbcddbb005a1b8c562095a04dc25a0@o4512248405164032.ingest.de.sentry.io/4512248441471056` |

A DSN is a public key, not a secret: the browser has to see one. It is safe in `application.conf`
and in the bundle.

What the codebase gives us, and what shapes the plan:

- **There is no Ktor *server* integration in `sentry-java`.** The repository's modules include
  `sentry-ktor-client`, `sentry-jdbc`, `sentry-logback`, `sentry-kotlin-extensions` and
  `sentry-opentelemetry`, but nothing for incoming Ktor calls. Server transactions are either a
  small plugin of our own or OpenTelemetry (D3).
- **Most of the desk's traffic is WebSocket, not HTTP.** `Sockets.kt:173` deserialises every inbound
  frame into one `InMessage` `when`. A request/response instrumentation sees almost none of the
  operator's gestures; the WS dispatcher is the second place a transaction must start (D4).
- **The DB is one SQLite connection.** `State.initDatabase` builds a Hikari pool with
  `maximumPoolSize = 1`, so *waiting for the connection* is as interesting as the query, and is
  invisible today.
- **The desk is real-time.** `ArtNetController` sends against absolute deadlines and `FxEngine`
  ticks on a timer. Nothing Sentry does may sit on those paths per frame; metrics from them are
  aggregated locally first (`perf/LatencyHistogram` already exists for MIDI).
- **The desk is often offline.** A venue's lighting network frequently has no route out. Every
  envelope has to survive that: queued to disk on the backend, and never blocking or slowing a show.
- **Other people install it.** The Windows MSI is published as a GitHub release, so a release build
  would report someone else's desk to Chris's Sentry. That is a consent question (D1).
- **Two listeners.** `startPublicListener` serves the whole desk on `mcp.port` for the tunnel.
  Anything the plan adds there is reachable from the internet (D9).
- **`BuildInfo` already names the build** — version, channel, commit — in both jars, from one task.
  It is the release name for both projects.
- **The frontend has one error boundary** (`FeatureErrorBoundary`, keyed per route in `Layout.tsx`),
  React Router 8 via `createBrowserRouter`, RTK, and one WebSocket (`api/internalApi.ts`).
- **Tests must never report.** `State` is built dozens of times in the suite; `module()` is the
  production-only entry point (`runBreakGlassIfRequested` sits there for the same reason).

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **Telemetry is an install setting, admin only, machine-local.** One switch covers both halves: `GET/PUT /api/rest/install/telemetry`, beside `/install/tunnel`, stored in its own local-only table (never synced, a `SyncCoverageTest` row saying so). Default: **on** for a `DEV` build and for this desk, **off** for a `RELEASE` install until an admin turns it on, with a one-line explanation of what is sent on the Install settings page. `sentry.enabled = false` in `local.conf` overrides it off. |
| D2 | **Native Sentry SDK, not the OTel agent.** `io.sentry:sentry-bom:8.60.0` pins `sentry`, `sentry-logback`, `sentry-kotlin-extensions`, `sentry-ktor-client`. The OTel *agent* is out: the launcher spawns the backend with its own command line, jpackage would need it shipped and wired, and it instruments Netty and every coroutine hop at a cost we cannot bound on a show machine. The Sentry Gradle plugin is out too: it auto-adds integrations and touches the build in ways `org.gradle.warning.mode=fail` and the shadow verifier will both object to. |
| D3 | **Incoming HTTP is our own Ktor plugin**, ~100 lines in `plugins/SentryTracing.kt`: continue the trace from `sentry-trace` / `baggage`, start an `http.server` transaction named by the **route template** (`GET /api/rest/projects/{id}/cues/{cid}` — never the raw path, which carries tokens), set status from the response, and run the call under `SentryContext()` so scope follows the coroutine across threads. Static assets, `/api/rest/status` polling, `/api.json` and stage-render uploads are not traced. Rejected: `sentry-opentelemetry-agentless` plus OTel's `ktor-3.0` library instrumentation, because it means two tracing systems bridged and more weight in the jar. |
| D4 | **Each inbound WS message is a transaction** of op `websocket.message`, named by its type (`cueStack.go`, `programmer.setScenery`), sampled by a per-type table: **never** for the continuous ones (`updateChannel`, slider and encoder writes, `windows.announce`, `speedMasters.tap`), always-eligible for discrete gestures. Outbound broadcasts are not traced; their health is a metric (D7). |
| D5 | **DB spans from Exposed, not P6Spy.** An Exposed `StatementInterceptor` registered once makes a `db.sql.query` span per statement (SQL text with parameters as `?`, row count), and a wrapper around `transaction(state.database)` makes a `db.sql.transaction` span whose first child is **`db.connection.acquire`** — the time spent waiting for the one connection. `sentry-jdbc` would mean a `jdbc:p6spy:` URL, a wrapped driver and P6Spy's own `spy.log` file, which it writes into the working directory unless configured not to. |
| D6 | **Outbound HTTP through `sentry-ktor-client`** on every `HttpClient(` (GitHub releases, GitHub OAuth, Hue): spans, breadcrumbs, trace headers only to hosts we own (none of these), so `propagateTraceparent` stays off for third parties. JGit's fetch/push are wrapped by hand as `http.client` spans in `sync/`. |
| D7 | **Metrics are aggregated in-process and flushed every 30 s,** never emitted per tick. `Sentry.metrics()` (Java 8.34+) takes count, gauge and distribution. A small `perf/MetricsReporter` reads the existing `LatencyHistogram`s and new counters and emits each as one distribution/gauge per interval. The API is new: 8.59.0's notes say `options.metrics.enabled` currently has no effect, so the switch is ours (D1), not the SDK's. |
| D8 | **Logs through `sentry-logback`**, configured in code from `local.conf`, not in `logback.xml`: `minimumLevel` INFO for Sentry Logs, ERROR for issues, INFO for breadcrumbs. The `uk.me.cormack.lighting7.midi` DEBUG logger stays local only. A `beforeSendLog` scrubs token shapes (`ghp_`, `gho_`, `github_pat_`, ngrok tokens, `Bearer …`). A logged `error` is the one way a handled failure becomes an issue — StatusPages does **not** capture as well, or every 500 is reported twice. |
| D9 | **The browser reports through the desk**: `tunnel: "/api/sentry"`, a route that forwards an envelope only if its header names the `lighting7-react` DSN, at most 1 MB, and only while telemetry is on — otherwise an open relay on the public listener. It buys four things: the D1 switch is authoritative for every browser; an iPad on an isolated show network reports through a desk that has a second route out; the backend's disk cache holds browser envelopes while the venue is offline; and ad-blockers stop mattering. |
| D10 | **One release name for both projects:** `lighting7@<version>+<shortSha>` from `BuildInfo`, the frontend's injected by Vite `define` from the same Gradle task's values. `environment` is `development` (`./gradlew run`), `release` (a `RELEASE` packaged install) or `cloud` (`scripts/cloud-session.sh`). |
| D11 | **Context on every event:** tag `install` (the install uuid), `machine` (friendly name), `packaged`, `listener` (`local` / `public`), `boot_phase` until ready; context `project` (uuid and kind, not name). The user is the desk account's **id and role only**; `sendDefaultPii = false`. Frontend adds `window` (the `?window=` name) and `view`. |
| D12 | **Not every exception is an issue.** Dropped in `beforeSend`: `CancellationException`, closed-channel and connection-reset `IOException`s from Netty and WS, every `ErrorResponse` 4xx, and the auth/remote-access exceptions StatusPages already turns into 401/403. User scripts' compile and runtime errors are tagged `origin: user-script` at level `warning`, and the script source never rides in an event. |
| D13 | **Replay records errors, not shows**: `replaysSessionSampleRate` 0 in `release` (0.1 in `development`), `replaysOnErrorSampleRate` 1.0. Masking is relaxed — `maskAllText: false` (fixture names and cue labels are the point of a replay) — with inputs still masked, and the Stage view's canvas, the channel grid and the Prompt Book's PDF `data-sentry-block`ed: they mutate every frame and would dominate both CPU and replay size. No canvas recording. |
| D14 | **Source maps upload in the release workflow only.** `build.sourcemap: "hidden"`, `@sentry/vite-plugin` (org `lighting7`, project `lighting7-react`) with `SENTRY_AUTH_TOKEN` from a repository secret, `filesToDeleteAfterUpload` so no `.map` ships in the MSI. Absent the secret (CI, local, forks) the plugin does nothing. The upload runs **inside** `windows-build.yml`'s `gradlew packageWindows` step (which runs `buildFrontend`), not as a later `getsentry/action-release` step: the plugin injects debug IDs into the bundle as it builds it, and a step after Gradle would inject them into `frontend/dist` after the unmarked bundle was already sealed into the MSI. The plugin also creates the release and sets its commits; that step passes `SENTRY_AUTH_TOKEN` and `SENTRY_RELEASE`, and checkout uses `fetch-depth: 0`. Upload is on the release workflow only: `ci.yml`'s PR builds would litter Sentry with releases. JVM stack traces need nothing: no obfuscation. |
| D15 | **Sentry never delays the show.** Disk cache at `<appDataDir>/sentry` (`maxCacheItems` 300), `shutdownTimeoutMillis` 2000 and `flushTimeoutMillis` 2000 so a desk quits promptly offline, no synchronous `flush()` on any request path, and a failing transport is a breadcrumb, never an error log (which would loop through the appender). |

## 3. Backend

### 3.1 Initialisation

`Sentry.init` runs at the top of `Application.module()` — production only, after config is loaded,
before `State` — from a `sentry { }` block in `application.conf`:

```hocon
sentry {
    dsn = "https://0beb…@o4512248405164032.ingest.de.sentry.io/4512248434262096"
    enabled = true            # local.conf can force off; D1's install switch is the other gate
    tracesSampleRate = 1.0    # tracesSampler applies D3/D4's rules on top
    environment = ""          # empty = derived (D10)
}
```

The logback `SentryAppender` is attached programmatically after init (D8), so `logback.xml` gains
nothing and tests, which never call `module()`, never meet Sentry. Options set: `release`,
`environment`, `serverName` = install friendly name, `cacheDirPath`, `maxBreadcrumbs` 200,
`enableUncaughtExceptionHandler`, `beforeSend`, `beforeSendTransaction`, `logs.beforeSend`,
`tracesSampler`. A session starts at boot and ends at `ApplicationStopping`, so **release health
counts desk runs**: a crash-free rate per release.

The shadow jar needs a look: Sentry ships `META-INF/services` entries, and `failOnDuplicateEntries`
will refuse a clash rather than merge it. `mergeServiceFiles()` if so. No native payloads are added
(the profiler is §6's, and not on Windows).

### 3.2 Coroutines

Scope does not follow a coroutine to another thread by itself. `SentryContext()` goes on the Ktor
call (D3), the WS per-message handler (D4), the show's long-lived scopes, and `runShowStartup`. A
`CoroutineExceptionHandler` on the show's supervisor scopes captures what is otherwise only
printed.

### 3.3 Transactions and spans

| Transaction | Op | Spans |
|---|---|---|
| Each traced HTTP call | `http.server` | DB (D5), outbound HTTP (D6), custom below |
| Each eligible WS message | `websocket.message` | same |
| Boot | `app.boot` | One per `BootPhase` — DB init (`createMissingTablesAndColumns` is ~1.2 s on its own), FX compile with a child per effect tagged cache hit/miss, fixtures load, cue pre-warm, mDNS |
| Cue GO | `cue.go` (child of the WS/REST transaction when there is one) | compose, scenery resolve, effect spawn, events fired |
| Project switch | `project.switch` | unload, load fixtures, recompose |
| Sync run | `sync.run` | pull (JGit fetch), diff, import/export, push |
| Venue publish / update / extract / detach | `venue.*` | the transaction and its review run |
| Script compile | `script.compile` | cache lookup, compile |
| MCP tool call | `mcp.server` | named by the tool, attribute `mcp.tool.name`, following Sentry's MCP span conventions (whether its MCP dashboard reads a Java server's spans is unverified) |

### 3.4 Metrics

Flushed every 30 s by `MetricsReporter` (D7). Attributes are low-cardinality: universe number,
device type, message type — never a fixture key or uuid.

| Metric | Kind | Source |
|---|---|---|
| `artnet.frame.lateness` | distribution (ms) | `ArtNetController`'s deadline loop: how late each send was |
| `artnet.frame.overrun` | count | sends that missed a whole interval |
| `fx.tick.duration` | distribution (ms) | `FxEngine` pass |
| `fx.active_effects` | gauge | engine |
| `ws.clients` | gauge | socket registry |
| `ws.outbound.dropped` | count | conflated/dropped frames per message type |
| `midi.latency` | distribution (ms) | the existing `MidiLatencyTracker` |
| `db.connection.wait` | distribution (ms) | Hikari `MetricsTrackerFactory` |
| `cue.go.duration` | distribution (ms) | cue GO span |
| `boot.duration` | distribution (s) | once per boot, tag cold/warm script cache |
| `script.cache` | count | hit / miss |
| `sync.run` | count | outcome |
| `jvm.heap.used`, `jvm.gc.pause` | gauge, distribution | `MemoryMXBean`, GC notifications |

## 4. Frontend

### 4.1 Initialisation

`src/lib/sentry.ts`, called from `main.tsx` before the store and the bridges start. It first fetches
`GET /api/rest/install/telemetry` (open to any role and to a signed-out client, answering only
`{enabled, release, environment, tracesSampleRate, replay…}`) with a 1 s timeout; no answer means
no Sentry. It then renders regardless — telemetry never gates the UI. `vitest` never imports it.

- `@sentry/react` 11.x, `reactRouterBrowserTracingIntegration` and `wrapCreateBrowserRouter` in
  `App.tsx` (the v8 names, unversioned), so page-load and navigation transactions are named by
  route pattern.
- `tracePropagationTargets: [/^\/api\//]`, so a REST call's backend transaction is the same trace.
- `FeatureErrorBoundary.componentDidCatch` calls `Sentry.captureException` with the feature name
  and the React component stack. A root `Sentry.ErrorBoundary` covers what is outside it.
- `Sentry.createReduxEnhancer` with a `stateTransformer` that returns only small slices (current
  project, connection, boot) — the full store is megabytes of patch and cue data.
- The WebSocket in `internalApi.ts` adds breadcrumbs for connect, close (with code) and reconnect,
  and a span for each outbound gesture that is answered (`programmer.keyStack` round trip). No
  breadcrumb per inbound frame.
- `ignoreErrors` for WebGL context loss, `ResizeObserver loop`, and `AbortError` from aborted fetches.

### 4.2 Replay and feedback

`replayIntegration` loaded lazily (`lazyLoadIntegration`) so it costs nothing until sampled, with
D13's settings. The `feedbackIntegration` sits behind a **Report a problem** item in the user menu,
not the floating button (it would sit over desk controls); it attaches a screenshot and the replay
buffer, and is how an operator says "this went wrong" mid-show without a stack trace to show for it.

### 4.3 Frontend metrics

`ws.reconnect` (count), `boot.ready` (distribution, page load to `BootGate` ready),
`stage.fps` (distribution, sampled every 10 s while the Stage view is visible), all through
`Sentry.metrics`.

## 5. Implementation — five sessions

One PR each, per CLAUDE.md §"Git workflow"; each ends with `./gradlew test` and `npm run check`
green and `docs/observability-engineering.md` updated.

1. **Backend foundation** — done, `df8c0b65`. BOM and deps; `module()` init; config block; D1's
   table, route and gate (no UI yet); release/environment/context (D10, D11); logback appender and
   log scrubbing (D8); `beforeSend` filters (D12); disk cache and shutdown (D15); run sessions;
   shadow-jar check; a test that `moduleWithState` leaves Sentry disabled.
   *As shipped:* a non-packaged run reads its commit from `git rev-parse` at startup, since local
   builds keep the SHA out of the build's inputs.
2. **Backend tracing** — done, `0fa1e6e4`. The Ktor plugin (D3) and `SentryContext` propagation;
   WS message transactions (D4); the Exposed interceptor and connection-acquire span (D5);
   `sentry-ktor-client` and JGit spans (D6); §3.3's custom transactions.
   *As shipped:* D5's transaction span wraps the **data source**, not the ~418 `transaction()` call
   sites, for the same span tree; an idle auto-sync run is sampled at `sentry.idleSyncSampleRate`
   rather than always kept (§7), which would have been ~43,000 transactions a month; an incoming
   trace is continued without the caller's `sample_rand` / `sample_rate`, so a caller cannot choose
   to be sampled.
3. **Backend metrics** — done, `ee704c36`. `MetricsReporter` and §3.4's table.
   *As shipped:* `jvm.gc.pause` needed `jdk.management` added to the jlinked runtime.
4. **Frontend foundation and tracing** — done, `fa8c5026` (and `a9899d6a`). §4.1; the Install
   settings switch (D1's UI); release injection from Gradle (D10); the envelope tunnel (D9);
   source-map upload in `windows-build.yml` (D14).
   *As shipped:* the tunnel spools envelopes to disk and a worker forwards them byte for byte with
   `java.net.http`, rather than re-sending through the JVM SDK; `sentry-java` 8.60.0 never reads its
   disk cache back on the JVM, so `SentryRuntime` registers the resender itself; turning telemetry
   off deletes both queues; the browser traces with `traceLifecycle: 'static'`, since 11.7.0's
   default `'stream'` skipped `beforeSendTransaction` and made every REST call a root trace.
5. **Replay, feedback and frontend metrics** — done, `5aec25a9`. §4.2, §4.3; the
   `data-sentry-block` markings; check the busk and Stage views' frame time with replay recording
   on, against off, on the dev desk (busk 60 fps either way, Stage ~20 fps either way).
   *As shipped:* replay and feedback load by a dynamic `import()` of a chunk the desk serves, not
   §4.2's `lazyLoadIntegration`, which in 11.7.0 fetches from Sentry's CDN — nowhere at an offline
   venue.

## 6. Other Sentry features worth having

Ranked by value to this desk.

- **Release health** — falls out of sessions on both sides (§3.1, browser default): crash-free
  desk runs and browser sessions per release, and a regression is visible the day a release ships.
- **User Feedback** — §4.2. The one way to capture "the rig didn't do what I pressed" that throws
  nothing.
- **Suspect commits and release commits** — connect the GitHub integration and have the release
  workflow call `sentry-cli releases set-commits --auto`; an issue then names the commit that likely
  caused it.
- **Alerts** — an issue alert on a new issue in `release` and on a regression; a metric alert on
  `artnet.frame.overrun` rising. Configuration in Sentry, no code.
- **Spotlight** in development — `sentry-spotlight` and the browser's Spotlight overlay show events
  locally without sending them, useful while building sessions 2–5.
- **Profiling** — JVM continuous profiling (`sentry-async-profiler`) is **macOS and Linux only**,
  so it would cover the dev desk and never a Windows install, and it adds native binaries the
  per-OS payload table would have to learn. Browser profiling needs a `Document-Policy: js-profiling`
  response header and is Chromium-only. Both are follow-ups, valuable for the Stage view and the FX
  compile, not for v1.
- **Not useful here:** Crons (the auto-sync scheduler would page every time a venue is offline),
  Uptime (the desk is not on the internet), feature-flag context (there are none).

## 7. Things to watch

- **Quota.** The org is on the **Developer (free)** plan, so every default is conservative.
  `tracesSampleRate` is 1.0 in `development` and 0.2 in `release`. The sampler also keeps every
  `app.boot`, `cue.go` and `sync.run` transaction, and every transaction that errors. Replays are the
  scarcest unit, which is why D13 records them on error only. D4's spans are the largest volume, and
  its per-type table is the place to cut. Sentry Logs start at INFO; if INFO fills the log quota,
  raise `minimumLevel` to WARN rather than sampling.
- **Privacy on the public listener.** A remote browser reports with the same switch; the tunnel's
  DSN check (D9) is what stops it relaying anything else. Reset and device-flow URLs carry tokens in
  the path: `beforeSend`, `beforeBreadcrumb` and replay's URL scrubbing redact `/auth/reset/*`,
  `/auth/device/*` and OAuth `code=` / `state=`.
- **Duplicate reporting.** Logged errors are issues (D8); nothing else captures the same exception.
- **The launcher reports nothing.** It is pure JDK by design, so a backend that dies before
  `module()` runs (a bad `local.conf`, a locked data dir) is invisible. A follow-up could have the
  launcher post one minimal envelope with `HttpClient`, without adding a dependency.
- **Warnings fail the build.** Sentry's Kotlin extensions and any `@Experimental` metrics API need
  an `@OptIn` at the site, with a comment.

## 8. Docs

`docs/observability-engineering.md` (new): what is sent, the switch, the sampling tables, how to add
a span or a metric, and the scrubbing rules. CLAUDE.md gains one line under §"Engineering
Documentation" and a note under the public-listener paragraph that `/api/sentry` is mounted there.

## 9. Answers (2026-10-13)

1. **Consent:** release installs send nothing until an admin opts in. Dev builds and this desk send by default (D1).
2. **Server tracing:** our own Ktor plugin on the native SDK (D3).
3. **DB spans:** an Exposed interceptor plus the connection-acquire span (D5).
4. **WS tracing:** every discrete gesture, sampled per type, never the continuous streams (D4).
5. **Tunnel:** browsers report through the desk (D9).
6. **Replay:** on error only in release, text unmasked, inputs masked (D13).
7. **Plan:** Developer (free); the sampling defaults in §7 are set for it.
8. **Auth token:** Chris adds a `SENTRY_AUTH_TOKEN` repository secret. The org slug is `lighting7`, and
   the projects are `lighting7-kotlin` and `lighting7-react`. The release workflow uploads source
   maps and calls `sentry-cli releases set-commits --auto` (D14, §6).
