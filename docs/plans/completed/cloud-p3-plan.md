# Lighting7 Cloud P3: fleet health and backups, the desk's side

> **Document status: DONE — written 2026-11-15; sessions 1–5 merged (2026-11-16).** The desk half of the PRD's
> phase P3; its exit, `FU-MANUAL-CLOUD-P3`, is an operator check in
> [`manual-validation.md`](../manual-validation.md#fu-manual-cloud-p3), added by session 4. The cloud half is
> `docs/plans/p3-plan.md` in `cjcormack/lighting7-cloud`. Design record:
> [`cloud-design/INDEX.md`](../cloud-design/INDEX.md), whose rules R1–R7 apply to every session here.

## 1. Context

P3 gives the owner two things the desk cannot give itself: being told when a desk is in trouble while
nobody is in the room (UC-7), and a copy of the whole install somewhere other than the machine it runs
on, which a replacement machine can be brought back from (UC-8). Both ride what P0 to P2 built:

1. **Health.** The heartbeat the desk already sends every `welcome.heartbeatS` seconds grows from
   `{uptimeS, deskVersion}` to FR-FLEET-1's full payload, and the desk learns one thing it has never
   known: whether its Art-Net controllers answer. The cloud judges the alert rules (FR-FLEET-3, -4); the
   desk only reports, and never anything a show contains (FR-FLEET-5).
2. **Backups.** The desk makes a consistent whole-install backup on a schedule and on demand, encrypts
   it on the desk under its org's data key, and uploads it to the cloud (FR-BAK-1, -2, REC-7). Restoring
   one onto a freshly installed desk is that desk's adoption with *Restore from a backup*, after which,
   once its admin confirms, it is the old install as far as the cloud is concerned: the same install id,
   MCP URL and links (FR-BAK-4).

What exists to build on:

- `cloud-protocol/`: protocol 1's envelope, `Health(uptimeS, deskVersion)` (`protocol/Envelope.kt`),
  `Capability` (`remote.v1`, `mcp.v1`, `sync.v1` and the state flags `remote.off`, `mcp.off`, `remote.no-sidecar`), request and reply by
  `replyTo`, signed HTTP over `RepoApiPayload` (`protocol/InstallKeys.kt`), whose doc already reserves
  "a payload with its own domain" for P3's backups (`docs/cloud-protocol.md` §"Signing").
- `cloud/CloudChannel.kt`: the heartbeat loop offering `Health` to `ChannelOutbox`'s drop-oldest queue
  of 16; `CloudService`'s capability list; `CloudApi`'s signed calls (`signRepoCall`), 15 s timeout and
  answer caps; `CloudAdoptionService`'s state machine (`start`, `poll`, `finish`, `unadopt`, `revoked`,
  `clearLocked`); `FakeCloud`; the Cloud tab (`components/cloud/CloudPanel.tsx`).
- What a heartbeat would report, and where it lives today: `BuildInfo.current`, `DeskPlatform.current`,
  JVM uptime, `ProjectManager.currentProjectOrNull` (a DAO read, not a `StateFlow`),
  `ArtNetController.packetsPerSecond` (a lock-free 30 s window, `dmx/PacketRateCounter.kt`), the sync
  engine's outcomes in `sync_log_entry` (no in-memory last result), `UpdateService.status()`. Nothing
  knows whether a controller answers: `dmx/ArtNetTransport.kt` only sends (`docs/dmx-engineering.md`),
  and its private `failing` flag tracks local send errors. Each universe's `DefaultArtNetTransport`
  binds its own socket on UDP 6454 with `SO_REUSEADDR` and never reads it.
- The data directory (`state/AppDataDir.kt`): `lighting7.db`, `prompt-scripts/`, `scene-images/`,
  `sync/<projectUuid>/repo/`, and what a backup must leave out — `script-cache/`, `exports/`, the Sentry
  spools, `credentials.enc`, `link/`, `updates/`, `logs/`, `lighting7.lock`, `RESET-ADMIN*` and the
  migrations' `<db>.pre-*.db` copies. `state/MigrationSupport.kt`'s `backUpDatabase()` is `VACUUM INTO`
  on the pool's connection in autocommit, and the HikariCP pool has exactly one connection. The database
  runs SQLite's default rollback journal, not WAL.
- `CredentialStore`: the OS keychain, else `credentials.enc` keyed by `installs.uuid` and the host name,
  so neither travels to another machine; the install key is its blob `cloud:install-key:<uuid>`.

P3 is done when `FU-MANUAL-CLOUD-P3` passes: a desk switched off is mailed to the owner after its
threshold, and a controller unplugged likewise (UC-7); last night's backup restored onto a fresh
install that comes back as the old install, its MCP URL and links working (UC-8); and the drill in
§6 recorded.

## 2. Decisions

