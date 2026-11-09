# Lighting7 Cloud on the desk

Lighting7 Cloud is a hosted service that desks connect out to. This doc is the desk's side: what it
holds, how it is adopted and unadopted, the control channel it keeps open while adopted, how a remote
session through it is authorised, and the rules that keep the cloud off the show's path. The wire
contract is [cloud-protocol.md](cloud-protocol.md); the plans are
[`plans/completed/cloud-p0-plan.md`](plans/completed/cloud-p0-plan.md), whose decisions (D1, D2, …)
this doc cites bare, and [`plans/completed/cloud-p1-plan.md`](plans/completed/cloud-p1-plan.md), cited as P1's D-items. The code is `src/main/kotlin/uk/me/cormack/lighting7/cloud/`,
`…/link/` for the desk's end of the sidecar, and `link/` (Go) for the sidecar itself.

The desk works with no cloud, no account and through any outage. Nothing here is needed to run a
show, and nothing here may slow one down (§"Show safety").

## What the desk holds

| What | Where | Lifetime |
|---|---|---|
| The **install key**'s private half | `CredentialStore` blob `cloud:install-key:<installs.uuid>`, PKCS#8 base64 (`InstallKeyStore`) | made when adoption starts, deleted when the desk stops being adopted |
| Its adoption | `cloud_connection`, one row (`models/cloudConnection.kt`) | while adopting or adopted |
| Each desk account's cloud account | `cloud_identity_links`, one row per desk user and per cloud account (`models/cloudIdentityLinks.kt`) | written by adoption, an accepted invite or a proof; deleted by an unlink, unadopt or the user's deletion (P1 D12) |
| Invites to link a cloud account | `cloud_link_invites` (`models/cloudLinkInvites.kt`) | until accepted, declined, cancelled or seven days old |
| The audit of links and invites | `cloud_link_events` (`models/cloudLinkEvents.kt`) | a row per change, in its transaction; a year |
| What the cloud is to hear of links while the channel is down | memory only (`CloudLinks`), at most 256 messages | until the next connection, unadopt or a restart |
| What is happening now: a poll's error, a notice, the channel's status | `CloudService`'s memory only | until the next transition or a restart |
| What may come through the cloud: *Remote sessions*, *Claude (MCP)*, *Approve each session* | `cloud_connection`'s `remote_access`, `mcp_access`, `approve_sessions`, held in memory as `CloudService.switches` | off at adoption; changed only on the desk's own listener (P1 D6) |
| Offers waiting for an admin at the desk, and the browser's candidates for each | memory only (`RemoteBroker`) | until allowed, denied, closed or 60 s old; nothing is written until allowed (P1 D13) |
| The cloud's token keys and the origin it serves remote sessions from | memory only (`JwksCache`, `CloudService.remoteOrigin`) | the last `jwks` and `welcome` heard, through any reconnect, until a newer one or a restart (P1 D3) |
| Open remote sessions, and those ended or revoked | memory only (`RemoteSessions`) | until each ends, then until any token issued for it has expired |
| The sidecar's launch, its link secret and the offers still to be answered | memory only (`LinkSidecar`, `RemoteSessions`, `RemoteBroker`) | one launch; a new secret each time it starts |
| The running sidecar's PID, binary and start time | `<appDataDir>/link/lighting7-link.pid` (`process/PidFile.kt`) | while it runs, so the next start can stop one a killed desk left behind |
| The audit of remote sessions | `cloud_remote_sessions` (`models/cloudRemoteSessions.kt`) | a row at a session's open and end, never between; a year |

Every table is `SyncDisposition.MachineLocal` and never touches `ProjectExporter` or `ProjectImporter`
(design record R7). The key never leaves the credential store: it is not exported, synced or logged, and
a desk never holds two (D4). The blob's name carries the install's uuid because the OS keychain is per
user, not per install: a second desk on the same machine, with its own data directory, never finds or
replaces this one's. `cloud_connection` keeps the key's public half and fingerprint for display. What the
cloud sends is stored as `text`, since the protocol bounds none of it.

A **link** says which cloud account a desk account is (§"Links"). A remote session reads its account's
link when it opens and acts as that user (§"Remote sessions"). SQLite runs with `PRAGMA foreign_keys`
off, so `AuthService.deleteUser` deletes the user's link and invites (`deleteCloudLinksOf`) and clears
`cloud_connection.adopted_by`, `cloud_remote_sessions.user_id` and the link audit's user columns by hand.

## Configuration

`local.conf`'s `cloud { baseUrl, enabled }` (D13), read once at startup by `CloudConfig`:

- **`baseUrl`** is the cloud, `https://lighting7.net` when absent (`CloudConfig.DEFAULT_BASE_URL`), so
  a packaged desk needs no `local.conf` to be adopted. Empty means no cloud is configured: adoption
  answers `CLOUD_NOT_CONFIGURED` and the desk calls nothing; the test suite's config and
  `scripts/cloud-session.sh`'s set it so. It must be `https`, or plain `http` to a loopback host (a
  cloud on the same machine), with no credentials, query or fragment (D12). A URL the desk refuses
  counts as not configured, and `cloud.state`'s `lastError` says why (`CLOUD_URL_INVALID`).
  A `local.conf` copied from `example.local.conf` between 2026-11-03 and 2026-11-05, as a packaged
  desk's first launch copies it, says `baseUrl = ""`: delete that line to take the default.
- **`enabled = false`** is the hard kill switch: no adoption (`CLOUD_DISABLED`), no poll, no control
  channel, no call to the cloud. The UI cannot override it.
- **`remote.channelStateHz`** (default 20, 0 for none) caps `channelState` to each socket on the public
  listener, where a remote session's frames cross the internet (P1 D14, §"The rate cap"). A value that is
  not a whole number from 0 to 1000 is the default, with a warning.

The desk talks only to `baseUrl`'s host and follows no redirect (`CloudApi`). The row records the URL it
was adopted through; if `cloud.baseUrl` later names another, `lastError` is `CLOUD_URL_CHANGED` and an
unadopt cannot reach the old cloud, so only *forget* works.

## Adoption

To **adopt** a desk is to make it an install of a cloud organisation. It runs RFC 8628's device flow,
driven by the desk's backend rather than the browser (D6), so closing the browser abandons nothing:

1. An admin presses *Adopt*: `POST /install/cloud/adoption`. The desk makes a fresh install key, sends
   its public half with `installs.uuid`, the friendly name, the platform (`windows-x64`,
   `macos-arm64`, …) and the version to `POST /v1/adoptions`, and writes the row `ADOPTING` with the
   answer: adoption id, user code, verification URIs, interval and expiry, and the admin as
   `adopted_by`. A cloud that refuses is 502 `CLOUD_REFUSED`, one that does not answer 409
   `CLOUD_UNREACHABLE`; either way the key is deleted and no row is written.
