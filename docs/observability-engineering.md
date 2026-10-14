# Observability — Sentry

The desk reports errors, logs, run sessions, traces and metrics to Sentry (`lighting7-kotlin`, EU
region), and its browsers report errors, sessions and traces to `lighting7-react` through the desk
(§"The browser"). Replay, feedback and the browser's metrics are later sessions of
[`plans/sentry-plan.md`](plans/sentry-plan.md).

## The switch

Telemetry is an **install setting**, admin only and machine-local (plan D1):

- `GET /api/rest/install/telemetry` answers `{enabled, release, environment, tracesSampleRate,
  browserDsn}` to any caller, signed in or not, on either listener — the frontend reads it before
  sign-in. The
  auth gate exempts the **GET only**: an exempt path carries no user, which `requireAdmin` reads as
  allowed, so a method-blind exemption would let a signed-out `PUT` through.
- `PUT /api/rest/install/telemetry` `{enabled}` is an admin's — the *Send diagnostics* switch on
  Install settings → General, shown to admins only. It stores the choice in `telemetry_settings`
  (one row, never synced), fires `installChanged`, and starts or stops Sentry without a restart;
  the tunnel refuses and stops forwarding at once, and every open browser stops its own Sentry on
  the `installChanged` frame. Off withdraws consent for what is already queued too: once the SDK
  has flushed (2 s at most) the backend's disk cache is deleted, and so is the tunnel's spool.
- An install that has never chosen reports from a `DEV` build and not from a `RELEASE` one.
- `sentry.enabled = false` in `local.conf` turns it off whatever the choice says; `enabled` in the
  GET answer is that combination.

`telemetry/TelemetrySettings.kt` holds the setting and lives in `State`. Sentry itself
(`telemetry/SentryRuntime.kt`) is started only by `Application.module()`, right after `State` —
which opens the database the choice is stored in — and never by `State` or `moduleWithState`, so
the test suite never meets it. `TelemetryTest` asserts that.

## Configuration

```hocon
sentry {
    dsn = "…"               # application.conf: the lighting7-kotlin DSN (public, not a secret)
    enabled = true          # local.conf can force off
    tracesSampleRate = ""   # empty: 1.0, or 0.2 in `release`
    idleSyncSampleRate = "" # an auto-sync run that changed nothing; empty: tracesSampleRate
    environment = ""        # empty: `release` for a RELEASE build, else `development`
    browserDsn = "…"        # application.conf: the lighting7-react DSN, all the tunnel forwards to
}
```