- **D1 — The protocol for P3** (R3). `cloud-protocol` gains, all additive within protocol 1:
  - `Health`'s new fields, every one optional so the cloud reads a P2 desk's heartbeat as before:
    `platform`, `project {uuid, name}?`, `universes: [{universe, address?, framesPerS?, reachable?,
    lastReplyS?}]`, `sync {lastRunAt?, lastOkAt?, failingSince?, code?}`, `diskFreeMb`, `update
    {phase, lastOutcome?, stagedVersion?}?`, `backup {enabled, lastOkAt?, lastFailedAt?, code?}`.
    `reachable` absent means unknown (no reply expected, `dmx.mock`, or the poller could not bind,
    D4). The capability `health.v2` says the desk sends them.
  - `backup.request {}` (cloud → desk), the portal's *Back up now*, and the capability `backup.v1`;
    `backup.off` while the desk's switch is off (D6), as `remote.off` is.
  - The backup API, signed over a new `BackupApiPayload` (domain `lighting7-cloud/backup-api/v1`, the
    method, path, timestamp and the body's SHA-256): `GET /v1/backups/key` (the org's current data key)
    and `GET /v1/backups/key/{keyId}`, answering `BackupKeyDto {keyId, key}`; `POST /v1/backups
    {backupId, kind, sizeBytes, parts, sha256, keyId, manifest}` (the id the desk minted, D7; `kind` `SCHEDULED`, `REQUESTED` by `backup.request` or `MANUAL` by the Cloud tab's button) answering
    `BackupDto {backupId, status, partsReceived, …}`; `PUT /v1/backups/{backupId}/parts/{n}` (one part,
    at most 64 MiB, its own signature over its own hash); `POST /v1/backups/{backupId}/complete`; `GET
    /v1/backups/{backupId}`, the backup's `BackupDto` whatever its status, `partsReceived` naming the
    parts the cloud holds (D8); `GET /v1/backups/{backupId}/file`, a complete backup's file (`Range`
    honoured); and `GET
    /v1/backups?installId=` for restore. `CloudPaths.BACKUPS`.
  - The replacement (D9), signed over the same payload: `POST /v1/installs/{installId}/replace`, the
    calling desk confirming the replacement its adoption recorded, answering `ReplacedDto {installId}`,
    the old install's id; `DELETE /v1/installs/{installId}/replace`, declining it.
  - `BackupCipher`: the file format (D7) with fixed-key vectors, and `BackupManifest` (D7).
  - The adoption answer's `restore {replacesInstallId?, replacesName?, backupId?}?` (D9): at least one
    of `replacesInstallId` and `backupId`, and `replacesName` beside `replacesInstallId`.
  - Codes: `BACKUPS_OFF`, `BACKUP_QUOTA_EXCEEDED`, `BACKUP_TOO_LARGE`, `BACKUP_ID_TAKEN`,
    `BACKUP_NOT_FOUND`, `BACKUP_PART_INVALID`, `BACKUP_INCOMPLETE`, `BACKUP_KEY_UNKNOWN`,
    `BACKUP_TOO_NEW`, `REPLACEMENT_NOT_FOUND`, `REPLACEMENT_EXPIRED`.
  - Golden fixtures for each, which `GoldenFixturesTest` demands.
  - As session 1 settled it (`docs/cloud-protocol.md` §"Health", §"Backups", §"The backup file"):
    `universes[].universe` is the Art-Net port address; `BackupApiPayload` signs the install id too,
    as `RepoApiPayload` does, and the request target with its query; `GET /v1/backups` without
    `installId` lists the org's backups, so a removed install's can be found; `BackupDto` carries
    `installId`, `installName`, `createdAt` and the manifest, so a restoring desk can show the backup and
    refuse a newer one (`BACKUP_TOO_NEW`) before downloading it; parts are numbered from 1;
    `backup.request` is sent and forgotten, its outcome in the next heartbeat; and `BackupIds` mints and
    checks the ULID both sides use. `GET /v1/backups/{backupId}` answers only `BackupDto`, so a desk
    resuming after a lost `complete` answer is never handed the whole archive: the bytes are `GET
    /v1/backups/{backupId}/file` (`CloudPaths.backupFile`), 409 `BACKUP_INCOMPLETE` while uploading, and a
    `complete` sent again on a complete backup answers its `BackupDto`. `backupPartCount` is the declare's
    `parts` on both sides; `backupsOf` URL-encodes the install id it signs. `HealthUniverse.framesPerS`
    is optional, as every other health field is. `AdoptionRequest` gains `capabilities?`, `hello`'s
    strings: a P2 desk silently drops an approval's `restore` and approval comes before its first `hello`,
    so the portal offers *Replace* and *Restore* only to an adoption whose request announces `backup.v1`
    (D9).
