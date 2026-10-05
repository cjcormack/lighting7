# MCP server

The desk is an [MCP](https://modelcontextprotocol.io) server, so Claude — on a phone through a
claude.ai connector, in Claude Code, or in the desktop app — can drive the lights with the same
tools the in-app AI chat has. The decisions behind the shape below are recorded in the project's
`decisions/mcp-auth-and-scripts.md`; this doc is how it is built.

## The public listener

The MCP server is **not** on port 8413. `mcp/McpServerModule.kt` starts a second, separate Netty
server — the **public listener** (`startPublicListener`, called from `Application.module` and
stopped on `ApplicationStopping`) — whose application is `publicModule`:

```kotlin
installRemoteHardening(state)   // mcp/RemoteRequests.kt: every call here is remote
moduleWithState(state)          // the whole desk: UI, REST, WebSocket
mcpModule(state)                // MCP, OAuth and the sign-in page
```

| Path | What |
|------|------|
| everything on 8413 | The desk itself — the UI, `/api/rest/…`, the `/api` WebSocket — under remote hardening |
| `GET /.well-known/oauth-protected-resource[/mcp]` | RFC 9728 metadata |
| `GET /.well-known/oauth-authorization-server[/mcp]` | RFC 8414 metadata |
| `POST /oauth/register` | RFC 7591 dynamic client registration |
| `GET`/`POST /oauth/authorize` | The sign-in-and-consent page |
| `POST /oauth/token` | Code exchange and refresh |
| `POST /oauth/revoke` | RFC 7009 |
| `POST /mcp` | The MCP endpoint (streamable HTTP, JSON responses) |

It carried only the MCP half until Remote access (`decisions/mcp-tunnel-ngrok.md`, Chris,
2026-09-26: "if we do one, I'd like us to do the other"). The **port split is still the security
boundary**, but it now draws a different line: not "what can be reached" but "what counts as
remote". Everything that arrives on this listener is remote — the tunnel points only at it, and
LAN users keep 8413 — so the desk knows for certain which requests came from outside **by port,
never by a forwarded header**, which anyone can spoof. `installRemoteHardening` marks each call
(`ApplicationCall.isRemote`) in the `Setup` phase and refuses what remote may not reach; see
§"Remote hardening". **Anything mounted on this listener is reachable from the internet**, so a
new route is a remote route whether or not its author thought about it.

It binds `127.0.0.1:8414` by default, because the tunnel agent runs on the desk machine; a tunnel
request therefore arrives from loopback, which is why no check here may read the peer address as
"on the LAN" (`requireLanPeer` answers 404 on this listener). If it fails to bind, it logs, the
desk carries on without it, and Remote access reports the failure rather than starting a tunnel
to nowhere.

### Config (`local.conf`, machine-local)

```hocon
mcp {
    enabled = true
    host = "127.0.0.1"
    port = 8414
    # A tunnel you run yourself (Tailscale Funnel, Cloudflare): its HTTPS base, no trailing /mcp.
    # When set it wins over Remote access's ngrok domain as the OAuth issuer.
    publicUrl = ""
    # Extra OAuth redirect URIs to accept, comma separated. claude.ai / claude.com and http
    # loopback (Claude Code, the desktop app) are already allowed.
    extraRedirectUris = ""
    tunnel {
        # Defaults for Remote access until it is first saved from the desk. The authtoken is not
        # here: it only ever goes into the CredentialStore, from the Remote access tab.
        enabled = false
        domain = ""
        # Run this ngrok binary instead of downloading the pinned one (dev, or an unpinned platform).
        ngrokPath = ""
    }
}
```

**The public URL is a provider, read per request** (`RemoteAccessService.publicUrl()`):
`mcp.publicUrl` if set → else `https://<Remote access domain>` → else `http://localhost:<port>`
(right for Claude Code on the desk machine, wrong for anything else). It is the OAuth issuer, the
metadata, the 401 challenge and the `resource` check, so saving a domain takes effect without a
restart — and **changing the domain changes the issuer**: every connector added under the old
address has to be removed and added again. The Remote access tab asks before saving one.

## Remote access (the ngrok tunnel)

`mcp/tunnel/`. The desk runs the tunnel itself, so phone control works from a fresh install with
nothing else to set up. What the operator does: sign up at ngrok, copy the authtoken and the free
static domain, paste both into **Install settings → Remote access** (admin), and turn it on. The
tab then reads *Online · https://<domain>* and shows `https://<domain>/mcp` with Copy.

- **The agent is downloaded on first enable, never bundled.** ngrok's agent licence allows
  distribution only where *we* hold the account, and every desk uses the operator's own. So
  `NgrokInstaller` fetches the pinned build (`NgrokDistribution`: version and SHA-256 per
  platform — darwin amd64/arm64, windows amd64/arm64, linux amd64) from ngrok's CDN into
  `appDataDir()/ngrok/`, verifies the checksum before anything is kept, and extracts only the
  agent. A platform with no pinned build (Linux on ARM, say) gets `NoBinary`, whose message names
  `mcp.tunnel.ngrokPath`. Bumping ngrok means editing that table from checksums read off ngrok's
  own download page, never from a mirror.
- **`NgrokTunnel` supervises it.** It writes `ngrok.yml` (mode 600; the token and URLs quoted as
  JSON strings so no value can break out of its scalar) naming one endpoint,
  `https://<domain>` → `http://127.0.0.1:<mcp.port>`, and runs `ngrok start --all --config …`
  with JSON logs on stdout, parsed for the started event and `ERR_NGROK_*` codes
  (`parseLogLine`, `friendlyMessage`). A crash restarts with backoff (1 s → 60 s); an auth,
  domain or config error does **not**, because only the operator can fix it. It starts only after
  the public listener has bound (`onListenerStarted`).
- **Only an agent this desk started is ever killed.** A PID file records the process and its
  command; the next start kills that PID only if it is still running *our* agent path, so a
  reused PID is left alone (see CLAUDE.md on never killing processes you did not start). On
  Windows the agent is also put in a Job Object with kill-on-close, so it dies with the desk
  however the desk dies. An orphan matters because it keeps holding the domain.
- **State** is a `StateFlow<TunnelState>` — `Off | Installing(read, total) | Starting | Online(url)
  | Error(code, message, retrying) | NoBinary(message)` — streamed to admin sockets as the
  machine-scoped `tunnel.state` frame, the connect snapshot included.
- **Settings are machine-local.** The authtoken is in the `CredentialStore` (`ngrok:authtoken`,
  the keychain or its encrypted-file fallback, beside the GitHub tokens) and is **write-only**:
  no answer ever carries it, only `hasAuthtoken`. Enabled, domain and allow-scripts are the one
  row of `remote_access_settings` (`models/remoteAccess.kt`, `MachineLocal` in
  `SyncCoverageTest`, never exported). `local.conf`'s `mcp.tunnel.*` seeds them until the first
  save, after which the row wins.
- **API**: `GET` / `PUT /api/rest/install/tunnel` (`routes/installTunnel.kt`), admin only. Every
  `PUT` field is optional; an empty `authtoken` or `domain` clears it, and clearing either turns
  remote access off. Turning it on is refused (`REMOTE_ACCESS_INVALID`) with no desk accounts, no
  domain or no token.
- **No plan warning.** ngrok's free plan (1 GB and 20,000 requests a month, a one-time browser
  interstitial) is not pre-empted (Chris, 2026-09-26): we react only if a quota problem is seen.

