# Lighting7 Cloud P2: cloud sync v1, the desk's side

> **Document status: PLANNED — written 2026-11-11, no session started.** The desk half of the PRD's
> phase P2; its exit, `FU-MANUAL-CLOUD-P2`, is an operator check in
> [`manual-validation.md`](manual-validation.md), added by session 4. The cloud half is
> `docs/plans/p2-plan.md` in `cjcormack/lighting7-cloud`. Design record:
> [`cloud-design/INDEX.md`](cloud-design/INDEX.md), whose rules R1–R7 apply to every session here.

## 1. Context

P2 moves show sync off GitHub and onto repos Lighting7 Cloud hosts (D-3, D-4). The cloud serves each
synced project as a bare git repository over smart HTTP (FR-SYNC-1), so the sync engine —
`RemoteSyncEngine`, snapshots, `ThreeWayDiff`, conflict sessions, `VenueRepoSync`'s tagged versions,
auto-sync — stays as it is, and only three things change around it (FR-SYNC-2, FR-SYNC-4):

1. **Credentials.** `AuthResolver` gains a cloud source: an adopted desk signs a short-lived git token
   with its install key, so syncing needs no account, no GitHub and no round trip.
2. **Where a repo comes from.** *Sync this project to the cloud* replaces GitHub's *Create repo*;
   *Import from the cloud* and onboarding list the org's cloud repos instead of a GitHub account's.
3. **Moving and deleting.** *Move GitHub-synced projects to the cloud* pushes each project's full
   history to a cloud repo and re-points it (FR-MIG-1); once the owner's projects have moved, the GitHub
   OAuth and PAT code is deleted (FR-MIG-2, FR-MIG-4), and `scripts/cloud-session.sh` imports from the
   cloud with a developer token (FR-SYNC-6, UC-10, RISK-7).

What exists to build on:

- `cloud/` from P0 and P1: the install key in `CredentialStore`, `CloudApi`'s signed HTTP (adoption's
  `Lighting7-Timestamp` and `Lighting7-Signature`), `CloudChannel` with its `capabilities` and its
  inbound `when`, `FakeCloud`, the Cloud tab.
- `sync/auth/AuthResolver.kt`: GitHub OAuth first (`sync/auth/oauth/*`, the install-wide identity in
  `oauth_identities`), then a per-repo PAT in `CredentialStore`, else `MissingCredentialsException`; every
  answer `GitCredentials.forGitHubToken`, user `x-access-token`.
- `routes/oauth.kt`: the web and device flows, the identity, the GitHub repo list (with each repo's
  `projectKind`, read from its `project.json`) and create; `routes/cloudSync.kt`'s `/sync/credentials`
  (the PAT); `BundledOAuthCredentials` from `-PghOauthClientId` / `-PghOauthClientSecret` in
  `build.gradle.kts`; `sync.oauth.github.*` in `local.conf`.
- The frontend's `components/cloudSync/` (`IdentityRow`, `DeviceFlowModal`, `PatPanel`, `RepoPicker`,
  `CreateRepoDialog`, `ImportFromRemoteDialog`, `AddRemoteProjectButton`, `SyncReauthBanner`),
  `store/oauthGithub.ts` and onboarding's projects step.
- `venue/VenuePins.kt`'s repo hint (`owner/repo` for a GitHub URL), carried in a show's `project.json`
  as `venueRepo`.
- `scripts/cloud-session.sh`, which stores a placeholder PAT and relies on the Claude Code session's git
  proxy authenticating `github.com`.

P2 is done when `FU-MANUAL-CLOUD-P2` passes: every owner project moved with its history (commit SHAs
the same), two desks syncing a show and resolving a conflict through the cloud (UC-4), a Claude Code
cloud session importing from the cloud (UC-10), GitHub's auth code gone, and `docs/sync-engineering.md`
describing the cloud.

## 2. Decisions