2. The admin opens the verification URI and enters the code in the portal.
3. The backend polls `POST /v1/adoptions/{id}/poll` every interval, each poll signed with the install
   key, so a leaked adoption id is useless without it. `slow_down` raises the interval for the rest of
   the adoption, to at most 60 s. A poll that fails (no answer, a refusal, a key the credential store
   cannot read just now) is retried at the interval and shown as `lastError` `CLOUD_POLL_FAILED`; only
   the expiry ends it. At the expiry the desk polls once more, since only a poll can deliver an
   approval made in the last interval.
4. The poll answers:
   - **`approved`**: the row becomes `ADOPTED` with the cloud's install id and org, and the starting
     admin's link is written with the approving account's id and email.
   - **`denied`**, **`expired`**, an unknown adoption (404), or anything but an approval or a denial
     from the poll at the expiry: row and key are deleted and `notice` says what happened.

Starting is refused 409 `CLOUD_ALREADY_ADOPTED` or `CLOUD_ADOPTION_IN_PROGRESS`.
`DELETE /install/cloud/adoption` cancels a pending one (409 `CLOUD_NO_ADOPTION` when there is none): row
and key go, with no call to the cloud, whose adoption then expires unapproved. If someone approves it
anyway, the cloud holds an install whose key no desk has; the portal removes it.

Adoption starts **only on the desk's own listener**, by a signed-in admin (D5): 403
`CLOUD_ADOPT_LOCAL_ONLY` on the public listener whatever the role, since a remote admin could otherwise
give a cloud organisation standing reach into the rig, and 409 `CLOUD_NEEDS_ACCOUNT` on a desk with no
accounts, which has no admin to link.

**A restart mid-adoption** resumes polling from the row (`CloudService.start`, from
`Application.module()`). One whose expiry passed while the desk was down gets its last poll at once, so
an approval made meanwhile still lands. One the desk cannot poll (`cloud.enabled = false`, or another
`baseUrl`) is ended at its expiry. A key with no row behind it, a crash between the two writes, is
deleted at the same point.

## Unadopt

`DELETE /install/cloud[?force=true]` (D8), from either listener, since it only reduces reach:

- Without `force` the desk signs `DELETE /v1/installs/{installId}` and, on a 2xx or a 404 (already gone
  in the cloud), deletes the row, every link and the key. Anything else is 409 `CLOUD_UNREACHABLE` and
  nothing changes: no answer, a refusal, `cloud.enabled = false`, a changed `baseUrl`, or a missing key.
- With `force` the desk forgets locally without asking the cloud. The cloud's record keeps a key that
  can never connect again, and the portal removes it.

409 `CLOUD_NOT_ADOPTED` when the desk is not adopted (a pending adoption is cancelled, not unadopted).

**Unadopted in the portal**, the cloud tells the desk with an `unadopted` message or close code 4004 at
its next connect (§"The control channel"). `CloudService.revoked()` forgets the adoption the same way
and sets `notice` to "This desk was removed from *org* in the cloud."

## The control channel

While the desk is adopted, `cloud.enabled` is true and `cloud.baseUrl` is the URL it was adopted
through, `CloudService` keeps one WebSocket open to `/v1/desk` on that host
(D9, `cloud/CloudChannel.kt`). It starts when `start()` runs or adoption is approved, and stops on
unadopt, cancel or revoke. The wire side is [cloud-protocol.md](cloud-protocol.md); the desk's side:

1. **Connect** to `wss://<host>/v1/desk` (`ws://` only for the loopback host `CloudConfig` allows, D12),
   following no redirect: a redirect fails the attempt like any other refusal.
2. **Handshake.** The cloud's first frame must be `challenge`; the desk answers `auth`, signing
   `AuthPayload(installId, nonce, timestamp)` with the install key, and waits for `welcome`. The
   upgrade and each step wait at most 15 s; a step that times out is closed `4000`, and the desk tries
   again. The key is read from the credential store at the first attempt that needs it, and again at
   each attempt until a read succeeds, so a keychain still locked at boot costs attempts, not the
   channel.
3. **Online.** The desk sends `hello` (protocol 1, its version, platform, `installs.uuid`, friendly
   name, its capabilities), then `health {uptimeS, deskVersion}` every `welcome.heartbeatS`
   (clamped to 1–300 s). `hello` carries the friendly name as it was when the channel started. The
   capabilities are `remote.v1` and `mcp.v1`, always, then the state entries of P1 D6: `remote.off` and
   `mcp.off` while their switch is off, `remote.no-sidecar` while no sidecar runs
   (`CloudService.capabilities`). Whenever the list changes the desk sends
   `capabilities`, the whole list, on the connection it was announced on, never ahead of its `hello`
   (`ChannelConnection.refreshCapabilities`). `welcome`'s `remoteOrigin`, when it is a bare origin
   `cloud.baseUrl` would accept (`https`, or `http` to a loopback host), is kept for the Origin check
   (§"Remote sessions").
4. **Reading.** `unadopted` forgets the adoption (`revoked()`). `jwks` replaces the token keys (P1 D3).
   `revoke` ends and refuses what it names (§"Remote sessions"). The `rtc.*` messages go to the broker
   (§"The sidecar"), `link.accepted` and `link.refused` to `CloudLinks` (§"Links"), and `mcp.call` to
   `McpRelay` (`docs/mcp-engineering.md` §"Through the cloud"). `error` is
   logged. Any other type,
   unknown ones, ones of another protocol version and ones only the desk sends, is ignored and logged
   once per type, up to 100 types, so v1 can grow additively. Text the cloud chose (a type, an error,
   a close reason) is cut to 64 characters before the desk logs or repeats it. A frame over 8 MiB, one that is not JSON text, or one that is not an
   envelope closes the connection `4000`. Ktor's own limit sits at twice that, so a frame between the
   two is seen and closed `4000`, and one above it is refused by Ktor (1009) without being read whole.
5. **Closed.** `4004 INSTALL_REVOKED` forgets the adoption. Every other end, a close code, a dropped
   connection or an attempt that failed in any way, is followed by a reconnect after a delay drawn
   uniformly between 1 s and `min(30 s, 1 s × 2^n)`, *n* the attempts since a connection last stayed
   online for 30 s, so a desk is back within 30 s of the cloud's return (NFR-PERF-3), and a cloud that
   welcomes and then closes at once, or two desks trading one install's connection, back off as a down
   cloud does.

