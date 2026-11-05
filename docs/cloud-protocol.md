# The cloud protocol

`cloud-protocol/` is the wire contract between the desk and Lighting7 Cloud: the control channel's
envelope and messages, the adoption API's bodies, the close and error codes, and the install key's
signing. It is MIT like the rest of the desk, and it is the desk's: a protocol change is a desk PR first
(cloud design record R3). The cloud builds it from a pinned desk commit (R4). P0 is the cloud-p0 plan
(`docs/plans/completed/cloud-p0-plan.md`), decisions D3 to D10.

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
   (`CLOCK_SKEW_TOLERANCE`, 5 minutes), then → `welcome {orgName, heartbeatS}`. A refusal closes 4003
   with an `AuthFailure` name as the reason.
5. Desk → `hello {protocol, deskVersion, platform, deskInstallUuid, friendlyName, capabilities}`. P0's
   capabilities are empty; later phases add theirs here.
6. Desk → `health {uptimeS, deskVersion}` every `heartbeatS` seconds (25 in P0).

## Messages

Each has a fixture in `cloud-protocol/src/test/resources/protocol/messages/`.

| Type | Direction | Body | Fixture |
|---|---|---|---|
| `challenge` | cloud → desk | `nonce`, `serverTime` | `challenge.json` |
| `auth` | desk → cloud | `installId`, `timestamp`, `signature` | `auth.json` |
| `welcome` | cloud → desk | `orgName`, `heartbeatS` | `welcome.json` |
| `hello` | desk → cloud | `protocol`, `deskVersion`, `platform`, `deskInstallUuid`, `friendlyName`, `capabilities` | `hello.json` |
| `health` | desk → cloud | `uptimeS`, `deskVersion` | `health.json` |
| `unadopted` | cloud → desk | none: the install was removed in the portal | `unadopted.json` |
| `error` | either | `code` (`ErrorCode`, or a newer one), `message` | `error.json` |

`unknown.json` is a type this version does not know, and must survive a decode and re-encode.

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

`ErrorCode` lists the codes an `error` message or an `ApiError` body carries: `PROTOCOL_ERROR`,
`BAD_SIGNATURE`, `CLOCK_SKEW`, `MALFORMED`, `NOT_FOUND`. They are strings, so a newer code reads as
unknown rather than failing to decode. `close-codes.json` pins all three lists and the constants.

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

A later phase that signs something new (P2's git token, P3's backups) adds a payload with its own
domain.

## Golden fixtures

`cloud-protocol/src/test/resources/protocol/` is the contract both repos test against (R3):

- `messages/*.json`: one envelope per message type, and `unknown.json`.
- `adoption/*.json`: every adoption body and `api-error.json`.
- `close-codes.json`: the close codes, auth failures, error codes and the constants.
- `signing/test-key.json`: RFC 8032 §7.1's TEST 1 key, its encodings and fingerprint, and the RFC's
  signature of the empty message. A published key, so never one any real install holds.
- `signing/vectors.json`: each payload's fields, canonical bytes (hex) and signature under the test
  key.

`GoldenFixturesTest` decodes each fixture to the value it names and re-encodes it to the same JSON, and
fails on a message type with no fixture or a fixture nothing checks. `InstallKeysTest` checks every
vector. The signing vectors were computed with Python's `cryptography` package, outside this code, so
passing them says the desk signs what another Ed25519 implementation would.

## Versioning

Within `PROTOCOL_VERSION` every change is additive: a new message type (an old reader sees
`Message.Unknown`), a new optional field (an old reader ignores it), a new capability in `hello`, a new
error code. Anything else is a new version: removing or renaming a field or a type, making an optional
field required, a new adoption status, a change to a payload's bytes (which takes a new domain string).
The cloud serves a new version beside the old one for at least one desk minor version (NFR-COMPAT-1),
and closes 4001 a desk it no longer serves.

A new message or field lands here with its fixture before either side uses it (cloud-p0 P3).