- **D1 — The protocol for P2** (R3). `cloud-protocol` gains:
  - `InstallGitTokens`: a compact JWS, EdDSA over the install key, claims `iss` (the install id), `aud`
    (`<cloud origin>/git`), `iat`, `exp` (at most 10 minutes after `iat`), `jti`; header `typ`
    `l7git`. Signing and verifying both live here, with fixed-key vectors, so the cloud checks exactly
    what the desk signs. Sent as HTTP Basic, user `x-install-token`.
  - The repo API: `CloudPaths.REPOS` (`/v1/repos`), `CreateRepoRequest {projectUuid, kind, name}`,
    `RepoDto {repoId, url, projectUuid, kind, name, headSha?, sizeBytes, formatVersion?, lastPushAt?}`,
    `RepoList`. Requests are signed as adoption's are.
  - `sync.changed {repoId, url, headSha}` (cloud → desk) and the capability `sync.v1`.
  - Codes: `SYNC_OFF`, `REPO_EXISTS`, `REPO_NOT_FOUND`, `SYNC_READ_ONLY`, and the push refusals, which
    reach the desk as a ref's reason text `CODE: words`: `SYNC_NON_FAST_FORWARD`, `SYNC_REF_PROTECTED`,
    `SYNC_TAG_IMMUTABLE`, `SYNC_FORMAT_INVALID`, `SYNC_QUOTA_EXCEEDED`, `SYNC_STORAGE_UNAVAILABLE`, and
    a parser from a reason to its code.
  - Golden fixtures for each, which `GoldenFixturesTest` already demands of every message.
