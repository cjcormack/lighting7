# Lighting7 Cloud P1: remote access and MCP, the desk's side

> **Document status: DONE — written 2026-11-05; sessions 1–9 merged (2026-11-07).** The desk half of
> the PRD's phase P1; its exit, `FU-MANUAL-CLOUD-P1`, is an operator check in
> [`manual-validation.md`](../manual-validation.md#fu-manual-cloud-p1). The cloud half is `docs/plans/p1-plan.md` in `cjcormack/lighting7-cloud`. Design record:
> [`cloud-design/INDEX.md`](../cloud-design/INDEX.md), whose rules R1–R7 apply to every session here.

## 1. Context

P1 replaces the ngrok tunnel and the desk's own OAuth server with two ways in through Lighting7 Cloud:

1. **Remote sessions.** A cloud member opens the desk's own SPA from any network, carried over WebRTC
   data channels between the browser and a bundled sidecar, `lighting7-link`, that forwards each
   request into the public listener on `127.0.0.1:8414` (FR-RA-1 to -5, REC-1). The cloud brokers the
   connection over the control channel and falls back to TURN; it never sees the payloads (NFR-PRIV-1).
2. **MCP through the cloud.** Claude calls one stable URL the cloud serves and authorises; the cloud
   relays each JSON-RPC call over the control channel, and the desk runs it through `McpProtocol` as
   the linked local user (FR-MCP-1 to -5).

Either way the cloud says who someone is with a short-lived **desk access token**, and the desk decides
what they may do through a **link** from that cloud account to a local user, whose role stands (PR-3,
FR-ID-1 to -7). Then ngrok and the desk's OAuth server are deleted (FR-MIG-3, -4).

What exists to build on (`docs/plans/completed/cloud-p0-plan.md`):

- `cloud/`: adoption, the install key, `CloudChannel` (one WebSocket on its own two `cloud-N` threads,
  one outbound queue of 16 that drops the oldest, a hard-coded `when` over inbound messages, `replyTo`
  unused, `hello` sent once per connection with no capabilities), `cloud.state`, `FakeCloud`, and
  `cloud_identity_links` (unique by `user_id` only, `ADOPTION`, written at adoption, and read for
  `cloud.state`'s adopter email but by nothing that authorises).
- `cloud-protocol/`: protocol 1's envelope, P0's messages, signing, golden fixtures that
  `GoldenFixturesTest` demands for every message type.
- The public listener (`mcp/McpServerModule.kt`): cookie sessions, resolved by `resolveSessionUser` for
  the auth gate, the `/api` WebSocket upgrade (`plugins/Sockets.kt`) and the gate-exempt `GET
  /api/rest/auth/status` alike; `installRemoteHardening` (`mcp/RemoteRequests.kt`) with its Origin
  check; and `/mcp` taking the desk's own OAuth bearer tokens, which
  `McpAuthService.resolveAccessToken` turns into an `AuthenticatedUser` whose `sessionTokenHash` is
  `mcp-grant:<id>` (the history source Claude's `list_history` and `undo_changes` key on).
- `mcp/tunnel/`: ngrok's download, supervision with backoff, an orphan sweep and a Windows Job Object,
  and `tunnel.state`. `RemoteAccessService` there also owns the scripts, effects and commands switches
  (`remote_access_settings`' `allow_*`, beside the tunnel's `enabled` and `domain`) and `publicUrl()`;
  `McpAuthService` also owns the public listener's sign-in lockout and the unnamed-client label history
  uses.

The riskiest piece is delivering the SPA through a service worker and a WebSocket shim (RISK-1), so P1
opens with the spike **P1a**, whose pass gates the rest. Sessions 1 to 4 here and 1 and 2 of the cloud
plan are P1a, built as the first cut of the real thing (D1). The gate is the operator check
`FU-MANUAL-CLOUD-P1A` (§6).

Decided before this plan: no desk role ever follows from a cloud org role, only from a link (D-13);
P1's MCP URL is per install (D-12); "approve new remote sessions on the desk" is offered and off by
default (D-14, FR-RA-12). Decided while writing it: MCP has its own desk switch (D-15, FR-RA-13); a
desk's invite is mailed only once an org owner or admin approves it (D-16); and the switches that limit
what comes through the cloud change only at the desk (D-17).

P1 is done when `FU-MANUAL-CLOUD-P1` (§6), the PRD's exit criteria, passes.

## 2. Decisions

- **D1 — The spike is the first cut, not a throwaway.** A request a service worker answers carries no
  cookie and can set none, so even the spike needs real bearer tokens on 8414, and the other pieces
  (the sidecar's bridge, the SPA's socket shim) are thin enough to build once. If P1a fails, RISK-1's
  fallback (REST through the shell by an SPA transport adapter rather than a service worker) changes
  session 4's remote mode and the cloud's shell, and nothing else here.
- **D2 — Desk access tokens are compact JWS, EdDSA over Ed25519, in `cloud-protocol`** (FR-ID-1, -2,
  R3). `DeskTokens` signs (the cloud's half) and verifies (the desk's), on the JDK's Ed25519 as
  `InstallKeys` does, so no JWT library joins either build. `DeskAccessClaims`: `iss`, `aud` (the
  cloud's install id), `sub` (cloud account id), `org`, `scope` (`remote` or `mcp`), `sid` for a remote
  session or `gid` and `azp` for an MCP grant, `iat`, `exp`, `jti`; the header's `kid` names the key.
  The desk refuses a token whose `aud` is not its install, whose lifetime is over 10 minutes, that is
  expired beyond 60 s of skew, whose `iat` is more than 60 s in the future, whose `kid` is unknown, or
  whose `scope` is not the door's (a remote token on the MCP relay, or the reverse). Golden vectors fix
  the bytes: a token signed with RFC 8032's TEST 1 key, and each refusal.
- **D3 — The JWKS is held in memory only** (FR-ID-2). The cloud sends `jwks {keys: [{kid, x}]}` after
  `welcome`; the desk keeps the last one it heard, through any blip, until a newer arrives. It is never
  written: a restart ends every remote session anyway (the sidecar restarts with it), every way in
  needs the cloud to begin, and P0's D11 allows no write outside a transition.
- **D4 — Bearer tokens on 8414 only, through the one resolver** (FR-RA-4). `resolveSessionUser` gains a
  bearer arm active only on the public listener, so the auth gate, the `/api` WebSocket upgrade and
  `GET /api/rest/auth/status` all see `Authorization: Bearer <desk access token>`. It admits a token
  only for a `sid` the broker opened and, with approval on, an admin allowed (D5, D13), and only if its
  `sub` and `org` are the account and org the registry recorded for that `sid` at `rtc.offer`, and only
  on a request carrying the sidecar's `Lighting7-Link` header: a secret the JVM makes for each launch
  of the sidecar and hands it on stdin, which nothing else on the machine learns. So a valid token that
  reaches 8414 any other way (ngrok until session 9, a local process) is refused, and a mismatched one
  also ends the session, so an approved session can never be carried on as someone else. The user is
  the registry's, resolved at the offer: a linked session is an `AuthenticatedUser` for the linked
  local user (`cloud_identity_links` by `sub`), with `sessionTokenHash = "cloud-session:<sid>"`; a
  disabled user is refused as a disabled cookie user is. An unlinked token is an *unlinked session*
  (D12): `auth/status` answers `{unlinked, email}`, the SPA's static files load, `POST
  /api/rest/cloud/link/prove` and the session's refresh and end are open, and everything else is 403
  `CLOUD_ACCOUNT_NOT_LINKED`, naming the two ways to link (FR-ID-3). All of `installRemoteHardening`
  applies to bearer requests, the Origin check included: the service worker sends each request's origin
  as it sees it and the sidecar forwards it as `Origin`, and the allowed origins gain the install's
  desk origin, which the cloud names in `welcome` (`remoteOrigin`). The scripts, effects and commands
  switches still apply (FR-RA-9, NFR-SAFE-3). Cookie sign-in on 8414 stays until session 9, so ngrok
  keeps working beside it.
- **D5 — Remote sessions are a registry in memory and a row at each end.** `RemoteSessions` holds each
  `sid` the broker opened: its account, email, local user (or none, unlinked), expiry and WebSockets. A
  session opens when the broker accepts its `rtc.offer` (session 3). A refreshed token arrives as `POST
  /api/rest/cloud/session/token` with the new bearer (same `sid`, `sub`, `org` and `aud`, later `exp`;
  anything else is refused and ends the session), which the sidecar sends when the shell hands it one.
  The registry fixes the session's link and local user id at the offer, never its role: each request
  reads the user, role and disabled state from `AuthService` as a cookie session does, so a demotion
  takes effect at once. A session whose token runs out unrefreshed, a `revoke {sid | accountId
  | all}`, a link removed, and any of `AuthService`'s credential revocations for its user (disable,
  delete, a password set or reset, *sign out everywhere*, through `addCredentialRevocationListener`, as
  the desk's MCP grants die today) end it: its sockets close 4401, its requests are refused, the broker
  closes its peer in the sidecar, and the `sid`, account or "all" (with the `at` the cloud stamped on
  the `revoke`, so the deny set refuses every token with `iat` at or before it on the cloud's own
  clock) stays in a deny set until every token it could have been issued has expired, so the next fetch
  cannot re-enter (FR-ID-2's "not revoked", FR-ID-6, within 5 s while the desk is online).
  `cloud_remote_sessions(sid, cloud_account_id, email, user_id, started_at, ended_at, path,
  end_reason)`, machine-local, gets a row when a session opens and its end, with the last path the
  sidecar reported, when it closes, never in between; a row left open by a crash is closed at startup
  as `DESK_RESTARTED`, and rows older than a year are pruned then. That is `FU-AUTH-AUDIT-LOG`'s remote
  slice (FR-RA-10), and the item's row says so.
- **D6 — Two switches for what comes through the cloud, set only at the desk** (FR-RA-9).
  `cloud_connection` gains `remote_access` and `mcp_access` (both off at adoption, an admin turns each
  on) and `approve_sessions` (off, D-14). Approval covers remote sessions only (D13), so MCP has its
  own switch: an admin can keep approval on and MCP off. `remote_access` off refuses a new session
  (`rtc.close {REMOTE_ACCESS_OFF}`), ends the open ones, and answers bearer requests 403
  `REMOTE_ACCESS_OFF`; `mcp_access` off answers every relayed call `MCP_ACCESS_OFF`. The desk announces
  what it speaks and its state in `hello` and, whenever that changes (the switch, the sidecar starting
  or stopping), in a `capabilities` message: `remote.v1` once it speaks remote sessions (session 3) and
  `mcp.v1` once it relays MCP (session 8), whatever their state, plus `remote.off` and `mcp.off` while
  their switch is off and `remote.no-sidecar` while no sidecar runs. So the cloud can tell the user why
  before it asks: a switch is off (`REMOTE_ACCESS_OFF`, `MCP_ACCESS_OFF`), the sidecar is down
  (`SIDECAR_UNAVAILABLE`), or the desk is too old (`DESK_REMOTE_UNSUPPORTED`, `DESK_MCP_UNSUPPORTED`).
  No message writes any of the three columns, and their routes refuse the public listener with 403
  `CLOUD_SETTINGS_LOCAL_ONLY`, as adoption is refused there: a compromised cloud can mint a linked
  admin's token, so every switch that limits the cloud has to be out of the reach of anything the cloud
  can sign (D-17). That is a change for the scripts, effects and commands switches, which a remote
  admin can change today through `PUT /install/tunnel`: session 2 refuses the public listener on that
  route too, which makes ngrok's own settings desk-local for its last sessions.
- **D7 — `lighting7-link` is Go on pion, spoken to over its own stdio** (REC-1, NFR-SAFE-2). Source in
  `link/` with its own `go.mod`, MIT. The JVM starts it only while the public listener is bound
  (`mcp.enabled` and a successful bind), with that listener's bound address as the target, and the
  desk's build id, hands it its `Lighting7-Link` secret (D4) as the first line on stdin, and talks to
  it in JSON lines: on stdin `session.open {sid, offer, iceServers}`, `session.ice`, `session.close`;
  on stdout `session.answer`, `session.ice`, `session.state {path}`, `session.closed {reason}`; logs on
  stderr. That is the PRD's "loopback-only local API" (§9.2) with no port to guard: the pipes belong to
  the parent. The sidecar decides nothing (§9.4): on `ctl` it takes `hello {token}`, asks `GET
  /api/rest/auth/status` with that bearer and answers `ready {user?, role?, unlinked?, build}` or
  `refused {code}`, and takes `token {token}` for each refresh; on every other channel it forwards per
  the framing (D8), dropping any `Cookie` and `Authorization` the frames carry and attaching the
  session's latest token and the secret. Beyond the broker's caps (D17) it keeps a hard limit on
  sessions of its own.
- **D8 — The framing, `ctl` and the frame contract are protocol** (R3). Three contracts cross the repos
  and are specified in `docs/cloud-protocol.md` with language-neutral fixtures, since the sidecar is Go
  and the shell and SPA are TypeScript:
  - *`lighting7-link` framing*: one data channel per HTTP request or WebSocket, labelled by kind; a
    header frame (method, path, headers, the request's origin; status and headers back), body chunks of
    at most 16 KiB, an end frame; a WebSocket's messages as typed frames and its close as a frame with
    the code. Senders hold off above a buffered threshold (`bufferedAmountLowThreshold`), so a 100 MB
    upload streams (FR-RA-5).
  - *`ctl`*: `hello`, `ready`, `refused` and `token` (D7).
  - *The remote-frame contract*: the `postMessage` messages between the shell and the SPA. The SPA
    speaks first, so nothing is sent before its listener exists: its `hello` to the parent is answered
    by `port`, carrying the `MessagePort` its socket rides; then the socket's `open`, `send`, `message`
    and `close`, `session.end` and `auth.unlinked` on that port. A service worker the browser restarted
    has lost its own port: it posts `port.request` to the SPA, which asks the shell for a fresh one
    (`port.request` on its own port, answered `port`) and hands that to the worker.

  Fixtures under `cloud-protocol/src/test/resources/protocol/link/` and `…/protocol/remote-frame/`
  (inside `protocol/`, the fixture root the cloud's build already reads), read by the sidecar's
  Go tests, the SPA's Vitest and the cloud's tests alike.
- **D9 — The SPA's remote mode is decided before the socket exists, and only on the public listener**
  (FR-RA-5). `lightingApi` builds its connection when its module is first evaluated, and `main.tsx`
  imports it statically, so the decision comes before `main.tsx` runs at all: a small boot entry, the
  page's only script, decides the mode and then imports `./main` dynamically. It enters remote mode
  only when two things hold: the page is framed, and its `index.html` carries `<meta
  name="lighting7-remote-parent">`, which the desk adds only when it serves the page on the public
  listener, naming the origin of `cloud.baseUrl`. Then it posts `hello` to that origin and waits for
  the `port` (D8), with a timeout after which it shows that the shell did not answer; a message from
  any other origin is ignored. Otherwise it imports `./main` at once, as today. The desk's own listener
  sends `frame-ancestors 'self'`, so no other page can frame the LAN desk at all. In remote mode the
  connection is `RemoteSocket`, an `InternalApiConnection` over the `MessagePort` that emits real
  `MessageEvent` and `CloseEvent`s, so `statusApi`, `wsGesture`, `windowsApi` and the 4401 path work
  unchanged. REST is untouched: it is relative and the service worker answers it. What remote mode
  changes: no sign-in screen (an unlinked session shows session 6's prove-once form, `auth.unlinked` to
  the shell until then); *Sign out* ends the session through the shell; the Screens sheet's new windows
  and loopback checks, which are the LAN's, are hidden. The SPA runs at `/` of its own origin, so no
  base path changes.
- **D10 — The sidecar ships beside the jars, built by a pinned Go** (RISK-3). Gradle downloads a pinned
  Go toolchain by checksum, as the node plugin does Node, and `buildLink` cross-compiles with
  `CGO_ENABLED=0` (pion is pure Go). A Go binary is per OS *and* architecture, so each OS
  `nativePayloadOs` names maps to the builds its installer carries: darwin/arm64 and darwin/amd64 (both
  shipped, the desk starting the one for its machine), windows/amd64 and linux/amd64. The binary goes
  into `jpackage-input` beside `lighting7.jar` and `launcher.jar`, not into the fat jar, since an
  executable cannot run from inside one; `./gradlew run` uses the host's build through
  `-Dlighting7.link`. The native-payload verifier gains the installer input, failing a wrong or missing
  sidecar as it fails a wrong native in the jar. The licence gate reads the modules linked into the
  built binary (`go version -m`), not `go.sum`, which lists test-only and unlinked ones, against
  `licences/go.json`, and lists the Go toolchain's own BSD licence, which every binary carries. The
  installers are not code-signed today (`windows-build.yml` says so of the MSI), so neither is the
  sidecar; signing it is the installers' signing, when that comes (`FU-CLOUD-LINK-SIGNING`).
- **D11 — Supervision is ngrok's, moved** (NFR-SAFE-2). The sidecar runs under `link/LinkProcess.kt`:
  restart with backoff from 1 s doubling to 30 s, reset after 60 s up; a shutdown hook, a PID-file
  orphan sweep and, on Windows, a Job Object, all lifted from `mcp/tunnel/NgrokTunnel.kt` into a shared
  `process/` package before session 9 deletes the tunnel. Its stdout and stderr are read on threads of
  their own (blocking reads, as ngrok's are on `Dispatchers.IO`), stderr drained into the desk's log
  and stdout handed to the `cloud-N` threads as bounded lines; its stdin writes go through a bounded
  queue that never drops a line, since every line is a control message: when it fills, the sidecar is
  taken as hung and restarted, which ends its sessions. So a hung or dead sidecar never blocks the JVM
  or holds the channel's threads. A crash ends its sessions; the shell offers to reconnect.
- **D12 — Links come two ways and go one way** (FR-ID-4). *Invite*: an admin names a user and an email
  on the Users page; the desk sends `link.invite {inviteId, email, userName, invitedByName, expiresAt}`
  and keeps the invite in `cloud_link_invites` (machine-local) until it expires (at most 7 days) or the
  admin cancels it (`link.cancelled`). The install key proves which desk asks, not which person, so the
  cloud mails nothing until an owner or admin of the org approves the invite in the portal, and answers
  `link.refused` if they decline it. The cloud's `link.accepted {inviteId, accountId, email}` writes
  the link (`INVITE`) only if the email is the invite's and the invite is live, and is answered
  `link.ack {inviteId, outcome, reason?}` either way. *Prove once*: an unlinked session (D4) posts the
  local username and password to `POST /api/rest/cloud/link/prove` over the encrypted session, checked
  by `AuthService` under the public listener's lockout, and writes the link (`PROVED`), telling the
  cloud `link.created`; the session then becomes a linked one. *Unlink*, by an admin on the Users page
  or by the user on their profile, and deleting the user, which removes the link with it, each end its
  sessions (D5) and tell the cloud `link.removed`. One cloud account links to one local user on a desk
  and the reverse, held by unique indexes on both `user_id` and `cloud_account_id`. Each link change is
  a row in `cloud_link_events`, machine-local.
- **D13 — Approval on the desk covers remote sessions, not MCP** (D-14, FR-RA-12). With
  `approve_sessions` on, an `rtc.offer` waits for a signed-in admin on the desk's own listener to allow
  it: a `remoteSession.pending` frame to those sockets naming the account's email and display name and,
  where there is one, the local user it links to; *Allow* and *Deny* as REST refused on the public
  listener; a 60 s wait (the cloud waits 75 s for the answer), then `REMOTE_SESSION_DENIED`. MCP calls
  have no person at the desk to ask; they stay under their own switch (D6), the policy switches and the
  grant the account consented to in the cloud, so a desk that wants every way in watched keeps MCP off.
- **D14 — A rate cap per remote socket** (FR-RA-8). `channelState` to a socket on the public listener
  is conflated to `cloud.remote.channelStateHz` (default 20; 0 is off) by merging the latest value of
  each (universe, channel) since the last send, since each frame carries only the channels that
  changed, a merged frame keeping `snapshot` if any frame merged into it had it; and `provenanceState`,
  a whole snapshot each time, kept to its latest frame at half that rate. LAN sockets are never capped.
- **D15 — The MCP relay runs `McpProtocol` as it runs today** (FR-MCP-3, FR-RA-11). `mcp.call {token,
  grant: {id, clientName}, request}` is verified (scope `mcp`, D2), its `sub` mapped through the link,
  and `McpProtocol.handle(request, user)` run on a scope of its own: four at once, four more queued,
  and `DESK_BUSY` beyond, the cloud's eight in flight. `mcp.result {response?}` answers with `replyTo`,
  with no response for a notification. The user's `sessionTokenHash` is `cloud-grant:<gid>`, which
  `historySourceOf` reads as `mcp-grant:` is read today, with the call's `clientName` as the source's
  name, so Claude's own history stays per grant. A call must fit one frame, so the cloud refuses a body
  over 7 MiB before it reaches the desk: `upload_scene_image` through the cloud takes an image of about
  5 MiB, not 16 (its description says so; `FU-CLOUD-MCP-CHUNKING`). A result over the frame limit
  becomes a JSON-RPC error. `render_view` already picks only a LAN window by port; nothing here changes
  that.
- **D16 — Replies are never displaced.** The control channel's outbound queue stays for `health`, which
  may drop; replies, `rtc.*`, `link.*`, `capabilities` and `mcp.result` take a second queue in which no
  newer frame displaces an older one, bounded by count and bytes, whose overflow refuses that one
  message locally (logged, and the cloud's request times out) rather than blocking. The per-frame send
  timeout, three heartbeats today, becomes the longer of that and the frame's size at 128 KiB/s, so an
  8 MiB reply on a slow uplink is not cut off; the cloud counts a desk with a request outstanding as
  heard from meanwhile (its plan's D13).
- **D17 — Caps on what the cloud can start** (P1). At most 8 open remote sessions and 20 offers a
  minute; beyond either, `rtc.close {REMOTE_SESSION_LIMIT}` before any sidecar work or database write.
- **D18 — Deleting ngrok and the OAuth server, what moves first, and what the tables do** (FR-MIG-3,
  -4). Before anything goes, session 9 moves what the deleted classes own: the scripts, effects and
  commands switches into `cloud/RemotePolicy.kt` over `remote_access_settings`' `allow_*` (every
  `require*Access` and `McpProtocol` reading it there), the public listener's sign-in lockout into
  `AuthService`, the unnamed-client label into `history/`, and `SetupTools`' desk link to the cloud's
  shell URL for the install (`<cloud.baseUrl>/r/<installId>/<path>`). Then it deletes `mcp/tunnel/*`,
  `routes/installTunnel.kt`, `tunnel.state`, `McpAuthService`, `McpSignInPage`, `/oauth/*`,
  `/.well-known/*`, `/mcp` on the public listener, the connected-apps routes and UI, the *Remote
  access* tab, `mcp.publicUrl`, `mcp.extraRedirectUris` and `mcp.tunnel.*` (a `local.conf` naming one
  logs a warning), and cookie sign-in on 8414, which then takes bearer tokens only. The tables go
  through the migration seam that now exists (`State.initDatabase`'s numbered steps): step 13 (11 and 12
  went to the cue-number and Own tempo steps first), `state/RetiredRemoteAccessMigration.kt`, drops `mcp_oauth_clients` and `mcp_oauth_grants` and
  `remote_access_settings`' `enabled` and `domain`, backing the database up first when either table has
  rows, and deletes the `ngrok:authtoken` credential and the downloaded agents under
  `<appDataDir>/ngrok`. `oauth_identities` is GitHub sync's and stays for P2. `SyncCoverageTest`'s
  dispositions follow.

## 3. Rules for every session

- **P1 — Cloud down, desk unchanged.** Each session's review asks R6's question of every line it adds,
  and the sidecar's too: does the show run exactly as before with the cloud or the sidecar down, hung
  or hostile?
- **P2 — No real cloud.** Tests use `FakeCloud`, which grows a signing key, `jwks`, `revoke`, `rtc.*`,
  `link.*` and `mcp.*` (R5). The sidecar's tests use a pion peer of their own.
- **P3 — Additive within protocol 1.** Every message, claim, code and fixture is in `cloud-protocol`
  before either side uses it (R3); the desk announces what it speaks as capabilities, `remote.v1` and
  `mcp.v1`.
- **P4 — Remote power does not grow.** Nothing reachable through the sidecar or the relay may do what
  the public listener refuses today (PR-4): no bootstrap of a zero-user desk, no script, effect or
  command without its switch, no adoption, no *Quit desk*, and no change to a switch that limits the
  cloud (D6).

## 4. Risks

- **RISK-1, browser delivery.** P1a's test; D1's fallback.
- **RISK-3, the second binary.** A Go toolchain in the build and a per-OS sidecar in the installers.
  Session 5 measures the installers' growth and records it here; the PRD expects 10–15 MB. *Measured
  2026-11-06, from Linux, with no Mac or Windows host to run `packageMac` or `packageWindows`:* each
  sidecar (`-s -w`) is 10.0–10.6 MB uncompressed and 3.9–4.2 MB at gzip -9, so the MSI should grow by
  about 4 MB compressed (10.5 MB installed) and the Mac pkg, carrying both architectures, by about 8 MB
  (20.6 MB installed). `windows-build.yml` now prints the MSI's size and the sidecar's on every run;
  `FU-MANUAL-CLOUD-P1-S5` records the real installers'.
- **A timing test that flakes.** Session 5's sidecar-crash test measures wall clock, as
  `CloudOutageOutputTimingTest` does; it compares against its own baseline with generous bounds and
  joins `docs/testing-engineering.md`'s table.
- **The token check is security code.** Session 1's vectors include every refusal, and session 2 tests
  the resolver with tokens `FakeCloud` signs, wrong keys and revoked sessions included.

## 5. Implementation — nine sessions

Each session is one PR, run by `/next-session`, in order, titled *Cloud P1: session N — title*. A
session runs the packages it touches, as root `CLAUDE.md` §"Pre-commit checks" says; sessions 1, 2, 3,
5, 6 and 9 change the build or the schema, so they run `./gradlew check shadowJar` (and 9 `npm run
check`) locally.

Sessions 1 to 4 are the desk's half of P1a. Sessions 5 to 9 need the gate, `FU-MANUAL-CLOUD-P1A` passed
and OQ-10 decided, which the owner records in §6. If OQ-10 makes the shell MIT, it moves into this repo
and a session is added here for it.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | The protocol for P1 | merged | `claude/cloud-p1-z6z56z` | [#353](https://github.com/cjcormack/lighting7/pull/353) | `f86d6bb` |
| 2 | Bearer tokens on the public listener | merged | `claude/cloud-p1-s2` | [#355](https://github.com/cjcormack/lighting7/pull/355) | `7deccac` |
| 3 | The sidecar and the broker | merged | `claude/cloud-p1-s3` | [#356](https://github.com/cjcormack/lighting7/pull/356) | `45ab6d1` |
| 4 | The SPA in remote mode | merged | `claude/cloud-p1-htmv50` | [#357](https://github.com/cjcormack/lighting7/pull/357) | `52c68fe` |
| 5 | Bundling and supervising the sidecar | merged | `claude/awesome-lamport-bbwlen` | [#361](https://github.com/cjcormack/lighting7/pull/361) | `bbeac1e` |
| 6 | Linking cloud accounts | merged | `claude/festive-wright-5j9oje` | [#364](https://github.com/cjcormack/lighting7/pull/364) | `0b40974` |
| 7 | Remote sessions on the Cloud tab | merged | `claude/gifted-hopper-w8jn4c` | [#366](https://github.com/cjcormack/lighting7/pull/366) | `a3984d1` |
| 8 | The MCP relay | merged | `claude/cloud-p1-v6rzx8` | [#367](https://github.com/cjcormack/lighting7/pull/367) | `33dd2fe` |
| 9 | Delete ngrok and the desk's OAuth server | merged | `claude/elegant-gauss-7h7ld1` | [#386](https://github.com/cjcormack/lighting7/pull/386) | `06cf83d` |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session` fills
*Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts the
next; whoever merges the last fills its own.

### Session 1 — The protocol for P1

- **Tokens** (D2): `DeskAccessClaims`, `DeskTokens.sign` and `verify` (a result naming the refusal),
  JWK encoding of an Ed25519 public key, and the vectors.
- **Messages** (each with its fixture, `GoldenFixturesTest` demanding them): `welcome` gains an
  optional `remoteOrigin`; `capabilities {list}` (and `hello`'s list) with D6's state entries; `jwks`;
  `revoke {at, sid? | accountId? | all}` (`at` the cloud's time of the revocation); `rtc.offer {sid,
  accountId, email, displayName, sdp, iceServers}`, `rtc.answer {sid, sdp}` (a reply), `rtc.ice {sid,
  candidate?}` (null ends the candidates) and `rtc.close {sid, reason}`, both ways, which is also the
  offer's reply when the desk refuses it; `link.invite`, `link.cancelled`, `link.refused`,
  `link.accepted`, `link.ack` (a reply), `link.created`, `link.removed`; `mcp.call` and `mcp.result
  {response?}` (a reply). The link and MCP messages are defined now so the cloud builds against one
  protocol commit, and used by sessions 6 and 8.
- **Replies**: `docs/cloud-protocol.md` says how `replyTo` answers a request (the request's `id`),
  which messages expect one (an `rtc.offer` is answered by `rtc.answer` or `rtc.close`), that an
  unanswered request is the asker's to time out, and `rtc.offer`'s 75 s.
- **Codes**: `CLOUD_ACCOUNT_NOT_LINKED`, `REMOTE_ACCESS_OFF`, `REMOTE_SESSION_DENIED`,
  `REMOTE_SESSION_LIMIT`, `MCP_ACCESS_OFF`, `SIDECAR_UNAVAILABLE`, `DESK_OFFLINE`, `DESK_DISCONNECTED`,
  `DESK_TIMEOUT`, `DESK_BUSY`, `DESK_REMOTE_UNSUPPORTED`, `DESK_MCP_UNSUPPORTED`, `REQUEST_TOO_LARGE`,
  the token refusals; the capabilities `remote.v1`, `mcp.v1`, `remote.off`, `mcp.off` and
  `remote.no-sidecar`.
- **Contracts** (D8): the framing, `ctl` and the remote-frame messages, specified in
  `docs/cloud-protocol.md`, with fixtures under `link/` and `remote-frame/`.
- **Tests:** `./gradlew :cloud-protocol:check` and `./gradlew check shadowJar`; token vectors computed
  with another Ed25519 implementation (Python's `cryptography`, as P0's signing vectors were).
- **Docs:** `docs/cloud-protocol.md` for all of the above.
- **As built:** the fixtures sit under `protocol/` with the rest (`protocol/tokens/`, `protocol/link/`,
  `protocol/remote-frame/`), each message's named by its type (`rtc.offer.json`). Beyond the codes
  above it defines `SESSION_ENDED` (an `rtc.close` for no other reason) and `link.ack`'s reasons,
  `LINK_EMAIL_MISMATCH`, `LINK_INVITE_NOT_LIVE` and `LINK_TAKEN`, so session 6 adds no protocol, and the
  desk's own refusals sessions 2 and 8 need: `TOKEN_REVOKED`, `REMOTE_SESSION_UNKNOWN`,
  `REMOTE_SESSION_MISMATCH` and `MCP_GRANT_MISMATCH` (an `mcp.call` whose `grant.id` is not its token's
  `gid`). A JWKS entry is a full RFC 8037 JWK, `{kty, crv, kid, x}`, every field optional on the wire, so
  a key of another type is skipped rather than failing the frame. A token must say `typ: JWT`. A
  `revoke` naming several targets revokes each, and covers a token whose `iat` is at or before `at`'s
  whole second (D5's comparison, made exact). A refused `mcp.call` is answered by `error` with
  `replyTo`, its code the refusal's. The sidecar strips `LinkFraming.STRIPPED_HEADERS` from every head
  before setting its own `Authorization`, `Lighting7-Link` and `Origin` (D4, D7).

### Session 2 — Bearer tokens on the public listener

- `CloudChannel` dispatches `jwks` and `revoke`, sends `capabilities` on a change, and gains D16's
  reply queue; `cloud/JwksCache.kt` (D3); the bearer arm of `resolveSessionUser` on the public listener
  and the unlinked session (D4); `cloud/RemoteSessions.kt` with its open (an API the broker calls in
  session 3, tests here), refresh route, expiry, revocation and deny set (D5), joined to
  `AuthService`'s revocations for a disabled or deleted user and a removed link; the Origin check
  allowing `remoteOrigin`; `cloud_remote_sessions` with its disposition and the startup close;
  `remote_access`, `mcp_access` and `approve_sessions` on `cloud_connection` and a desk-listener-only
  admin route for the first two (D6; the Cloud tab's switches are session 7's), the `Lighting7-Link`
  secret held by the registry for the sidecar session 3 brings, and the policy switches' route refused
  on the public listener too.
- `FakeCloud` gains a signing key, sends `jwks` after `welcome`, mints tokens for tests, and sends
  `revoke`.
- **Tests:** a linked token reads as its user on 8414 through the gate, the WebSocket upgrade and
  `auth/status`, and is ignored on 8413 and refused without the `Lighting7-Link` secret; a token whose
  `sid` the registry never opened refused, and a token or refresh for a recorded `sid` naming another
  `sub` or `org` refused and the session ended; unlinked (only `auth/status`, static files, prove and
  its own refresh open, and it outlives its first token), expired, an `iat` in the future, wrong `aud`,
  wrong `kid`, wrong scope, a token after a JWKS rotation; a WebSocket with a bearer closed 4401 on
  each kind of revoke within 5 s, on a link deleted, a user disabled, a password changed and *sign out
  everywhere*, and when its token lapses unrefreshed, a still-valid token for a revoked `sid` refused
  afterwards, and a token minted a moment before a `revoke {accountId}` refused by its `at` whatever
  the desk's clock says; a demoted admin's next request read as an operator; the switch off refusing
  everything and refused itself on the public listener; the policy switches still refusing scripts,
  effects and commands; a bearer request with a foreign `Origin` refused and one with `remoteOrigin`
  allowed; one audit row per session, written at open and close only, and an open row closed at
  startup; `capabilities` sent with `remote.off` when the switch goes off and without it when it goes
  on; a reply kept while `health` frames drop, and a reply just under the frame limit sent at a slow
  rate not cut off; `SyncCoverageTest`. `./gradlew test --tests` for `cloud`, `mcp`, `auth`, `plugins`,
  `routes` and `sync`.
- **Docs:** `docs/cloud-engineering.md` §"Desk access tokens" and §"Remote sessions";
  `docs/desk-accounts.md` §"Lighting7 Cloud" gains what a link now authorises; `FU-AUTH-AUDIT-LOG`'s
  row notes its remote slice.
- **As built:** `rtc.offer` names no org, so a session's org is the adoption's `org_id`, recorded at
  `RemoteSessions.open`. One resolver, `resolveCaller` (`auth/AuthGate.kt`), answers the gate, the
  upgrade and `auth/status` once per call, and replaces `resolveSessionUser`. A bearer request
  without `Lighting7-Link`, or with the desk not adopted through an enabled cloud, is 401
  `unauthenticated`, as no cookie is; an unlinked session's socket closes 4401 with
  `CLOUD_ACCOUNT_NOT_LINKED`, so the SPA re-reads `auth/status`. Besides the refresh, `DELETE
  /cloud/session` and `POST /auth/logout` end the caller's own session; a refresh may not expire before
  a token already used (the same token again is a retry, not a mismatch), and both are exempt from the
  warm-up gate, as `/auth/` is. A sid the audit has ever held is refused, so a reused one cannot open
  after a restart. The deny set keeps one `revoke` per account and one for everything (the latest
  `at`), and past 4096 sids or accounts treats a further one as a revoke of everything. A clean stop
  ends open sessions as `DESK_STOPPED`. `remoteOrigin` is allowed only on a bearer request, and kept
  only when it is a bare origin `cloud.baseUrl` would accept (`https`, or `http` to a loopback host).
  *Sign out everywhere else* spares the session that asked, through
  `AuthService.addSessionRevocationListener`. `GET /install/cloud/remote` answers all three switches;
  the `PUT` sets `remoteAccess` and `mcpAccess` only, approval being session 7's with its wait. Until
  session 3 no sidecar runs, so `remote.no-sidecar` is announced from here, beside `remote.off` and
  `mcp.off`. The reply queue holds 256 frames and 16 MiB.

### Session 3 — The sidecar and the broker

- `link/` (D7, D8): the Go module on pion, the stdio protocol, `ctl` and its `ready`, the bridge to
  HTTP and WebSocket on the public listener with chunking, backpressure and the request's origin
  forwarded, per-session limits on open channels and a hard limit on sessions, and its `session.state`
  reporting the selected candidate pair's type (`DIRECT` or `RELAY`).
- `cloud/RemoteBroker.kt`: `rtc.offer` from the channel checked against D17's caps, then opened in the
  registry and the sidecar, its account and org recorded (refused with `rtc.close`, as the offer's
  reply, when the switch is off or no sidecar runs), ICE both ways, `rtc.close` both ways, the audit
  row's `path` kept for its close. Replies use `replyTo`.
- A development launch only: `cloud.link.binary` in `local.conf` names a binary built by hand (`cd
  link && go build ./cmd/lighting7-link`), started and stopped with the channel, its pipes read as D11 says;
  session 5 bundles and supervises it. The desk announces `remote.v1`, and `remote.no-sidecar` while
  the binary is not running (D6).
- CI: a *Link* job running `go vet`, `go test` and a build on Linux, with Go pinned as session 5's
  toolchain will be. `licences/go.json` and the gate's Go arm (D10) land here, since the module's
  dependencies do.
- **Tests:** Go: a pion peer against the sidecar and an HTTP and WebSocket test server, through the
  framing and `ctl` fixtures, a 100 MB body streamed in both directions with bounded memory, a peer
  that stops reading, `Cookie` and `Authorization` dropped, the session limit. Kotlin: the broker
  against a fake sidecar process (a script speaking the stdio protocol), against one that hangs, dies,
  floods stdout and floods stderr, and against an offer flood beyond D17; a full stdin queue restarting
  the sidecar rather than dropping a line; no sidecar started and no `remote.v1` without
  `remote.no-sidecar` while the public listener is off or failed to bind; the licence gate over a built
  binary's modules.
- **Docs:** `docs/cloud-engineering.md` §"The sidecar"; `docs/licensing.md` §"Go modules".
- **As built:** the build is `cd link && go build ./cmd/lighting7-link`, not `go build
  ./link/cmd/lighting7-link` as this section first said: `link/` is its own module and the root has no
  `go.mod`. The sidecar's first stdout line is `started {build}`, once it has read its secret: the
  desk counts it as running from then, not from the process starting. The stdio protocol is the desk's
  own (both ends are here), so it is not in `cloud-protocol`; `link/testdata/stdio.json` is its fixture,
  read by the Go tests and `LinkMessagesTest`. The desk's end is `link/LinkSidecar.kt` beside
  `link/LinkMessages.kt`, which session 5's `LinkProcess` supervises; a stop sends the sidecar SIGTERM
  from a thread of its own, since a write blocked on its full stdin holds the pipe's lock. The broker
  also refuses an offer the sidecar could not read (`MALFORMED`: an SDP over 64 KiB, a sid over 128
  characters, a `session.open` line over 128 KiB) and drops a cloud candidate over 4 KiB, and a session's end gains three
  reasons, `CLOSED_BY_CLOUD`, `LINK_CLOSED` and `SIDECAR_STOPPED` (told to the cloud as
  `SIDECAR_UNAVAILABLE`). Beside `STRIPPED_HEADERS` the sidecar drops a request's `content-length` (set
  from the head's) and `accept-encoding`, since the service worker builds a `Response` from the bytes and
  a `Response` never decodes a body, and an answer's hop-by-hop headers and `set-cookie`. It never sends
  first on a channel, so a channel past its limit is reset in answer to its first frame, and its own
  candidates follow its answer. Its module is
  `github.com/cjcormack/lighting7/link`, on pion v4 and `coder/websocket` (ISC); Go is pinned by
  `link/go.mod`'s `go 1.24.7`, which CI reads with `GOTOOLCHAIN=local`, so session 5's toolchain should
  read the same line. The *Link* job is not yet a required check: the owner adds it to branch
  protection. Measured on the session's container: a static linux/amd64 build is 16 MB, and each 100 MB
  stream takes 2 to 5 s over loopback with the heap growing by 15 to 25 MiB.

### Session 4 — The SPA in remote mode

- D9: the remote-parent meta on the public listener, `frame-ancestors 'self'` on the desk's own
  listener, the boot entry deciding the mode before `./main` loads, its `hello` and wait for the `port`
  when both hold, `api/remoteSocket.ts` beside `internalApi.ts`, the connection chosen by mode, the
  unlinked state in remote mode, *Sign out* through the shell, the hidden LAN-only controls. Remote
  mode must change nothing on the LAN: a page without the meta never waits.
- **Tests:** Vitest for `RemoteSocket` against the remote-frame fixtures (open, frames in and out, a
  4401 close, the port closing), for the mode decision (no frame, no meta, a parent that never sends
  the `port`, a `port` from another origin, and `./main` not loaded before the decision), for the
  worker's `port.request` answered with a fresh port, and for each hidden control; a backend test that
  the meta is on the public listener's `index.html` only and the header on the desk's own; `npm run
  check:quick`.
- **Docs:** `frontend/CLAUDE.md` gains a line on remote mode; `docs/cloud-engineering.md` §"Remote
  mode".
- **The gate's check:** `FU-MANUAL-CLOUD-P1A` (§6) added to `docs/plans/manual-validation.md`.
- **As built:** the boot entry is `src/boot.ts`, its decision `src/remoteBoot.ts`, giving the shell
  10 s to answer `hello`; the mode and the port live in `lib/remoteMode.ts`, which `lightingApi` reads.
  The meta goes into `index.html` on every path the index answers, and only while `cloud.baseUrl` is
  configured and `cloud.enabled` is on (`routes/spaPage.kt`); `frame-ancestors 'self'` is on every
  bundle file on the desk's own listener, not on API answers. `docs/cloud-protocol.md` now says how a
  socket on the port begins and ends, which the contract left open: `open` replaces any socket the shell
  holds, the SPA's `close` is not answered, and a shell `close` before `open` means the socket did not
  open; and the port outlives its sockets. A port the SPA takes as gone — its `close` event, which only
  Chromium fires, or an `open` unanswered for 15 s — reads as a 1006 close, and the SPA replaces it with
  another `hello`, whose port supersedes the old one. Signed out in remote mode the SPA says *Remote session ended* rather than offering a form, and
  *Log out* keeps its label. Beside the new-window section and its localhost note, the Screens sheet's
  per-row *Copy link*, a cue's *Copy link* and ⌘K's *Open … on another display* are hidden too: each
  names this origin or opens a window outside the shell. The Vitest
  files read the remote-frame fixture through `src/test/remoteFrameFakes.ts`, over fake ports, since
  jsdom has no `MessagePort`.

### Session 5 — Bundling and supervising the sidecar

**Needs:** operator: `FU-MANUAL-CLOUD-P1A` passed and OQ-10 decided (§6)

- D10: the pinned Go toolchain, `buildLink` per OS and architecture, the binary in `jpackage-input`,
  the verifier's new arm, `-Dlighting7.link` for `run` and tests; CI's *Link* job building every OS's
  binaries, and `windows-build.yml` taking the sidecar into the MSI.
- D11: `process/` lifted from the tunnel, `link/LinkProcess.kt`, the sidecar started with the cloud
  channel while `remote_access` is on and stopped when it goes off or the desk unadopts,
  `remote.no-sidecar` sent as it stops and cleared as it starts, its status in `cloud.state` (`link:
  OFF | STARTING | RUNNING | RESTARTING`).
- **The safety test** (NFR-SAFE-2): beside `CloudOutageOutputTimingTest`, a fade's frames timed with no
  sidecar, then while the sidecar is killed mid-fade, hangs on stdin, floods stdout and floods stderr;
  every frame sent, the fade's end and p99 gap within the baseline's plus a margin.
- **Tests besides:** restart backoff under virtual time; an orphan from a killed desk swept at the next
  start; the verifier failing a jar built for one OS with the sidecar of another. `./gradlew check
  shadowJar`; `packageMac` on a Mac or `-PnativePayloadOs=windows` as root `CLAUDE.md` describes, with
  the installers' growth recorded in §4.
- **Docs:** `docs/cloud-engineering.md` §"The sidecar" (bundling, supervision); root `CLAUDE.md`'s note
  on `lighting7.jar` gains the sidecar beside it; `docs/testing-engineering.md`'s wall-clock table.
- **As built:** Go comes from the Go module proxy, as `GOTOOLCHAIN` fetches it (the zip
  `golang.org/toolchain@v0.0.1-go1.24.7.<os>-<arch>`, resolved by Gradle through an exclusive ivy
  repository and checked against `goToolchainSha256`), pinned for five hosts: the four the installers
  are built on or for and linux/arm64, for `run` and the tests; the build refuses to configure when
  `link/go.mod`'s `go` line is not the pinned version. The binaries are built `-ldflags=-s -w`, a third
  smaller, `go version -m` still reading their modules. The verifier reads the staged jar's natives to
  say which OS it is for and each sidecar's header for its OS and architecture; it is a build-script
  function, so its test is `verifyJpackageInputSelfTest` in `check`, over fixture headers.
  `cloud.link.binary` stays, as an override of the installed sidecar, beside `-Dlighting7.link`
  (`link/LinkBinary.kt`). `LinkSidecar` no longer restarts a hung sidecar itself: it reports each
  launch's end and `LinkProcess` starts the next after the backoff, so a hung one is replaced after 1 s
  rather than at once. The sidecar also needs *Remote sessions* on, which the section's D11 line says
  and session 3's launch did not check. The PID file records the process's start time beside its PID and
  binary, and the sweep leaves a process started at another time alone; it runs once, in
  `CloudService.start` before anything launches, not at each supervisor's first launch, where it would
  catch the previous supervisor's own sidecar still stopping, under the cloud's locks; a launch's exit
  deletes the file only while it still names that launch; ngrok's sweep gets the same, and
  reads its older two-line files as before. The safety test, `LinkSidecarSafetyTest`, kills and hangs the
  real sidecar (hung by SIGSTOP, so on POSIX only, its stdin then filled until the desk takes it as hung)
  and floods through `FakeSidecar`; it and `CloudOutageOutputTimingTest` share `testsupport/FadeTiming.kt`.
  The *Link* job builds and gates all four targets; the Backend job now downloads Go to build the host's
  sidecar for the tests. `cloud.state` carries `link`, and the frontend's type with it; no UI shows it
  before session 7. Not run here: `packageMac` and `packageWindows`, for want of either host.

### Session 6 — Linking cloud accounts

**Needs:** operator: `FU-MANUAL-CLOUD-P1A` passed and OQ-10 decided (§6)

- D12: `cloud_link_invites` and `cloud_link_events` with their dispositions; a unique index on
  `cloud_identity_links.cloud_account_id`; `linked_via` gains `INVITE` and `PROVED`; the channel's
  `link.*` handling with its acknowledgements; the prove-once route under the lockout, turning an
  unlinked session into a linked one; unlink from either side; every change ending the account's
  sessions where it removes access.
- **The UI:** Users gains a *Cloud account* column, *Invite a cloud account…* in a user's sheet (an
  admin, either listener, the sheet saying an owner or admin of the org approves it in the portal),
  *Cancel invite* and *Unlink*; the profile shows the user's own link with *Unlink*; remote mode's
  unlinked state shows the prove-once form.
- **Tests:** invite accepted, declined in the portal (`link.refused`), expired and cancelled; an
  acceptance whose email differs, and one for a cancelled invite, each answered `link.ack` with the
  reason; an acceptance heard after a reconnect; prove-once right, wrong and locked out; a second
  account for a linked user refused, and the reverse; unlink and a user's deletion each closing
  sessions and sending `link.removed`; each event row; Vitest for the column, the sheet and the form.
  `./gradlew test --tests` for `cloud`, `auth`, `routes`, `sync`; `npm run check:quick`.
- **Docs:** `docs/desk-accounts.md` §"Lighting7 Cloud" (links: the three ways, what each audits);
  `docs/cloud-engineering.md` §"Links".
- **As built:** `cloud/CloudLinks.kt` owns every link change but adoption's, and `cloud_link_events` rows
  carry the user's name beside its id, so a row says who after the user is deleted; both tables keep a
  year, pruned at startup. The routes are `POST` / `DELETE /users/{id}/cloud-invite` and `DELETE
  /users/{id}/cloud-link` for an admin, and `GET` / `DELETE /cloud/link` for the caller's own, beside
  `POST /cloud/link/prove`, which is also exempt from the warm-up gate. An invite needs the channel online
  (409 `CLOUD_OFFLINE`, nothing kept) and is one per user and per email, since the cloud keeps one live
  invite per address; `link.invite`'s `userName` is the desk username. `cloud_identity_links` gains
  `invite_id`, so an acceptance the cloud sends again after a lost `link.ack` is answered linked rather
  than `LINK_INVITE_NOT_LIVE`. An email mismatch keeps the invite; `LINK_TAKEN` deletes it. A link the
  account gains while one of its sessions is open, by proof or by invite, turns that unlinked session into
  the user's (`RemoteSessions.linked`), and the audit row names the user it gained at its close.
  `link.cancelled`, `link.removed` and `link.created` sent while the channel is down wait in memory (at
  most 256) for the next connection. A wrong password on prove is 401, as the sign-in form's is, which the
  SPA treats as no session loss; a disabled user is 403. Proving cancels the user's live invite. Unadopting
  records each link and invite it deletes as `UNADOPTED`. The Users column is drawn once the desk is
  adopted, or while any user is still linked. Every link and invite write re-reads the adoption and the
  user in its own transaction, an invite waits behind any link message still queued, and
  `RemoteSessions.open` reads the link again once the session is registered, so a link change racing a
  session's opening is never missed. An invite for an email another user's link carries is sent and
  refused only at its acceptance (`LINK_TAKEN`), since a link's email can be stale after an email change
  in the cloud (decided 2026-11-06).

### Session 7 — Remote sessions on the Cloud tab

**Needs:** operator: `FU-MANUAL-CLOUD-P1A` passed and OQ-10 decided (§6)

- The Cloud tab's *Remote access* section, editable only on the desk's own listener and read-only with
  the reason on the public one (D6): *Remote sessions* and *Claude (MCP)*, each switch with what
  turning it on allows, *Approve each new session on this desk* (D13), the scripts, effects and
  commands switches moved here from the Remote access tab (the tab keeps only ngrok's fields until
  session 9), the sidecar's status, and the open and recent sessions from `cloud_remote_sessions` with
  *End* on an open one.
- D13: `remoteSession.pending` to admin sockets on the desk's own listener, *Allow* and *Deny*, the
  wait, and a toast on every such window. D14: the conflation on public-listener sockets.
- **Tests:** a pending session allowed, denied and timed out; *Allow* refused on the public listener;
  the conflation under virtual time sending every channel that changed, at the rate, a snapshot frame
  kept a snapshot through the merge, `provenanceState` sent as its latest frame, and a LAN socket
  uncapped; Vitest for each state of the section on each listener. `./gradlew test --tests` for
  `cloud`, `plugins`, `routes`; `npm run check:quick`.
- **Docs:** `docs/cloud-engineering.md` §"The Cloud tab"; `docs/websocket-engineering.md` gains
  `remoteSession.pending` and the cap; `frontend/CLAUDE.md`'s note on the Remote access tab's switches.
- **As built:** the six switches share `PUT /install/cloud/remote`, the scripts, effects and commands
  three needing no adoption, since they hold for ngrok too; an offer waiting for approval writes nothing,
  counts against D17's cap of 8 and keeps the browser's candidates for its open, and a denied or
  timed-out one leaves no audit row; *Allow* reads the link again and, when the desk user it links to
  has changed since it was shown, answers 409 and shows it afresh; a dropped control channel or a
  `revoke` ends the wait; `cloud.state` carries the three cloud switches so every window follows them; turning approval off leaves what waits to be answered; *End*
  (`ENDED_BY_ADMIN`) is allowed from either listener, as unadopt is, since it only reduces reach; and
  the session list is re-read every 5 s while the tab is open rather than announced by a frame.

### Session 8 — The MCP relay

**Needs:** operator: `FU-MANUAL-CLOUD-P1A` passed and OQ-10 decided (§6)

- D15: `mcp.call` in the channel, `cloud/McpRelay.kt`, the scope, its queue and `DESK_BUSY`,
  `cloud-grant:` read by `historySourceOf` and `HistorySource.mcp` with the call's client name,
  notifications answered with no response, the frame-limit error, `upload_scene_image`'s description
  stating the relayed limit; `mcp_access` off (D6) answering `MCP_ACCESS_OFF` and announcing `mcp.off`;
  `mcp.v1` announced from here on.
- `FakeCloud` relays a call. The desk's own `/mcp` stays until session 9.
- **Tests:** `tools/list` and a tool call through `FakeCloud` as the linked user, an operator's call
  refused where `venueToolRefusal` refuses it, `run_fixture_command` held to its switch, an unlinked
  account's call answering `CLOUD_ACCOUNT_NOT_LINKED`, a remote-scope token refused, `list_history`
  keeping two grants apart, `notifications/initialized` answered with no response, nine calls at once
  (the ninth `DESK_BUSY`), a 9 MB result, and a show unaffected by a flood of calls (the tool work is
  the show's own, as today, but the channel's threads never run it). `./gradlew test --tests` for
  `cloud`, `mcp`, `ai`, `history`.
- **Docs:** `docs/mcp-engineering.md` §"Through the cloud".
- **As built:** the channel's thread judges only the switch and the queue; the token, the link and the
  tool run on `Dispatchers.IO` (`cloud/McpRelay.kt`), and the desk keeps one `McpProtocol`
  (`State.mcpProtocol`) for `/mcp` and the relay. Beyond D15's checks a token's `org` must be the
  adoption's (`TOKEN_WRONG_AUDIENCE`), and a `revoke` naming its account or everything refuses it
  through the remote sessions' deny set (`TOKEN_REVOKED`); a `revoke {sid}` has nothing to name here. A
  disabled desk user's call is answered with a JSON-RPC error (`-32001`) rather than a new code. The
  call's `clientName`, cut to 64 characters, rides on `AuthenticatedUser.clientName`, and the source is
  `mcp:cloud:<gid>`, apart from a desk grant's `mcp:<id>`. `FU-CLOUD-MCP-CHUNKING` is in
  `docs/plans/followups.md`. The flood test is `McpRelayLimitsTest`, beside the other wall-clock tests in
  `docs/testing-engineering.md`.

### Session 9 — Delete ngrok and the desk's OAuth server

**Needs:** operator: `FU-MANUAL-CLOUD-P1A` passed and OQ-10 decided (§6); cjcormack/lighting7-cloud docs/plans/p1-plan.md session 4 merged; operator: a Claude connector reaches this desk through the cloud (UC-3, §6)

- D18, all of it, in its order (moves first), and the *Remote access* tab removed (its switches are on
  the Cloud tab since session 7); `REMOTE_NEEDS_ACCOUNT` and the rest of `installRemoteHardening` stay,
  less the OAuth exemption.
- **Tests:** step 13 over a frozen database at step 12 holding grants, clients, a tunnel row and the
  authtoken (`src/test/resources/db/pre-retired-remote-access.sql`, never regenerated), and over one
  holding none; the policy switches and the lockout working from their new homes; a cookie on 8414
  refused; the deleted routes 404; `SyncCoverageTest`. `./gradlew check shadowJar` and `npm run check`.
- **Docs:** `docs/mcp-engineering.md` rewritten around the relay; `docs/desk-accounts.md` §"Connected
  apps (MCP)" points at the portal; root `CLAUDE.md`'s *MCP and Remote access* and its remote-access
  endpoints, `example.local.conf`; the glossary's remote rows; `frontend/CLAUDE.md` §"Remote access";
  `docs/websocket-engineering.md`'s `tunnel.state` rows; `docs/desk-accounts.md`'s Remote access and
  public-listener sign-in passages; the tunnel steps in `docs/plans/manual-validation.md`.
  `FU-MANUAL-CLOUD-P1` (§6) added to `docs/plans/manual-validation.md`.

  As built: the step is 13, its frozen database at 12. On the public listener `POST /auth/login` is 403
  `REMOTE_PASSWORD_SIGN_IN` and `resolveCaller` reads no cookie, and `/mcp`, `/oauth/…` and `/.well-known/…`
  answer 404 there rather than the SPA's page; only `/api/` is traced now. A desk grant's `mcp-grant:`
  history source went with the grants, so `HistorySource.mcp` names cloud grants alone. `SetupTools`' desk
  link is the shell's only while *Remote sessions* is on (the owner's call, 2026-11-07), else the desk's own
  `/prompt-book`. Step 13 stops an ngrok agent the old PID file names before deleting it. The leftovers
  outside the database (the authtoken, `<appDataDir>/ngrok`) go once the credential store exists, and only
  when step 13 met the tunnel's schema, so a fresh database never opens the store for them. The tests sign
  in on 8414 as remote sessions a `FakeCloud` adoption signs (`testsupport/RemoteDesk.kt`). The glossary
  had no remote rows to change. `TokenScrubber` keeps its ngrok patterns, for logs written before.

## 6. The gate and the operator checks

**`FU-MANUAL-CLOUD-P1A`**, the gate, run by the owner once sessions 1 to 4 here and the cloud plan's
sessions 1 and 2 are merged and deployed, with a desk running a hand-built sidecar (session 3) and
remote sessions turned on through session 2's admin route (the Cloud tab's switches are session 7's):

1. On desktop Chrome, Firefox and Safari and on iOS Safari, *Open* the desk from the portal: the SPA
   loads and is usable through the shell, its socket and REST both (busk a look, the Stage view, a cue
   GO), and the bar says *Direct*.
2. Twenty opens on each browser, timed to an interactive desk with cached assets, with the p50 and p95
   recorded against NFR-PERF-1 (under 3 s and 8 s); the round-trip time the bar shows on a direct path,
   against a ping between the same two machines (under 30 ms more).
3. A 100 MB prompt-book PDF uploads and opens.
4. With the shell's *relay only*, then on a network that blocks UDP, the bar says *Relayed*, the second
   time over TURN on 443, on every target browser.
5. The session outlives two token refreshes (25 minutes), and *End* in the portal closes it within 5 s;
   a reload after *End* does not get back in.
6. Ten minutes of fades and FX relayed, with the relay's bytes recorded.
7. A second visit loads in under 2 s from the asset cache (FR-RA-7).

When it passes, the owner records here: the date, what was measured, and OQ-10's answer (and OQ-11's,
if the spike settles it). **Passed 2026-11-06**, the owner reports, with the performance checks (steps 2
and 7) deferred: they stay open under `FU-MANUAL-CLOUD-P1A`. **OQ-10:** the shell stays in
`cjcormack/lighting7-cloud`, so no session is added here for it. OQ-11 is not settled by the spike.

**UC-3 through the cloud**, the operator item session 9 needs: once the cloud plan's session 4 is
deployed, a Claude connector added with the desk's cloud URL drives this desk. The owner records the
date here. **Seen 2026-11-07**, the owner reports.

**`FU-MANUAL-CLOUD-P1`**, the PRD's exit, added by session 9 and run once both plans are merged and
deployed:

1. **UC-2.** From a phone on 4G, open the venue desk from the portal and busk a look.
2. **UC-3.** Add the desk to Claude with its cloud URL and no tunnel, and ask Claude to patch a rig.
3. **UC-6.** Invite an operator by email from Users and approve the invite in the portal; they accept
   with their own cloud account and land on the desk as `OPERATOR`, unable to do what an operator
   cannot.
4. **Revocation.** Unlinking, disabling the user, removing their membership in the portal and ending
   the session each close an open remote session within 5 s.
5. **Off.** *Remote sessions* off refuses a new session and closes an open one; *Claude (MCP)* off
   refuses an MCP call while sessions still work; neither switch can be changed from a remote session.
6. **Gone.** The Remote access tab, `/oauth/*`, `/mcp` and the tunnel are absent, and a desk upgraded
   from a pre-P1 build keeps its policy switches and loses its grants.
7. **The show.** During a running fade, killing the sidecar and stopping the cloud's app change nothing
   visible on the rig.

## 7. Explicitly out of scope

- Hosted git, `CloudCredentialSource` and the GitHub migration, and with them `oauth_identities` (P2).
- Health telemetry, backups and updates from the cloud (P3, P4).
- "Sign in with Lighting7 Cloud" on the LAN (FR-ID-8, P5) and the account-level MCP endpoint (D-12).
- A WebSocket relay of last resort (OQ-11), unless P1a decides it is needed.
- Relaying an MCP call larger than one frame (`FU-CLOUD-MCP-CHUNKING`) and signing the sidecar apart
  from the installers (`FU-CLOUD-LINK-SIGNING`).
- Every cloud-side requirement: the shell, signalling, TURN, invites, the OAuth server (the cloud's P1
  plan).

## 8. Follow-ups

Recorded in `docs/plans/followups.md` by the session that finds them: `FU-CLOUD-LINK-SIGNING` (D10,
session 5); `FU-CLOUD-MCP-CHUNKING` (D15, session 8); `FU-AUTH-AUDIT-LOG` narrowed to its LAN slice
(session 2); `FU-MANUAL-CLOUD-P1A` (session 4) and `FU-MANUAL-CLOUD-P1` (session 9), §6.