**Liveness.** Outbound frames go through two queues (`ChannelOutbox`, P1 D16). Heartbeats take a queue
of 16 that drops the oldest, so a cloud that stops reading costs stale heartbeats, never a blocked
sender. Everything else (`hello`, `capabilities`, and the replies of later sessions) takes a queue in
which nothing newer displaces anything older, bounded at 256 frames and 16 MiB; one that does not fit,
or is over a frame, is refused and logged, and the cloud's request times out. That queue is written
first. WebSocket pings every 25 s end a connection whose cloud stops answering, or vanished without
closing. A frame that cannot be written within three heartbeats, or within its size at 128 KiB/s where
that is longer (64 s for 8 MiB), ends it too, but at a heartbeat's size that takes hours of a cloud not
reading; the pings are what notice first. Either way the desk reconnects.

**Status** is memory only (D11): `connection` is `OFF` (not adopted, or the cloud off), `CONNECTING`,
`ONLINE` or `RETRYING`, with `retryAt` the next attempt, `lastOnlineAt` the last `welcome`, and
`lastError` why the last connection ended:

| `lastError.code` | Why |
|---|---|
| `CLOUD_CONNECT_FAILED` | No connection: the cloud is down, the upgrade was refused or redirected, or nothing answered in 15 s |
| `CLOUD_KEY_UNREADABLE` | The credential store holds the install key but could not read it; retried with the connection |
| `CLOUD_AUTH_FAILED` | `4003`, its reason (`BAD_SIGNATURE`, `MALFORMED`, `TIMEOUT`) in the message |
| `CLOUD_CLOCK_SKEW` | `4003 CLOCK_SKEW`: "check this machine's clock", with how far it is from the challenge's `serverTime` |
| `CLOUD_PROTOCOL_UNSUPPORTED` | `4001`: the cloud no longer serves this desk's protocol, and names the version to update to |
| `CLOUD_PROTOCOL_ERROR` | `4000`, sent by either side |
| `CLOUD_REPLACED` | `4009`: another connection from this install took this one's place |
| `CLOUD_DISCONNECTED` | Any other close, the connection dropped, or an attempt failed in a way the desk did not expect |

An adoption error (the row's, or a poll's) outranks the channel's. Only a revoke changes what the
desk holds; nothing about a connection is written to the database.

## Desk access tokens

A request through the cloud says who is calling with a **desk access token**: a compact JWS the cloud
signs with a key its `jwks` message names, checked by `DeskTokens.verify` (cloud-protocol.md §"Desk
access tokens", P1 D2). The desk holds the last `jwks` it heard in memory (`JwksCache`, P1 D3), through
any reconnect, until a newer one arrives; `publicKeysOf` turns it into the `kid` → key map `verify`
reads. It is never written: a restart ends every remote session anyway, and every way in needs the cloud
to begin. The keys go when the desk stops being adopted.

The token's audience is the install id `cloud_connection` holds, its door (`remote` here, `mcp` at the
MCP relay, `docs/mcp-engineering.md` §"Through the cloud") the one it arrives at. What `verify` does not judge is the desk's: the session it
names (`REMOTE_SESSION_UNKNOWN`), its account and org against that session's (`REMOTE_SESSION_MISMATCH`),
and whether a `revoke` covers it (`TOKEN_REVOKED`), below.

## Remote sessions

A **remote session** is a cloud member using the desk's own SPA from anywhere, carried by the sidecar
`lighting7-link` into the public listener (P1 D4, D5). `cloud/RemoteSessions.kt` is its registry; the
broker (§"The sidecar") opens each one when it accepts an `rtc.offer`.

**Opening.** `RemoteSessions.open(sid, accountId, email, displayName)` resolves the account's link to a
local user (none: an *unlinked* session), records the adoption's org, writes the session's audit row and
gives it until its first token's expiry, or 11 minutes. It refuses while the desk is not adopted, with
*Remote sessions* off (`REMOTE_ACCESS_OFF`), and for a sid already open, ended, revoked or ever audited
(`SESSION_ENDED`). The
link and user are fixed for the session's life, except that an unlinked session takes the link its account
gains while it is open (`linked`, §"Links"); the role is not fixed.

**Each request.** `resolveCaller` (`auth/AuthGate.kt`) is the one answer to "who is calling", used by the
auth gate, the `/api` WebSocket upgrade and `GET /auth/status`, once per call. On the public listener a
request with `Authorization: Bearer` is judged by `RemoteSessions.authenticate` and only that way, in
order:

1. It must carry `Lighting7-Link` equal to the secret the registry made for the running sidecar
   (`newLinkSecret`, handed to the sidecar on stdin), compared in constant time; else 401
   `unauthenticated`. So a valid token that reaches 8414 any other way, from a local process say, is
   refused. No sidecar, no secret, no bearer request.
2. The desk must be adopted through an enabled cloud; *Remote sessions* off is 403 `REMOTE_ACCESS_OFF`.
3. The token must verify (401 with its `TOKEN_*` code), be covered by no `revoke` (`TOKEN_REVOKED`), name
   an open session (`REMOTE_SESSION_UNKNOWN`), and name that session's account and org: a token that
   does not ends the session (`REMOTE_SESSION_MISMATCH`), so a session is never carried on as someone else.
4. A linked session's caller is its local user as `AuthService` holds them now, with
   `sessionTokenHash` `cloud-session:<sid>`, so a demotion counts on the next request and a disabled user
   is refused as a disabled cookie user is. An unlinked one reaches `auth/status` (answered `{unlinked:
   true, email}`), the SPA's static files, `POST /cloud/link/prove` (P1 session 6) and its own refresh and
   end; everything else is 403 `CLOUD_ACCOUNT_NOT_LINKED`, naming the two ways to link. Its socket closes
   4401 with that code.

On the desk's own listener a bearer token is never read. All of `installRemoteHardening` applies to a
bearer request, and the Origin check also allows `welcome`'s `remoteOrigin`, which the sidecar forwards
as the request's origin; a cookie request is not given it. The scripts, effects and commands switches
apply as to any remote request.

**Refresh and end.** The sidecar hands over each refreshed token as the bearer of `POST
/cloud/session/token`: the gate judges it as above, and it must not expire before a token the session
has already used, or the session ends; the same token again, on a request that beat the refresh or on a
retry, is accepted. The refresh and end are exempt from the warm-up gate, so a project switch cannot
let a session lapse. Each accepted token moves the session's deadline to its `exp` plus the minute
of skew `verify` allows; a session whose deadline passes unrefreshed ends `EXPIRED`, timed on one
`cloud-sessions` thread that exists only while sessions are open. `DELETE /cloud/session` and *Sign out*
(`POST /auth/logout`) end the caller's own session.