## Remote hardening

`mcp/RemoteRequests.kt` and the call sites that read `isRemote`. On by default, not configurable
except for scripts and the cannons:

- **A desk with no accounts is closed remotely.** Bootstrap-open is a LAN convenience; from
  outside, `/api…` answers 403 `REMOTE_NEEDS_ACCOUNT`, so nobody on the internet can create the
  first admin.
- **Sign-in lockout.** The desk login (`POST /auth/login`) takes the MCP sign-in page's lockout —
  ten failures in fifteen minutes, then 429 `SIGN_IN_LOCKED` — on remote requests only, sharing
  that failure table. The LAN login keeps its throttle only, so a stranger hammering the tunnel
  cannot lock the crew out at the desk.
- **`Secure` session cookies** on this listener (the tunnel terminates HTTPS); none on the LAN,
  where there is no HTTPS.
- **Origin.** A state-changing request (any non-safe method) or a WebSocket upgrade outside
  `/mcp`, `/oauth/` and `/.well-known/` must carry no `Origin` or one naming the public origin or
  this listener's localhost; anything else is 403 `REMOTE_ORIGIN_REFUSED`. That is the WebSocket's
  CSRF guard too, since a browser attaches cookies to a cross-site upgrade.
- **Not reachable remotely at all (404):** the QR password-reset and device-login flows (both lean
  on "the phone is on the desk's LAN"), `requireLanPeer`, and the API docs (`/api.json`,
  `/openapi`).
- **Scripts are refused remotely unless an admin allows them** (Remote access → *Allow scripts
  over remote access*). A script is arbitrary Kotlin in the desk's JVM. Refused
  (`REMOTE_SCRIPTS_DISABLED`, `requireScriptAccess`): creating, compiling and running a project
  script, changing a saved script's text or type (a rename is fine), the same for FX definitions,
  the whole `/script-editor` service, and the AI chat's `run_lighting_script` (the chat is given
  the MCP tool list and a prompt without the script API instead). MCP never has the script tool,
  whatever this says.
- **Arming and firing are refused remotely unless an admin allows them** (Remote access → *Allow
  arming and firing over remote access*; stage-view plan session 9, P2, D16). A confetti cannon spends
  something physical in a room a remote caller cannot see. Refused (`REMOTE_EFFECTS_DISABLED`,
  `requireEffectsAccess`, the `requireScriptAccess` twin): `POST …/effects/arm`, `…/patches/{id}/fire`
  and `…/patches/{id}/reload`. They are REST precisely so this is the only door to refuse — the
  socket only *reports* the arm and fires (`effects.*`), and MCP offers no tool that arms or fires,
  so it is refused by construction whatever the setting. Authoring a cue's **events** stays open
  remotely: it is data, and an event fires only on a GO while someone at the desk has armed it.
  **The local arm is the consent, by decision** (session 9 review): a GO from a remote session or an
  MCP tool (`go_cue_stack`, `apply_cue`) into a cue with events fires them while the desk is armed,
  as a GO at the desk would. What a remote caller cannot do is arm, fire a tube outright or reload.
