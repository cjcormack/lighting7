# The cloud protocol

`cloud-protocol/` is the wire contract between the desk and Lighting7 Cloud: the control channel's
envelope and messages, the adoption API's bodies, the close and error codes, the install key's signing,
the desk access tokens, the git tokens and repo API cloud sync uses, and the contracts a remote session's browser speaks to the desk's sidecar and to
the desk's SPA. It is MIT like the rest of the desk, and it is the desk's: a protocol change is a desk
PR first (cloud design record R3). The cloud builds it from a pinned desk commit (R4). P0 is the
cloud-p0 plan (`docs/plans/completed/cloud-p0-plan.md`), decisions D3 to D10; P1's additions are the
cloud-p1 plan's (`docs/plans/completed/cloud-p1-plan.md`), D2 to D8; P2's are the cloud-p2 plan's
(`docs/plans/cloud-p2-plan.md`), D1 and D2.

Package `uk.me.cormack.lighting7.cloud.protocol`, JVM only, `kotlinx.serialization` and no Ktor: each
side brings its own HTTP client, server and WebSocket.

## The build

`cloud-protocol/` is its own Gradle build (`settings.gradle.kts`, `build.gradle.kts`), so it builds
alone from a sparse checkout of that directory and the repo's root files:

- **Versions come from the desk's root `gradle.properties`**: `kotlin_version` for both Kotlin plugins,
  `serialization_version` for `kotlinx-serialization-json`. Cone-mode sparse checkouts always include
  root files. Without them the build stops at configuration, saying so. `ProtocolBuildTest` fails when
  the module compiles with another Kotlin than the desk's, which a version pinned in its settings would
  cause.
- **Its own `gradle.properties`** holds only what a build reads from its own directory: the warning
  switches (`org.gradle.warning.mode=fail`, `org.gradle.kotlin.dsl.allWarningsAsErrors=true`), the build
  cache and the code style. Without it a warning in `build.gradle.kts` passed, both standalone and from
  the desk. Source warnings fail through `compilerOptions.allWarningsAsErrors`, as in the desk.
- **Toolchain 24**, the desk's, so a consumer runs on JVM 24 or later.
- **The desk** includes it with `includeBuild("cloud-protocol")` in the root `settings.gradle.kts` and
  depends on `uk.me.cormack.lighting7:cloud-protocol`, which Gradle substitutes with the included build.
  The root `check` depends on its `check`, so CI's `./gradlew check` runs its tests. To run them alone:
  `./gradlew :cloud-protocol:check` from the root, or `../gradlew check` from `cloud-protocol/`.

The licence gate needs nothing for it: an included build is a project component, which
`writeRuntimeModules` leaves out, and its dependencies are already the desk's.

## How the cloud consumes it

The cloud repo names one desk commit in `desk.ref`. Its CI sparse-checks out `cloud-protocol/` at that
commit, cone mode, and includes it in its own Gradle build as a composite (`includeBuild`), depending on
`uk.me.cormack.lighting7:cloud-protocol`. Moving the pin is a cloud PR. Its tests run the same golden
fixtures (below) against its server. Nothing is published to a registry
(`FU-CLOUD-PROTOCOL-PUBLISH`).

## The envelope

Every control-channel frame is one JSON text frame, at most `MAX_FRAME_BYTES` (8 MiB):

```json
{"v": 1, "type": "health", "id": "d-0003", "replyTo": "c-0002", "body": {"uptimeS": 3600, "deskVersion": "1.4.0"}}
```

- `v`: the sender's protocol version, `PROTOCOL_VERSION` (1).
- `type`: the message type, the `@SerialName` of a `Message` subclass.
- `id`: unique per message from one sender. `replyTo`, optional: the `id` of the message this answers.
- `body`: the message's fields, an object.

`Envelope.decode` and `Envelope.encode` read and write it with `CloudJson`, the one `Json` both sides
use: unknown keys ignored, absent optional fields omitted. A frame that is not an envelope, or a known
type whose body does not decode, throws `SerializationException` (the receiver closes 4000). A `type`
this version does not know decodes to `Message.Unknown(type, body)`, which re-encodes as it came; the
receiver ignores it and logs it once per type. A frame of another `v` decodes to `Message.Unknown` too,
whatever its type, since this version's classes may not describe its body; the reader judges `v`
(the cloud closes 4001 a desk version it no longer serves).

## The handshake

1. The desk opens `wss://<cloud host>/v1/desk` (`CloudPaths.DESK_CHANNEL`).
2. Cloud → `challenge {nonce, serverTime}`.
3. Desk → `auth {installId, timestamp, signature}`, `signature` over `AuthPayload(installId, nonce,
   timestamp)`.
4. The cloud checks the signature against the install's key and the timestamp against its clock
   (`CLOCK_SKEW_TOLERANCE`, 5 minutes), then → `welcome {orgName, heartbeatS, remoteOrigin?}`. A
   refusal closes 4003 with an `AuthFailure` name as the reason. `remoteOrigin` is the origin the cloud
   frames this install's SPA on (`https://<label>.desk.<domain>`), which the desk allows in its Origin
   check; a cloud that serves no remote sessions leaves it out.