**Ending.** A session ends on a `revoke` naming it, its account or everything; its token lapsing; a
token naming another account or org; *Remote sessions* turned off; the desk unadopted; its link removed
(`linkRemoved`, which every unlink calls); and every credential revocation of its user
(disable, delete, a password set or reset, *sign out everywhere*) through
`AuthService.addSessionRevocationListener`, which spares the session that asked for "everywhere else".
Its sockets close 4401 at once (they wait on `RemoteSession.ended`, which no burst can drop), its next
request is refused, and `addEndListener` tells whoever drives the sidecar. An ended sid can never be
opened or used again. A `revoke {accountId}` or `{all}` is also kept with its `at`, the cloud's own time,
and refuses every token for it whose `iat` is at or before that second (`Revoke.covers`), whatever the
desk's clock says, so a token minted a moment before the revoke cannot open a new session. One is kept
per account and one for everything, the latest `at` winning, and a sid revoked before it opens is
denied too. All are remembered until any token issued before them has expired (12 minutes). A cloud
that names more than 4096 sids or accounts at once is answered as if it revoked everything, so the deny
set stays bounded and errs towards refusing.

**The audit.** `cloud_remote_sessions(sid, cloud_account_id, email, user_id, started_at, ended_at,
path, end_reason)` gets a row when a session opens and its end when it closes (`ended_at`, the last path
the sidecar reported, the `RemoteSessionEnd`), never in between. A clean stop ends every open session as
`DESK_STOPPED`; a row a crash left open is closed at startup as `DESK_RESTARTED`, and rows older than a
year are pruned then.

**The switches** (P1 D6) are `cloud_connection`'s `remote_access`, `mcp_access` and `approve_sessions`,
off at adoption. `GET /install/cloud/remote` answers them beside the scripts, effects and commands
switches every remote caller answers to (`cloud/RemotePolicy.kt` over `remote_access_settings`' `allow_*`,
a remote session and an MCP call alike), as `RemoteAccessDto`; `PUT` sets any of the six, a cloud switch only once adopted (409
`CLOUD_NOT_ADOPTED`), and is refused on the public listener, 403 `CLOUD_SETTINGS_LOCAL_ONLY`, whatever the
role: a compromised cloud can mint a linked admin's token, so nothing it can sign may widen what comes
through it. No message writes them.

**Approval** (P1 D13). With `approve_sessions` on, an offer past the caps, the switch and the sidecar
waits in the broker rather than opening: nothing is written and nothing reaches the sidecar, the
browser's candidates are kept (64 at most) to follow its `session.open`, and it counts as open against
the cap of 8. `CloudService.pendingSessions` lists what waits, each with the account's email and display
name, the display name of the desk user it links to (read once, at the offer) and when its wait ends,
and `remoteSession.pending` carries the list to every admin socket on the desk's own listener.
`POST /install/cloud/sessions/{sid}/allow` reads the account's link again first: if the desk user it links
to is not the one shown, it answers 409 `CLOUD_SESSION_LINK_CHANGED` and the offer waits on, shown
afresh, so an admin never allows one person and opens another. Otherwise it opens the offer as an
unapproved one would have opened (the switch and the sidecar checked again) and hands the sidecar the
kept candidates. Until that `session.open` has gone the offer stays listed as *opening*, answerable by
no one: it still counts against the cap and still keeps the candidates that arrive, and a close or a
revoke that lands meanwhile ends the session as soon as it is registered. `…/deny` refuses it
`REMOTE_SESSION_DENIED`. Both are refused on the public listener, 403 `CLOUD_APPROVAL_LOCAL_ONLY`, and are
404 `CLOUD_NO_SESSION` once it no longer waits. Unanswered for 60 s (the cloud waits 75 s for the reply)
it is denied, timed by a coroutine on the cloud's scope. The cloud's `rtc.close` for it, a `revoke`
naming it, its account or everything (refused `TOKEN_REVOKED`), *Remote sessions* turned off
(`REMOTE_ACCESS_OFF`) and the control channel dropping all end the wait too; the last replies nothing,
since an offer's reply names the connection it came on and the cloud has already failed it. A second
offer for a sid that waits or is open is refused `SESSION_ENDED`. Turning approval off leaves what waits
to be answered. A denied or timed-out offer leaves no audit row: it never was a session. MCP calls are
not covered: they have their own switch.

**The Cloud tab's list.** `GET /install/cloud/sessions` answers every open session, newest first, and the
20 most recently ended from the audit, each with the desk user it was (by their name now), its path and
why it ended (`RemoteSessionsDto`). `DELETE /install/cloud/sessions/{sid}` ends an open one
`ENDED_BY_ADMIN`, from either listener, since it only reduces reach; 404 `CLOUD_NO_SESSION` for one not
open. All of `/install/cloud` is admin-only.

**The rate cap** (P1 D14, `plugins/RemoteFrameCap.kt`). Every frame a socket on the public listener is
sent goes through `SocketScope.send`, where `channelState` and `provenanceState` are held to
`cloud.remote.channelStateHz` a second and half that. `channelState` carries only the channels that
changed, so the frames sent within an interval are merged, each (universe, channel) at its latest level,
and a merged frame is a snapshot if any frame in it was; `provenanceState` is a whole snapshot, so only
the latest is kept. The first frame after a quiet spell goes at once, and a coroutine on the socket's own
session drains the rest, ending once an interval passes with nothing to send. A socket on the desk's own
listener has no cap, and neither does one with the setting at 0.

## Links

A link is one cloud account to one desk user and the reverse, held by unique indexes on both of
`cloud_identity_links`' columns (P1 D12). No desk role follows from anything in the cloud: a linked
session is its user, with that user's role. `cloud/CloudLinks.kt` owns every change but adoption's, and
each writes a `cloud_link_events` row in its own transaction: `LINKED` (with `via`), `UNLINKED` (with
its `CloudUnlinkReason`), `INVITED`, `INVITE_CANCELLED`, `INVITE_REFUSED`, `INVITE_EXPIRED` and
`ACCEPTANCE_REFUSED` (with the `link.ack` reason), naming the user, the account, the email, the invite
and the desk user who made the change, by name as well as id so a row outlives the user.

**Three ways in.**