- **D2 — The heartbeat reads only what is already in memory** (FR-FLEET-1, R6, `docs/cloud-engineering.md`
  §"Show safety" rule 5). A new `cloud/DeskHealth.kt` assembles the payload on a `cloud-N` thread from
  `StateFlow`s and lock-free counters, never the database and never a lock the show takes:
  - the current project: `ProjectManager` gains a `StateFlow` of `{uuid, name}`, set where it switches;
  - frames per second per universe: each controller's `PacketRateCounter`;
  - sync: `RemoteSyncEngine` publishes the install's last run, last good run and the code of a run
    failing now to a `StateFlow` (`SyncHealth`), across every project — `failingSince` is the oldest
    project still failing — seeded at startup from `sync_log_entry` once;
  - disk free: `FileStore.usableSpace` of the data directory's store, read at most once a minute;
  - update: `UpdateService`'s status, through a `StateFlow` if it is not one;
  - backup: D6's service state; reachability: D4's.

  As session 2 built it: the live controllers are a second `ProjectManager` `StateFlow`, followed by a
  `FixturesChangeListener` (`Fixtures.controllers` takes the register's lock); `SyncHealth` lives on
  `State`, moved by the activity log's run rows as `SyncLogger` writes them and replayed from it at
  startup, with `last_synced_at` standing for the quiet auto runs the log omits, and counts a run
  stopped on conflicts as failing (`CONFLICTS_PENDING`) until it is applied or aborted, since
  auto-sync then waits on the operator; a refusal that says the project is not set up to sync is no
  run, and a disconnect ends a failure; `update` is absent unless the
  update client runs (a packaged Windows install); `backup` is absent until session 4. The Cloud tab
  reads the last heartbeat from `GET /install/cloud/heartbeat`, memory only.

  It is offered to the drop-oldest queue as today. Sent: names the operator chose for the project and
  addresses of controllers on the LAN. Never sent: anything a show holds (cues, looks, levels, patch),
  user names, emails or DMX values (FR-FLEET-5). The Cloud tab shows the last heartbeat sent, as JSON
  in a disclosure, so the owner can see exactly what leaves the desk.
- **D3 — `health.v2` is announced while adopted, always.** Health is not a switch: the cloud already
  sees a desk's presence and version, and the rest is what the owner adopted it to watch. No desk-side
  setting turns it down; the payload's limits are D2's.
- **D4 — Controller reachability by ArtPoll** (FR-FLEET-1, FR-FLEET-3(b)). A unicast ArtPollReply lands
  on one of the sockets bound to 6454, and today every universe's transport binds one and never reads
  it, so a poller on a socket of its own would miss replies and call a live controller unreachable. The
  desk therefore binds 6454 once: `dmx/ArtNetSocket.kt` owns the one socket, every universe's transport
  sends through it, and `dmx/ArtNetPoller.kt` reads it on its own `artpoll-1` thread (a
  `DatagramSocket` sends and receives safely from different threads). The output thread's sends are
  unchanged in timing, which session 3's output timing test holds. The poller sends an ArtPoll to each
  distinct controller address the live project's universes name every 10 s and matches ArtPollReply by
  source address. A controller is reachable while it replied within 30 s and unreachable after that, but
  only once the poller has received at least one ArtPollReply from any node since it started, proof that
  replies reach it; before that, before a controller's first 30 s, or when the universe's address is
  broadcast, it is unknown. `dmx.mock` runs no poller and binds nothing; a bind failure (another Art-Net
  program holding 6454 without `SO_REUSEADDR`) logs once and leaves every controller unknown, which the
  cloud never alerts on. The Universes settings show each controller's state, so a desk without the
  cloud benefits too.

  As session 3 built it: the socket is the process's, `ArtNetSocket.shared`, bound by the first
  transport or poller to ask for it and kept across every project switch, since a transport's `stop`
  no longer closes it; a transport built without one (tests) still binds and closes its own. The
  "Universes settings" are the Patches page's universe chips, which draw a dot from the machine frame
  `artNet.reachability` (`StateFlow`-backed, every socket) on the live project only. An address that
  leaves the live project and comes back starts its 30 s afresh. Addresses are resolved and classed
  again every 10 s, so one that did not resolve, or a broadcast address whose interface was down, is
  picked up. A bind failure is not final: the poller tries 6454 again every 10 s and the socket moves
  onto it once it binds.
- **D5 — A backup is one archive of what matters** (FR-BAK-1). `backup/InstallBackup.kt` writes to
  `<data>/backups/staging/`:
  1. `VACUUM INTO` a snapshot of the database on its own JDBC connection, opened with `DriverManager` to
     the same file in autocommit and never taken from the pool, so the show's one pooled connection
     stays free and the snapshot is consistent without stopping the desk;
  2. a tar of `manifest.json`, `lighting7.db` (the snapshot), `prompt-scripts/`, `scene-images/` and
     `sync/` (every project's working tree, which holds a project's snapshot history, synced or not),
     and `local.conf` as `extra/local.conf`, kept for the operator and never restored (D10);
  3. encrypted as it is written (D7), so the archive never touches the disk in plaintext.

  The snapshot is the one plaintext file: staging is owner-only (`rwx------`), the snapshot is deleted as
  soon as the archive is encrypted, and staging's plaintext is swept at every start.

  Nothing else in the data directory is included; in particular `credentials.enc` and the keychain are
  not, so no secret leaves the desk (FR-BAK-1). The manifest names the desk version, the database's
  `user_version`, the sync `formatVersion` it writes, the source `installs.uuid` and friendly name,
  the cloud install id, the time, and every file's size and SHA-256.

  As session 4 built it: `manifest.json` is the archive's **last** entry, not its first, since it lists
  every file's hash and the files are hashed as they stream in (a tar header needs the size first, so a
  file is read once); the tar is the desk's own (`backup/Tar.kt`, ustar with a PAX `path` record for a long
  path) rather than a new dependency. A file deleted between the walk and its turn is left out (it is
  opened before its header is written, so it leaves nothing behind); one that shrinks fails the backup.
  `local.conf` is the file the desk actually loaded, which `main` records as the `lighting7.configFile`
  property, not a guess at its place; the database path is one `State.databasePath` the backup and
  `initDatabase` share. The snapshot's own connection sets a 10 s `busy_timeout`. Staging keeps each
  archive's declare beside it as `<backupId>.json`, which is what a resume reads.