5. Cloud → `jwks {keys}`, the keys desk access tokens are signed with (§"Desk access tokens"), and
   again whenever they change.
6. Desk → `hello {protocol, deskVersion, platform, deskInstallUuid, friendlyName, capabilities}`, and
   `capabilities {list}` whenever the list changes (§"Capabilities").
7. Desk → `health {uptimeS, deskVersion}` every `heartbeatS` seconds (25 in P0).

`jwks` and `hello` cross: neither waits for the other.

## Messages

Each has a fixture in `cloud-protocol/src/test/resources/protocol/messages/`, named by its type
(`rtc.offer.json`). A reply's *Answers* column names the request it answers (§"Replies").

| Type | Direction | Body | Answers |
|---|---|---|---|
| `challenge` | cloud → desk | `nonce`, `serverTime` | |
| `auth` | desk → cloud | `installId`, `timestamp`, `signature` | `challenge` |
| `welcome` | cloud → desk | `orgName`, `heartbeatS`, `remoteOrigin?` | `auth` |
| `hello` | desk → cloud | `protocol`, `deskVersion`, `platform`, `deskInstallUuid`, `friendlyName`, `capabilities` | |
| `health` | desk → cloud | `uptimeS`, `deskVersion` | |
| `unadopted` | cloud → desk | none: the install was removed in the portal | |
| `error` | either | `code` (`ErrorCode`, or a newer one), `message` | any request it refuses |
| `capabilities` | desk → cloud | `list`: the whole capability list, replacing `hello`'s | |
| `jwks` | cloud → desk | `keys`: `[{kty, crv, kid, x}]` | |
| `revoke` | cloud → desk | `at`, and normally one of `sid`, `accountId` or `all: true` | |
| `rtc.offer` | cloud → desk | `sid`, `accountId`, `email`, `displayName`, `sdp`, `iceServers: [{urls, username?, credential?}]` | |
| `rtc.answer` | desk → cloud | `sid`, `sdp` | `rtc.offer` |
| `rtc.ice` | either | `sid`, `candidate?: {candidate, sdpMid?, sdpMLineIndex?, usernameFragment?}` | |
| `rtc.close` | either | `sid`, `reason` (an `ErrorCode`) | `rtc.offer`, when refused |
| `link.invite` | desk → cloud | `inviteId`, `email`, `userName`, `invitedByName`, `expiresAt` | |
| `link.cancelled` | desk → cloud | `inviteId` | |
| `link.refused` | cloud → desk | `inviteId`: an owner or admin declined it | |
| `link.accepted` | cloud → desk | `inviteId`, `accountId`, `email` | |
| `link.ack` | desk → cloud | `inviteId`, `outcome` (`LINKED` or `REFUSED`), `reason?` | `link.accepted` |
| `link.created` | desk → cloud | `accountId`, `userName`: a link proved once | |
| `link.removed` | desk → cloud | `accountId` | |
| `mcp.call` | cloud → desk | `token`, `grant: {id, clientName}`, `request` (a JSON-RPC message) | |
| `mcp.result` | desk → cloud | `response?` (absent for a notification) | `mcp.call` |
| `sync.changed` | cloud → desk | `repoId`, `url`, `headSha`: a cloud repo was pushed | |

`unknown.json` is a type this version does not know, and must survive a decode and re-encode.

- **`revoke`** ends and refuses whatever it names: one remote session, every session and grant of one
  account, or everything through the cloud. It normally names one; one naming several revokes each
  (`Revoke.targets`), so a mistake revokes too much rather than nothing, and one naming none revokes
  nothing. `at` is a `Timestamps` string on the cloud's clock. A token's `iat` is the floor of its mint
  time in whole seconds, and a token for a revoked target is refused when its `iat` is at or before
  `at`'s whole second (`Revoke.covers`), whatever the desk's clock says: so a token minted in the second
  of the revocation is refused too, and the cloud mints nothing for a target it revoked until the next
  second. An `at` that does not parse covers every token. `all` is written only when true.