- *Adoption* links the admin who started it to the cloud account that approved it (`ADOPTION`).
- *Invite*: an admin names an email on a user's sheet, `POST /users/{id}/cloud-invite`. The desk keeps
  the invite for seven days and sends `link.invite`; the install key proves which desk asks, not which
  person, so the cloud holds it until an owner or admin of the org approves it in the portal and only
  then mails it, answering `link.refused` if they decline. An invite needs the channel online (409
  `CLOUD_OFFLINE` otherwise, nothing kept), and goes after any link message still waiting, so it never
  overtakes the cancel of the invite before it. One live
  invite per user and per email (`CLOUD_INVITE_PENDING`), none for a user already linked
  (`CLOUD_LINK_EXISTS`). `link.accepted` writes the link (`INVITE`, its `invite_id` kept) only while the
  invite is live and its email is the invite's, case aside, and only when neither the user nor the
  account is linked already; it is answered `link.ack` with `replyTo` either way, refused as
  `LINK_INVITE_NOT_LIVE`, `LINK_EMAIL_MISMATCH` (the invite kept) or `LINK_TAKEN`. An acceptance the
  cloud sends again, having never heard the ack, finds the link by its invite and is answered linked.
  The cloud delivers one a desk missed at its next `welcome`. `DELETE /users/{id}/cloud-invite` cancels
  (`link.cancelled`); an expired invite is deleted when one is next written, when its acceptance arrives
  and at startup.
- *Prove once*: an unlinked remote session posts a desk username and password to `POST
  /cloud/link/prove`. The password is checked by `AuthService.verifyCredentials`, as the sign-in form's is,
  under the public listener's per-username lockout (429 `SIGN_IN_LOCKED`); a wrong one is 401 and ends
  nothing. Then the account is linked to that user (`PROVED`), the user's live invite is cancelled, the
  cloud hears `link.created`, and every unlinked session of the account becomes the user's
  (`RemoteSessions.linked`), so the session that proved carries on signed in. 409 `CLOUD_LINK_EXISTS`
  when the user is linked to another account.

**One way out.** An admin's unlink (`DELETE /users/{id}/cloud-link`), the user's own from their profile
(`DELETE /cloud/link`), and the user's deletion each end the account's remote sessions and tell the cloud
`link.removed`; a deletion also cancels the user's invite. Unadopting deletes every link and invite,
recorded as `UNADOPTED`, and tells nothing, since there is no cloud left to tell.

**Races.** Each write re-reads, in its own transaction, that the desk is still adopted and the user
still exists, so an unadopt or a deletion that committed after the caller's checks leaves no link or
invite behind. A session opening while its account's link changes reads the link again once it is
registered, so an unlink or a proof that landed in between is not missed.

**While the channel is down** `link.cancelled`, `link.removed` and `link.created` wait in memory, in
order, at most 256 (the oldest dropped past that), and go when the channel is next online; unadopting
forgets them. `link.ack` is not kept: it is a reply, and an acceptance the desk never answered is the
cloud's to send again. Like the rest of the connector, none of this writes on a timer or touches the
show's threads; the `link.*` handlers run on the `cloud-N` threads and write only on a change.

## The sidecar

`lighting7-link` is the desk's WebRTC end of a remote session (P1 D7, D8): a Go program on pion, in
`link/` with its own `go.mod`, MIT like the desk. The JVM starts it, is its only client and talks to it
over its stdio; each remote session is one peer connection, and each HTTP request or WebSocket the
browser makes is one data channel, which the sidecar bridges to the public listener. It decides
nothing: every request it forwards carries the session's token and the launch's secret, and the desk
judges it as §"Remote sessions" says.

**Building it.** Gradle builds it with a Go toolchain it downloads itself, as it does Node: the zip
`golang.org/toolchain@v0.0.1-go<version>.<os>-<arch>` that `GOTOOLCHAIN` fetches, from the Go module
proxy, checked against a SHA-256 pinned per host in `build.gradle.kts` (`goToolchainSha256`) and unpacked
into `.gradle/go/`. The version is `link/go.mod`'s `go` line, which CI's *Link* job reads too; the build
refuses to configure if the two disagree. `buildLink<Os><Arch>` builds one target into `build/link/`
(`CGO_ENABLED=0`, `-trimpath`, `-ldflags=-s -w`, `GOTOOLCHAIN=local`), cacheable, and `buildLink` the ones
`nativePayloadOs` ships:

| `nativePayloadOs` | Sidecars beside the jars |
|---|---|
| `windows` | `lighting7-link-windows-amd64.exe` |
| `mac` | `lighting7-link-darwin-arm64`, `lighting7-link-darwin-amd64` |
| `linux` | `lighting7-link-linux-amd64` |
| `all` | all four |

They go into `jpackage-input` beside `lighting7.jar` and `launcher.jar`, never into the fat jar, since an
executable cannot run from inside one. `stageJpackageInput`'s verifier fails a staged directory whose
jar's natives and sidecars are not for the same OS, whose sidecar is missing, extra or not its target's
format (ELF, Mach-O, PE, read from the header) and architecture, or not executable; its self-test,
`verifyJpackageInputSelfTest`, runs in `check` against fixture headers. Measured from Linux: each binary
is 10–11 MB, 4 MB deflated.

**Which one runs** (`link/LinkBinary.kt`): `cloud.link.binary` when `local.conf` names one, else
`-Dlighting7.link`, which `./gradlew run` and `./gradlew test` pass naming the host's build, else the
installer's beside `lighting7.jar`, named for this machine (`lighting7-link-<goos>-<goarch>`, `.exe` on
Windows). None of them, and no sidecar runs.

**When it runs.** The desk starts it while the control channel runs, *Remote sessions* is on and the
public listener has bound (`mcp.enabled` and a successful bind: `startPublicListener` calls
`CloudService.publicListenerBound`), with that listener's address as `-target` and the desk's build as
`-build`, and stops it when any of those ends: the channel stopping, the desk unadopted, the switch off.
Without one serving, `remote.no-sidecar` is announced, set as it stops and cleared as it starts, and
every offer is refused `SIDECAR_UNAVAILABLE`; the desk announces `remote.v1` whatever its state.
`cloud.state`'s `link` says where it is: `OFF`, `STARTING`, `RUNNING` (it said `started`) or
`RESTARTING`.

**Supervision** (`link/LinkProcess.kt`, P1 D11): a launch that ends, by exiting, crashing
or being killed as hung, is followed by the next after 1 s, doubling to 30 s, and the backoff resets once
a launch has served for 60 s. The sidecar must not outlive the desk, so each launch is put in the desk's
Windows Job Object with kill-on-close (`process/WindowsJob.kt`), a shutdown hook stops it, and each
launch records itself in a PID file (`process/PidFile.kt`), deleted at its exit only while it still names
that launch. `CloudService.start` sweeps the file once per desk run, before anything can launch and under
no lock, so a sidecar a killed desk left running is stopped: only while that PID is alive, still running
the same binary and, where the platform reports it, started at the recorded time, so a reused PID is left
alone. It also exits by itself when its stdin ends, as it does when the JVM dies.