- **Fixture commands are refused remotely unless an admin allows them** (Remote access → *Allow
  fixture commands over remote access*; fixture optics plan session 7, D13). A reset swings a head
  through its travel and a lamp off leaves a discharge head dark for minutes, in a room a remote caller
  cannot see. Refused (`REMOTE_COMMANDS_DISABLED`, `requireCommandsAccess`, the `requireEffectsAccess`
  twin): `POST …/patches/{id}/commands/{command}`, the one REST door — and **MCP's
  `run_fixture_command`**, which `McpProtocol` holds to the same setting since MCP is always remote,
  and the in-app chat's, through `AiService`'s `allowCommands` for a remote caller. On the desk's own
  listener both roles run one behind the fixture panel's confirm, and the chat may run one. Off by
  default; `remote_access_settings.allow_commands`, machine-local.

**Residual risks, known and accepted:** a remote *admin* can still bring scripts in by importing a
project or pulling a cloud-sync repo, which the gate does not cover — an admin account is trusted
with the desk. The `tunnel.state` frame's admin check uses the role resolved when the socket
connected, so a demoted admin keeps receiving it until they reconnect (their REST calls are
refused at once). And a remote guesser can lock an account out *remotely* for fifteen minutes;
the LAN login is unaffected.

## Adding a connector

A claude.ai connector (which is how iPhone and iPad reach it) is called from Anthropic's servers,
so the listener must be reachable from the internet over HTTPS: Remote access, above, or a tunnel
of your own — **Tailscale Funnel** (`tailscale funnel --bg 8414`) or **Cloudflare Tunnel**
(`cloudflared tunnel --url http://localhost:8414`) — with `mcp.publicUrl` set to its URL. Then:

- claude.ai → Settings → Connectors → Add custom connector → `<publicUrl>/mcp`. It then appears
  on the phone and iPad apps for that account.
- Claude Code: `claude mcp add --transport http lighting <publicUrl>/mcp` (or
  `http://localhost:8414/mcp` on the desk machine, with no tunnel).

Either way the client opens the desk's sign-in page, the operator signs in with their desk
account and presses **Sign in and allow**, and the client holds a token from then on.

## Auth: the desk is its own authorization server

`mcp/McpAuthService.kt`. OAuth 2.1, public clients, nothing a client could hold as a secret.

- **Registration** is open (clients register themselves), but a redirect URI must be on the
  allowlist: `https://claude.ai/api/mcp/auth_callback`, the same on `claude.com`, `http`
  loopback on any port, and `mcp.extraRedirectUris`. Clients are capped at 1000; ones older than
  a day with no grant are pruned, and a full table evicts the oldest client with no grant and no
  sign-in in progress rather than refusing. Sign-ins in progress are capped at 1000 the same way,
  oldest evicted. Refusing at a cap would let anyone on the internet keep claude.ai out with a
  few hundred junk requests; evicting means a flood has to outpace a real sign-in. A rate limit
  at the tunnel is still the real defence against a sustained flood. An unknown client or unlisted redirect gets an error **page**,
  never a redirect, so the endpoint can't be used to bounce a browser anywhere.
- **PKCE S256 only**; `plain` and a missing challenge are refused. The RFC 8707 `resource`
  parameter, when sent, must name this server (`<publicUrl>/mcp` or `<publicUrl>`).