- **`rtc.offer`** brings the browser's SDP for a new remote session `sid`, the account asking for it,
  and the ICE servers (STUN and TURN, in WebRTC's `RTCIceServer` shape) its peer should use. The cloud
  relays the `rtc.*` messages without reading the SDP or candidates. `rtc.ice` without a candidate
  says there are no more. `rtc.close` ends a session from either side, its `reason` one of the codes
  below (`SESSION_ENDED` when none fits).
- **`link.*`** carry an invite to its acceptance (`link.invite` → `link.accepted` or `link.refused`,
  keyed by `inviteId`; `link.cancelled` withdraws it) and keep the cloud's mirror of a desk's links
  (`link.created`, `link.removed`). `link.ack`'s `reason` is `LINK_EMAIL_MISMATCH`,
  `LINK_INVITE_NOT_LIVE` or `LINK_TAKEN`.
- **`mcp.call`** relays one MCP JSON-RPC message under a desk access token of scope `mcp`; its
  `request` and `mcp.result`'s `response` are JSON objects. `grant.id` must be the token's signed `gid`
  (the desk refuses a call where it is not, `MCP_GRANT_MISMATCH`); `grant.clientName` is the name the
  desk shows for the grant. A call must fit one frame: the cloud refuses a body over
  `MAX_RELAYED_REQUEST_BYTES` (7 MiB) with `REQUEST_TOO_LARGE` before it reaches the desk.
- **`sync.changed`** tells a desk that announces `sync.v1` a repo it tracks was pushed, by another
  install: `url` is the repo's (§"Repos") and `headSha` `main`'s new commit. A nudge, never a reply and
  never kept for a desk that is away, so a desk still finds every change by syncing. The desk matches
  `url` only against the repo URLs it already stores, and never fetches a URL a message names.

## Replies

A reply is a message whose envelope `replyTo` is the `id` of the request it answers. Besides the
handshake (`challenge` → `auth` → `welcome`), three requests expect one:

- **`rtc.offer`** is answered by `rtc.answer`, or by `rtc.close` when the desk refuses it
  (`REMOTE_ACCESS_OFF`, `REMOTE_SESSION_DENIED`, `REMOTE_SESSION_LIMIT`, `SIDECAR_UNAVAILABLE`), within
  `RTC_OFFER_REPLY_TIMEOUT` (75 s), which outlasts the minute a desk that approves each session gives an
  admin to allow it.
- **`link.accepted`** is answered by `link.ack`, linked or not.
- **`mcp.call`** is answered by `mcp.result`, or by `error` when the desk refuses the call: its `code`
  is `MCP_ACCESS_OFF`, `DESK_BUSY`, `CLOUD_ACCOUNT_NOT_LINKED`, `MCP_GRANT_MISMATCH`, `TOKEN_REVOKED` or
  a token refusal.

An unanswered request is the asker's to time out, and the asker drops a reply whose `replyTo` it no
longer waits for. Every other message is sent and forgotten. `link.invite` is not a request in this
sense: its answer, `link.accepted` or `link.refused`, may come days later and over another connection,
so it names the invite rather than the message.

## Capabilities

`hello`'s `capabilities` and `capabilities`' `list` (`Capability`) say what the desk speaks and what
stops it serving it, so the cloud can tell a user why before it asks:

| Capability | Means | The cloud refuses with |
|---|---|---|
| `remote.v1` | The desk takes remote sessions | `DESK_REMOTE_UNSUPPORTED` when absent |
| `mcp.v1` | The desk relays MCP calls | `DESK_MCP_UNSUPPORTED` when absent |
| `sync.v1` | The desk syncs with cloud repos and takes `sync.changed` | nothing: the cloud sends it no `sync.changed` |
| `remote.off` | Its *Remote sessions* switch is off | `REMOTE_ACCESS_OFF` |
| `mcp.off` | Its *Claude (MCP)* switch is off | `MCP_ACCESS_OFF` |
| `remote.no-sidecar` | No sidecar is running | `SIDECAR_UNAVAILABLE` |

`remote.v1` and `mcp.v1` are announced whatever their switches say. A `capabilities` message replaces
the whole list. A capability the cloud does not know is ignored.

## Close codes

`CloseCode`, with the detail each carries in the close's reason text. Only 4004 changes the desk's
state; after any other close the desk reconnects.

| Code | Name | Reason text |
|---|---|---|
| 4000 | `PROTOCOL_ERROR` | free text |
| 4001 | `PROTOCOL_UNSUPPORTED` | the lowest desk version still served |
| 4003 | `AUTH_FAILED` | an `AuthFailure`: `BAD_SIGNATURE`, `CLOCK_SKEW` (the desk says "check this machine's clock"), `MALFORMED`, `TIMEOUT` |
| 4004 | `INSTALL_REVOKED` | free text |
| 4009 | `REPLACED` | free text: a newer connection from the same install took its place |

## Codes

`ErrorCode` lists the codes the protocol carries: an `error` message's or `ApiError`'s `code`, an
`rtc.close`'s `reason`, a `link.ack`'s `reason`, a `ctl` `refused`'s `code`, and the cloud's own
refusals to the portal, the shell and MCP clients. They are strings, so a newer code reads as unknown
rather than failing to decode. `close-codes.json` pins every list here and the constants.

| Code | Means |
|---|---|
| `PROTOCOL_ERROR`, `BAD_SIGNATURE`, `CLOCK_SKEW`, `MALFORMED`, `NOT_FOUND` | P0's: a bad frame, and the install key's refusals |
| `CLOUD_ACCOUNT_NOT_LINKED` | The account has no link to a local user on the desk |
| `REMOTE_ACCESS_OFF`, `MCP_ACCESS_OFF` | The desk's switch for that door is off |
| `REMOTE_SESSION_DENIED` | An admin at the desk denied the session, or none allowed it in time |
| `REMOTE_SESSION_LIMIT` | The desk has as many sessions open, or offers this minute, as it takes |
| `SESSION_ENDED` | A session ended for none of the other reasons |
| `REMOTE_SESSION_UNKNOWN` | A remote token for a `sid` the desk never opened, or has ended |
| `REMOTE_SESSION_MISMATCH` | A remote token whose `sub` or `org` is not its session's; the desk ends the session |
| `TOKEN_REVOKED` | A token that verifies but whose target a `revoke` covers |
| `MCP_GRANT_MISMATCH` | An `mcp.call` whose `grant.id` is not its token's `gid` |
| `SIDECAR_UNAVAILABLE` | The desk's sidecar is not running |
| `DESK_OFFLINE` | The cloud has no channel open to the desk |
| `DESK_DISCONNECTED` | The channel closed with a request outstanding |
| `DESK_TIMEOUT` | The desk did not answer in time |
| `DESK_BUSY` | The desk runs as many relayed calls as it takes |
| `DESK_REMOTE_UNSUPPORTED`, `DESK_MCP_UNSUPPORTED` | The desk does not announce `remote.v1` or `mcp.v1` |
| `REQUEST_TOO_LARGE` | A body over `MAX_RELAYED_REQUEST_BYTES` |
| `LINK_EMAIL_MISMATCH`, `LINK_INVITE_NOT_LIVE`, `LINK_TAKEN` | Why a `link.ack` refused |
| `TOKEN_*` | Why a desk access token (§"Desk access tokens") or a git token (§"Git") was refused |
| `SYNC_OFF` | The org does not have cloud sync |
| `REPO_EXISTS` | A create for a project that already has a repo, answered with it (§"Repos") |
| `REPO_NOT_FOUND` | A repo the org does not have: never made, deleted, or another org's |
| `SYNC_READ_ONLY` | A push under a credential that may only read |
| `SYNC_NON_FAST_FORWARD`, `SYNC_REF_PROTECTED`, `SYNC_TAG_IMMUTABLE`, `SYNC_FORMAT_INVALID`, `SYNC_QUOTA_EXCEEDED`, `SYNC_STORAGE_UNAVAILABLE` | Why a pushed ref was refused (§"Git") |

## Desk access tokens

How the cloud tells a desk who is calling (cloud-p1 D2), in `DeskTokens.kt`: a compact JWS (RFC 7515),
`alg` `EdDSA` over Ed25519 (RFC 8037), signed by the cloud and checked by the desk with the JDK's
Ed25519, so neither side carries a JWT library. Both use `DeskTokens`, so the cloud signs exactly the
bytes the desk checks.

- **Header**: `{"alg":"EdDSA","kid":…,"typ":"JWT"}`, `kid` naming the key in the JWKS. The keys a
  `jwks` names sign desk access tokens and nothing else, so no other JWT the cloud issues can pass for
  one (RFC 8725 §3.11).
- **Claims** (`DeskAccessClaims`): `iss` (the cloud's origin), `aud` (the install id), `sub` (the cloud
  account id), `org`, `scope` (`remote` or `mcp`), `sid` for a remote session, or `gid` and `azp` (the
  MCP grant and its client) for an MCP call, `iat` and `exp` in seconds since the epoch, and `jti`.
- **Bytes**: each part is base64url without padding of the compact JSON `CloudJson` writes, the fields
  in the order above and absent ones left out; the signature is over the ASCII `header.claims`.
  Ed25519 is deterministic, so a token's string is exact.

`DeskTokens.verify(token, keys, audience, scope, now)` answers `TokenCheck.Valid` with the claims or
`TokenCheck.Refused` with the first `TokenRefusal` found, checking the signature before trusting any
claim:

| Refusal | Code | When |
|---|---|---|
| `MALFORMED` | `TOKEN_MALFORMED` | Not three canonical base64url parts, over 4096 characters, a header or claims that do not decode, an `alg` other than `EdDSA`, a `typ` other than `JWT`, any `crit` header (a null one included), `exp` not after `iat`, a time outside 1970–9999, or claims of the wrong shape for their scope (a remote token without `sid` or with a grant; an MCP token without `gid` and `azp` or with a `sid`) |
| `UNKNOWN_KEY` | `TOKEN_UNKNOWN_KEY` | `kid` is not in the desk's JWKS |
| `BAD_SIGNATURE` | `TOKEN_BAD_SIGNATURE` | The signature does not verify under that key |
| `WRONG_AUDIENCE` | `TOKEN_WRONG_AUDIENCE` | `aud` is not this install |
| `WRONG_SCOPE` | `TOKEN_WRONG_SCOPE` | `scope` is not the door's: a remote token at the MCP relay, or the reverse |
| `TOO_LONG_LIVED` | `TOKEN_TOO_LONG_LIVED` | `exp` is more than 10 minutes after `iat` (`DeskTokens.MAX_LIFETIME`) |
| `NOT_YET_VALID` | `TOKEN_NOT_YET_VALID` | `iat` is more than 60 s ahead of the desk's clock (`DeskTokens.CLOCK_SKEW`) |
| `EXPIRED` | `TOKEN_EXPIRED` | `exp` is more than 60 s behind the desk's clock |

`DeskTokens.sign` refuses (`IllegalArgumentException`) claims `verify` would refuse whatever the time,
so the cloud cannot mint a token no desk takes. `verify` does not judge `iss`, `sub`, `org` or `jti`:
what they mean is the desk's, and so are its refusals for them: `REMOTE_SESSION_UNKNOWN`,
`REMOTE_SESSION_MISMATCH` and `TOKEN_REVOKED`.

**The JWKS.** `jwks {keys}` lists the keys tokens are signed with, each an RFC 8037 JWK
(`Jwk {kty: "OKP", crv: "Ed25519", kid, x}`, `x` the raw key as `InstallKeys` writes one); a JWK with
only `kid` and `x` reads as Ed25519. Every field is optional on the wire, so a JWKS that also holds a
key of another type (RSA's `n` and `e`, no `x`) still decodes. `publicKeysOf` turns the list into the
map `verify` takes, leaving out a key with no `kid`, one that is not Ed25519 and one that is not
canonical, and keeping the first usable key of a `kid` named twice. There is
no public JWKS URL: only desks verify, and they hear the keys over the channel.

## Git

The cloud serves each repo as a bare git repository over smart HTTP at its `url` (§"Repos"). An adopted
desk proves itself with a **git token** it signs (cloud-p2 D1), in `InstallGitTokens.kt`: the same
compact JWS as a desk access token, but signed by the install key and checked by the cloud against the
public key of the install it names, so syncing needs no round trip through the control channel.

- **Header**: `{"alg":"EdDSA","typ":"l7git"}`, no `kid`: `iss` names the key. The type keeps a git
  token and a desk access token from passing for each other.
- **Claims** (`InstallGitClaims`): `iss` (the install id), `aud`, `iat`, `exp` and `jti`, in that
  order, encoded as desk access tokens are. `aud` is the cloud's origin in one spelling (scheme and host
  lowercased, a default port dropped, no path) and `/git`: `https://cloud.example/git`,
  `http://localhost:5173/git`. Both sides compute it with `InstallGitTokens.audience`, which refuses
  anything but an http(s) URL.
- **Sent** as HTTP Basic auth, user `x-install-token` (`InstallGitTokens.BASIC_USER`), the token the
  password. `InstallGitTokens.mint` makes one living `MAX_LIFETIME` (10 minutes) with a random `jti`,
  which the desk does per sync run.

`InstallGitTokens.verify(token, audience, now, keyOf)` answers `GitTokenCheck.Valid` with the claims or
`GitTokenCheck.Refused` with the first `TokenRefusal` found. It reads `iss` from the unverified claims
only to find the key (`keyOf`, null for an install the cloud does not hold or has removed, and never
asked for an empty `iss`), checks the signature before trusting anything else, and refuses claims whose
`iss` is not the one it looked up:

| Refusal | When |
|---|---|
| `MALFORMED` | As a desk access token's, a `typ` other than `l7git`, no `iss` or an empty one, or an empty `aud` or `jti` |
| `UNKNOWN_KEY` | `keyOf(iss)` is null |
| `BAD_SIGNATURE` | The signature does not verify under that install's key |
| `WRONG_AUDIENCE` | `aud` is not the cloud's git |
| `TOO_LONG_LIVED` | `exp` is more than 10 minutes after `iat` (`InstallGitTokens.MAX_LIFETIME`) |
| `NOT_YET_VALID`, `EXPIRED` | `iat` ahead of, or `exp` behind, the cloud's clock by more than 60 s (`InstallGitTokens.CLOCK_SKEW`) |

`WRONG_SCOPE` never applies: a git token has no scope. What the cloud answers a refused token is its
own (a 401 with `WWW-Authenticate: Basic`).

**Push refusals.** The cloud refuses a pushed ref with a reason `CODE: words`, which JGit reports as the
ref's message: `SYNC_NON_FAST_FORWARD` (`main` would not fast-forward), `SYNC_REF_PROTECTED` (a delete of
`main`, or a ref other than `main` and a tag), `SYNC_TAG_IMMUTABLE` (a tag that exists would move or go),
`SYNC_FORMAT_INVALID` (`main`'s tree has no well-formed `formatVersion.json`), `SYNC_QUOTA_EXCEEDED` and
`SYNC_STORAGE_UNAVAILABLE`. `PushRefusals.reason` writes one and `PushRefusals.codeOf` reads its code
back, null for a reason not of that form or a code not in `PushRefusals.CODES`, so a newer refusal reads
as an unknown one.

**Refused requests.** A git request under no credentials, or ones the cloud refuses, is 401 with
`WWW-Authenticate: Basic`. An org without cloud sync is 403 and a repo the org lacks is 404 on every
git route, as on the repo API; JGit's client reports a git 403 or 404 by its status alone, so the desk
learns which by asking `GET /v1/repos/{repoId}`, whose `ApiError` names `SYNC_OFF` or
`REPO_NOT_FOUND` (cloud-p2 D4). A developer token is sent as Basic auth too, any user (the desk sends
`x-dev-token`), the token the password.

## The link framing

A remote session is a WebRTC peer connection between the browser (the cloud's shell and its service
worker) and the desk's sidecar, `lighting7-link` (cloud-p1 D7, D8). Each HTTP request and each
WebSocket rides a data channel of its own, labelled by kind (`LinkFraming`): `ctl` (one per session,
opened first, §"ctl"), `http` and `ws`, all ordered and reliable. On `http` and `ws` every data-channel
message is binary, its first byte its kind:

| Byte | Kind | Payload |
|---|---|---|
| `0x01` | `HEAD` | UTF-8 JSON, at most 65,535 bytes, so the frame is at most 64 KiB |
| `0x02` | `DATA` | 1 to 16 KiB of body bytes, raw |
| `0x03` | `END` | none: the body is complete |
| `0x04` | `RESET` | UTF-8 JSON `{reason}`: the exchange is abandoned |
| `0x10` | `TEXT_PART` | 1 to 16 KiB of a WebSocket text message, more to come |
| `0x11` | `TEXT_END` | its last (or only) 0 to 16 KiB |
| `0x12` | `BINARY_PART` | 1 to 16 KiB of a WebSocket binary message, more to come |
| `0x13` | `BINARY_END` | its last (or only) 0 to 16 KiB |
| `0x20` | `CLOSE` | UTF-8 JSON `{code, reason}`: the WebSocket closed |

- **HTTP**: the browser sends `HEAD {method, path, headers, origin}` (`path` with its query; `headers`
  as `[[name, value], …]`; `origin` the request's origin as the service worker sees it, which the
  sidecar forwards as `Origin`), its body as `DATA`, then `END`; the sidecar answers `HEAD {status,
  headers}`, `DATA`, `END`, and closes the channel. Either side may send `RESET` instead, after which
  the channel closes.
- **WebSocket**: the browser sends `HEAD {path, headers, origin}`; the sidecar answers `HEAD {status,
  headers}`, `101` when the desk upgraded, otherwise the refusal's status, after which the channel
  closes. Then each message is zero or more `_PART` fragments and one `_END` of its kind (a text
  message is split at byte boundaries and decoded once whole); an empty message is one empty `_END`.
  `CLOSE {code, reason}` from either side ends it, answered by the other's own `CLOSE` if it has not
  sent one; the desk's codes (4401 among them) pass through unchanged.
- **Backpressure**: a sender stops writing to a channel once its `bufferedAmount` passes 1 MiB and
  resumes at its `bufferedAmountLowThreshold`, 256 KiB, so a 100 MB upload streams in bounded memory.
- **What the sidecar adds**: it removes from a head's `headers`, without regard to case, every header
  in `LinkFraming.STRIPPED_HEADERS`: those that authorise or identify a request (`Authorization`,
  `Cookie`, `Host`, `Lighting7-Link`, `Origin`) and those its own connection to the desk owns
  (hop-by-hop headers and the WebSocket handshake's). It then sets `Authorization` to the session's
  latest token, `Lighting7-Link` to the desk's link secret and `Origin` to the head's `origin`, once
  each; the browser's headers never authorise anything.

`protocol/link/framing.json` gives the constants, the stripped headers and six exchanges frame by
frame, each frame its kind, its JSON or text, and its bytes as hex (a long body as `fill {byte,
count}`).

## ctl

The `ctl` channel carries `CtlMessage`s, JSON text with `type` naming each; a reader ignores a `type`
it does not know (`CtlMessage.decodeOrNull` answers null for one). `protocol/link/ctl.json` has one of
each.

| Type | Direction | Body |
|---|---|---|
| `hello` | browser → sidecar, first | `token`: the session's desk access token |
| `ready` | sidecar → browser | `user?`, `role?` for a linked session, `unlinked: true` for an unlinked one, and `build` |
| `refused` | sidecar → browser | `code`: why the desk refused the token; the sidecar then ends the session |
| `token` | browser → sidecar | `token`: a refreshed token for the same session |

The sidecar decides nothing: it answers `hello` by asking the desk's `auth/status` with the token.

## The remote frame

The `postMessage` messages between the cloud's shell, the desk's SPA framed in it, and the shell's
service worker (cloud-p1 D8, D9), each a JSON object whose `type` is one of `RemoteFrame.MESSAGE_TYPES`.
`protocol/remote-frame/messages.json` lists each with its sender, receiver and channel (`over`: the
window, the SPA's `MessagePort`, or the worker), and the ports it transfers.

- **The SPA speaks first**, so nothing is posted before it listens: `hello {protocol: 1}` to the
  parent, at the origin the page's `lighting7-remote-parent` meta names, answered by `port`,
  transferring the `MessagePort` its socket rides. A message from any other origin is ignored.
- **The socket**, on that port, one at a time: the SPA sends `open {path}` and the shell answers `open`
  once the socket is open; `send {data}` and `message {data}` carry text messages; `close {code,
  reason}` either way ends it, the shell's carrying the desk's code. An `open` replaces any socket the
  shell holds for the port, which it closes without a `close`; the SPA's `close` is not answered; and a
  shell `close` while the SPA waits for `open` means that socket did not open. The port outlives its
  sockets: the shell keeps it while the frame lives. A port the SPA takes as gone — its `close` event,
  which only some browsers fire, or an `open` unanswered for 15 s — it replaces with another `hello`,
  whose `port` supersedes the old one: the shell drops whatever it held on that.
- **The session**, on the same port: `session.end` (*Sign out*) and `auth.unlinked` (the account is not
  linked here), SPA → shell.
- **A restarted service worker** has lost its port: it posts `port.request` to the SPA, which posts
  `port.request` on its own port; the shell answers `port` with a fresh one, which the SPA hands to the
  worker as `port`.

## Adoption

RFC 8628's shape, run by the desk's backend (D6). Fixtures in `protocol/adoption/`.

| Call | Request | Answer |
|---|---|---|
| `POST /v1/adoptions` | `AdoptionRequest {publicKey, deskInstallUuid, friendlyName, platform, deskVersion}` | `AdoptionStarted {adoptionId, userCode, verificationUri, verificationUriComplete, intervalS, expiresInS}` |
| `POST /v1/adoptions/{adoptionId}/poll`, signed | no body | `AdoptionPollResult`, by `status`: `pending`, `slow_down {intervalS}`, `approved {installId, org {id, name}, account {id, email, displayName}}`, `denied`, `expired` |
| `DELETE /v1/installs/{installId}`, signed | no body | 2xx, or an `ApiError` |

A refusal is an HTTP error status with an `ApiError {code, message}` body. `userCode` is `XXXX-XXXX`
from RFC 8628's twenty consonants (`UserCode`). An `AdoptionPollResult` status this version does not
know fails to decode, so a new status is a new protocol version. Its `status` is written only when the
value is encoded as an `AdoptionPollResult`, not as one of its subclasses: Ktor's `call.respond(value)`
takes the static type, so the cloud responds with `call.respond<AdoptionPollResult>(…)`.

A **signed request** carries `Lighting7-Timestamp` (a `Timestamps` string) and `Lighting7-Signature`
(`SignatureHeaders`): a poll over `AdoptionPollPayload(adoptionId, timestamp)`, an unadopt over
`UnadoptPayload(installId, timestamp)`. The cloud verifies against the key the adoption was started
with, so a leaked adoption id is useless to anyone without the install key.

## Repos

The repos an org's desks sync with (cloud-p2 D2), one per project uuid. Fixtures in `protocol/repos/`.

| Call | Request | Answer |
|---|---|---|
| `POST /v1/repos`, signed | `CreateRepoRequest {projectUuid, kind, name}` | 201 `RepoDto`, or 409 `RepoExistsError {code: REPO_EXISTS, message, repo}` with the project's repo |
| `GET /v1/repos`, signed | no body | `RepoList {repos}`, newest push first |
| `GET /v1/repos/{repoId}`, signed | no body | `RepoDto`, or 404 `REPO_NOT_FOUND` |

`RepoDto` is `{repoId, url, projectUuid, kind, name, headSha?, sizeBytes, formatVersion?, lastPushAt?}`:
`url` is `<cloud origin>/git/<orgId>/<repoId>.git`, by ids, so a rename in the portal moves nothing on
a desk; `kind` is `SHOW` or `VENUE` (`RepoKind`), or a newer kind, a string the reader skips; `headSha` is `main`'s commit, absent before the first
push; `formatVersion` is the last push's; `lastPushAt` a `Timestamps` string. Every call is refused
403 `SYNC_OFF` for an org without cloud sync. A `RepoExistsError` read as an `ApiError` is one, so a
reader that only wants the code needs nothing more.

A call to the repo API names no install in its path, so it carries a third header, `Lighting7-Install`
(`SignatureHeaders.INSTALL`), and signs a `RepoApiPayload`: the install, the method, the path exactly as
sent on the request line, which is `CloudPaths`' string (`/v1/repos`, `/v1/repos/{repoId}`) with no query,
since the repo API takes none, the timestamp, and the lowercase hex SHA-256 of the body (an empty body's for a `GET`),
built by `RepoApiPayload.of`. Binding the method, path and body keeps a signature from being replayed as
another call within the timestamp's tolerance.

## Signing

`InstallKeys` is the install key (D4): Ed25519 from the JDK, no dependency.

- **Public key** on the wire: its 32 raw bytes (RFC 8032), base64url without padding. A key or
  signature spelled any other way (padded, or with stray bits in its last character) is refused, so
  one value has one string.
- **Private key** as the desk stores it: PKCS#8, base64. Never sent.
- **Fingerprint**: `SHA256:` and the unpadded base64 of the raw key's SHA-256.
- **Signature**: Ed25519, base64url without padding. Deterministic, so a vector is exact.
- **Timestamps** are ISO-8601 UTC with three fraction digits, `2026-11-02T22:18:03.000Z`
  (`Timestamps.format`). `Timestamps.parse` refuses any other spelling of an instant (lowercase, an
  offset, another number of digits). A signature covers the string as sent, never a reformatting.

Each signed thing is a `SignedPayload`, whose bytes are its domain string followed by its fields, each
part written as a 4-byte big-endian length and its UTF-8 bytes. The domain is the first part, so a
signature over one kind never verifies as another, and the lengths keep a field from running into the
next.

| Payload | Domain | Fields, in order |
|---|---|---|
| `AuthPayload` | `lighting7-cloud/auth/v1` | `installId`, `nonce`, `timestamp` |
| `AdoptionPollPayload` | `lighting7-cloud/adoption-poll/v1` | `adoptionId`, `timestamp` |
| `UnadoptPayload` | `lighting7-cloud/unadopt/v1` | `installId`, `timestamp` |
| `RepoApiPayload` | `lighting7-cloud/repo-api/v1` | `installId`, `method`, `path`, `timestamp`, `bodySha256` |

A later phase that signs something new (P3's backups) adds a payload with its own domain. A desk access
token and a git token are not `SignedPayload`s: each is a JWS, its bytes JOSE's (§"Desk access tokens",
§"Git").

## Golden fixtures

`cloud-protocol/src/test/resources/protocol/` is the contract both repos test against (R3):

- `messages/*.json`: one envelope per message type, named by its type, and `unknown.json`.
- `adoption/*.json`: every adoption body and `api-error.json`.
- `repos/*.json`: every repo API body, and `push-refusals.json`, reasons and the code each parses to.
- `close-codes.json`: the close codes, auth failures, error codes, token refusals, capabilities and the
  constants.
- `tokens/vectors.json`: the test key's JWK under `kid` `test-1`, a clock (`now`), and desk access
  tokens: five that verify, each with its claims and scope, and one per way a token is refused, each
  with the refusal it gets. Signed with the test key, bar `wrong-key`'s, which RFC 8032's TEST 2 key
  signs.
- `tokens/git.json`: git tokens for the test key's install, at the same clock: four that verify, each
  with its claims, and one or more per way a git token is refused. `wrong-key`'s is signed with TEST 2.
- `link/framing.json` and `link/ctl.json`: the sidecar's framing and `ctl` (§"The link framing").
- `remote-frame/messages.json`: the remote-frame messages (§"The remote frame").
- `signing/test-key.json`: RFC 8032 §7.1's TEST 1 key, its encodings and fingerprint, and the RFC's
  signature of the empty message. A published key, so never one any real install holds.
- `signing/vectors.json`: each payload's fields, canonical bytes (hex) and signature under the test
  key.

`GoldenFixturesTest` decodes each fixture to the value it names and re-encodes it to the same JSON, and
fails on a message type with no fixture or a fixture nothing checks. `InstallKeysTest` checks every
signing vector, `DeskTokensTest` every desk access token vector and `InstallGitTokensTest` every git
token vector (the valid ones sign to their tokens, the refused ones are refused for their reason),
`PushRefusalsTest` every push-refusal reason, and `LinkContractsTest` the framing, `ctl` and remote-frame
fixtures, which describe contracts the desk's JVM never speaks: each frame's bytes are its kind and
payload, within the limits, in an order its label allows. The signing and token vectors were computed
with Python's `cryptography` package, outside this code, so passing them says the desk signs and checks
what another Ed25519 implementation would. The framing, `ctl` and remote-frame fixtures are read by the
sidecar's Go tests, the SPA's Vitest and the cloud's tests too.

## Versioning

Within `PROTOCOL_VERSION` every change is additive: a new message type (an old reader sees
`Message.Unknown`), a new optional field (an old reader ignores it), a new capability, a new error code,
a new `ctl` or remote-frame message type, a new framing kind on a new label. Anything else is a new
version: removing or renaming a field or a type, making an optional field required, a new adoption
status, a new `link.ack` outcome or token scope (both enums, which an old reader cannot decode), a change
to a payload's bytes (which takes a new domain string).
The cloud serves a new version beside the old one for at least one desk minor version (NFR-COMPAT-1),
and closes 4001 a desk it no longer serves.

A new message or field lands here with its fixture before either side uses it (cloud-p0 P3).