- **D2 — A cloud repo is a URL under the cloud's origin.** `https://<cloud.baseUrl host>/git/<orgId>/
  <repoId>.git`, ids not names, so a rename in the portal moves nothing on a desk. `CloudRepoUrls`
  decides whether a URL is one (same scheme, host and port as `cloud.baseUrl`, the path's shape), and
  every place the desk names a repo to a person shows the repo's name, read from the cloud and cached
  in `sync_configs`, never the URL. Nothing changes in `sync_configs`' or `sync_linked_repos`' meaning:
  a project is synced iff its `repoUrl` is set.
- **D3 — `AuthResolver`'s cloud source** (FR-SYNC-2, PRD §9.8). For a cloud URL:
  - an adopted desk mints an `InstallGitTokens` token per sync run (D1), so a run that outlives it
    re-resolves (the engine already resolves per run, and a conflict apply is its own run);
  - an unadopted desk uses `LIGHTING7_CLOUD_TOKEN`, a developer token read from the environment at
    startup and never stored, which is how a Claude Code cloud session reaches the cloud (D9);
  - otherwise `MissingCredentialsException`, answered as 409 `CLOUD_NOT_ADOPTED` naming the Cloud tab.

  Until session 6 a GitHub URL still resolves as today, so both work while projects move. Neither path
  needs the control channel, so a desk whose channel is down but whose HTTPS reaches the cloud syncs. A
  401 from the cloud is `AUTH_FAILED` with the cloud's words, not GitHub's.
- **D4 — Push refusals keep their meaning.** `JGitClient`'s push result reads a rejected ref's reason
  through D1's parser. `SYNC_NON_FAST_FORWARD` is the existing `REJECTED_NONFASTFORWARD`, which the
  engine pulls, merges three ways and retries, unchanged; `SYNC_TAG_IMMUTABLE` on a venue's publish is
  the existing `VENUE_VERSION_TAKEN`; `SYNC_QUOTA_EXCEEDED`, `SYNC_STORAGE_UNAVAILABLE`, `SYNC_OFF` and
  `REPO_NOT_FOUND` become `SyncErrorCode`s the Sync tab and the activity log show in the cloud's words,
  each with what to do (*Ask the org's owner for more storage*, *The cloud repo was deleted: sync to a
  new one*). Auto-sync backs off on them as it does on `AUTH_FAILED` (`AutoSyncScheduler`'s backoff), so
  a refused desk does not knock every minute.
- **D5 — `sync.changed` wakes auto-sync, never more** (FR-SYNC-5, R6). The desk adds `sync.v1` to
  `hello` while sync is built in, always. On `sync.changed`, the `cloud-N` thread offers the URL to a
  conflated channel `AutoSyncScheduler` reads and returns; the scheduler, on its own coroutine, runs an
  early sync for each project whose `sync_configs.repoUrl` is that URL and whose auto-sync is on, at
  most once in 10 s per project, else ignores it. Nothing is written, nothing waits and nothing runs on
  the channel's thread, and with the cloud down auto-sync's timer is what finds a change, as it does
  today.
- **D6 — Sync to the cloud, import from the cloud** (FR-SYNC-4). Admin only, as sync's routes are.
  - `POST /api/rest/projects/{id}/sync/cloud` makes the project's cloud repo (`POST /v1/repos` with its
    uuid, kind and name) and attaches it as `repoUrl` with auto-sync on, then runs the first sync, which
    pushes. `REPO_EXISTS` attaches the repo the cloud already has for that project uuid instead (another
    desk made it): the first run then pulls and, where both have history, merges three ways, which the
    project-uuid guard in `RemoteSyncEngine` allows because the uuid matches. 409 `CLOUD_NOT_ADOPTED`,
    `SYNC_OFF`, 503 `CLOUD_UNREACHABLE`. A venue's repo is made the same way and its versions pushed
    with their tags by `VenueRepoSync`.
  - `GET /api/rest/cloud-sync/repos` lists the org's repos (`GET /v1/repos`), each with whether a project
    on this desk already syncs it and its `kind`, which is what onboarding's venues-first order and the
    import dialog read, as the GitHub list's `projectKind` was.
  - `POST /api/rest/cloud-sync/import` takes a cloud URL as it takes a GitHub one; `VENUE_MISSING` still
    names the venue's repo, now by the cloud repo's name.
  - The Sync tab's unsynced state is one button, *Sync to the cloud*, and a list of remembered repos to
    reconnect; *Import from the cloud* and *Add remote project* list the org's repos; onboarding's
    projects step lists them with the venue badge. An unadopted desk sees *Adopt this desk to sync* with
    a link to the Cloud tab, and nothing else.
  - `VenuePins`' repo hint for a cloud URL is the repo's name, so a show's `venueRepo` names the venue's
    cloud repo after its next snapshot; an old GitHub `owner/repo` hint in a show is only a hint, read and
    shown as it is.
- **D7 — The move pushes the working tree, so SHAs are kept** (FR-MIG-1, UC-5). *Move GitHub-synced
  projects to the cloud*, on the Cloud tab (admin, desk's own listener only, as adoption is):
  1. `GET /api/rest/cloud-sync/move` plans: every project whose `repoUrl` is a GitHub URL, venues before
     shows, each with whether the cloud already has a repo for its project uuid (another desk moved it).
  2. `POST …/move {projectIds}` runs one project at a time, each in the engine's per-project lock, so no
     sync runs beside it: a sync with GitHub first, when GitHub's credentials still work, so the working
     tree holds the latest; then the cloud repo made (or found by `REPO_EXISTS`); then every commit on
     `main`, and a venue's every `vN` tag, pushed from the working tree to the cloud repo — a push, so
     every SHA is the one GitHub had. Where the cloud repo already has commits (moved from another desk),
     the push is fast-forward only, and a desk whose history has diverged pushes nothing and re-points,
     leaving the next sync's three-way merge to reconcile, as two desks syncing one GitHub repo do today.
  3. `repoUrl` re-pointed to the cloud repo and the GitHub URL kept in `sync_linked_repos`, so it stays
     readable for the window FR-MIG-2 allows; then a verifying sync run whose result is the project's row
     in the answer (`MOVED`, `MOVED_DIVERGED`, `FAILED` with the code). A project that fails keeps its
     GitHub URL and nothing else changes for it.

  The move needs GitHub only for step 1's freshening, so it survives session 6's deletion: a desk
  updated past it moves from what its working tree holds, which loses at most the GitHub commits that
  desk never pulled, and those reached the cloud from the desk that made them.
- **D8 — Deleting GitHub** (FR-MIG-2, FR-MIG-4, PRD §9.10). Session 6 deletes `sync/auth/oauth/*`,
  `routes/oauth.kt`, `/sync/credentials`, `BundledOAuthCredentials` and its Gradle wiring and CI inputs,
  `sync.oauth.github.*` (`CloudConfig` and `example.local.conf`), `GitCredentials.forGitHubToken` and
  `forGitHubPat`, and the frontend's GitHub pieces and every GitHub mention in the sync UI (the
  `GithubIcon` stays only where the update check, P4's, uses it). `AuthResolver` keeps only the cloud
  source. A project still pointing at GitHub syncs no more: 409 `GITHUB_RETIRED`, and the Sync tab
  offers *Move to the cloud* (D7 from the working tree). `oauth_identities` is dropped by database step
  18, `state/GitHubRetirementMigration.kt`, which also deletes `CredentialStore`'s GitHub entries (the
  OAuth tokens and every PAT, keyed by repo URL) once the store is open, as step 13 did ngrok's: a
  database holding no identity is only marked; one holding one is backed up first to
  `<db>.pre-github-retirement-<utc>.db`. `GitHubRetirementMigrationTest` runs it over
  `src/test/resources/db/pre-github-retirement.sql`, a database at 17; never regenerate it.
  `SyncDispositions` loses the table. The update check's GitHub Releases client is P4's and stays.
- **D9 — Claude Code cloud sessions from the cloud** (FR-SYNC-6, UC-10, RISK-7).
  `scripts/cloud-session.sh` takes cloud repos by name or URL; with `LIGHTING7_CLOUD_TOKEN` set (a read
  developer token from the portal, saved as the environment's secret) it resolves each name through
  `GET /v1/repos` as a bearer, starts the desk with the token in its environment (D3) and `cloud.baseUrl`
  in `local.conf`, and imports as before, venues first, pull-only. The placeholder PAT and the GitHub
  proxy paragraph go. The session's environment must allow the cloud's host: the script checks it can
  reach `GET /v1/repos` before starting the desk and fails naming the host if not. Until session 6 a
  GitHub `owner/name` still imports through the GitHub proxy, so sessions keep working while projects
  move.
- **D10 — Traces and logs name no token.** `TokenScrubber` learns the git token's shape (a JWS whose
  header says `l7git`) and `l7_dev_…`; a sync span names its repo by `repoId` at most, never the URL's
  credentials; `CloudRepoUrls` never logs a URL with userinfo.

## 3. Rules for every session

- **R5 holds**: every test here runs against `FakeCloud`, which gains a git smart-HTTP server over
  in-memory bare repos (JGit's own `UploadPack` and `ReceivePack`, test scope only) that keeps the
  protocol's push rules — fast-forward `main`, immutable tags, `formatVersion.json` present — and answers
  the D1 codes, so the desk's mapping is tested against what the protocol says, not the cloud's code.
- **R6 holds**: sync was never on the show's path and stays off it; the one new path from the cloud,
  `sync.changed`, hands off on a conflated channel (D5). `CloudOutageOutputTimingTest` gains a case with
  `sync.changed` flooding the channel mid-fade.
- **R7 holds**: nothing here adds a table, and the one new column, `sync_configs.repo_name` (D2), is
  machine-local as the table is; the git token is minted, never stored, and a developer token lives in
  the environment, never in `CredentialStore` or a table.
- **Both paths work until session 6.** Sessions 2 to 5 leave every GitHub path as it is.

## 4. Risks

- **A move on two desks at once.** Two desks moving one project race to `POST /v1/repos`; the loser gets
  `REPO_EXISTS` and pushes fast-forward onto the winner's, or re-points if diverged (D7). A test runs both
  against one `FakeCloud`.
- **A straggler desk.** A venue desk not updated before session 6 ships keeps syncing GitHub until it is,
  then is told `GITHUB_RETIRED` and moves from its working tree (D7). Session 6's *Needs* makes the owner
  move every desk first, so this is a recovery path, not the plan.
- **The working tree is the history.** D7 trusts the working tree to hold every commit the GitHub repo
  has. Step 1's sync makes it so while GitHub credentials work; session 4 checks after the push that the
  cloud's `main` is the GitHub `main` it fetched, and fails the project if not.
- **A Claude Code session's network.** The cloud host must be on the session environment's allowlist,
  an operator step the script checks for (D9).

## 5. Implementation — six sessions

Sessions run through `/next-session` in order, one PR each, titled *Cloud P2: session N — title*. Each
runs the checks §"Pre-commit checks" names for what it touches; a session with a schema migration
(session 6) runs the full suites.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | The protocol for P2 | planned | | | |
| 2 | Syncing with a cloud repo | planned | | | |
| 3 | Sync to the cloud, import from the cloud | planned | | | |
| 4 | Move GitHub-synced projects to the cloud | planned | | | |
| 5 | Claude Code sessions from the cloud | planned | | | |
| 6 | Delete GitHub sync | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session` fills
*Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts the
next; whoever merges the last fills its own.

### Session 1 — The protocol for P2

- D1: `InstallGitTokens` with its vectors, the repo DTOs and path, `sync.changed`, `sync.v1`, the codes
  and the reason parser; golden fixtures; `docs/cloud-protocol.md` §"Git" and §"Repos".
- **Tests:** `:cloud-protocol:check` — a token signed by a fixed key verifies, and one past its life,
  with a wrong `aud`, a bad signature or a `typ` other than `l7git` does not; each reason string parses
  to its code and an unknown one to none; every new message round-trips its fixture.

### Session 2 — Syncing with a cloud repo

- D2's `CloudRepoUrls`; D3's cloud source in `AuthResolver` (`CloudCredentialSource`), the install key
  read through `InstallKeyStore`, `LIGHTING7_CLOUD_TOKEN` read once at startup; D4's mapping and the
  new `SyncErrorCode`s, with the auto-sync backoff; `CloudApi`'s three repo calls; D5's `sync.v1` and
  the conflated hand-off to `AutoSyncScheduler`; D10's scrubber patterns; `FakeCloud`'s git server (§3).
- No UI: a cloud URL is attached in tests through the existing config route.
- `docs/sync-engineering.md` §"Auth / credential storage" gains the cloud source beside GitHub's, and
  `docs/cloud-engineering.md` a §"Cloud sync".
- **Tests:** a show synced to `FakeCloud` from one desk and pulled by a second; a conflict made on both,
  resolved on the second and pushed (the UC-4 path, two working trees, one fake); a venue published
  twice with its tags and a second desk's `v2` answered `VENUE_VERSION_TAKEN`; a non-fast-forward
  retried as today; `SYNC_QUOTA_EXCEEDED` and `REPO_NOT_FOUND` surfaced with their codes and backed off;
  an unadopted desk with and without the environment token; `sync.changed` running auto-sync for its
  project once in 10 s and not for one with auto-sync off; the outage case (§3); a scrubbed token in a
  captured log line.
- **Operator check** `FU-MANUAL-CLOUD-P2-S2` (needs the cloud's session 1 deployed): a project attached
  to its cloud repo by hand (`PUT /sync/config`) syncs, and a second desk pulls it.

### Session 3 — Sync to the cloud, import from the cloud

- D6: the three routes, the Sync tab's unsynced state, *Import from the cloud*, *Add remote project*,
  onboarding's projects step over the cloud list, the venue badge and venues-first order, the repo name
  in `sync_configs` and wherever the UI named a GitHub repo, `VenuePins`' hint; the error banners for
  D4's codes (`SyncReauthBanner`'s place, the cloud's words).
- GitHub's dialogs stay beside the cloud's for projects already on GitHub (§3), behind *GitHub
  (legacy)* in each place, so the new path is the default.
- **Tests:** route tests against `FakeCloud` — create, `REPO_EXISTS` attaching the existing repo and
  merging, unadopted and `SYNC_OFF` refusals, the list's `syncedHere` and `kind`, an import of a venue
  then a show pinning it, `VENUE_MISSING` naming the cloud repo; Vitest for the Sync tab's states, the
  import dialog and onboarding's order.
- **Operator check** `FU-MANUAL-CLOUD-P2-S3` (needs the cloud's sessions 1 and 2 deployed): *Sync to the
  cloud* on a new show, the repo on the portal's Projects page, the show imported on a second desk from
  *Import from the cloud*, and an edit on one reaching the other within seconds by `sync.changed`.

### Session 4 — Move GitHub-synced projects to the cloud

- D7: the plan and run routes, the Cloud tab's *Move to the cloud* section (the plan, a row per project
  with its result, a re-run for failures), refused on the public listener (403
  `CLOUD_MOVE_LOCAL_ONLY`); the SHA check after the push (§4); the activity log's row per moved project.
- `manual-validation.md` gains `FU-MANUAL-CLOUD-P2-MIGRATE` (every owner desk's projects moved, SHAs
  compared against GitHub for each, a venue's tags present, a show pinning it importing on a fresh desk)
  and `FU-MANUAL-CLOUD-P2`, the phase's exit (§6).
- **Tests:** against a `file://` bare repo standing in for GitHub and `FakeCloud`: a show and a venue
  moved with every SHA and tag the same; a second desk moving the same project fast-forward, and one
  diverged re-pointing and merging on its next sync; two desks racing (§4); GitHub unreachable moving
  from the working tree; a failure leaving the project on GitHub untouched; the public listener refused.

### Session 5 — Claude Code sessions from the cloud

**Needs:** cjcormack/lighting7-cloud docs/plans/p2-plan.md session 4 merged; operator: the cloud's
session 4 is deployed, a read developer token is saved as the Claude Code environment's
`LIGHTING7_CLOUD_TOKEN` and the environment allows the cloud's host

- D9: the script's cloud path, the reachability check, its header; `CLAUDE.md` §"Cloud sessions".
- **Tests:** `shellcheck`; the script run in this session against the deployed cloud, importing a venue
  and a show that pins it — the session's own proof of UC-10, recorded in §6.

### Session 6 — Delete GitHub sync

**Needs:** operator: `FU-MANUAL-CLOUD-P2-MIGRATE` passed, every desk the owner runs on a build with
session 4 and every one of its projects moved

- D8: the deletions, step 18 and its frozen database, `GITHUB_RETIRED` and the Sync tab's *Move to the
  cloud* for a straggler, the licence and notices entries of anything only GitHub used, `SyncDispositions`;
  the script's GitHub path (D9).
- `docs/sync-engineering.md` rewritten where it says GitHub (§"Architecture overview", §"Auth / credential
  storage" and its GitHub OAuth, PAT and bundled-credentials sections, §"Error codes"); `CLAUDE.md`'s
  GitHub OAuth paragraph; `docs/cloud-engineering.md`.
- **Tests:** the full suites (a schema migration); `GitHubRetirementMigrationTest` over the frozen
  database, with and without an identity, the backup written only with one; a GitHub `repoUrl` answering
  `GITHUB_RETIRED` and moving from its working tree; nothing under `src/` or `frontend/src/` naming
  `oauth/github` (a test, as P1's deletion had for ngrok).

## 6. The gate and the operator checks

There is no spike. The phase's exit is `FU-MANUAL-CLOUD-P2`: every owner project moved with its SHAs
(`FU-MANUAL-CLOUD-P2-MIGRATE`), a conflict made on two desks and resolved on the venue desk through the
cloud (UC-4), session 5's import from a Claude Code session (UC-10), an upgraded desk's
`oauth_identities` gone and a straggler moved from its working tree, and the docs. Each session's own
check is recorded in `manual-validation.md`.

## 7. Explicitly out of scope

Record-level sync and web history (P5, FR-SYNC-8 to -10); a desk switch for sync through the cloud (sync
reaches nothing on the desk but its own working tree, and only the desk pushes); sync for an unadopted
desk beyond a developer token in its environment (D-4: no BYO remote); per-install repo access (P5); the
update check's GitHub Releases client (P4).

## 8. Follow-ups

Recorded in [`followups.md`](followups.md) by the session that finds them.
