# Observability — Sentry

The desk reports errors, logs and run sessions to Sentry (`lighting7-kotlin`, EU region). The
frontend (`lighting7-react`), tracing and metrics come in later sessions of
[`plans/sentry-plan.md`](plans/sentry-plan.md); this document covers what exists today: the
backend foundation.

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
its catch-all and its database-error branch log inside `withSentryCallScope`, which adds the
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
  event raised while handling a request, which `withSentryCallScope` marks with the `listener` tag.
  The same exception logged at `ERROR` by background work (a `SerializationException` in a sync
  import) is a fault, and is sent.

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