- **Sign-in** is the desk's own: `AuthService.verifyCredentials`, the same throttle, dummy
  verify and disabled check the login uses. On top of it, this page has a **lockout** — ten
  failures in fifteen minutes and it answers 429 until the window passes — because this page is
  on the internet. The failure table is bounded by dropping
  expired windows and then keys that name no account, never a real account's failures, so junk
  usernames cannot reset a real lockout. The desk login shares it for remote requests (§"Remote
  hardening"); the LAN login keeps its throttle only, so a stranger hammering the tunnel cannot
  lock the crew out of the desk.
- **The consent page** is server-rendered HTML with no script, `X-Frame-Options: DENY`,
  `frame-ancestors 'none'`, `no-store` and `no-referrer`. It posts a form, which is the one place
  the desk takes a form body. That does not reopen the CSRF hole `docs/desk-accounts.md`
  describes: the page reads no cookie, the POST carries the password itself, and the
  `request_id` it names is single-use and bound to an allowlisted redirect.
- **Codes** live one minute, in memory, hashed, and are single-use: a code presented a second
  time is refused.
- **Tokens** are opaque: a one-hour access token and a thirty-day refresh token, stored as SHA-256
  hashes in `mcp_oauth_grants` and cached in memory. A new or rotated grant is cached first and
  then checked against the table and a per-user revocation counter, so a revocation that lands
  mid-exchange or mid-refresh cannot leave a live token behind it. Refresh **rotates** both. Presenting the
  previous refresh token again is taken as theft and revokes the whole grant.
- **A desk with no accounts refuses** (`GET /oauth/authorize` is 403 and `/mcp` has no one to
  authenticate as). A bootstrap-open desk is unauthenticated on the LAN by design; that must not
  extend to the internet.

A resolved token becomes an ordinary `AuthenticatedUser` with the grant's user and role, and a
`sessionTokenHash` of `mcp-grant:<id>` so nothing mistakes it for a browser session.

### Revocation

Grants die on the same events as sessions. `AuthService` fires a credential-revocation listener
from `revokeAllSessionsFor` (disable, password change by anyone, reset redemption, "sign out
everywhere else") and from `deleteUser`; `McpAuthService` subscribes at construction, which is
why it is eager in `State`. A token for a user who is disabled or gone also fails to resolve, as
a second line. Users see their grants under **Connected apps** in Profile → Devices, one row per
grant with Revoke (`GET`/`DELETE /api/rest/auth/connected-apps`, on the LAN port, any role, own
grants only). A client can also revoke its own token at `/oauth/revoke`.

### Tables

`mcp_oauth_clients` and `mcp_oauth_grants` (`models/mcpOAuth.kt`) are **machine-local**
(`SyncCoverageTest`): a grant is a credential for this desk, and a registration names this
desk's URL. Neither is exported, synced or cloned.

## The protocol

`mcp/McpProtocol.kt` is JSON-RPC 2.0 with no transport, so tests call it directly. It speaks
protocol versions 2025-06-18, 2025-03-26 and 2024-11-05, answers every POST with a JSON body (no
SSE, no `Mcp-Session-Id`, no server-initiated messages), answers a notification with 202, refuses
batches, and 405s `GET`/`DELETE /mcp`. A tool answers text content, and `render_view` an `image`
beside it (`{type: "image", data, mimeType}`, base64 — the same shape in all three versions). The `Origin` header, when present, must be the public
URL's origin, claude.ai, claude.com or loopback (DNS-rebinding protection).

### Tools

`describe_rig`, then `AiTools.mcpTools` — every chat tool **except `run_lighting_script`** —
then the show-setup tools below, which only this surface has. A script runs arbitrary Kotlin inside the desk's JVM, so reaching it
through a tunnel would make a leaked token a shell on the desk machine; every other tool is a
bounded operation on the show.

`describe_rig` returns what the chat puts in its system prompt each turn (`ai/RigBriefing.kt`,
shared with `AiService`): fixtures, groups, the effect library, what is running and parked,
speed masters, Looks, colour templates, cues and stacks. The chat gets that for free; an MCP client gets
nothing it does not ask for, so the `instructions` sent at `initialize` tell the model to call it
first, along with the composition rules (`RigBriefing.keyConcepts(scriptTool = false)`).

Each conventional's line names its **lantern** (stage-view plan session 7): `lantern=Source Four
19°`, `(default)` after one the patch does not name, and for a paired dimmer whose lanterns differ,
`lanterns=[Source Four 19°; SR: Par 64 · CP62 MFL]` in placement order. It is what a model needs to
reason about a beam it cannot see; the focus itself is `get_patch`'s.

A unit with loadable settings names its **fitted media** the same way (fixture optics plan session
3): `media=gelScroller L201_FULL_CT_BLUE: R26 Light Red; the rest stock`, or `media=stock`, and for a
fixture with extra placements `media=[…; SL: …]`, each placement's own laid over the fixture's. Only
the fitted slots are named — which gel a scroller frame holds is the unit's, so "frame 9" says nothing
until the model can see what is loaded there.

`describe_rig` and `get_current_state` both report **parked channels** — address, held value,
and the fixture channel it drives (`ai/ParkReport.kt`, from `Fixtures.getChannelMappings`) — and
`park_channel` / `unpark_channel` (chat tools, so the chat has them too) park and release one. Park
sits above every layer the other tools write, so a model that cannot see it applies a look to a
parked head, sees nothing change and cannot say why. The two tools make exactly the WebSocket's
`parkChannel` / `unparkChannel` write (`ParkManager` plus the provenance refresh), so unpark keeps
its hand-down; `park_channel` refuses a universe the show does not output, the silent miss a model
counting universes from 1 would otherwise make, and `unpark_channel` on an unparked address answers
`wasParked: false` rather than an error.

`aim_fixtures` (a chat tool too) points moving heads at a stage coordinate — the same
`aimIntoProgrammer` as `POST …/programmer/aim`, writing pan/tilt into the programmer, with a
`dryRun` that answers each head's degrees and writes nothing, and a `saveAsTemplate` that also
records the aims as a new position template (one fixture row per head, in degrees), since an aim
left only in the programmer is lost at the operator's next Clear. It is the setup-time answer to "focus
the specials on DSC": a model reading a plot knows where a region or a mark is in stage metres (from
`get_patch`) and has no way to turn that into DMX itself. Heads it cannot aim come back by name
(`docs/fixtures-engineering.md` §"Aiming a head at a point").

Its `focus: true` (fixture-optics plan session 1) also focuses each aimed head on the aim point — the
same `focusIntoProgrammer` as `POST …/programmer/focus`, the Stage view's *Focus here*: each head's
FOCUS channel solved for its distance from its lens through its declared range, written beside the
aim. A head aim skipped is not focused, since it is not pointing there. The answer adds `focused`
(each head's focus literal and the distance it was solved for) and `focusSkipped` (a head that was
not aimed, has no focus channel or range, or a point outside it, by name); `dryRun` answers both and
writes neither. `saveAsTemplate` still saves the position only: the template
grammar holds focus, but a template is one family and focus is beam, so a focus is kept with
`record_cue` (`docs/fixtures-engineering.md` §"Focusing a head on a point"). With `render_view`,
that is the plan's MCP check: aim and focus a Revolution on the back wall, then look at it.

`run_fixture_command` (a chat tool too; fixture optics plan session 7) runs one fixture command — a
reset, a lamp strike, a lamp off — through the same `runFixtureCommand` as `POST
…/patches/{id}/commands/{command}`, and answers when the hold ends. `describe_rig` names each
fixture's commands beside its triggers (`commands=reset (Reset, 5.0 s),…`, with what a command sets
for its hold). Over MCP it answers `isError` unless an admin has allowed fixture commands (§"Remote
hardening"), and the schema says to run one only when the operator asked for that command on that
fixture: a model cannot see the head swing or the stage go dark. Its refusals carry the route's codes
(`COMMAND_BUSY`, `COMMAND_BLIND`, `COMMAND_PARKED`, `COMMAND_UNKNOWN`). A row naming a command as a
property is refused by name (`COMMAND_NOT_STORABLE`) by every tool that writes rows.

Tools act on the desk's **current** project, as the chat's do. Before the show is warm a call
answers `isError` with "still starting". `describe_rig`, `get_current_state` and the five setup
readers below (`list_projects`, `list_fixture_types`, `get_patch`, `get_prompt_book`, `get_scene`)
and `render_view` carry `readOnlyHint`.

### Show-setup tools

`ai/SetupTools.kt` (schemas in `ai/SetupToolSchemas.kt`) adds fifteen tools that **build** a show
rather than run one — and one, `render_view`, that looks at the result — for four jobs: a project and patch from another console's patch export, the
Stage view (stage, regions, riggings, fixture placement) from plots or photos, the venue and set
around it (the scene document) from photos or a video frame, and the show's cue stacks and
prompt-book markup from a script and lighting notes.

| Tool | Does |
|------|------|
| `list_projects` / `create_project` / `switch_project` | Projects. `create_project` seeds speed masters as the REST create does and does not switch unless `switchTo`; `switch_project` is `ProjectManager.switchProject` — a blackout — and says so in its description |
| `list_fixture_types` | `FixtureTypeRegistry.allTypes` with a text filter: the vocabulary a patch list is matched against. A type whose length is set per install (a lightstrip) is marked `acceptsLength` with its `defaultLengthM`; a type hung with a lantern from the library (the generic dimmer) is marked `acceptsLantern`, and the tool schema lists the library's ids and each kind's default; a type with loadable settings (the Source Four Revolution) carries `loadable` — each loadable setting, what its slots `take` (`GEL` · `GOBO` · `GOBO_OR_GEL`) and each slot with its stock colour or gobo, the open hole left out |
| `get_patch` | Stage, regions, riggings, universes, every patch (address, `headNumber` where set, groups, rigging, placement, `lengthM` where set, and `alsoAt` — a paired dimmer's other lanterns, or the other sides of a lightstrip run, each with its own `lengthM`) and groups. Every placed fixture and `alsoAt` lantern also carries `world` — its x/y/z composed through its rigging's pose (`show/StageCoords.kt`'s `worldPosition`, the backend copy of the frontend's `worldPositionLighting`, so the model is told the position the Stage view draws), rounded to the millimetre and absent when x or y is unset |
| `patch_fixtures` | Bulk patch, **upsert by key**, `dryRun`; creates missing universes (ARTNET, no address) and groups; takes `headNumber` (the source console's head number — absent leaves it, `null` clears, unique in the project as the patch will stand, so a list that swaps two numbers goes through) and the same placement fields as `place_fixtures`, `alsoAt` and the lantern and focus included, and `media` the same way; a re-patch to another type drops stored media the new type cannot hold, on the fixture and each of its placements |
| `delete_groups` | Delete groups by name; the fixtures stay patched. A group that still has members is refused unless `force`, so a typo cannot take apart a group looks and cues address. Shares `deleteFixtureGroupRows` with the REST delete (memberships and busk rig tiles go with it) |
| `set_stage` | Stage dimensions plus regions and riggings **upserted by name** (sent fields only), and removals. A field it does not know (`width` for `widthM`) is refused rather than skipped, and the answer carries the stage box as stored. `get_patch` re-reads the project row rather than trusting `ProjectManager.currentProject`, whose columns are the values loaded at the last switch — reading those made every stage-box correction look unsaved. The write fires `projectDetailsChanged` so the Stage view redraws the box |
| `set_scene` | The scene document (stage-view plan session 2, D2): **elements and viewpoints upserted by name**, with `removeElements` / `removeViewpoints` and a `dryRun`. An element row is `set_stage`'s shape — `name`, `kind`, `layer`, `x`/`y`/`z`, `yawDeg`, the three sizes, `finish {colour, pattern, emissive}`, `params` (replaced whole: what it may hold depends on the kind) and `hidden`; a platform's `params.region` names a region, stored as its uuid. A viewpoint row is `name`, `kind`, `eye`/`target` as `{x, y, z}`, `fovDeg`, and for a seat `seating` (omissible when the scene has one) and `seat` (`F6`). `template: "proscenium-hall"` with `templateParams` (hall, stage and opening sizes required; deck height, apron, rows, pitches, first-row distance and a balcony optional) expands server-side into named elements — `Hall`, `Stage house`, `Main stage`, `Proscenium`, `Stalls`, `Balcony` — before the explicit rows, and an explicit row of the same name lays its fields over the template's (`finish` one level down). A change that would leave a stored seat view without its seat is refused unless that view is changed or removed in the same call. Shares `validateStageElement` / `validateStageViewpoint` with the REST routes, so the two refuse the same things |
| `get_scene` | The document back, in `set_scene`'s shape so a row can be corrected and resent: a platform's region by name, a seating's seat count and range, a seat view's `seating`, `seat` and the `seatedEye` it resolves to, and the built-in viewpoints. Read-only |
| `render_view` | A PNG of a stage viewpoint, drawn by a signed-in desk window (§"`render_view`" below): `viewpoint` a camera (`orbit`, `eye`, `plan`, `front`, `side`), a saved view by name or uuid, or a seat `{seating, seat}`; `width` / `height` 160–1920 and at most 1920 × 1080 pixels in all (default 1280 × 720; one side alone is 16:9 to it, refused if that puts the other out of range); `source` `output` (default), `outputProgrammer`, `programmer` or `nextGo`. Read-only. `ai/RenderViewTool.kt` |
| `place_fixtures` | Partial placement per key: rigging (by name, `null` detaches), offsets, yaw/pitch/roll (`rollDeg` stands a strip on end), beam, gel, kind, hidden, `lengthM` (only for an `acceptsLength` type — refused by name for any other, `null` clears), and `alsoAt` — a paired dimmer's other lanterns (label, rigging, offsets, yaw/pitch/roll, and a side's own `lengthM`), the whole list replacing the stored one, matched by position so a re-sent lantern keeps its identity; `[]` or `null` clears. The description steers a model to patch a paired circuit once rather than a second fixture at one address. For an `acceptsLantern` type, the row and each `alsoAt` entry also take the **lantern and its focus** (stage-view plan session 7): `lanternType` (a library id; on an `alsoAt` entry null means the fixture's own), `zoomDeg`, `lampRotationDeg`, `shutters` (four `{depth, angleDeg}`, top · bottom · left · right), `gateRotationDeg`, `iris`, `focusSoftness` — absent leaves each, `null` clears — checked by the same `resolvePatchFocus` the REST routes call, so an unknown id, a zoom outside the lantern's range, a kind that contradicts the lantern, or any of them on a DMX type is refused by name with nothing written; a lantern names the kind, so a model sending one need not send `kind`. `get_patch` reports all seven where set, per placement too. For a type with loadable settings, the row and each `alsoAt` entry also take **`media`** (fixture optics plan session 3) — `{slots: {<setting>: {<slot>: {gel} | {gobo} | {}}}}`, naming only the slots that differ from the stock, `{}` an empty slot; on the row absent leaves it and `null` clears, on an `alsoAt` entry it is the lantern's own, layered over the fixture's slot by slot — checked by the same `patchMediaRefusal` the REST routes call, every problem at once and nothing written: an unknown setting or slot, the open hole, an unknown gel (the schema lists the library's codes) or gobo, a gobo in a gel-only slot, any of it on a type with no loadable settings. `get_patch` reports each unit's `media` where set |
| `get_prompt_book` | Page count, cover pages, anchors (with cue number and stack) and notes; with no book, where to import one |
| `build_cue_stack` | A new stack (or `stackId` to append) of cues in running order: number, name, notes, fade, curve, follow, marker, look layers, `scenery` (stage-view plan session 8: the elements each cue moves on GO, below), `events` (session 9: the one-shot triggers each cue fires after GO, below), and `at` — its place in the prompt book. A stack-level `scenery` is the stack's *set*, replacing the one stored when given |
| `mark_up_prompt_book` | Cover pages, anchor upserts for existing cues, and notes (NOTE with tone, FREETEXT, STRIKETHROUGH) |

**Scenery** (stage-view plan session 8). The cue and Look authoring tools carry a `scenery` list —
`create_cue`, `create_look` and `build_cue_stack` (per cue, and the stack's set) — and `set_scenery`
replaces one cue's, stack's or Look's whole list (`cueId` | `stackId` | `lookId`; `[]` clears), so
"close the tabs on the blackout at the end of Act 1" is one call. Those three are the chat's tools
too, so MCP gets them through `AiTools.mcpTools`; `build_cue_stack` is MCP-only like the rest of this
table. One item is `{element, visible?, open?, trimM?, transitionSeconds?}`: the element by its
`set_scene` **name** (or uuid), the states beside it, and on a cue its own clock (omitted, it moves
with the cue's fade). Each is checked against the element's kind exactly as the REST `PUT
…/scenery` routes check it (`parseToolSceneryList` beside `parseSceneryList`, `models/scenery.kt`):
`open` only on a drawn drape, `trimM` only on a flown piece, an element named twice refused, every
problem at once and nothing written. The schema text says scenery **tracks** — list only what
changes — and that a Look's scenery shows while it is live, above every cue; templates carry none.

**Events** (stage-view plan session 9). `create_cue` and `build_cue_stack` (per cue) carry an
`events` list, and `set_cue_events` replaces one cue's whole list (`cueId`, `events`; `[]` clears),
so "both cannons at the curtain call, 0.6 and 0.9 s after GO" is one call. One item is `{fixture,
trigger, offsetSeconds?}`: a fixture with one-shot triggers by key or name (`describe_rig` lists them,
`one-shot triggers=output1 (A),output2 (B)`), the tube by name or label, and the offset from GO
(0–600 s, default 0). Checked as the REST `PUT …/events` checks it (`parseToolCueEventList` beside
`parseCueEventList`, `models/cueEvents.kt`): a fixture with no trigger, an unknown tube, a tube named
twice refused, every problem at once and nothing written. The schema text says what an event is —
fired on GO into the cue only, never tracked or previewed, and only while the operator has armed the
desk — and that arming and firing are the operator's: **no tool arms or fires**, and a row naming a
trigger as a property is refused by name (`TRIGGER_NOT_STORABLE`) by every tool that writes rows.

Four decisions shape them:

- **MCP only.** They are not in `AiTools.allTools`, so the in-app chat does not get them: its
  conversation belongs to the current project, which `switch_project` would move out from under
  it, and the documents these tools are for arrive through an MCP client.
- **No tool takes a file.** The model reads the PDFs and photos in its own conversation and passes
  structured data. The one file the desk must hold — the prompt-book PDF — comes in through the
  Prompt Book view's import (`/prompt-book`), which hashes it and counts its pages with pdf.js;
  the backend has no PDF parser and this did not add one. `get_prompt_book` and every `at` that
  meets a book-less project say so, naming the desk's public URL when it is known.
- **Whole request validated, nothing written on any error, every problem answered at once**
  (`rejected()`, capped at 100). These calls carry tens or hundreds of rows transcribed from a
  document; all-or-nothing makes resending the corrected request the whole recovery. The
  validators are the routes' own (`validateStageMetadata`, `validateRiggingPose`,
  `validateStageRegion`, `validateStageDimensions`, `checkPromptBookRegion`,
  `validateCueChildren`), and `patch_fixtures` checks overlaps against the patch *as it will
  stand* — rows being updated leave their old address — so a re-addressing list lands in one call.
  Upsert by key / name and refusing a duplicate stack name or cue number make a retried call safe.
- **Writes broadcast as the routes do** (`patchListChanged` after a `DbFixtureLoader` reload,
  `riggingListChanged`, `stageRegionListChanged`, `stageElementListChanged`,
  `stageViewpointListChanged`, `projectDetailsChanged` for the stage box,
  `buskRigChanged` when `delete_groups` takes rig tiles off, `cueListChanged` /
  `cueStackListChanged`, `promptBookChanged`), so the desk's views follow along live. `place_fixtures` writes only the
  metadata columns `METADATA_ONLY_PUT_KEYS` names, so it skips the fixture reload the same way —
  and, like both REST placement paths, refreshes the one thing the running show caches from them,
  the gel (`Fixtures.setPatchMetadata`, which `GET /fixtures` reads).
- **A malformed value is refused, never read as a clear.** An explicit `null` clears a field; a
  number field holding a string is a problem like any other. A cue layer naming a Look that is
  not in the project is refused too, although the shared cue write (`createCueChildren`) drops
  one silently — there, by design, for a Look deleted since.

A prompt-book place is `{pdfPage, y, x?, width?, height?}`: `pdfPage` counts from 1 at the
file's first page (converted to the stored 0-based index), `y` is a fraction of the page from the
top, and the defaults (x 0.06, width 0.88, height 0.03) are the Prompt Book view's text-column
band. A height that would run off the page is clamped rather than refused. Anchor labels are
written `Q<number>` (else the cue name), as the view writes them. Cue times are **seconds** on
this surface (`fadeSeconds`, `followSeconds`) because lighting notes are written that way;
`followSeconds` is the cue's auto-advance delay.

**Why a template, and why it is expanded server-side.** The research the design record cites is
that a model filling in a template's parameters does far better than one writing free-form
geometry. The template's origin is the desk's — the centre of the stage's downstage edge at deck
level — so the hall floor sits at −`deckHeightM` and the hall runs from the edge back to
−`hallDepthM`; what it produces is ordinary named elements the operator (or a later call) edits.

**`describe_rig` gains a stage summary** (`RigBriefing.stageSummary`): the coordinate frame, the
stage box, the regions, the riggings upstage first with their kind and trim, a count of the scene's
venue and set elements, and the saved viewpoints — a paragraph, not the document, because the
briefing is also the in-app chat's prompt on every turn. Omitted for a project with none of it.
Since stage-view plan session 6 a rigging of a standing kind (`STANDING_RIGGING_KINDS`: `LEDGE`,
`FLOOR_STAND`) says *units stand on it*, and a *Mounts:* line names each moving head whose
base orientation disagrees with how its rigging carries it — 180 on a ledge is drawn and aimed hung
under it, 0 on a bar stands on top of it, read through roll as the view reads it and silent for a
head on its side (`docs/fixtures-engineering.md` §"Aiming a head at a point").

### `render_view`: Claude seeing the model

Stage-view plan session 4 (D4). `set_scene` lets a model build the hall from a photo; this lets it
look at what it built and correct it. **The backend cannot draw WebGL**, so a signed-in desk window
does: the desk resolves the viewpoint, asks one window to render it offscreen, and answers the PNG
it uploads. A desk with no window open cannot answer — by design (plan §10), and said so by name.

**The tool** (`ai/RenderViewTool.kt`) validates first and asks a window only when everything
resolves: the size and source, then the viewpoint against the **current** project's rows — a
camera word (any case), else a saved view by exact name, else by uuid (so a saved view named like a
camera is reached by its uuid), or `{seating, seat}` with the seating omissible when the scene has
one. What it sends the window is the Stage view's own vocabulary — a camera, a saved view's uuid,
or `seat:<uuid>:<id>` — so the window resolves exactly what its picker would. A saved seat view is
sent as its row, so the window lands the row's own target and lens; one whose seat has gone is
refused, as the picker disables it. Every failure is a named `error` in the text content:

| Code | When |
|------|------|
| `RENDER_INVALID_REQUEST` | An unknown field, a side outside 160–1920 or more than 1920 × 1080 pixels in all, one side alone whose 16:9 partner would be out of range, an unknown source, a malformed viewpoint, or an ambiguous seat (several seatings, none named) |
| `RENDER_UNKNOWN_VIEWPOINT` | No camera, saved name or uuid matches; the message lists the cameras and the saved views |
| `RENDER_UNKNOWN_SEAT` | No such seating, no such seat (the message gives the rows and seat range), or a saved seat view whose seat has gone |
| `RENDER_NO_WINDOW` | No eligible window is open |
| `RENDER_BUSY` | Another render held the desk for a whole timeout's worth of waiting |
| `RENDER_TIMEOUT` | The window did not answer within 30 s; the message names it |
| `RENDER_WINDOW_CLOSED` | The window's socket closed before it answered |
| `RENDER_FAILED` | The window answered with a reason — its WebGL context was lost, a read failed, it gave up waiting for the scene, or its frame was refused as too large or not a PNG |

**The request path** (`state/StageRenderService.kt`) is a **job, not a command**. The five
`windows.*` commands are rebroadcast to every socket and acted on by the one they name; a render is
addressed to **one** socket, carries a one-shot request id and a secret token, and is answered once.

- **Which window.** Only a socket on the desk's own listener — never the public one (`isRemote`, by
  port) — that is signed in and has announced itself, on a view that does not name another project
  (`/projects/{id}/…`; a view that names none, `/install` say, is eligible). Of those, a window on
  the desk machine itself (its socket's peer is loopback) comes first — the desk's GPU, not an
  iPad's — then the registry's order, oldest announce first: on an ordinary night, the operator's
  first screen. A remote socket and a bootstrap-open one are never even attached
  (`setupStageRenderSubscriptions`), so they are never chosen and never sent a request.
- **The request** goes out as `stageRender.request` (`docs/websocket-engineering.md`), down the
  chosen socket's own queue — each attached socket has one, attached and detached on that
  connection's own coroutine, so a request sent a moment after the window announces waits for it. The socket gains **no inbound message**, so `FU-AUTH-WS-PER-MESSAGE` is
  not fired: nothing an operator's client could send over the socket changes.
- **The answer is a REST upload**, `POST /api/rest/stage-renders/{requestId}` with the raw PNG and
  `X-Render-Token`, or `…/failure` with `{reason}` (`routes/stageRenders.kt`). REST rather than a
  socket frame because a PNG is hundreds of KB — base64 in JSON would be a third larger, through the
  one sequential message loop the operator's writes use, on a socket with no per-message cap — and
  because a bounded read of a raw body is the prompt book's pattern. It is accepted only while the
  job is live, with its token (sent to that socket only) **and** from that socket's session; anything
  else is 404 `RENDER_REQUEST_UNKNOWN` (answered, timed out, or never issued) or 403
  `RENDER_REQUEST_NOT_YOURS`. The body is read bounded at 4 MB (`MAX_RENDER_BYTES`) and must carry
  the PNG signature; either refusal (413 `RENDER_TOO_LARGE`, 400 `RENDER_NOT_PNG`) also ends the job
  with that reason, since its window will not send another, and so does a `…/failure` body that
  will not parse. **Never on the public listener**: a signed-in remote answer is 404 before
  anything is looked up (an unsigned one is the auth gate's 401, before the route runs).
- **One render at a time**, desk-wide: a render holds a WebGL context on someone's screen for a
  moment. A call waits behind one in flight for no longer than the timeout, then answers
  `RENDER_BUSY`, so every call ends within twice the timeout however many are queued. A socket that
  closes mid-render ends its job at once (`RENDER_WINDOW_CLOSED`) rather than at the timeout.
  Nothing is persisted and nothing is a table.
- **Sizes.** Each side 160–1920 and at most 1920 × 1080 pixels in all, because the frame a window
  may upload is capped at 4 MB: a stage render at 1280 × 720 is a few hundred KB, so the pixel cap
  keeps even a hazy frame well inside the byte cap.

**Residual, accepted:** the desk's own listener is a port, not a peer address, so a signed-in
browser that reaches it from off the LAN (a port forward) is a window like any other. And a remote
MCP client can make a desk window draw — read-only, one at a time,
bounded by the timeout. The desk cannot see whether a window's tab is hidden; a hidden one still
renders (the frontend draws without `requestAnimationFrame`), but a machine asleep will time out.

The window's half — a lazily loaded render host in the app shell, the Stage view's own scene on a
detached canvas — is `frontend/docs/stage-vis-engineering.md` §"Rendering for `render_view`".

Tests: `src/test/kotlin/.../mcp/McpSetupToolsTest.kt` and `McpSceneToolsTest.kt`; `render_view` in `McpRenderViewTest.kt` (the vocabulary, the named errors, and the whole round trip over a real signed-in socket) and `state/StageRenderServiceTest.kt`.

## Tests

`src/test/kotlin/.../mcp/McpServerTest.kt` mounts `publicModule` and runs the whole
connect: discovery, registration, sign-in, code exchange, `initialize`, `tools/list`, a real tool
call reaching the master clock, refresh rotation and reuse, the redirect allowlist, the lockout,
deny, a bootstrap desk, revocation on disable and password change, and the Connected apps REST
pair, plus that the public listener serves the desk's own API and that a saved domain becomes
the issuer without a restart. `RemoteHardeningTest` covers §"Remote hardening" item by item and
the tunnel settings route (the token never comes back, admin only). `mcp/tunnel/` drives the
supervisor against a **fake `ngrok`** — a shell script printing the real agent's JSON log lines —
through online, crash-and-restart, a bad token (not retried), a plain-text error and the orphan
sweep (POSIX only); the installer against hand-built zip and tgz archives, including a checksum
mismatch; and `RemoteAccessService` end to end with the fake agent. Nothing is tested against the
real claude.ai or the real ngrok.