**The stdio protocol.** The launch's link secret is the first line on stdin, then one JSON object per
line each way (`link/LinkMessages.kt`, `link/internal/sidecar/stdio.go`). `link/testdata/stdio.json`
has one of each, read by both ends' tests.

| Type | Direction | Body |
|---|---|---|
| `session.open` | desk → sidecar | `sid`, `offer` (the browser's SDP), `iceServers` |
| `session.ice` | either | `sid`, `candidate?` (none for the end of the candidates) |
| `session.close` | desk → sidecar | `sid`, `reason` |
| `started` | sidecar → desk, first | `build`: it has read its secret and serves |
| `session.answer` | sidecar → desk | `sid`, `sdp` |
| `session.state` | sidecar → desk | `sid`, `path`: `DIRECT`, or `RELAY` when either end of the selected candidate pair is a TURN relay |
| `session.closed` | sidecar → desk | `sid`, `reason` (an `ErrorCode`): its peer went, the desk refused its token, a limit |

It exits when stdin ends, closing every session. Its stdout carries nothing but this protocol: pion's
logs and anything else that prints go to stderr.

**The broker** (`cloud/RemoteBroker.kt`) sits between the channel and the sidecar. An `rtc.offer` is
checked against D17's caps first, before any sidecar work or database write: at most 8 sessions open
and 20 offers a minute past the caps, each refused `REMOTE_SESSION_LIMIT`. Then *Remote sessions* off is
`REMOTE_ACCESS_OFF`, no sidecar serving `SIDECAR_UNAVAILABLE`, and an offer the sidecar could not read
`MALFORMED`: an SDP over 64 KiB, a sid over 128 characters, or a `session.open` line over 128 KiB. Then
`RemoteSessions.open` records the account and the adoption's org and writes the audit row, and the
sidecar gets `session.open`; its `session.answer` goes to the cloud as `rtc.answer` replying to the
offer. Every refusal is an `rtc.close` replying to the offer. Candidates go both ways (a cloud
candidate over 4 KiB is dropped), and `session.state`'s path is kept on the session for its audit row.
However a session ends, its end listener tells the cloud (`rtc.close`, the sidecar's reason when the
sidecar closed it) and the sidecar (`session.close`), each unless it was the one that ended it, so the
offer of a session that ends unanswered is still replied to. A sidecar that stops ends every session it
carried, `SIDECAR_STOPPED`, told to the cloud as `SIDECAR_UNAVAILABLE`.

**Its pipes** (`link/LinkSidecar.kt`, P1 D11) never block the JVM or hold the cloud's threads. stdout and
stderr are read on threads of their own (`link-stdout-N`, `link-stderr-N`), in lines of at most
256 KiB, a longer one skipped. stdout's lines go to the cloud's scope through a channel of 32, its reader
waiting (and through the pipe, the sidecar) while the cloud's threads catch up, which yield every 64
lines; a line that is no message is ignored. stderr goes to the log, at most 50 lines in 10 s, then a
count of those not logged. stdin is written by `link-stdin-N` from a queue of 256 lines that never drops
one: when it is full the sidecar has stopped reading, so it is killed, which ends its sessions, and the
supervisor starts the next, with a new secret. A stop never waits: a thread of its own sends the sidecar SIGTERM (on
Windows, a kill), on which it ends its sessions and exits, and kills it if it has not gone in 5 s. A
launch ends once, whichever of a stop, a restart or its exit comes first, and a restart after a stop
starts nothing.

**Inside it** (`link/internal/sidecar/`), per the link framing (`docs/cloud-protocol.md` §"The link
framing" and §"ctl"):

- *`ctl`*: `hello {token}` is answered by asking `GET /api/rest/auth/status` with that bearer, `ready
  {user, role, build}` for a linked session, `ready {unlinked, build}` for an unlinked one, else
  `refused {code}` (the desk's `code`) and the session ends. Each `token {token}` is handed to the desk as
  the bearer of `POST /api/rest/cloud/session/token`; a refusal ends the session. An `http` or `ws`
  channel opened before a token is accepted waits up to 15 s for one, then is reset `NOT_READY`.
- *HTTP*: the head's headers less `LinkFraming.STRIPPED_HEADERS`, and less `content-length` (set from
  the head's) and `accept-encoding`, so the desk sends an identity body: the service worker builds a
  `Response` from the bytes, which never decodes one. Then `Authorization`, `Lighting7-Link` and `Origin`
  from the session. The body streams through a pipe, the response back in 16 KiB `DATA`; no redirect is
  followed, no proxy used; hop-by-hop headers and `set-cookie` are dropped from the answer. A response
  the desk cuts short is `RESET {reason: DESK_UNREACHABLE}`, never `END`. An upload the desk answers
  without reading is answered all the same: the rest of its body is dropped until the answer has gone,
  then the channel closes. A path must
  be absolute and stay on the desk's host.
- *WebSocket*: dialled with the same headers and the head's subprotocols; a refused upgrade answers its
  status. Messages are forwarded as fragments both ways, the desk's codes (4401 among them) and the
  browser's passing through each way's close handshake, and the browser's own `CLOSE` awaited for 5 s
  after the desk's.
- *ICE*: the sidecar's candidates follow its answer, held until it has gone; the browser's are queued
  per session (64) and applied in order once its peer connection exists, so no session's setup holds
  up another's lines.
- *Backpressure*: a send waits while the channel buffers more than 1 MiB, until it falls to 256 KiB; each
  channel queues at most 16 inbound messages before its reader blocks, which holds the browser back
  through SCTP. A 100 MB body streams each way with the heap growing by about 20 MiB, and a browser that
  stops reading holds the desk's response at a few MiB.
- *Limits*: 16 sessions (the broker opens at most 8) and 128 `http` and `ws` channels per session, a
  channel past it answered `RESET {reason: TOO_MANY_CHANNELS}`. The sidecar never sends first on a
  channel: even that reset answers the channel's first frame. A session that has not connected within
  60 s is closed.

## Remote mode

The desk's SPA framed by the cloud's shell, its socket a `MessagePort` the shell bridges to the sidecar
(P1 D8, D9). LAN behaviour is unchanged: a page that is not framed, or whose `index.html` lacks the
meta below, loads exactly as before and never waits.

- **The page.** `routes/spaPage.kt`, a route-scoped plugin on the bundle's static route, adds `<meta
  name="lighting7-remote-parent" content="<origin of cloud.baseUrl>">` to `index.html` on the public
  listener alone, on `/` and on every app path the index answers, and nothing with no cloud configured
  or `cloud.enabled = false`. On the desk's own listener every bundle file carries
  `Content-Security-Policy: frame-ancestors 'self'`, so no other page can frame the LAN desk; the
  public listener sends none, since the shell's service worker serves its pages.
- **The decision.** `frontend/src/boot.ts` is the page's only script. `remoteBoot.ts` enters remote mode
  only when the page is framed *and* carries the meta: it posts `hello {protocol: 1}` to that origin and
  waits up to 10 s for the shell's `port` (from that origin and the parent only), then imports `./main`.
  `lightingApi` builds its connection as its module is evaluated, so the mode is fixed before anything
  reads it (`lib/remoteMode.ts`). A shell that never answers leaves the page saying so, with the app
  unloaded.
- **The socket.** `api/remoteSocket.ts` is an `InternalApiConnection` over the port, emitting real
  `Event`s, `MessageEvent`s and `CloseEvent`s through the same subscriber hub as the WebSocket
  (`createConnectionEvents`), so every bridge, `statusApi`, `wsGesture` and the 4401 path are unchanged.
  It keeps `internalApi`'s backoff, its breadcrumbs and its `ws.reconnect` count, and leaves a 4401
  closed. A port taken as gone — its `close` event where the browser fires one (Chromium), or an
  `open` unanswered for 15 s — reads as a 1006 close, and the reconnect asks the shell for a fresh port
  with another `hello`, closing the old one. REST is untouched: it is
  relative, and the service worker answers it.
- **The worker's port.** A service worker the browser restarted posts `port.request`; the SPA asks the
  shell for a fresh port on its own and hands the `port` it gets to that worker, oldest request first,
  asking again on the next port for a request the last one never answered, and closing a port nobody
  asked for.
- **What changes in remote mode.** No sign-in or setup screen: an unlinked session shows *Not linked to
  this desk*, posts `auth.unlinked` and offers the prove-once form (§"Links"), and a signed-out one
  *Remote session ended*. *Log out* posts `session.end` and the shell ends the session. What names this
  origin or opens a window outside the shell is the LAN's and is not drawn: the Screens sheet's
  new-window section, its per-row *Copy link* and its localhost note, ⌘K's *Open … on another display*
  (`canChooseDisplay` is false) and a cue's *Copy link*.

## The state

`CloudService.state` is a `StateFlow<CloudStateDto>` (D14), rebuilt from the row and memory on every
transition and every account change. `GET /install/cloud` answers it, and so does every route in
`routes/installCloud.kt`. The `cloud.state` frame carries it to admin sockets (and bootstrap-open ones),
in the machine-scoped band (`plugins/MachineSocket.kt`), so it does not wait for a
project. Its shape:

`{configured, enabled, adoption?: {userCode, verificationUri, verificationUriComplete, expiresAt,
fingerprint}, adopted?: {installId, orgName, adoptedAt, adoptedBy, linkedEmail, fingerprint},
connection, link?, retryAt?, lastError?: {code, message}, lastOnlineAt?, notice?, noticeAt?, remote,
remoteSwitches: {remoteAccess, mcpAccess, approveSessions}}`, `link` the sidecar's `OFF | STARTING |
RUNNING | RESTARTING` (§"The sidecar"), `OFF` when absent. `remoteSwitches` is what may come through the
cloud now, so a switch moved on one window, or an adoption that starts them all off, reaches every
window's Cloud tab (P1 session 7)

On the public listener the pending adoption's `userCode` and both verification URIs are left out, from
the REST answers and the frame alike (`CloudStateDto.forRemote`): whoever holds the code can approve the
desk into an organisation of their own, which is what D5's refusal of a remote *Adopt* is for. A remote
admin still sees that an adoption is pending, and its expiry and fingerprint. `remote` is true on that
listener alone (the WS Json leaves a false one out), so the Cloud tab can disable *Adopt* rather than
offer a button the desk refuses.

`adoptedBy` is the adopting account's display name, null once it is deleted; `fingerprint` is the
install key's, which the portal shows too. `connection`, `retryAt`, `lastOnlineAt` and the channel's
`lastError` are §"The control channel"'s; a frame goes out on each change of status, never per
heartbeat.

## The Cloud tab

Install settings → **Cloud** (`frontend/src/components/cloud/CloudPanel.tsx`, `store/cloud.ts`) is the
admin's view of all of it: hidden from operators like *Remote access*, reading `GET /install/cloud` and
patching its cache from `cloud.state`, so it is the desk's state on every window and a pending adoption
survives a reload. Every route answers the whole state, which a mutation writes into the cache rather
than refetching. Its states:

- *No cloud configured* (`cloud.baseUrl` empty, or unusable, when `lastError` says why) or *Turned off
  on this desk* (`cloud.enabled = false`), naming the key; a desk adopted before either still shows its adoption, so
  it can be removed.
- *Not adopted*: what adopting does, and **Adopt this desk**, disabled with D5's reason when `remote`.
- *Adopting*: the user code, a QR of `verificationUriComplete`, the link, the time left, the key's
  fingerprint and **Cancel**. Over remote access, where the code and links are left out, it says the
  adoption can only be approved from the desk. In a Sentry replay the code is masked, and the QR and the
  link, whose address carries the code, are blocked.
- *Adopted*: the org, the install id (copyable), who adopted it and when, the linked cloud email, the
  fingerprint, the connection (*Online*, *Connecting…*, *Retrying in n s* counted down from `retryAt`,
  *Not connected*) with `lastError`'s message, and **Remove from cloud**. Its confirm unadopts; when the
  answer is `CLOUD_UNREACHABLE` it shows the desk's reason (unreachable, refused, turned off, a changed
  URL, a missing key) and offers **Forget on this desk only**, `?force=true`.
- A `notice` above the rest, dismissed on that browser alone (localStorage) by its `noticeAt`, the
  instant the desk set it, since the next expiry or revoke says the same words. The desk keeps both in
  memory until the next adoption or a restart, and no route clears them.

Below the adoption, the **Remote access** section (`CloudRemoteAccess.tsx`, P1 D6, D13): once adopted,
*Remote sessions* with the sidecar's status under it while it is on, *Approve each new session on this
desk* and *Claude (MCP)*, each a switch saying what turning it on allows; on any desk, adopted or not,
the scripts, effects and commands switches; and once adopted the open sessions, each with *End* (asked once), and the
recent ones with why they ended, re-read every 5 s while the tab is open, since no frame announces a
session. The three cloud switches follow `cloud.state`'s `remoteSwitches` (`store/cloud.ts`'s bridge
patches the cached settings), and a change of install re-reads the switches and the list. Over remote access (`remote`) every switch is drawn disabled with the reason; *End* still works.

**Approval toasts** (`RemoteSessionApprovals.tsx`, mounted once by `Layout`): every window that is sent
`remoteSession.pending` (an admin's, on the desk's own listener) toasts each waiting session — who, as
which desk user, the seconds left — with *Allow* and *Deny*, and takes the toast down once the frame no
longer lists it, whichever window answered. A socket that closes forgets the list until the next
snapshot.

The adoption's three mutations and the switches' are in `SILENT_ENDPOINTS`: the tab shows each refusal
where it was met.

## Show safety

The cloud is never on the show's path (design record R6, D11). Every change here answers one question:
does the show run exactly as before with the cloud down, hung or hostile? The rules:

1. **Own threads.** Everything runs on `CloudService`'s scope, on two threads of its own named
   `cloud-N`, so a cloud that hangs holds those and none of the show's. Not a slice of
   `Dispatchers.IO`: its workers are `Dispatchers.Default`'s too, where the show runs, so a test could
   not tell the connector's work from the show's (`CloudChannelTest`, "never runs on a thread the show
   uses"). Ktor's socket I/O runs on its engine's own dispatcher.
2. **Bounded waits and answers.** Every call to the cloud has a 15 s timeout, and the desk reads at
   most 64 KB of an answer, treating a longer one as none (`CloudApi`). A route that calls the cloud
   waits at most that long, and no route the show uses calls it.
3. **No database write on a timer.** The database is written only on a transition: adopt, approve,
   cancel, unadopt, revoke, a switch, a remote session's open and end, and a link or invite made,
   accepted, refused or removed (§"Links"). Never per poll, per heartbeat or per reconnect: the pool has one connection,
   so a timed write would queue behind, or in front of, show writes. Live status is memory only.
   `CloudChannelTest` holds SQLite's `total_changes()` still across heartbeats and a reconnect.
4. **Bounded queues.** The channel's heartbeat queue holds 16 frames and drops the oldest; its other
   queue refuses a frame it has no room for. Neither blocks, so nothing that sends to the cloud ever
   waits on it.
5. **No lock the show takes.** The connector reads desk state only from `StateFlow`s and `BuildInfo`.
   The adoption reads the database only on a transition, an account change or a request (the install
   row, the adopting account's name and link), never on a timer: a poll's error is republished from
   memory.
6. **No outbound but to `cloud.baseUrl`'s host**, and nothing at all with `cloud.enabled = false`. The
   sidecar's peer connections go wherever ICE finds the browser, which is what they are for.
7. **The sidecar is a process apart.** Its pipes are read and written on threads of their own, through
   bounded queues, and one that hangs is restarted rather than waited on (§"The sidecar").
8. **A relayed MCP call is the desk's own work, not the channel's.** The channel's thread only judges
   the switch and the queue and hands the call on: the token, the link and the tool run on
   `Dispatchers.IO`, four at once and four waiting, `DESK_BUSY` beyond (`docs/mcp-engineering.md`
   §"Through the cloud").

`CloudOutageOutputTimingTest` is the rules' proof (NFR-SAFE-1): a fade's Art-Net frames, timed with no
connector, then with the cloud connected, stopping reading, dropping the connection and refusing every
reconnect, each run held to the baseline's frame count, end, p99 and longest frame gap, each plus a margin.
`LinkSidecarSafetyTest` holds the sidecar to the same (NFR-SAFE-2): the real one running, killed mid-fade
and stopped with SIGSTOP mid-fade until its stdin queue fills, then a stand-in flooding its stdout and one
flooding its stderr. `McpRelayLimitsTest` holds the MCP relay to the same: a fade with 200 calls sent
mid-fade, none run on a `cloud-N` thread. All three read `testsupport/FadeTiming.kt`.

## Tests

`FakeCloud` (`src/test/kotlin/.../testsupport/FakeCloud.kt`) is a stand-in cloud on a loopback port,
built on `cloud-protocol`, that verifies every signature it is sent. Its adoption switches approve, deny,
expire, slow down, refuse, stall and send an answer too large to read. Its control channel checks `auth`
against the install's key and its own clock (`clockOffset` makes it skewed), keeps one connection per
install, closes a removed install `4004`, and can stall a connection, stop reading one, drop it, refuse
or redirect the upgrade, or force any `4003` reason. For P1 it holds a token-signing key, sends its
`jwks` after every `welcome` (and `remoteOrigin` in it when set), mints desk access tokens (`mint`),
rotates its key, sends `revoke` and relays an MCP call (`mcpCall`). Desk tests never use the real
cloud (R5).
`CloudAdoptionTest` drives `CloudService` through every adoption and unadopt path;
`InstallCloudRoutesTest` the routes and their refusals on both listeners; `CloudChannelTest` the
channel against the fake; `CloudChannelBackoffTest` the reconnect loop on a virtual clock; and
`CloudOutageOutputTimingTest` the show's output through a cloud outage. `RemoteSessionsTest` drives
bearer requests and sockets on the public listener through every refusal and every way a session ends;
`ChannelOutboxTest` the two send queues on virtual time; `RemoteFrameCapTest` the rate cap on virtual
time. `CloudLinksTest` carries invites through
`FakeCloud`'s `acceptInvite` and `declineInvite` to every answer, proves links through a remote session,
and unlinks from each side. `RemoteBrokerTest` drives offers from the fake
through `FakeSidecar`, a stand-in sidecar run as a process of its own that speaks the stdio protocol and
hangs, dies or floods its stdout or stderr on request; `LinkMessagesTest` reads the stdio fixture;
`LinkProcessTest` the supervisor's backoff on a virtual clock and the orphan sweep; `LinkBinaryTest` which
binary runs. `RemoteBrokerTest` also carries an offer through approval (allowed, denied, timed out, closed
by the cloud, the switch turned off, the cap), the frame to an admin on the desk's own listener, *Allow*
refused on the public one, and the Cloud tab's list and *End*. `McpRelayTest` relays calls through the
fake to the desk's own `McpProtocol` and through every refusal; `McpRelayLimitsTest` the queue, a
result over the frame limit and a flood of calls.

The sidecar's own tests (`cd link && go test ./...`, CI's *Link* job) run a pion peer as the browser
against it and a test server as the desk, through the framing and `ctl` fixtures, both 100 MB streams,
a peer that stops reading, the stripped headers and the limits.
