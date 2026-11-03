# Lighting7 Cloud on the desk

Lighting7 Cloud is a hosted service that desks connect out to. This doc is the desk's side: what it
holds, how it is adopted and unadopted, and the rules that keep the cloud off the show's path. The
wire contract is [cloud-protocol.md](cloud-protocol.md); the plan is
[`plans/cloud-p0-plan.md`](plans/cloud-p0-plan.md), whose decisions (D1, D2, …) this doc cites. The
code is `src/main/kotlin/uk/me/cormack/lighting7/cloud/`.

The desk works with no cloud, no account and through any outage. Nothing here is needed to run a
show, and nothing here may slow one down (§"Show safety").

## What the desk holds

| What | Where | Lifetime |
|---|---|---|
| The **install key**'s private half | `CredentialStore` blob `cloud:install-key:<installs.uuid>`, PKCS#8 base64 (`InstallKeyStore`) | made when adoption starts, deleted when the desk stops being adopted |
| Its adoption | `cloud_connection`, one row (`models/cloudConnection.kt`) | while adopting or adopted |
| Each desk account's cloud account | `cloud_identity_links`, one row per desk user (`models/cloudIdentityLinks.kt`) | written by adoption, deleted by unadopt or the user's deletion |
| What is happening now: a poll's error, a notice | `CloudService`'s memory only | until the next transition or a restart |

Both tables are `SyncDisposition.MachineLocal` and never touch `ProjectExporter` or `ProjectImporter`
(design record R7). The key never leaves the credential store: it is not exported, synced or logged, and
a desk never holds two (D4). The blob's name carries the install's uuid because the OS keychain is per
user, not per install: a second desk on the same machine, with its own data directory, never finds or
replaces this one's. `cloud_connection` keeps the key's public half and fingerprint for display. What the
cloud sends is stored as `text`, since the protocol bounds none of it.

A **link** says which cloud account a desk account is. Adoption writes one, for the admin who started
it (`linked_via` `ADOPTION`). Nothing reads links for authorisation in P0; P1 adds invites and proofs.
SQLite runs with `PRAGMA foreign_keys` off, so `AuthService.deleteUser` deletes the user's link and
clears `cloud_connection.adopted_by` by hand.

## Configuration

`local.conf`'s `cloud { baseUrl, enabled }` (D13), read once at startup by `CloudConfig`:

- **`baseUrl`** is the cloud. Empty means no cloud is configured: adoption answers
  `CLOUD_NOT_CONFIGURED` and the desk calls nothing. It must be `https`, or plain `http` to a loopback
  host (a cloud on the same machine), with no credentials, query or fragment (D12). A URL the desk
  refuses counts as not configured, and `cloud.state`'s `lastError` says why (`CLOUD_URL_INVALID`).
  There is no default until the production domain exists (`FU-CLOUD-DEFAULT-URL`).
- **`enabled = false`** is the hard kill switch: no adoption (`CLOUD_DISABLED`), no poll, no call to the
  cloud. The UI cannot override it.

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
its next connect (cloud-p0 session 5). `CloudService.revoked()` forgets the adoption the same way and sets
`notice` to "This desk was removed from *org* in the cloud."

## The state

`CloudService.state` is a `StateFlow<CloudStateDto>` (D14), rebuilt from the row and memory on every
transition and every account change. `GET /install/cloud` answers it, and so does every route in
`routes/installCloud.kt`. The `cloud.state` frame carries it to admin sockets (and bootstrap-open ones),
in the machine-scoped band beside `tunnel.state` (`plugins/MachineSocket.kt`), so it does not wait for a
project. Its shape:

`{configured, enabled, adoption?: {userCode, verificationUri, verificationUriComplete, expiresAt,
fingerprint}, adopted?: {installId, orgName, adoptedAt, adoptedBy, linkedEmail, fingerprint},
connection, retryAt?, lastError?: {code, message}, lastOnlineAt?, notice?}`

On the public listener the pending adoption's `userCode` and both verification URIs are left out, from
the REST answers and the frame alike (`CloudStateDto.forRemote`): whoever holds the code can approve the
desk into an organisation of their own, which is what D5's refusal of a remote *Adopt* is for. A remote
admin still sees that an adoption is pending, and its expiry and fingerprint.

`adoptedBy` is the adopting account's display name, null once it is deleted; `fingerprint` is the
install key's, which the portal shows too. `connection` is `OFF` until the control channel arrives
(session 5).

## Show safety

The cloud is never on the show's path (design record R6, D11). Every change here answers one question:
does the show run exactly as before with the cloud down, hung or hostile? The rules:

1. **Own scope.** Everything runs on `CloudService`'s scope, on `Dispatchers.IO.limitedParallelism(2)`,
   so a cloud that hangs holds at most two IO threads and none of the show's.
2. **Bounded waits and answers.** Every call to the cloud has a 15 s timeout, and the desk reads at
   most 64 KB of an answer, treating a longer one as none (`CloudApi`). A route that calls the cloud
   waits at most that long, and no route the show uses calls it.
3. **No database write on a timer.** The database is written only on a transition: adopt, approve,
   cancel, unadopt, revoke. Never per poll, per heartbeat or per reconnect: the pool has one connection,
   so a timed write would queue behind, or in front of, show writes. Live status is memory only.
4. **No lock the show takes.** The connector reads desk state only from `StateFlow`s and `BuildInfo`.
   The adoption reads the database only on a transition, an account change or a request (the install
   row, the adopting account's name and link), never on a timer: a poll's error is republished from
   memory.
5. **No outbound but to `cloud.baseUrl`'s host**, and nothing at all with `cloud.enabled = false`.

## Tests

`FakeCloud` (`src/test/kotlin/.../testsupport/FakeCloud.kt`) is a stand-in cloud on a loopback port,
built on `cloud-protocol`, that verifies every signature it is sent. Its switches approve, deny, expire,
slow down, refuse, stall and send an answer too large to read. Desk tests never use the real cloud (R5). `CloudAdoptionTest` drives
`CloudService` through every adoption and unadopt path; `InstallCloudRoutesTest` the routes and their
refusals on both listeners.
