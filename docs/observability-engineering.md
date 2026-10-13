# Observability — Sentry

The desk reports errors, logs, run sessions, traces and metrics to Sentry (`lighting7-kotlin`, EU
region). The frontend (`lighting7-react`) comes in later sessions of
[`plans/sentry-plan.md`](plans/sentry-plan.md); this document covers what exists today: the
backend foundation, its tracing and its metrics.

## The switch

Telemetry is an **install setting**, admin only and machine-local (plan D1):

- `GET /api/rest/install/telemetry` answers `{enabled, release, environment, tracesSampleRate}` to
  any caller, signed in or not, on either listener — the frontend reads it before sign-in. The
  auth gate exempts the **GET only**: an exempt path carries no user, which `requireAdmin` reads as
  allowed, so a method-blind exemption would let a signed-out `PUT` through.
- `PUT /api/rest/install/telemetry` `{enabled}` is an admin's. It stores the choice in
  `telemetry_settings` (one row, never synced), fires `installChanged`, and starts or stops Sentry
  without a restart.
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

Offline, envelopes queue in `<appDataDir>/sentry` (300 at most), and shutdown and flush wait at
most 2 s each, so a desk with no route out still quits promptly.

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
request URL and query), every breadcrumb, and every log's body and string attributes. It redacts:

- GitHub tokens: `ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_`, `github_pat_`.
- ngrok authtokens, by shape (`<digit><20+ alphanumerics>_<15+ alphanumerics>`) and by key
  (`authtoken: …`, `NGROK_AUTHTOKEN=…`, `--authtoken …`).
- `Bearer …` credentials, keeping the scheme.
- The one-time token in `/auth/reset/{token}` and `/auth/device/{token}`.
- OAuth `code=`, `state=` and `token=` query parameters, and `access_token` / `refresh_token` /
  `id_token` values.
- The `lighting7_session` cookie.

Add a pattern there, with a case in `TokenScrubberTest`, when the desk starts handling a new secret.

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
- The UI's files, `/api/rest/status` (boot polling), `/api.json`, `/openapi`, stage-render uploads
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