`scripts/cloud-session.sh` writes `environment = "cloud"`. The release is
`lighting7@<version>+<shortSha>` from `BuildInfo` (D10). Only CI bakes a commit into `BuildInfo`
(`build.gradle.kts` keeps it out of local builds' inputs on purpose), so a non-packaged run with none
takes its working directory's `git rev-parse --short HEAD` at startup (`DevCommit`), and goes
without a suffix if that is not a checkout.

## What is sent

| What | From | Level |
|---|---|---|
| Issues | a logged `ERROR` (the logback `SentryAppender`, attached in code after init), and uncaught exceptions | error |
| Logs | every log line at `INFO` and above | — |
| Breadcrumbs | log lines at `INFO` and above, and the SDK's own errors (a failing transport) | — |
| Sessions | one per desk run: started at init, ended at `ApplicationStopping` — release health counts desk runs | — |

`DEBUG` never leaves the machine, so the `uk.me.cormack.lighting7.midi` debug logger stays local.
Logging from `io.sentry.*` and from the `telemetry` package is never sent (`LocalOnlyFilter`), and
the SDK's internal logger turns its own errors into breadcrumbs rather than log lines (D15): a
transport failure logged at `ERROR` would go round through the appender.

A logged `ERROR` is the one way a handled failure becomes an issue. StatusPages does not capture;
its catch-all and its database-error branch log under the call's scope (§"Tracing"), which adds the
`listener` tag (`local` / `public`) and the user — the desk account's **uuid and role**, nothing
else (`sendDefaultPii` stays off).

Logs take the scope's attributes, not its tags: besides release, environment and `server.address`
(the machine), each carries an `install` attribute, set on the global scope beside the tag.

Every event carries the tags `install` (install uuid), `machine` (friendly name, following a
rename), `packaged`, and `boot_phase` until the show is ready, and the context `project` (uuid and
kind, never the name) from the first ready boot and every switch.

The session sits on the root isolation scope, which every thread's scope fork shares. Turning
telemetry off and on again without a restart starts a new session, but a thread that already held a
fork keeps the ended one, so that run's release-health counts are approximate; its events are not
affected.

Offline, envelopes queue in `<appDataDir>/sentry/<dsn hash>/` (300 at most), and shutdown and flush
wait at most 2 s each, so a desk with no route out still quits promptly. The queue is sent each time
Sentry starts — at boot, or when the switch is turned back on — by
`SendCachedEnvelopeFireAndForgetIntegration`, which `SentryRuntime.resendDiskCache` registers: on the
JVM nothing else ever reads the cache, and the SDK has no connectivity signal there, so a desk that
regains its route mid-run sends the backlog at its next start, not at once (`DiskCacheResendTest`).
A shutdown keeps the queue; turning the switch off deletes it (§"The switch").

## What is not sent (`SentryFilters.beforeSend`)

- A `CancellationException`, a closed channel, or an `IOException` saying the connection was reset,
  a broken pipe or (on Windows) forcibly closed or aborted — anywhere in the cause chain.
- Anything StatusPages answers with a 4xx (`isClientErrorException` in `plugins/ErrorHandling.kt`,
  which must name every exception a handler there turns into a client error) — but only on an
  event StatusPages' own logger (`error-handling`) raises while handling a request, which the
  call's scope marks with the `listener` tag. The same exception logged at `ERROR` by a route that
  answers 500 (a `SerializationException` in a cloud-sync import), or by background work, is a
  fault, and is sent.

A log line marked `USER_SCRIPT` (`telemetry/SentryFilters.kt`) is a failure in an operator's own
script: it is sent at level `warning` with the tag `origin: user-script`. Mark a log call with it
only when the failure is in the script, and never put the script's source in the message. Today
that is the cue-trigger script runs in `CueTriggerManager`; compile errors are returned to the
editor as diagnostics and never logged at `ERROR`, so they never reach Sentry.

## Scrubbing

`TokenScrubber` runs over every event (message, its parameters, exception values, breadcrumbs,
request URL and query), every breadcrumb, and every log's body and string attributes. Its patterns
are `src/main/resources/telemetry/secret-patterns.json`, the one list of what is secret: the
browser's scrubber (`frontend/src/lib/sentryScrub.ts`) compiles the same file. It redacts:

- GitHub tokens: `ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_`, `github_pat_`.
- ngrok authtokens, by shape (`<digit><20+ alphanumerics>_<15+ alphanumerics>`) and by key
  (`authtoken: …`, `NGROK_AUTHTOKEN=…`, `--authtoken …`).
- `Bearer …` credentials, keeping the scheme.
- The one-time token in `/auth/reset/{token}` and `/auth/device/{token}`, and in the pages' own
  `/reset/{token}` and `/device/{token}` at the start of a path or right after a host — never a
  route that only has a `device` segment, such as `/oauth/github/device/start`, whose transaction
  `beforeSendTransaction` would otherwise drop.
- OAuth `code=`, `state=` and `token=` query parameters, and `access_token` / `refresh_token` /
  `id_token` values.
- The `lighting7_session` cookie.

Add a pattern there when the desk starts handling a new secret, with a case in
`src/test/resources/telemetry/scrubCases.fixture.json`. Both `TokenScrubberTest` and
`sentryScrub.test.ts` run every case and fail on a pattern no case exercises; the two scrubbers
apply a match the same way (group 1 kept, the rest replaced), and those shared cases are where they
are kept in step. A pattern must mean the same in Java and JavaScript regex: no inline flags such as `(?i)`
(set `ignoreCase` instead).

## Tracing

Everything below checks `Sentry.isEnabled()` first and otherwise does nothing, so the suite — which
never starts Sentry — runs untouched; `TracingTest` proves it, and drives the rest against an
in-process Sentry whose transport sends nothing (`withTestSentry`).

### Where transactions start

| Transaction | Op | Started by | Spans |
|---|---|---|---|
| A call under `/api/`, `/mcp`, `/oauth/`, `/.well-known/` | `http.server` | `plugins/SentryTracing.kt` | DB, outbound HTTP, the operations below |
| A gesture WS frame | `websocket.message` | `traceMessage` in the socket loop | the same |
| Boot | `app.boot` | `module()` | `boot.phase` `starting` (with `db.init`, recorded before Sentry started), `show_init` (with `fx.compile`, a child per effect whose `cache` is `memory`, `disk` or `compiled`: the
parallel workers' threads hold no scope, so the cache lookup is this attribute rather than a
`cache.get` span), `fixtures` (`show.fixtures`, `show.prewarm`), `mdns` |
| Cue GO | `cue.go` | `CueStackManager.activateCueInStack` | `cue.compose`, `cue.spawn`, `scenery.resolve`, `cue.events` |
| Project switch | `project.switch` | `ProjectManager.switchProject` | `project.unload`, `project.load`, `project.start`, `project.notify` |
| Sync run | `sync.run` | `RemoteSyncEngine.runSync` | `sync.export`, git `http.client`, `sync.diff`, `sync.apply` |
| Venue operations | `venue.publish`, `venue.update`, `venue.extract`, `venue.detach` | `venue/` | `venue.diff`, `venue.snapshot`, `venue.behind`; `venue.dry_run` marks the review |
| Script compile | `script.compile` | `Show.script`, on a miss in the show's own cache | `cache.get` on the compiled-jar cache, with `cache.hit` |
| MCP tool call | `mcp.server` span, `tools/call <tool>` | `McpProtocol` | under the `/mcp` call's transaction |

An HTTP call is continued from the caller's `sentry-trace` / `baggage` and named by its **route
template** (`GET /api/rest/auth/reset/{token}`), never the raw path. A call no API route took — a
404, or a path that fell through to the UI's catch-all — is dropped. Each call runs under a scope
forked fresh from the root, traced or not (a WebSocket upgrade has none: each of its frames is
forked on its own), so an error StatusPages logs carries the call's
`listener` tag, user and trace; the plugin is installed first in `moduleWithState` so that its
pipeline phase sits outside StatusPages'. A WS message's scope is forked per message too, and puts
`listener` on the transaction only, so the 4xx drop above stays a request's.

A span or transaction that ends in an exception is `internal_error` with the exception attached —
unless the exception is a refusal the caller is answered with a 4xx for (`isClientErrorException`,
D12), which sets that status and attaches nothing.

The operations in the table join the request or message that runs them as a child span. Started
from nowhere traced — the auto-sync scheduler, a MIDI GO, the auto-advance timer — they are their
own transaction. A cue GO is REST or MIDI, never a WS frame.

### Sampling

`TraceSampling.sample` is the `tracesSampler`:

| Transactions | Rate |
|---|---|
| `app.boot`, `cue.go`, `sync.run` | always. When the request around one was sampled out, the operation stands alone as its own transaction rather than joining it, so it is still kept |
| an auto-sync `sync.run` that changed nothing (`NO_OP`) | `sentry.idleSyncSampleRate`, or `tracesSampleRate` when empty, decided as it is sent (`TraceSampling.keepSyncRun`): the outcome is only known at the end. A manual run, a failed one, or one that pulled or pushed is always kept |
| `websocket.message` of a continuous or snapshot type | never — the frame is handled with no Sentry work at all |
| everything else, a gesture frame included | `tracesSampleRate`: 1.0, 0.2 in `release`, or `sentry.tracesSampleRate` |

The caller's sampling decision is not inherited, and its baggage loses `sentry-sample_rand` and
`sentry-sample_rate` before the trace is continued (`withoutSamplingInputs`): the SDK would
otherwise draw the call's sampling number from them, so any caller could choose to be sampled.
Errors are never sampled: a sampled-out transaction does not stop its errors being sent.

`TraceSampling.WS_MESSAGES` classifies every inbound frame type, and `TraceSamplingTest` fails until
a new one has a row:

| Kind | Frames |
|---|---|
| Continuous (never) | `updateChannel`, `programmer.set` / `setColour` / `setPosition` / `patchLayer` / `keyStack`, `updateFx`, `surfaceScaler.setGrandMaster`, `speedMasters.setBpm` / `tap` / `requestBeat`, `windows.announce` |
| Snapshot (never) | every state request a client sends on connect (`channelState`, `programmer.state`, …) |
| Gesture (sampled) | the rest: selection, hand, busk page, programmer layers, blind and scenery, park, FX pause, resume and remove, surface learn and banks, blackout, the windows commands |

An idle auto-sync tick is the steady state, so it is not worth a transaction each time: at a
one-minute interval that would be about 43,000 a month for one project.

### Database, outbound HTTP and git

- **Database** (`telemetry/DbTracing.kt`). `State` hands Exposed a data source whose connections,
  taken while a span is active, open a `db.sql.transaction` span with `db.connection.acquire` — the
  wait for the pool's one connection — as its first child; it ends when Exposed gives the
  connection back. `DbTracing.StatementSpans`, registered once through
  `META-INF/services/org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor`, adds a
  `db.sql.query` span per statement: the SQL with `?` for each parameter, and `db.rows_affected` for
  a write. A query's rows are read after its span ends, so they are not counted.
- **Outbound HTTP**. `installSentry()` puts `sentry-ktor-client` on every Ktor `HttpClient` (GitHub
  releases, GitHub OAuth; the Hue controller makes no HTTP calls today). No trace headers go out:
  `tracePropagationTargets` is empty, since no host the desk calls is ours. A failed response is
  the caller's to judge, not an issue.
- **Git**. `JGitClient` wraps clone, fetch and push as `http.client` spans named
  `git <verb> <url>`, the URL without its credentials.

### Scrubbing transactions

`SentryFilters.beforeSendTransaction` drops an unmatched call, and any transaction whose name the
token scrubber would change: a name is a route template or a constant, so that is a naming fault,
and the SDK gives a name no setter. It scrubs every span's description and string data, an HTTP
span's `http.query` included.

### Coroutines

A Ktor call, a WS message and the boot coroutine each run under `SentryContext`. A scope that
outlives a telemetry toggle takes `LiveSentryContext` instead, which gives each coroutine launched
on it a `SentryContext` forked from the root as it starts: a plain `SentryContext()` there would
fork every coroutine from the scopes it was built with, no-op or closed. The show's supervisor
scopes (`EffectsService`, `CommandOutput`) take it with `reportingExceptionHandler`, which logs an
escaped exception at `ERROR` so it is reported rather than only printed; the speed-master flush job
and the user-script runner take it alone.

### What is deliberately not traced

- The FX engine's tick and ArtNet's send loop: no span and no context element, nothing per frame.
  Their health is a metric (§"Metrics").
- Outbound WS frames, and the continuous and snapshot frames above.
- The UI's files, `/api/rest/status` (boot polling), `/api.json`, `/openapi`, stage-render uploads,
  the browser's tunnel (`/api/sentry`: a transaction per browser envelope would double the volume)
  and the WebSocket upgrade itself.
- The ngrok agent's download, which uses `java.net.http` rather than Ktor.

### Adding a span

- Inside something already traced: `Tracing.span("op", "description") { span -> … }`
  (`spanSuspending` in a coroutine). It is a child of the active span, or just runs the block.
- An operation that may also start on its own (a scheduler, a timer, a surface):
  `Tracing.transaction("op", "name") { … }` (`transactionSuspending`). Add its op to
  `TraceSampling.ALWAYS_KEEP` only if every one is worth the quota.
- On a worker thread the caller's scope is not current on, take the parent first and use
  `Tracing.childOf(parent, …)`, as `FxFileLoader`'s parallel compile does.
- A name or description is a constant, a template or SQL with placeholders — never a path, a URL
  with credentials, a token or a script. `beforeSendTransaction` drops a name that needs scrubbing.
- Never on a per-frame path.

## Metrics

`perf/MetricsReporter` sends what `perf/DeskMetrics` recorded every 30 s through `Sentry.metrics()`
(plan D7). `SentryRuntime` starts it after `Sentry.init` and stops it before `Sentry.close`, so the
switch stops metrics with everything else. Nothing in SDK 8.60.0 reads `options.metrics.isEnabled`,
so the reporter running is the switch. Its first read on start throws away whatever was recorded
while telemetry was off, and `stop` waits up to a second for a flush in progress. The flush runs on
its own `metrics-reporter` thread and only enqueues; the SDK batches and sends on its own executor.
A failed flush is logged at `WARN` under a `telemetry` logger name, so it stays local.

Every flush reads and resets what it reports, and sends nothing for a window that recorded nothing:

- A **count** is the window's total.
- A **histogram** (a `LatencyHistogram`) is three distribution samples in milliseconds: its p50,
  p95 and max, under the attribute `stat`. Always filter or group a query by `stat`: unfiltered,
  the three are mixed. Filtered, a query reads across windows — `max(value)` where `stat` is `max`
  is the worst frame, but `p95` over the `p95` samples is a percentile of window percentiles, not
  of frames. `HistogramWindow` reads it by diffing its buckets rather
  than resetting it, so `/api/rest/perf/midi-latency`, which the MIDI histograms also feed, is
  unaffected. The max is exact. The percentiles are interpolated inside log2 buckets, so they are
  accurate to the bucket, not the nanosecond.
- A **rare event** (a GO, a boot) is one sample per occurrence, at most 50 per window (4 for boot).
- A **gauge** is read when the flush runs.

| Metric | Kind | Recorded by | Attributes |
|---|---|---|---|
| `artnet.frame.lateness` | histogram | `ArtNetController`'s loop, at the top of each frame: how far past its deadline it started | `universe` (`subnet:universe`) |
| `artnet.frame.overrun` | count | the same loop, each time it fell more than a whole interval behind and re-based its deadline | `universe` |
| `fx.tick.duration` | histogram | `FxEngine`'s two loops, around each pass (an idle pass included) | `loop` (`beat`, `wall_clock`) |
| `fx.active_effects` | gauge | the live show's `FxEngine.activeEffectCount` | — |
| `ws.clients` | gauge | sockets past the warm-up gate | — |
| `ws.outbound.dropped` | count | frames a socket never sent, see below | `type` (the frame's `@SerialName`), `reason` (`conflated`, `closed`) |
| `midi.latency` | histogram | `MidiLatencyTracker`, read and never reset | `stage` (`ingressButton`, …) |
| `db.connection.wait` | histogram | Hikari's `recordConnectionAcquiredNanos`, through `DbConnectionMetrics` | — |
| `cue.go.duration` | event (ms) | `CueStackManager.activateCueInStack`, around the call whether or not its span is sampled | — |
| `boot.duration` | event (s) | `module()`'s first line to the show being ready | `cache`: `cold` if the Kotlin compiler ran, else `warm` |
| `script.cache` | count | `ScriptCacheProbe.record`, once per compile | `result` (`hit`, `miss`), `cache` (`memory`, `disk`, `compiled`) |
| `sync.run` | count | `RemoteSyncEngine.runSync` | `outcome` (`no_op`, `pushed`, …, `failed`), `trigger` (`auto`, `manual`) |
| `jvm.heap.used` | gauge (bytes) | `MemoryMXBean` | — |
| `jvm.gc.pause` | histogram | GC notifications, one per stop-the-world pause. A ZGC cycle is concurrent work and is left out | `gc` (`minor`, `major`) |

`ws.outbound.dropped` counts the only frames the desk's socket layer loses. `SocketScope.send` never
drops: a slow tab blocks on Ktor's outgoing channel, and while it does, the two outbound
replay-1/DROP_OLDEST flows (`ProvenanceService.flow` and `ProgrammerStore.layersFlow`) overwrite what
it has not taken yet. Their emissions carry a `seq`, and each socket's collector counts a gap as
`conflated`, so one emission skipped by three slow tabs counts three. Numbering and emitting happen
under one lock, so a gap is always a drop. A send that raced the connection closing counts as
`closed`. A `StateFlow` family's
conflation is by design (a `*.state` frame is the whole fact) and is not counted.

GC notifications come from `jdk.management`, which is why `jlinkModules` ships it. Without it the GC
beans emit nothing, and the metric would be silent on every packaged install.

### Volume

The org is on the free plan, so what each window sends is worth knowing. On an idle desk the steady
state per window is 3 gauges, plus 3 samples for each histogram that recorded anything:
`3 × universes` for ArtNet, 6 for the two FX loops (both tick idle), usually 3 for the database and
3 for GC. With two universes that is about 21 samples a window, or about 60,000 a day.
An auto-sync run is one more count in the window it lands in: about 1,440 a day at one a minute.
Counts and events cost nothing in a window without them.

### Adding a metric

- Record it in `DeskMetrics`, in a structure allocated there: a `LatencyHistogram`, an atomic counter,
  or an `EventSamples`. A hot-path hook is an increment and nothing else: no allocation, no lock, no
  Sentry call, no logging (`MetricsReporterTest` checks the ArtNet and FX hooks allocate nothing).
- Read it in `MetricsReporter.collect`, resetting what it reads, and add a row above.
- An attribute comes from an enum, a universe number or a compiled frame type. Never a fixture key,
  a name, a uuid or anything a user typed. `MetricsReporterTest` lists the allowed keys.
- Never derive a metric from a span. Spans are sampled, and a metric must count every occurrence.

### What is deliberately not measured

- Anything per fixture, per cue, per group or per user: the cardinality is the rig's.
- Per-frame or per-tick values sent as they happen. Every hot path is aggregated first.
- Inbound WS frame rates. Traces already sample the gestures.
- `StateFlow` conflation (above), and the MIDI flows' own DROP_OLDEST buffers, which feed the
  surfaces, not a socket.
- The browser. Frontend metrics are session 5's.

## The browser

`frontend/src/lib/sentry.ts` is the browser's Sentry, and `main.tsx` is the only module that imports
it (`sentryImports.test.ts` fails on a second), so no vitest file can start it. Everything else calls
the SDK directly — `captureReactException`, `addBreadcrumb`, `startSpan`, the Redux enhancer — and
those do nothing until a client exists.

### Starting and stopping

- `startSentry()` fetches `GET /install/telemetry`, aborted after 1 s. No answer, an error,
  `enabled: false` or no `browserDsn` means no Sentry for this page load.
- Rendering never waits. `AuthGate`, `BootGate` and their overlays render at once; only the router
  waits, because the SDK instruments a router only if `Sentry.init` ran before it was created
  (`wrapCreateBrowserRouter` reads the client then). `App` creates it once, when
  `lib/sentryReady.ts` says the fetch has settled, or after 2 s if nothing says so. The router
  already renders only after `/auth/status`, which the same desk answers, so the wait shows only on
  the public `/reset` and `/device` pages, and never for more than a second.
- `installChanged` makes every open page fetch the setting again: off closes the client (it flushes
  for up to 2 s, then drops everything); on starts it, with errors and fetch spans but no route
  transactions until the next load, since the router was made before the client.

| Option | Value |
|---|---|
| `release` | `__LIGHTING7_RELEASE__` (§"Release and source maps"), else the GET's `release` |
| `environment`, `tracesSampleRate` | the GET's, so one setting governs both halves |
| `tunnel` | `/api/sentry` |
| `traceLifecycle` | `'static'`. 11.7.0's default is `'stream'`, which sends each span as it ends: a fetch with no page load or navigation open becomes a root span of its own (a transaction per auto-sync refetch), and `beforeSendTransaction` is never called, so nothing would be scrubbed. Found by session 4's live check |
| `tracePropagationTargets` | `[/^\/api\//]`: a REST call's backend transaction is in the browser's trace. The backend continues the trace and draws its own sampling decision (§"Sampling") |
| `dataCollection` | every category off. It is v11's replacement for `sendDefaultPii`, every default of which collects; 11.7.0's browser reads only `userInfo`, which on would have Sentry infer the client's IP |
| `integrations` | `reactRouterBrowserTracingIntegration` from `@sentry/react/react-router`, which binds the router hooks itself |
| `ignoreErrors` | `ResizeObserver loop…`, WebGL context loss, and `AbortError` from an aborted fetch |

### What is sent

| What | From |
|---|---|
| Issues | uncaught errors and rejections; `FeatureErrorBoundary`, through `captureReactException` with the tag `feature` (`This page`, `The script editor`) and the component stack; the root `ErrorBoundary` in `App` for what no feature boundary contains |
| Transactions | a page load and each navigation, named by route pattern (`/projects/:projectId/busk`); fetch spans under them; a `websocket.request` span per `programmer.keyStack` or `programmer.clearTarget` round trip, only under an open page transaction (`onlyIfParent`), since the key-stack view asks once per property |
| Sessions | one per page load (the SDK's `page` lifecycle), so browser release health counts page loads |
| Breadcrumbs | the SDK's (clicks, console, fetch, history); the socket's open, close with its code, and reconnects; and one per Redux action, carrying its type and RTK Query endpoint but never a payload — a query's is its whole response — and not RTK Query's per-frame bookkeeping (`queryResultPatched`, subscriptions, config) |

Every event carries the tags `window` (this tab's name, following a rename) and `view`
(`windowViewOf`'s id for the route, `other` elsewhere), and the Redux enhancer's `state` context:
`{project: {id, kind}, connection, boot: {phase, ready}}`, never the store. Its `redux_state.json`
attachment is off. The browser sets no user.

`beforeSend`, `beforeSendTransaction` and `beforeBreadcrumb` run `lib/sentryScrub.ts` (§"Scrubbing")
over the message, the transaction name, the request's URL, query and `Referer`, exception values,
breadcrumbs, and every span's description and data.

### The tunnel (`/api/sentry`)

`routes/sentryTunnel.kt` is mounted under `/api` outside the warm-up and auth gates, so the login
screen and the boot overlay report too, on both listeners, and is not traced. `BrowserTunnel.accept`
answers:

| Status | Code | When |
|---|---|---|
| 403 | `TELEMETRY_OFF` | the switch is off, or no `sentry.browserDsn` |
| 413 | `ENVELOPE_TOO_LARGE` | above 1 MB, by `Content-Length` and then by a bounded read |
| 400 | `ENVELOPE_INVALID` | the header line is not JSON naming a `dsn` |
| 403 | `ENVELOPE_DSN_REFUSED` | the `dsn` is not `sentry.browserDsn`: key, host, port and project are compared |
| 503 | `ENVELOPE_NOT_SPOOLED` | the spool could not be written |
| 200 | — | spooled as it came to `<appDataDir>/sentry-browser/`, 300 envelopes or 64 MB at most, oldest dropped. The spool is listed once, on first use, and kept as an index after that; a `.part` file a crash left half-written is swept then |

On the public listener those checks, with the listener's own (an account must exist; a POST from a
foreign `Origin` is refused), are the whole defence. Whatever is spooled can only go to
`lighting7-react`, which anyone holding its public DSN could post to directly.

Only `Application.module()` starts the forwarder (the `sentry-browser-tunnel` thread), so the suite
spools and never sends. It posts each envelope byte for byte, oldest first, to the project's
envelope endpoint, with `java.net.http` rather than a Ktor client, whose Sentry plugin would trace it.
Nothing is re-serialised: the JVM SDK's envelope model would drop what it does not know. It sends
only while telemetry is on. A 2xx deletes the envelope, and so does any other 4xx (logged at `WARN`,
locally). A 429 waits as long as `Retry-After` or `X-Sentry-Rate-Limits` says, a minute if neither
does. A network failure, a 5xx or a spool file it cannot read backs off from 5 s, doubling to 5 min;
nothing stops the worker but shutdown. A new envelope or a toggle wakes it, but does not cut a
backoff short. Turning the switch off deletes the spool (§"The switch").

### Release and source maps

- **Release** (D10). `buildFrontend` hands Vite `LIGHTING7_RELEASE`, made from `:generateBuildInfo`'s
  own version and commit and spelled as `TelemetrySettings.releaseName` spells the backend's — the
  two spellings are kept in step by hand. Only a build that carries its commit has one, which every
  release build does; otherwise the browser takes the desk's from the GET, which includes the
  checkout's commit (§"Configuration").
- **Source maps** (D14). `windows-build.yml` gives the `gradlew packageWindows` step
  `SENTRY_AUTH_TOKEN`, and only then does `vite.config.ts` load `@sentry/vite-plugin`: hidden source
  maps, debug IDs injected as it bundles (which is why it runs inside `buildFrontend`, not after it),
  the upload, the release's commits (`fetch-depth: 0`), and the maps deleted. `copyFrontend`
  excludes `*.map` as well, so none can reach the jar. The plugin's own telemetry is off. Its
  default is to fail the build on an upload or set-commits error, so a release build needs the
  GitHub integration connected in Sentry. Without the token — locally, in `ci.yml`, on a fork —
  there are no source maps and no plugin.

### Volume

Per page load: one session and one page-load transaction. Per navigation: one transaction, at
`tracesSampleRate`. A REST call makes a span only inside one of those (the SDK makes a fetch span
only under an open span), so the auto-sync's refetches cost nothing between navigations, though they
still carry the page's trace id to the backend. Nothing per WS frame, gesture or Redux action leaves
the browser on its own; errors go as they happen. A search-only URL change is a `replace` throughout
the app, which the SDK does not treat as a navigation.