- **D6 — When a backup runs** (FR-BAK-1, D-17's spirit). A machine-local `backup_settings` row (R7):
  `enabled` (off until an admin turns it on), `daily_at` (local time, default 04:00) and the last
  result. The Cloud tab's *Off-site backups* section turns it on, sets the time and runs one now; like
  every switch that limits what leaves through the cloud, the settings change only from the desk's own
  listener (403 `CLOUD_SETTINGS_LOCAL_ONLY`). A scheduled backup waits while a cue stack is running or
  the programmer holds anything, checking every 10 minutes, for at most 6 hours, then runs anyway; one
  asked for (the button, `backup.request`) runs at once. `backup.request` is honoured only with the
  switch on, and at most one backup runs at a time (a second ask while one runs is answered with the
  running one). The work runs on a dedicated single thread at low priority, never `cloud-N`, never the
  show's dispatchers, and writes the database only at its start and end (a transition). A backup needs
  the cloud only to fetch the key (before it starts) and to upload (after the archive is made).

  As session 4 built it: the Cloud tab's *Back up now* at the desk runs with the switch off too (the
  switch is the nightly run and the cloud's `backup.request`); over remote access it runs only with the
  switch on (403 `BACKUP_REMOTE_OFF`), since what leaves through the cloud is the desk's decision. It
  needs the desk adopted (409 `CLOUD_NOT_ADOPTED`). The
  settings `PUT` takes no adoption, like the scripts switches. The work writes the database only when a
  backup ends, never at its start, and holds no row while it runs. A desk without the cloud's backups
  sees each run's refusal (`BACKUPS_OFF`) as its last result. One asked for while the scheduled one is
  only waiting for a quiet show runs at once and ends that night's wait; the ask claims the run before
  the route answers, so its 202 shows it. The scheduled backup runs outside the schedule loop, so a
  settings change never cancels one in progress, and re-checks the switch before it runs. The last
  result's kind is the backup's own, as the cloud lists it: a resumed one keeps its declared kind.
- **D7 — Encrypted on the desk under the org's data key** (FR-BAK-2, REC-7). The desk fetches the
  org's current data key (`GET /v1/backups/key`, D1) for each backup and holds it in memory only, never
  stored. It mints the backup's id, a ULID, before encrypting, so the header can carry it before the
  cloud has heard of the backup; the declare sends it (D8). `BackupCipher` is AES-256-GCM in 1 MiB
  chunks: a header (magic, format version, `keyId`, a random 12-byte base nonce, the backup's id), then
  each chunk sealed with the nonce plus its index and AAD of the header's hash, the index and a
  final-chunk flag, so a chunk cannot be reordered, dropped or truncated without failing. The cloud
  stores ciphertext and never needs the plaintext: it could decrypt with KMS, which is REC-7's stated
  cost; a passphrase mode is OQ-8's and out of scope.
- **D8 — Uploads go through the cloud, in parts** (FR-BAK-1, R6 rule 6). The desk never talks to S3:
  rule 6 keeps its egress to `cloud.baseUrl`'s host. It declares the backup (`POST /v1/backups`, its
  minted id in the body; the cloud keeps ids unique per org, 409 `BACKUP_ID_TAKEN`), then uploads each
  64 MiB part with its own signature and SHA-256, retrying a failed part three times with backoff, then
  completes. A backup whose upload fails keeps its encrypted archive in staging and, on the next
  attempt within 20 hours of its declare, asks `GET /v1/backups/{backupId}` for `partsReceived` and
  sends the rest, inside the cloud's 24-hour abort; after that it is deleted and the next scheduled run
  starts afresh. Staging never holds more than one backup.

  As session 4 built it: a part is retried when the cloud does not answer or answers 5xx; a 4xx ends the
  run at once. A declare refused `BACKUP_ID_TAKEN` (its answer lost) resumes as a staged one does, and a
  backup the cloud already holds whole is done. `BACKUP_TOO_LARGE`, `BACKUP_NOT_FOUND` and
  `BACKUP_KEY_UNKNOWN` discard the staged archive, since no resume gets past them; every other failure
  keeps it. The data key's bytes are zeroed once the archive is sealed.
- **D9 — Restore is adoption with a backup** (FR-BAK-4, OQ-6). On a fresh desk, adoption proceeds as
  today; in the portal the approver may say the desk *replaces* an install of the org, and may pick a
  backup (the cloud plan's D13). Approval adopts the desk as a new install, as today, and records on it
  a pending replacement; the desk's adoption answer carries `restore`, which `cloud_connection` keeps
  until the desk confirms or declines. Nothing about the old install moves until the desk confirms.
  The portal offers *Replace* and *Restore* only to an adoption whose `AdoptionRequest.capabilities`
  names `backup.v1`: a desk that sends none (P2) would drop `restore` unread, and the cloud has no
  `hello` from it until it is approved (D1). `restore` has three shapes:
  - `replacesInstallId` and `backupId`: the Cloud tab (or onboarding's step) shows *Replace "<old
    name>" and restore its backup of <date>*;
  - `replacesInstallId` alone: *Replace "<old name>"*, which moves the identity and restores no data;
  - `backupId` alone, a removed install's backup (the cloud keeps them 90 days): *Restore the backup of
    <date>*, which restores data and moves no identity; the desk keeps its own install id. The backup's links named the old install's cloud identity, so this shape clears `cloud_identity_links` and `cloud_link_invites` after the apply, and each user links again.

  Only an admin on the desk's own listener may confirm, typing the old name (or, for a backup alone,
  the backup's install name), since it replaces everything on this desk. Confirming a backup first
  downloads it to staging, decrypts it with the key its header names (`GET /v1/backups/key/{keyId}`),
  checks every file against the manifest and refuses one written by a newer desk (`BACKUP_TOO_NEW`: its
  database `user_version` or sync `formatVersion` above this build's), so a bad backup is refused before
  anything changes. Then, with a replacement, the desk calls `POST /v1/installs/{installId}/replace`; the
  cloud moves the identity in one transaction and answers the old install id, and the desk writes it to
  `cloud_connection.install_id` and reconnects as it. With a backup, the decrypted archive is marked
  ready and the desk asks to be restarted. Declining (`DELETE /v1/installs/{installId}/replace`, or the
  pending replacement expiring after 7 days, `REPLACEMENT_EXPIRED`) leaves the old install untouched and
  this desk adopted as its own install; a downloaded backup is decrypted into staging and deleted once it
  is applied or declined.

  As session 5 built it: the desk sends its capabilities in `AdoptionRequest` (no desk did before), so
  the portal can offer a restore. The confirm (`POST /install/cloud/restore/confirm {name}`) answers 202
  and the work goes on, on `restore-1`, followed by `GET /install/cloud/restore`; `DELETE` declines, from
  either listener, since it only leaves the desk as it is. Staging is `<data>/backups/restore/`, apart
  from the backups' own, whose sweep would take a download for an orphaned archive; `backups.root` in
  `local.conf` moves both, for tests. The downloaded file is checked against the cloud's declared size and
  hash (taken as it downloads) before it is decrypted, and every archive entry must be one of the paths a
  backup writes. A checked backup waits unmarked in `verified/`, which a start sweeps, and is marked ready
  (`ready/` beside `ready.json`) only once the replacement is confirmed, so a restart mid-replacement
  applies nothing. A replacement refused empties staging; one expired clears the restore; one the cloud no
  longer has clears it too unless a harmless call signed as the old install with this desk's key (`GET
  /v1/backups/key`) is accepted, which means an earlier confirm's answer was lost and the replacement
  stands: the desk takes the old id and carries on. An unanswered replacement leaves the restore pending
  and the checked tree for the next confirm; a confirm after a restart, refused because the cloud no
  longer knows this desk's own id, makes the same check and stages the backup as the old install. A staged
  backup survives a restart before the one that applies it: the desk asks for the restart again
  (`cloud.state`'s `restartToRestore`). Until that restart it can be declined (*Don't restore*), which
  deletes it; a replacement already confirmed stays. An unadopt, cancel or revoke deletes it too. A
  confirm never accepts a blank name to type.
- **D10 — The restore is applied at startup, before the database opens.** `State.initDatabase` first
  looks for a ready restore in staging. If one is there:
  1. this machine's own rows, `installs` and `cloud_connection`, are read from the current database and
     saved to staging as JSON;
  2. the current database is kept as `<db>.pre-restore-<utc>.db` and the snapshot takes its place;
     `prompt-scripts/`, `scene-images/` and `sync/` are replaced, the old ones kept beside as
     `*.pre-restore-<utc>` until the next successful start;
  3. `initDatabase` runs the migrations and the reconciliation over it as over any database, so an older
     backup's schema is upgraded first;
  4. then, before anything reads them (before `CloudService` starts), the saved rows are written and
     `user_sessions` emptied, so everyone signs in again.

  The put-back: `installs` takes this machine's row, whose uuid keys `CredentialStore` and so the
  install key; `cloud_connection` takes this machine's key fingerprint and install id (the old install's
  after a replacement, this desk's own after a backup alone), with `adopted_by_user_id` cleared, since it
  named a user of the fresh desk, and the remote, MCP and approval switches kept from the backup's row,
  the old desk's choices, all off if the backup had none. `extra/local.conf` is never
  applied. Users, links, sync configs, machine overrides and every project come back as the old desk had
  them; the install's secrets do not, and need none: the install key is this desk's, and GitHub's tokens
  are gone. A failure at any step puts the kept files back and starts on the database as it was.

  As session 5 built it: the saved rows are kept column by column, read and written with plain JDBC,
  so a machine row survives a schema the migrations then widen; they are held in a journal rewritten
  after each move, so a start after a crash mid-apply undoes it; `installs.onboarding_pending` is the
  backup's, not the fresh desk's, which may still be in onboarding; a backup alone records its links'
  removal as `RESTORED`; and the kept trees are swept at the next start that applies nothing, while the
  kept database stays, as the migrations' copies do. Each move is journalled before it is made, and undoing
  one a crash left unmade changes nothing; each undone move leaves the journal, and a journal is deleted
  only once every move in it is undone, so a failed undo is retried at the next start and no kept tree is
  swept while a journal remains. A backup's `installs` row stays when this machine had none to save. A
  restore that fails at startup is recorded as `failed.json` in staging, which the next start reads into
  the restore status's `lastError` (`CLOUD_RESTORE_FAILED`) and the Cloud tab's notice, until the next
  confirm or decline. `SCHEMA_USER_VERSION` (`state/MigrationSupport.kt`)
  is what `BACKUP_TOO_NEW` compares a backup's database with, and a new one-shot step moves it.
- **D11 — Traces and logs name no key.** `TokenScrubber` learns the data key's shape in `BackupKeyDto`;
  a backup span is named by its constant (`backup.make`, `backup.upload`, `backup.restore`) with sizes
  and part counts, never a key, a path under the data directory or a project's name.

## 3. Rules for every session

- **R5 holds**: every test here runs against `FakeCloud`, which gains the backup API and the
  replacement (D1) over an in-memory store, with the protocol's rules (part sizes, hashes, quota, the
  size cap, unique ids, `BACKUPS_OFF`), and the health fields recorded for assertions.
- **R6 holds, and is tested**: `CloudOutageOutputTimingTest` gains a case with a full heartbeat every
  second (D2), and one with a backup being made and uploaded mid-fade (D5–D8), each held to the
  baseline as the others are. The poller (D4) gets the same treatment beside its own test.
- **R7 holds**: `backup_settings` is machine-local in `SyncDispositions`; the data key is never stored.
- **Nothing a show holds leaves the desk in a heartbeat** (FR-FLEET-5): the project's name and its
  controllers' addresses are the only operator-chosen strings sent, deliberately (FR-FLEET-1 lists the
  active project); a test over `DeskHealth`'s JSON for a rich project
  (`testsupport/RichProjectFixture.kt`) finds nothing else from it, no cue, look, palette or fixture name
  and no level.

## 4. Risks

- **`VACUUM INTO`, the pool and the single writer.** The snapshot's own connection (D5) keeps the
  pool's one connection free for the show, but under the rollback journal its shared lock still holds
  off writes for the snapshot's duration. Session 4 measures it on the richest local project and
  records it here; if a write would wait past `busy_timeout`, it moves the snapshot to SQLite's online
  backup API in small steps (`sqlite3_backup_step`), which yields between pages.

  **Measured by session 4, 2026-11-16**, in a Claude Code cloud container (no operator install is
  reachable from one), on databases padded with 64 KiB blobs, a writer committing every 5 ms beside the
  snapshot: `VACUUM INTO` took 64 ms at 13 MB, 143 ms at 50 MB and 446 ms at 200 MB, and the longest
  write waited 81, 182 and 535 ms — the snapshot's length, inside sqlite-jdbc's default 3 s busy timeout
  with room to spare up to about a gigabyte. So the snapshot stays `VACUUM INTO`. `InstallBackupTest`
  holds a pool writer running through a backup to no failure. The operator's own database is measured
  by `FU-MANUAL-CLOUD-P3-S4`.
- **Port 6454.** The desk now binds it once and reads it (D4); a reply that went to a socket the
  poller does not read would look like silence, which is why nothing is unreachable until a reply has
  arrived. Another Art-Net program on the same machine may hold the port exclusively; D4 degrades to
  unknown, never to an alert, and logs once.
- **Backup size.** Prompt-book PDFs dominate (up to 100 MB each, and each also lives in its project's
  working tree). Session 4 records a real install's archive size here, and the cloud's
  `backups.storage.mb` bounds it.

  **Not yet measured**: session 4 ran where no real install was, so the size is
  `FU-MANUAL-CLOUD-P3-S4`'s step 4, which records it here. The archive is the database, the PDFs and
  images and the working trees uncompressed (each PDF counted twice, once in `prompt-scripts/` and once
  in its working tree), plus 16 bytes a MiB of encryption.
- **A restore over a desk with data.** D9's typed confirm on the desk's own listener, and D10's kept
  copies, are the guard; the cloud alone can never start one, and a replacement approved in the portal
  moves no identity until the desk confirms it.
- **Restoring an older backup.** The migrations already upgrade an older database; a backup from a
  newer desk is refused (D9).

## 5. Implementation — five sessions

Sessions run through `/next-session` in order, one PR each, titled *Cloud P3: session N — title*. Each
runs the checks §"Pre-commit checks" names for what it touches; `cloud-protocol/` changes run
`./gradlew :cloud-protocol:check`.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | The protocol for P3 | merged | `claude/eager-pasteur-p6ei2n` | [#435](https://github.com/cjcormack/lighting7/pull/435) | 7f55dac |
| 2 | The full heartbeat | merged | `claude/relaxed-ptolemy-rbcias` | [#439](https://github.com/cjcormack/lighting7/pull/439) | fbcc3a0 |
| 3 | Art-Net controller reachability | merged | `claude/vigilant-cori-bwv3ry` | [#440](https://github.com/cjcormack/lighting7/pull/440) | cc4d6d5 |
| 4 | Making and uploading backups | merged | `claude/funny-brown-mcmta3` | [#441](https://github.com/cjcormack/lighting7/pull/441) | d2ed8d4 |
| 5 | Restoring onto a new desk | merged | `claude/keen-newton-7nu4og` | [#442](https://github.com/cjcormack/lighting7/pull/442) | ccaeb5d |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session` fills
*Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts the
next; whoever merges the last fills its own.

### Session 1 — The protocol for P3

- D1: `Health`'s optional fields, `health.v2`, `backup.request`, `backup.v1` and `backup.off`, the
  backup DTOs and paths (the declare's `backupId`, `partsReceived`), the replacement's routes and
  `ReplacedDto`, `BackupApiPayload`, `BackupCipher` with its vectors and a streaming encrypt/decrypt,
  `BackupManifest`, the adoption answer's `restore` in its three shapes, the codes; golden fixtures;
  `docs/cloud-protocol.md` §"Health" and §"Backups".
- **Tests:** `:cloud-protocol:check` — a P2 heartbeat (`{uptimeS, deskVersion}`) still decodes; every
  new message round-trips its fixture; a `restore` with neither `replacesInstallId` nor `backupId`
  refused; the cipher's vectors decrypt; a reordered, dropped, truncated or altered chunk, a wrong key
  and a header naming another backup fail; a payload's signature does not verify as a repo call's.

### Session 2 — The full heartbeat

- D2: `DeskHealth`, the project `StateFlow`, `SyncHealth`, disk free, the update summary, frames per
  second; `health.v2` (D3); the Cloud tab's last-heartbeat disclosure; D11's scrubber shape is session
  4's. `reachable` stays absent until session 3.
- `docs/cloud-engineering.md` §"Health" (what is sent, and what never is).
- **Tests:** the heartbeat's fields from a running desk against `FakeCloud`; sync failing on one project
  and good on another reported as failing since the first failure, cleared by its next good run; the
  FR-FLEET-5 test (§3); `CloudChannelTest`'s `total_changes()` still across heartbeats; the outage case
  (§3).
- **Operator check** `FU-MANUAL-CLOUD-P3-S2` (needs the cloud's session 1 deployed): the portal's install
  page shows the desk's project, universes' frame rates, disk and sync state within a minute.

### Session 3 — Art-Net controller reachability

- D4: `ArtNetSocket`, the one 6454 socket every universe's transport sends through; `ArtNetPoller`
  reading it on `artpoll-1`, ArtPoll and ArtPollReply encoding, the live project's controller addresses
  followed across a switch, the rule that nothing is unreachable before a first reply, a `StateFlow` the
  heartbeat and the Universes settings read; `docs/dmx-engineering.md` §"Controller reachability" and
  §"Art-Net transport" (one socket).
- **Tests:** with the universes' transports bound and sending, a fake node on loopback answering
  ArtPoll reachable, falling silent unreachable after 30 s (on a test clock), a broadcast address
  unknown; every controller unknown until the first reply arrives; 6454 held exclusively leaving
  everything unknown; `dmx.mock` binding nothing and starting no poller; the Art-Net output timing test
  sending through the shared socket with the poller reading it beside a fade.
- **Operator check** `FU-MANUAL-CLOUD-P3-S3`: on the rig, unplugging a controller turns its universe
  unreachable on the desk within 40 s and back within 15 s of plugging it in.

### Session 4 — Making and uploading backups

- D5–D8: `InstallBackup` (snapshot, archive, cipher), `backup_settings`, the schedule and its wait, the
  on-demand run, `backup.request` and `backup.v1`/`backup.off`, the parts upload with resume, the
  Cloud tab's *Off-site backups* section (switch, time, *Back up now*, last result, size), REST under
  `/api/rest/install/cloud/backups` (admin only; the settings `PUT` local only), D11.
- `docs/cloud-engineering.md` §"Backups"; §4's `VACUUM INTO` measurement and archive size recorded.
- `manual-validation.md` gains `FU-MANUAL-CLOUD-P3-S4` and `FU-MANUAL-CLOUD-P3` (§6).
- **Tests:** an archive's contents and manifest for a rich install, and nothing it must leave out; a
  write through the pool during the snapshot completing; staging owner-only, the snapshot deleted once
  encrypted and a left-over one swept at start; an upload to `FakeCloud` decrypting back to the archive;
  a failed part resumed from `partsReceived`, and one older than 20 hours started afresh; quota, the size
  cap and `BACKUPS_OFF` surfaced with their codes in the last result; the
  schedule waiting while a stack runs and running after its cap (test clock); `backup.request` ignored
  with the switch off; the settings `PUT` refused on the public listener; the outage case (§3).
- **Operator check** `FU-MANUAL-CLOUD-P3-S4` (needs the cloud's session 4 deployed): a backup made on
  demand appears in the portal with its size, and the next night's scheduled one too.

### Session 5 — Restoring onto a new desk

- D9, D10: the adoption answer's `restore` kept in `cloud_connection` until confirmed or declined, the
  Cloud tab's and onboarding's *Replace* and *Restore* with their typed confirm, download, decrypt,
  verify, staging, `BACKUP_TOO_NEW`, the replace call and the reconnect as the old install id, the
  decline, the startup apply (rows saved, files swapped, migrations, rows written) and its rollback, the
  restart prompt.
- `docs/cloud-engineering.md` §"Restore"; `docs/desk-accounts.md` (after a restore the accounts are the
  old desk's).
- **Tests:** a backup made on one test desk restored on a second (two data directories): every project,
  user, link, sync config and working tree back; the second's `installs.uuid` and install key kept,
  `adopted_by_user_id` cleared and the remote switches the backup's, and the channel coming up as the
  old install id against `FakeCloud`; sessions emptied; a backup from an older schema migrated before
  the rows are written; a replacement without a backup moving the identity and no data; a backup alone
  restoring data under the desk's own install id; a decline, and an expired replacement, leaving the
  desk its own install; nothing moved in `FakeCloud` before the confirm; a newer backup refused; a
  corrupt chunk refused before anything is replaced; a failure mid-apply rolling back; the confirm
  refused on the public listener and without the typed name.

## 6. The gate and the operator checks

There is no spike. The phase's exit is `FU-MANUAL-CLOUD-P3`, added by session 4:

1. **UC-7.** The venue desk switched off is mailed to the owner within its offline threshold (default
   10 minutes), and its return clears the alert; a controller unplugged is mailed after its threshold.
2. **UC-8, the restore drill.** Last night's scheduled backup of the owner's main desk restored onto a
   fresh install (a spare machine or VM): adopted with *replaces*, confirmed on the desk, restarted; it
   opens the old desk's current show, its users sign in, the MCP URL in Claude reaches it, a synced
   project syncs, and the old machine, if still on, shows itself unadopted.
3. The time each step took, recorded beside the check.

## 7. Explicitly out of scope

Zero-knowledge backups (OQ-8); restoring a single project from a backup (sync's snapshot restore covers
a project); backing up `CredentialStore`; update distribution and the update client (P4); sending show
content or DMX values to the cloud for any reason; desk-side alerting (the desk shows its own state, the
cloud alerts).

## 8. Follow-ups

Recorded in [`followups.md`](../followups.md) by the session that finds them.
