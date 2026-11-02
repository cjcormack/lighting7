# Lighting7 Cloud P0: the desk's foundations

> **Document status: READY — decisions confirmed 2026-11-02.** The desk half of the PRD's phase P0.
> The cloud half is `docs/plans/p0-plan.md` in `cjcormack/lighting7-cloud`. Design record:
> [`cloud-design/INDEX.md`](cloud-design/INDEX.md), whose rules R1–R7 apply to every session here.

## 1. Context

Lighting7 Cloud is a hosted service that desks connect *out* to (PRD §1). It will replace the ngrok
tunnel, the desk's own OAuth server and GitHub sync, and the desk keeps working with no account and
through any outage (G-6, PR-1, PR-2). P0 builds the ground every later phase stands on: an account
that owns a desk, a desk that can prove who it is, and one outbound connection that says it is online.
No remote session, MCP relay or sync moves in P0; ngrok and GitHub sync stay exactly as they are.

On the desk, P0 is five things (PRD §6, §9.10, §13):

1. **The desk becomes MIT** (D-1): a `LICENSE`, third-party notices, and a licence audit (RISK-9).
2. **`cloud-protocol/`**, an MIT Gradle build the private cloud consumes (§9.2, R3).
3. **Adoption** (FR-ADOPT-1 to -5, -7): an install key, a device-code flow, and unadopting.
4. **The control channel** (§9.3, FR-FLEET-1's presence): one outbound WSS connection, authenticated
   by the install key, with heartbeats, and isolated from the show (NFR-SAFE-1).
5. **Install settings → Cloud**, the admin's view of all of it.

The desk's identity today is the single `installs` row (`models/installs.kt`): a `uuid` and a
`friendlyName`, registered nowhere. Secrets live in `CredentialStore` (`sync/auth/CredentialStore.kt`,
OS keychain or encrypted file). The nearest precedent for a supervised outbound service is
`mcp/tunnel/RemoteAccessService.kt`, which streams `tunnel.state` to admin sockets.

P0 is done when the operator check `FU-MANUAL-CLOUD-P0` (§6) passes against the deployed cloud.

## 2. Decisions

- **D1 — MIT for the code, not for everything in the tree.** `LICENSE` covers the source the project
  wrote. Third-party material is listed in `THIRD-PARTY-NOTICES.md` under its own licence, and material
  the project may not redistribute leaves the tree. Known suspects to settle first: `ch.bildspur:artnet4j`
  (the artnet4j it forks was published under the GPL; if this one is too, an MIT binary cannot bundle
  it), `uk.co.xfactory-librarians:coremidi4j` (EPL), the manufacturers' manuals under `Manuals/` and the
  PDF at the root, the MagicQ-derived personality transcriptions in `Manuals/personalities/`, the gel
  library (`src/main/resources/gels.json`) and the lantern library. The repo is already public, so
  these questions exist today; relicensing only makes them explicit. Purging history is the owner's
  call, not a session's (§7).
- **D2 — A licence gate in CI.** A check that fails on a dependency licence outside an allowlist,
  covering the root and `launcher` runtime classpaths, `frontend/`'s production npm dependencies, and
  a hand-kept table of bundled natives and vendored data. `THIRD-PARTY-NOTICES.md` is generated from
  the same data and checked for drift. Later phases extend it (P1's Go sidecar adds its modules).
- **D3 — `cloud-protocol/` is its own Gradle build**, with its own `settings.gradle.kts`, included by
  the desk through `includeBuild("cloud-protocol")` and depended on by coordinates
  (`uk.me.cormack.lighting7:cloud-protocol`). A subproject would not do: the cloud's composite build
  (R4) needs a directory that builds alone from a sparse checkout. It reads `kotlin_version` and the
  serialization version from the root `gradle.properties` (cone-mode sparse checkouts always include
  root files), keeps the desk's warnings-as-errors, and the root `check` depends on its `check` so CI
  runs its tests. JVM only, `kotlinx.serialization`, no Ktor: both sides bring their own transport.
- **D4 — The install key is Ed25519 from the JDK** (`KeyPairGenerator.getInstance("Ed25519")`), no new
  dependency. The private key is a `CredentialStore` blob, `cloud:install-key` (PKCS#8, base64), never
  exported, synced or logged (FR-ADOPT-1). The public key and its fingerprint are also kept in
  `cloud_connection` for display. A key is made when adoption starts and deleted on unadopt; a desk
  never holds two.
- **D5 — Adoption starts only from the desk's own listener, by a signed-in admin.** 403
  `CLOUD_ADOPT_LOCAL_ONLY` on the public listener, whatever the role, as *Quit desk* is refused there;
  409 `CLOUD_NEEDS_ACCOUNT` on a zero-user desk (FR-ADOPT-7, mirroring `REMOTE_NEEDS_ACCOUNT`).
  Unadopting reduces reach, so an admin may do it from either listener.
- **D6 — The adoption flow is RFC 8628's shape, run by the backend.** `POST /v1/adoptions` sends the
  public key, `installs.uuid`, `friendlyName`, platform and desk version, and answers a user code
  (`XXXX-XXXX` from RFC 8628's 20-letter alphabet), a verification URI with and without the code, an
  interval and an expiry. The **desk backend** polls `POST /v1/adoptions/{id}/poll`, each poll signed
  with the install key so a leaked device code is useless to another key, and handles `slow_down`. The
  browser only displays progress, so closing it does not abandon the adoption. `approved` answers the
  cloud's install id, the org (id, name) and the approving account (id, email, display name).
- **D7 — Two machine-local tables.** `cloud_connection`, one row while adopted or adopting: state
  (`ADOPTING | ADOPTED`), cloud base URL, install id, org id and name, public key and fingerprint,
  adopted at, adopted by (local user id), the pending adoption's id, code and expiry.
  `cloud_identity_links`: local user id (cascade on user delete), cloud account id, email, linked at,
  linked via (`ADOPTION` in P0; P1 adds `INVITE` and `PROVED`). Adoption writes one link, for the admin
  who started it (FR-ADOPT-3). Nothing reads links for authorisation until P1 (FR-ID-3). Both tables
  `SyncDisposition.MachineLocal`; neither touches `ProjectExporter` or `ProjectImporter` (R7).
- **D8 — Unadopt from the desk is a signed HTTP call**, `DELETE /v1/installs/{installId}` with a
  signature, so it works without the control channel. If the cloud cannot be reached the desk refuses
  with 409 `CLOUD_UNREACHABLE` unless `?force=true` (api-conventions' guard override), which forgets
  locally: key, row and links deleted; the cloud's record keeps a revoked-in-effect key that can never
  connect, and the portal can remove it (FR-ADOPT-4). Unadopt from the **portal** reaches the desk as an
  `unadopted` message, or as close code `4004 INSTALL_REVOKED` at its next connect; either way the desk
  clears its local state and says so on the Cloud tab.
- **D9 — The control channel** (§9.3, with the corrections in the design record): `wss://<host>/v1/desk`;
  the cloud sends `challenge {nonce, serverTime}`; the desk answers `auth {installId, timestamp,
  signature}`, signing the domain-separated bytes `cloud-protocol` defines (one function both sides
  call, so they cannot drift); the cloud accepts ±5 minutes of skew and answers `welcome {orgName,
  heartbeatS}`; the desk sends `hello {protocol, deskVersion, platform, deskInstallUuid, friendlyName,
  capabilities}` (P0's capabilities are empty), then `health {uptimeS, deskVersion}` every
  `heartbeatS` (25 s). The cloud calls an install offline after three missed heartbeats. Reconnect is
  exponential from 1 s with full jitter, **capped at 30 s** (NFR-PERF-3). One connection per install: a
  second one replaces the first, which closes `4009 REPLACED`. Frames are JSON text, at most 8 MB.
  Unknown message types are ignored and logged once per type, so v1 can grow additively (R3).
- **D10 — Close codes and errors are protocol constants**: `4000 PROTOCOL_ERROR`, `4001
  PROTOCOL_UNSUPPORTED` (with the minimum desk version), `4003 AUTH_FAILED` (with a reason, `CLOCK_SKEW`
  among them, which the Cloud tab shows as "check this machine's clock"), `4004 INSTALL_REVOKED`, `4009
  REPLACED`. Only 4004 changes local state; every other close is followed by a reconnect.
- **D11 — Show safety is structural, not careful** (NFR-SAFE-1, R6). The connector runs in its own
  `CoroutineScope` on `Dispatchers.IO.limitedParallelism(2)`; its outbound queue is a bounded channel
  dropping the oldest; it reads desk state only from `StateFlow`s and `BuildInfo`, never under a lock
  the show takes. It **writes the database only on a state transition** (adopt, unadopt, revoke),
  never per heartbeat or reconnect: the pool has one connection (root `CLAUDE.md` §"Database"), so a
  timed write would queue behind, or in front of, show writes. Live status is memory only.
- **D12 — The host is pinned.** The connector talks only to `cloud.baseUrl`'s host, over `https` and
  `wss` with the JDK's normal certificate checks, and follows no redirect to another host. Plain
  `http`/`ws` is accepted only for a loopback host, so a desk can be pointed at a cloud running on the
  same machine (NFR-SEC-1).
- **D13 — Config.** `cloud { baseUrl = "", enabled = true }` in `local.conf` and `example.local.conf`.
  `enabled = false` is the hard kill switch the UI cannot override, like `update.enabled`. An empty
  `baseUrl` means "no cloud configured": the tab says so and the connector does not start. The
  production URL becomes the default once OQ-1 settles the domain (`FU-CLOUD-DEFAULT-URL`).
- **D14 — The REST and WS surface.** All under `/api/rest/install/cloud`, admin only, machine-scoped:
  `GET` the state; `POST .../adoption` starts one (409 `CLOUD_ALREADY_ADOPTED`,
  `CLOUD_ADOPTION_IN_PROGRESS`, `CLOUD_NOT_CONFIGURED`, `CLOUD_DISABLED`); `DELETE .../adoption`
  cancels one; `DELETE /install/cloud[?force=true]` unadopts (409 `CLOUD_NOT_ADOPTED`,
  `CLOUD_UNREACHABLE`). `cloud.state` is outbound only and `StateFlow`-backed, sent to admin sockets as
  `tunnel.state` is, registered in the machine-scoped band so it does not wait for a project:
  `{configured, enabled, adoption?: {userCode, verificationUri, verificationUriComplete, expiresAt},
  adopted?: {installId, orgName, adoptedAt, adoptedBy, linkedEmail}, connection: OFF | CONNECTING |
  ONLINE | RETRYING, retryAt?, lastError?: {code, message}, lastOnlineAt?, notice?}` (`notice` carries
  "removed from <org> in the cloud" after a revoke).

## 3. Rules for every session

- **P1 — Cloud down, desk unchanged.** Each session's review asks R6's question of every line it adds.
- **P2 — No real cloud.** Tests use `FakeCloud` (session 4), never a network (R5).
- **P3 — Additive within v1.** A message or field this plan adds is in `cloud-protocol` with a golden
  fixture before either side uses it (R3).
- **P4 — Nothing about ngrok, the desk's OAuth server or GitHub sync changes in P0.** Those are P1's and
  P2's deletions (FR-MIG-1 to -3).

## 4. Risks

- **The licence audit finds a blocker** (RISK-9). An artnet4j under the GPL would block an MIT binary.
  Session 1 is written so that it ships the audit and the gate either way, and adds `LICENSE` only when
  nothing blocks; a blocker becomes its own session (§5).
- **A timing test that flakes.** The safety test (session 5) measures wall clock. It compares against a
  baseline it takes itself, with generous bounds, joins the table in `docs/testing-engineering.md`
  §"Tests that assert real elapsed time", and follows `flaky-tests-plan.md` if it fails on CI.
- **The included build drifts from the desk's build settings.** Warnings-as-errors and the Kotlin
  version have to reach `cloud-protocol/`; session 3 checks both by introducing a warning and a version
  mismatch once, locally, and seeing each fail.

## 5. Implementation — six sessions

Each session is one PR, run by `/next-session`; sessions run in order. The cloud plan's sessions say
which of these they need (R2); nothing here needs the cloud. A session's PR is titled *Cloud P0:
session N — title*. Sessions 1 to 3 change the build, so they run the full `./gradlew check` (and
session 1 `npm run check`) locally; sessions 4 to 6 run the packages they touch, as root `CLAUDE.md`
§"Pre-commit checks" says.

If session 1's audit finds a dependency that blocks MIT, session 1 adds a session after itself for the
replacement (its row `planned`, its section saying what to replace and how to prove the desk's output
unchanged), and the later sessions renumber.

It did (artnet4j, under the GPL v3), and added session 2 on 2026-11-02. Every session after it moved up
one, so a cloud plan *Needs* line written before then that names this plan's session *N*, for *N* of 2
or more, now means session *N* + 1.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | MIT and the licence gate | in review | `claude/cloud-p0-img1pv` | | |
| 2 | Replace artnet4j | planned | | | |
| 3 | The protocol module | planned | | | |
| 4 | Adoption on the desk | planned | | | |
| 5 | The control channel | planned | | | |
| 6 | Install settings → Cloud | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session` fills
*Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts the
next; whoever merges the last fills its own.

### Session 1 — MIT and the licence gate

- **The audit.** List every runtime dependency of the root project and `launcher` with its licence,
  every production npm dependency of `frontend/`, every bundled native (sqlite-jdbc, JNA, libremidi,
  coremidi4j, as `shadowJar`'s payload verifier names them), and every piece of vendored data or
  document in the tree. Settle D1's suspects first, from each artifact's own POM and source, not from
  memory. Record the result as data the gate reads (D2), with a *why* for anything allowlisted by hand.
- **The gate** (D2): a Gradle task wired into `check` and an npm script wired into `npm run check`, or
  one script both call; the session picks the tool, preferring one that keeps the configuration cache
  and adds no warning (`org.gradle.warning.mode=fail`). It fails on an unknown or disallowed licence
  and on `THIRD-PARTY-NOTICES.md` drift. Allowlist to start: MIT, BSD-2/3, Apache-2.0, ISC, EPL-1.0/2.0
  and MPL-2.0 as separate libraries, the JNA dual licence; the GPL family is never allowed.
- **Material that cannot be redistributed** leaves the tree in this session: the manufacturers'
  manuals and the root PDF move to a private home the owner names (ask before choosing one), and the
  fixture KDocs that cite `Manuals/…` keep the citation as a document title, not a path. Personality
  transcriptions stay only if the audit finds them redistributable.
- **With no blocker:** `LICENSE` (MIT, copyright Chris Cormack), the README's "Private project" text
  replaced by the licence and a short trademark note (the cloud's name is not covered by MIT, §9.10),
  `frontend/package.json` and `frontend/design-system/package.json` `license` fields, and a line in root
  `CLAUDE.md`. **With a blocker:** everything but `LICENSE` and the README change, and §5's extra session.
- **Tests:** the gate itself, run red once on a planted GPL entry (then removed), and green.
  `./gradlew check shadowJar` and `npm run check` locally.
- **Docs:** `THIRD-PARTY-NOTICES.md`; a short `docs/licensing.md` saying how to add a dependency and
  what the gate allows, which later phases extend.

**Outcome (2026-11-02).** The audit found one blocker: `ch.bildspur:artnet4j` is under the GPL v3 (its
POM and its upstream `LICENSE`), and the jaxb-core and jaxb-impl it alone pulls in are CDDL 1.1 or GPL 2.
So the session shipped everything but `LICENSE` and the README change, and added session 2. Nothing
else blocks: CoreMIDI4J is EPL-1.0; the dual licences (logback, JNA, JavaCPP, Jetty ALPN,
java-json-tools) are taken under their allowed side; libremidi and alsa-javacpp link the system's ALSA
and JACK rather than bundle them; the vendored react-kotlin-playground and shadcn/ui code is MIT, its
notice now kept beside it; the gel and lantern libraries are the project's own.

- **The manuals:** asked where they should go, the owner answered "just remove them". The PDFs under
  `Manuals/` and the Laserworld PDF at the root left the tree with no private home; the owner keeps
  copies, and git history is the owner's call (§7).
- **The personality transcriptions** left too: they reproduce ChamSys's MagicQ head files, whose terms
  grant no redistribution. The fixture KDocs cite each by its `.hed` file name.
- **The gate** is `scripts/licences.mjs` over the data in `licences/`, run by `./gradlew licenceCheck`
  (in `check`, handed the resolved runtime modules) and `npm run licences` (in `check`, `check:quick`
  and CI's Frontend job). Two additions to the starting allowlist: `0BSD` (two npm packages) and
  `blessing` (SQLite's public domain). artnet4j and the two jaxb modules pass only as `pendingRemoval`,
  which also fails the gate if `LICENSE` exists while they stand.
- **Found outside the gate:** the installers' jlink runtime is Temurin under `GPL-2.0-only WITH
  Classpath-exception-2.0`, which the exception keeps from reaching the desk's code; the notices name
  it. The notices name each licence but carry no texts (`FU-LICENCE-TEXTS`), and a bump that relicenses
  a Java module is not caught, since `jvm.json` is keyed by name, not version; the owner chose to
  record that and leave it to `FU-LICENCE-POM-DRIFT`.

### Session 2 — Replace artnet4j

- **What goes:** `ch.bildspur:artnet4j`, and with it `com.sun.xml.bind:jaxb-core` and `jaxb-impl`;
  `build.gradle.kts`'s `javax.xml.bind:jaxb-api` exclude and the part of its comment that names
  artnet4j (`jakarta.xml.bind-api` stays: swagger-core needs it). The jvm.json rows and their
  `pendingRemoval` go, and the notices are regenerated.
- **What replaces it:** `DefaultArtNetTransport` (`dmx/ArtNetTransport.kt`) is artnet4j's only user, and
  the `ArtNetTransport` seam asks for four calls: `start`, `stop`, `broadcastDmx(subnet, universe,
  data)` and `unicastDmx(address, subnet, universe, data)`. Write an ArtDMX sender over a
  `DatagramSocket` from Artistic Licence's published Art-Net 4 specification, with no new dependency:
  the `Art-Net\0` ID, OpCode 0x5000 little-endian, ProtVer 14, Sequence, Physical, the port-address from
  the desk's (subnet, universe), Length big-endian and even, then the data. Write it from the
  specification, not from artnet4j's source, since the new code is MIT. Keep the behaviour the
  transport's KDoc relies on: only one socket in the process gets port 6454, later ones send from an
  ephemeral port, and `stop` closes whichever was bound.
- **Prove the output unchanged.** Before artnet4j goes, a test captures the datagrams it sends on a
  loopback receiver for a fixed set of inputs: several subnets and universes, a full 512-channel
  frame, a short and an odd-length one, broadcast and unicast. The captured bytes are committed under
  `src/test/resources/dmx/artdmx/` with what produced each, and the new transport must send exactly
  those bytes for the same inputs, Sequence included. Where the specification and artnet4j disagree,
  match artnet4j and say so in the test: the rig's nodes accept what it sends today. The existing
  `RecordingTransport` tests stay as they are.
- **Then the licence:** `LICENSE` (MIT, copyright Chris Cormack); the README's "Private project" text
  replaced by the licence and a short trademark note (the cloud's name is not covered by MIT, §9.10);
  `docs/licensing.md`'s opening and root `CLAUDE.md`'s licence line say the desk is MIT.
- **Tests:** the golden comparison; `./gradlew check shadowJar` locally, since the build changes;
  `npm run licences`.
- **Docs:** `docs/dmx-engineering.md`'s ArtNet section; an operator check in
  `docs/plans/manual-validation.md` that a real Art-Net node lights from the new transport as it did.

### Session 3 — The protocol module

- **The build** (D3): `cloud-protocol/` with `settings.gradle.kts`, `build.gradle.kts` and its own
  `gradle.properties` only where the root's cannot reach; `includeBuild` in the root settings; the
  root's `implementation("uk.me.cormack.lighting7:cloud-protocol")`; root `check` depending on the
  included build's `check`. CI unchanged unless it must name the included build.
- **The types**, package `uk.me.cormack.lighting7.cloud.protocol`: `PROTOCOL_VERSION = 1`; the envelope
  `{v, type, id, replyTo?, body}` as a sealed hierarchy with `@SerialName` types, decoding an unknown
  type to `Unknown(type)` rather than failing; the P0 messages of D9 and D8 (`challenge`, `auth`,
  `welcome`, `hello`, `health`, `unadopted`, `error {code, message}`); the adoption DTOs of D6; the close
  codes and error codes of D10 and D14 that cross the wire; `MAX_FRAME_BYTES = 8 MiB`.
- **Signing** (D4, D9): `InstallKeys` — generate, encode and decode Ed25519 keys, a fingerprint, and
  the canonical bytes for each signed thing (auth, poll, unadopt), each prefixed with its own domain
  string (`lighting7-cloud/auth/v1`, …) so no signature can be replayed as another.
- **Golden fixtures** in `src/test/resources/protocol/`: one JSON file per message and DTO, a fixed
  test key with the signature it gives for each signed payload (Ed25519 is deterministic, so the vector
  is exact), and a test that decodes, re-encodes and compares each. These are the contract the cloud
  runs too (R3, R4).
- **Tests:** the module's own; `./gradlew check` locally, since the build changed.
- **Docs:** `docs/cloud-protocol.md` — the envelope, the handshake, every message with its direction
  and fixture, close codes, the versioning rule (R3), and how the cloud consumes the module (R4). Root
  `CLAUDE.md` gains the module in its structure list and a line pointing at the doc.

### Session 4 — Adoption on the desk

- **Package `cloud/`**: `CloudConfig` (D13), `InstallKeyStore` over `CredentialStore` (D4),
  `models/cloudConnection.kt` and `models/cloudIdentityLinks.kt` (D7) in `ALL_TABLES` with their
  dispositions, `CloudAdoptionService` (D6: start, the backend poll loop with `slow_down` and expiry,
  approve → row `ADOPTED` and the link, deny or expiry → row and key deleted), unadopt (D8), and
  `CloudService` owning the `cloud.state` `StateFlow` (D14), started from `Application.module()` after
  the database, on D11's scope.
- **Routes** (D14) in `routes/installCloud.kt`, mounted with the admin routes; D5's two refusals.
- **`FakeCloud`** in `src/test/kotlin/.../testsupport/`: a Ktor test server built on `cloud-protocol`
  that implements the adoption API and, in session 5, the control channel, with switches to approve,
  deny, expire, `slow_down`, refuse, stall and drop. It verifies every signature, so a desk that signs
  the wrong bytes fails here before it fails against the cloud.
- **Tests:** adopt approved, denied, expired and slowed; a poll whose signature is wrong is refused;
  cancel; unadopt online, unreachable (409) and forced; the public-listener and zero-user refusals; a
  restart mid-adoption resumes polling from the stored row; a user delete removes their link;
  `SyncCoverageTest` green. `./gradlew test --tests '<pkg>.*'` for `cloud`, `routes` and `sync`.
- **Docs:** `docs/cloud-engineering.md` (new): what the desk holds, adoption, unadopt, the tables and
  the key, and §"Show safety" stating D11 as rules. `example.local.conf`'s `cloud` block. Glossary rows
  for *adopt*, *install key* and *link* (PRD §17). Follow-up row `FU-CLOUD-DEFAULT-URL`.

### Session 5 — The control channel

- **`CloudChannel`** (D9, D10, D12): `ktor-client-websockets` moves from `testImplementation` to
  `implementation`; connect while `ADOPTED` and enabled; the handshake; heartbeats from `welcome`'s
  interval; reconnect with capped full jitter; `4004` and `unadopted` clear local state through
  session 4's path and set `notice`; every other close reconnects; inbound frames over 8 MB close
  `4000`. Connection status in `cloud.state` with `retryAt` and `lastError`.
- **Isolation** (D11): the scope, the bounded outbound channel, no database write outside a
  transition, and a test that runs the connector against a `FakeCloud` that stalls and asserts the
  connector's work never ran on a thread the show uses.
- **The safety test** (NFR-SAFE-1), `cloud/CloudOutageOutputTimingTest`: a recording controller logs
  each frame's time while a fade runs, first with no connector (the baseline), then while the fake
  cloud (a) stops reading mid-fade, (b) drops the connection mid-fade, (c) refuses every reconnect.
  It asserts every frame was sent, the fade ended within the baseline's end plus a margin, and the p99
  inter-frame gap stayed within the baseline's plus a margin; margins generous, as §4 says. It joins
  `docs/testing-engineering.md`'s table of wall-clock tests.
- **Tests besides:** handshake happy path and each `4003` reason; clock skew reported; replaced
  connection; backoff bounds over many attempts under virtual time (never above 30 s, never below 1 s);
  host pinning (a redirect to another host refused, `http` refused except loopback); unknown message
  type ignored once. `./gradlew test --tests` for `cloud` and the safety test's package.
- **Docs:** `docs/cloud-engineering.md` §"The control channel"; `docs/websocket-engineering.md` gains
  `cloud.state`; root `CLAUDE.md` gains a short **Lighting7 Cloud** section under External
  Integrations and a `cloud.state` line under WebSocket Messages, with R6's review question.

### Session 6 — Install settings → Cloud

- **The tab**, admin only, beside *Remote access* in `frontend/src/routes/InstallSettings.tsx` (`settingsTabs`
  updated), reading `GET /install/cloud` and `cloud.state` through RTK Query and the socket store, in
  the design system's components:
  - *No cloud configured* or *Turned off on this desk* (D13), with the `local.conf` key named.
  - *Not adopted*: what adopting does in two sentences, and **Adopt this desk**.
  - *Adopting*: the user code large, a QR of `verificationUriComplete`, the link, the time left and
    **Cancel**; it survives a reload because the state is the desk's.
  - *Adopted*: org, install id (copyable), adopted by and when, the linked cloud email, the connection
    (Online / Connecting / Retrying in *n* s with the last error) and **Remove from cloud**, whose
    confirm offers *Forget on this desk only* when the answer is `CLOUD_UNREACHABLE`.
  - A `notice` after a revoke, dismissable.
  - On the public listener the Adopt button is disabled with D5's reason.
- **Tests:** Vitest for each state and the confirm's two arms; `npm run check:quick` in `frontend/`.
- **Docs:** `docs/cloud-engineering.md` §"The Cloud tab"; `docs/desk-accounts.md` gains the adoption
  link in one paragraph; the operator check `FU-MANUAL-CLOUD-P0` (§6) added to
  `docs/plans/manual-validation.md`.

## 6. Operator checks

`FU-MANUAL-CLOUD-P0`, run by the owner once both plans' sessions are merged and the cloud is deployed.
It is the PRD's P0 exit:

1. Each of the owner's desks is adopted from its Cloud tab and shows **Online** in the portal, with its
   version and last seen.
2. During a running cue with a fade and an FX, stopping the cloud's app changes nothing visible on the
   rig, and the desk reconnects within 30 s of the app's return.
3. Unadopt from the desk: the portal shows it gone. Re-adopt, then unadopt from the portal: the desk's
   tab shows the notice within a heartbeat.
4. A desk set to `cloud.enabled = false` makes no connection (checked in the portal and the desk log).
5. The cloud plan's cross-tenant tests are green on its main.

## 7. Explicitly out of scope

- Remote sessions, the sidecar, bearer tokens on 8414, the link flows and revocation pushes (P1).
- Deleting ngrok, the desk's OAuth server or GitHub sync (P1, P2), and the retired tables (FR-MIG-4).
- Full health telemetry (P3): P0's `health` carries uptime and version only (FR-FLEET-5 holds already).
- Publishing `cloud-protocol` to a registry (`FU-CLOUD-PROTOCOL-PUBLISH`).
- Rewriting git history to remove material D1 takes out of the tree: the owner decides that.
- Every cloud-side requirement: accounts, orgs, entitlements, the portal, infra (the cloud's P0 plan).

## 8. Follow-ups

Recorded in `docs/plans/followups.md` by the session that finds them:

- `FU-CLOUD-DEFAULT-URL` (session 4): set `cloud.baseUrl`'s default once OQ-1 picks the domain.
- `FU-CLOUD-PROTOCOL-PUBLISH` (session 3): publish `cloud-protocol` when a third party builds against it.
- `FU-MANUAL-CLOUD-P0` (session 6): §6.

## 9. Building on this: P1 onwards

What later desk plans can rely on, so they are written against it rather than around it:

- **One channel, more messages.** P1's `rtc.*`, `mcp.call`, `jwks`, `revoke` and `link.*` are new types
  in `cloud-protocol` v1 behind capabilities in `hello`; `CloudChannel` dispatches by type, so a new
  message is a handler, not a new connection.
- **The link table exists.** P1's FR-ID-3 reads `cloud_identity_links` and adds `INVITE` and `PROVED`.
- **The key signs everything the install does.** P2's git token (FR-SYNC-2) and P3's backup requests use
  `InstallKeys`' signing with their own domain strings.
- **The Cloud tab is the home** for P1's remote-access switch (FR-RA-9) and the policy switches that
  today sit on *Remote access*, which P1 retires.
- **The licence gate** takes P1's sidecar modules, and **`FakeCloud`** grows with each phase's messages.
