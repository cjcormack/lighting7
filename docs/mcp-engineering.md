# MCP server

The desk is an [MCP](https://modelcontextprotocol.io) server, so Claude — on a phone through a
claude.ai connector, in Claude Code, or in the desktop app — can drive the lights. (The desk once had its own
in-app AI chat, *Lux*, calling Anthropic's API with these same show-running tools; it was removed in
favour of this server.) The decisions behind the shape below are recorded in the project's
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
  and the whole `/script-editor` service. MCP never has a script tool, whatever this says.
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
  `run_fixture_command`**, which `McpProtocol` holds to the same setting since MCP is always remote.
  On the desk's own listener both roles run one behind the fixture panel's confirm. Off by
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

`describe_rig`, then the show-running tools (`AiTools.toolDefs`), then the show-setup tools
below. There is **no script tool** (§"No script tool"): a script runs arbitrary Kotlin inside the
desk's JVM, so reaching it through a tunnel would make a leaked token a shell on the desk machine;
every tool here is a bounded operation on the show.

`describe_rig` returns the live rig in prose (`ai/RigBriefing.kt`): fixtures, groups, the effect
library, what is running and parked, speed masters, Looks, colour palettes, cues and stacks. An MCP
client gets nothing it does not ask for, so the `instructions` sent at `initialize` tell the model
to call it first, along with the composition rules (`RigBriefing.keyConcepts()`).

Each conventional's line names its **lantern** (stage-view plan session 7): `lantern=Source Four
19°`, `(default)` after one the patch does not name, and for a paired dimmer whose lanterns differ,
`lanterns=[Source Four 19°; SR: Par 64 · CP62 MFL]` in placement order. It is what a model needs to
reason about a beam it cannot see; the focus itself is `get_patch`'s.

A coloured fixture's and group's line ends its facts with `colour=red` (tables-colours plan D7), the
colour as the show's rig draws it — a venue record's recolour by this show where it has one — so "the
red fixtures" names something the model can find.

A unit with loadable settings names its **fitted media** the same way (fixture optics plan session
3): `media=gelScroller L201_FULL_CT_BLUE: R26 Light Red; the rest stock`, or `media=stock`, and for a
fixture with extra placements `media=[…; SL: …]`, each placement's own laid over the fixture's. Only
the fitted slots are named — which gel a scroller frame holds is the unit's, so "frame 9" says nothing
until the model can see what is loaded there.

**A cue's places** (cue model plan §3.10). A cue may sit in several stacks, twice in one, or in none,
so every tool names a cue by id and a place by **entry** — `entryId`, or the stack's `number`, the
one the Show view shows and unique in its stack (`ai/CueEntryRefs.kt`'s `EntryRef` and
`findStackEntry`). `activate_cue_stack` and `set_standby` take one and refuse a `cueId` by name;
`add_cue_to_stack` and `create_cue` (with `stackId`) take a new entry's `number` and `after`;
`remove_cue_from_stack` and `move_cue_to_stack` are the REST entry delete and move
(`announceEntriesRemoved`, `moveEntryChecked`); `include_into_programmer` names the entry a cue is
included from; the prompt book's anchors name entries. `describe_rig` lists each cue's places
(`in: Act 1 Q14, Act 2 Q3`, or `loose` — `cuePlacesLabel`) and each stack's entries with their
numbers and ids, and the briefing says a cue may be shared and that only `delete_cues` deletes one.
`get_current_state`'s `cues` carry `entries`, `cue_run` the entry ids, and `cue_holds` what holds
each live cue. The stack tools are in `docs/cue-stacks-engineering.md` §"MCP tools".

`describe_rig` and `get_current_state` both report **parked channels** — address, held value,
and the fixture channel it drives (`ai/ParkReport.kt`, from `Fixtures.getChannelMappings`) — and
`park_channel` / `unpark_channel` park and release one. Park
sits above every layer the other tools write, so a model that cannot see it applies a look to a
parked head, sees nothing change and cannot say why. The two tools make exactly the WebSocket's
`parkChannel` / `unparkChannel` write (`ParkManager` plus the provenance refresh), so unpark keeps
its hand-down; `park_channel` refuses a universe the show does not output, the silent miss a model
counting universes from 1 would otherwise make, and `unpark_channel` on an unparked address answers
`wasParked: false` rather than an error.

On a show on a venue (`docs/fixtures-engineering.md` §"Venue parks") every parked entry carries its
`owner` — `SHOW`, or `VENUE` for one inherited from the pinned version — and a show's re-park its
`venueValue`; `get_current_state` adds `releasedVenueParks`, and `describe_rig` marks a venue park
*(venue)* and lists the released ones. `park_channel` answers `owner: SHOW`, `previousOwner` and the
venue's `venueValue` under it; `unpark_channel` answers an `outcome` — `UNPARKED`, `RELEASED` (a venue
park released for this show, the venue keeping it) or `VENUE_RESTORED` (the show's park gone, the
venue's at `venueValue` standing again). In a venue both are admin-only, as every stored write there is
(`VENUE_STORED_WRITE_TOOLS`).

`create_look` authors a Look's **values** as well as its effects and scenery (`ai/LookValues.kt`):
`values: [{target, propertyName, value}]`, each bound to its own fixture or group, as a Look row
always is. The model states an intent — the palette grammar (`pct:`, `#rrggbb`, `pos:` — a
centre-relative position, `-128..127` with 0 the centre of travel — or a stored `deg:`, `dmx:`,
`on`/`off`), or a setting's option name for a gobo or a wheel slot — and each is resolved per head
through `PaletteResolver`, the resolver a palette press uses, into the literal a Look row stores;
`pct:` and `dmx:` reach any single channel (a hazer's `fanSpeed`), not only the palette vocabulary. A
group stays one group row while its members agree on the literal, and becomes a row per head when they
do not (a wheel head and an RGB head given one colour). A head that cannot take a value is reported
under `skipped`; a value no head of its target can take, or the same target and property twice,
refuses the call. A fixture's own value beats its group's in the cook, so a split group leaves a
member the call names on its own alone. **A multi-head fixture's cells are targets too**, each by its
own key (`shehds-1.zone-2`), stored as a cell row (the parent as the target, the cell as
`elementKey`); a value the fixture cannot take itself but its cells can — the 24-channel Shehds' colour,
which lives only on its three zones — lands on **every cell**, one cell row each (the cook never fans
a fixture or group row out to cells), and a cell named on its own keeps its own value
(`resolveOnHeadOrCells` in `ai/LookValues.kt`; `set_programmer` resolves the same way). That was the
gap a model hit: the Shehds' fixture-level colour was refused and nothing said a cell had a key, so
`describe_rig` now lists each multi-head fixture's cells (`cells=3 (…zone-1 … …zone-3)`) and the
target and `create_look` descriptions say how to name one. The rows then go through the
REST write path (`validateLookRows`, `writeLookChildren`), so a trigger or command name is refused as
it is there. **A Look's effects name their own targets** (absolute-looks plan D2), taken as
`create_cue`'s ad-hoc effects take one (`parseAdHocFxTarget`): `targetType` and `targetKey`, or
`targets` for one effect over a selection; `update_look` takes them the same way, and one naming no
target is refused before anything is written. `apply_look` puts a Look on whole, wherever its own
values and effects land, and a cue layer of a Look names no targets — any given are dropped; its
`asValues: true` is `POST /looks/{id}/apply` instead, the Look's values and effects as the programmer's
own. `list_fixture_types` with `properties: true` is where the model learns the names. A layer with no
targets on a generic palette is refused by `create_cue` and `build_cue_stack`, as the busk pad refuses
the same press. There is no static
effect: `StaticValue` was removed once a Look could hold a value, and `StaticSetting`, `StaticColour` and
`StaticPosition` once `set_programmer` could set any property, so a fixed state is always a value on the
fixture's own property. A stored effect naming a type the registry no longer has is skipped with a warn
wherever a cue or Include spawns it (`FxSpawner.createFxInstanceOrNull`), not left to abort the GO.
A colour value may carry the head's own emitter levels as the desk's colour literal does —
`#ff0000;w128;a64;uv32`, each token at most once, any other token refused rather than dropped — laid over
the resolved mix only where the head bundles that emitter with its colour (`bundleWithColour`). A
`;policy=extract|additive|rgbonly` token is the palette grammar's white policy (`PaletteResolver.mixColour`),
absent reading as `rgbonly`, so a colour with neither sets W/A/UV to zero; a named level wins over the
policy's; a `position` takes `pos:` (or a stored `deg:`), which reaches every DMX-backed head,
annotated or not, and pan and tilt also take `pct:` / `dmx:` each on its own.

`update_look` edits a Look as the REST `PUT /looks/{id}` does: a new name (refused when another
Look holds it) and notes, and each of `values`, `fx` and `scenery` given replaces that part
whole while one left out is kept — the same resolve and checks as `create_look`, every part checked
before anything is written. A values or effects change goes through `republishForLookEdit`, so every
live cue layering the Look moves without a re-fire; a rename, notes or scenery fire
`lookListChanged`, which the library and the live scenery read. `delete_look` is the REST delete
itself (`performLookDelete`): refused with `LOOK_IN_USE` and the cues that layer it unless `force`,
which deletes those layers — rebuilding a live cue that held one, so the Look's values leave the
stage without a re-fire (effects the cue spawned from it run until the cue stops) — and the Look's busk pads, cue slots, scenery, programmer layers and include
target go with it.

A cue's **own values** — `create_cue`'s and each `build_cue_stack` cue's `values` — take
`create_look`'s grammar and resolve the same way, stored as the cue's property assignments (a cell by
its own key, as Record stores one). They beat every layer of the cue whatever its order. They are
resolved against the loaded rig, so `build_cue_stack` refuses them in a project that is not current,
as it refuses `at`. A cue **layer** names a Look or, with `paletteId`, a palette — exactly one. A
layer naming a record the project lacks is refused by both tools (`layerSourceProblems`), where
`writeCueChildren` would drop it silently; a generic palette (an effect palette, or rows for
whatever it is pointed at) with no layer targets is refused as the busk pad refuses it with nothing
selected. An effect missing a required field is refused by the field's name.

**The programmer** (`ai/ProgrammerTools.kt`). Five tools write it the way the desk does, each through
the code the desk's own gesture goes through, on the current project:

- `set_programmer` `{targets, values: [{propertyName, value}], fadeSeconds?}` — plain values in
  `create_look`'s grammar, resolved per head by `resolveProgrammerValues` (the same parse and
  `PaletteResolver` per-head resolution as a Look's values, answering each head's `PropertyValue`
  rather than Look rows), written as `ProgrammerOwner.WEB` in one `writeProperties` batch with each
  head's `sourceGroup` from `groupHintsForTargets` — *Apply as values*' value arm — so Record
  collapses a group's agreeing heads back to one group row. A head that cannot take a value, or whose
  write resolves to no channel, is in `skipped`; a value no selected head can take, an unknown target,
  or a trigger or command name refuses the call before anything is written. A colour write carries the
  head's bundled W/A/UV with it, so a later colour call resets an emitter an earlier call set — the
  desk's colour picker does the same; within one call `writeProperties` stores emitter rows last, so a
  `white` value beside an `rgbColour` one wins.
- `clear_programmer` `{targets?, fadeSeconds?}` — none is `clearProgrammerCompletely` (the WS
  `programmer.clearAll`); targets are each `ProgrammerHandler.clearTarget` (the WS
  `programmer.clearTarget`, the grid's *Release*), all checked before any is cleared.
- `set_blind` `{blind, fadeSeconds?}` — the WS `programmer.setBlind` branch, call for call:
  `programmer.setBlind`, `sceneryService.onBlindChanged`, `effectsService.onBlindChanged`.
- `apply_palette` `{paletteId | paletteName, targets?, asValues?, mask?, fadeSeconds?}` — by default
  the desk's press (apply-model plan D9): the toggle route's `programmerLayerStack.toggle`, with its
  family replacement and widening, answering `released` when it took heads off another palette.
  `asValues: true` is `applyPaletteToProgrammer`, the values *Apply as values* writes. A generic palette with no targets is `PALETTE_NEEDS_SELECTION`;
  a per-fixture one with none lands on its own heads, as its pad press does. `mask` refuses a palette
  outside it (`PALETTE_OUTSIDE_MASK`), on the layer's on arm only (`pressWouldRelease`). Both press-log
  as the desk's doors do.
- `record_look` — `performProgrammerRecordLook`, which `POST …/programmer/record-look` now calls too
  (extracted from the route as Record into a cue's `performProgrammerRecord` was), with record_cue's
  modes, source and mask, `fxIds` to move programmer effects into the Look, and the Look's rows in
  the answer.

Every fade on these is `fadeSeconds`, 0–600.

**The palette tools were the template tools** until venue-visit plan D2: `create_template` and
`apply_template`, with `templateId` / `templateName`, a cue layer's `templateId`, `aim_fixtures`'
`saveAsTemplate`, `get_current_state`'s `templates` section and a programmer layer's `TEMPLATE`
`sourceKind`. All of them say palette now, with no alias, because Claude reads them and the desk
stores none of them. A client connected before the rename holds a stale `tools/list` and must
reconnect to see the new names; a call to an old name is refused as an unknown tool. The stored side
moved too, in terminology plan session 4: the `palettes` tables, `pal:` references and the
`palettes/` archive folder. A connected MCP client must reconnect to see the tools and fields
terminology plan session 5 renamed (`clear_fx`, `active_fx`, a Look's and a palette's `fx`, the FX
schema's `family`, `order`, `amount`, `segments`, `parts` and `timing`), because a client caches the
tool list it was given at connect. Until it does, an old argument is refused by name, not dropped:
both tool sets check every call's arguments against the tool's input schema before running it
(`ai/ToolArguments.kt`, into object properties and array items, a map's keys aside) and answer
`'fx[0].size' is not an argument of create_look; it takes …`, naming the arguments it does take and
suggesting a reconnect, where the tool would have read the fields it knows and reported success. An
argument a tool once took and retired (`add_cue_to_stack`'s `sortOrder`, a `cueId` where an entry is
meant, `apply_palette`'s `mode`, `apply_look`'s `targets`) is answered with what replaced it instead.
The tools that already refuse an unknown argument themselves, beside every other problem of the call
(`set_stage`, `set_scene`, the patch-edit and venue tools, `render_view`, `move_scenery`:
`CHECKS_OWN_ARGUMENTS`), keep their own check, which a test holds them to. A field `get_scene` answers
and `set_scene` takes back unread is declared in its schema as read-only, so a row still goes back as
it stands.

`aim_fixtures` points moving heads at a stage coordinate — the same
`aimIntoProgrammer` as `POST …/programmer/aim`, writing pan/tilt into the programmer, with a
`dryRun` that answers each head's degrees and writes nothing, and a `saveAsPalette` that also
records the aims as a new position palette (one fixture row per head, centre-relative `pos:`), since an aim
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
writes neither. `saveAsPalette` still saves the position only: the palette
grammar holds focus, but a palette is one family and focus is beam, so a focus is kept with
`record_cue` (`docs/fixtures-engineering.md` §"Focusing a head on a point"). With `render_view`,
that is the plan's MCP check: aim and focus a Revolution on the back wall, then look at it.

`run_fixture_command` (fixture optics plan session 7) runs one fixture command — a
reset, a lamp strike, a lamp off — through the same `runFixtureCommand` as `POST
…/patches/{id}/commands/{command}`, and answers when the hold ends. `describe_rig` names each
fixture's commands beside its triggers (`commands=reset (Reset, 5.0 s, RESET),…` — the role where it has one, venue-visit plan S6 — with what a command sets
for its hold). Over MCP it answers `isError` unless an admin has allowed fixture commands (§"Remote
hardening"), and the schema says to run one only when the operator asked for that command on that
fixture: a model cannot see the head swing or the stage go dark. Its refusals carry the route's codes
(`COMMAND_BUSY`, `COMMAND_BLIND`, `COMMAND_PARKED`, `VENUE_HOLD`, `COMMAND_UNKNOWN`). A row naming a command as a
property is refused by name (`COMMAND_NOT_STORABLE`) by every tool that writes rows.

Tools act on the desk's **current** project, except where they take a `projectId` (§"Another
project than the live one" below). Before the show is warm a call
answers `isError` with "still starting". `describe_rig`, `get_current_state` and the six setup
readers below (`list_projects`, `list_venues`, `list_fixture_types`, `get_patch`, `get_prompt_book`,
`get_scene`) and `render_view` carry `readOnlyHint`.

### Show-setup tools

`ai/SetupTools.kt` (schemas in `ai/SetupToolSchemas.kt`; the venue and cross-project ones in
`ai/VenueSetupTools.kt`) adds twenty-five tools that **build** a show
rather than run one — and one, `render_view`, that looks at the result — for four jobs: a project and patch from another console's patch export, the
Stage view (stage, regions, riggings, fixture placement) from plots or photos, the venue and set
around it (the scene document) from photos or a video frame, and the show's cue stacks and
prompt-book markup from a script and lighting notes.

| Tool | Does |
|------|------|
| `list_projects` / `create_project` / `switch_project` | Projects. `list_projects` carries a show's `venue` (`id`, `name`, pinned `version`, `installed`, `behind` when not `NONE`) and a venue's `installedVersion`. `create_project` seeds speed masters as the REST create does and does not switch unless `switchTo`; with `venueId` it is the REST create's show on a venue — pinned to the installed version (`VENUE_NOTHING_PUBLISHED` before v1), the stage size the venue's (stage fields refused beside it) — and `seatingPreset` names one of that version's Preset seating blocks by name, copied in through `SeatingPresets.copyOnCreate` (`SEATING_PRESET_UNKNOWN` names what it offers). `switch_project` is `ProjectManager.switchProject` — a blackout — and says so in its description; a show whose pinned venue version is not on the desk is refused with `"code": "VENUE_MISSING"` (venue plan session 4), as the set-current route refuses it |
| `list_venues` | Every venue: its installed version and note, what that version holds (elements, viewpoints, patches, universes, riggings, groups, Fixed seating — the draft's before v1), `hasRig`, its seating presets, `unpublishedChanges` (the draft against the installed version, `VenueVersions.draftChanges`, read outside the listing's transaction since it exports) and every show on it with its pinned version and `behind`. Read-only, both roles — the Projects page's venue cards are anyone's |
| `extract_venue` | `VenueExtract.extract`, as `POST …/venue/extract` calls it: `{fromProjectId, name, include?, dryRun?}`, `publishedBy` the caller's display name. **Admin only** (`McpProtocol.VENUE_ADMIN_TOOLS`). Extracting the **current** project reloads it — the route switches to it again — so the tool refuses that unless `reloadLiveShow: true`, the description saying when that is safe; a dry run never needs it |
| `attach_to_venue` | `VenueAttach.attach`, as `POST …/venue/attach` calls it: `{projectId, venueId, version?, decisions?, dryRun?}`, answering the same `AttachReviewDto` — so `dryRun` is the review, and the decisions and overlaps it lists are answered by id on the next call. **Both roles** and no remote-access gate: a stored write to the show, as Detach is; `projectId` is project-scoped and a stored write (`PROJECT_SCOPED_TOOLS`, `VENUE_STORED_WRITE_TOOLS`), so an operator naming a venue is refused as over REST. Attaching the **current** project reloads it (`afterAttach`, shared with the route: *Run against* first when it lands behind on output), so it takes `extract_venue`'s `reloadLiveShow` rule; a dry run never needs it |
| `publish_venue` | `VenueVersions.publish` with a note, as `POST /venues/{id}/publish` calls it, then `venueHold.refreshCurrent()` so a live show the publish leaves behind runs on rather than blacking out. Answers the version and the venue's shows, and says each takes it only when the operator updates it — **no tool runs Update**, whose review and decisions are the operator's. Admin only |
| `copy_to_project` | Show content between two projects, neither switched to (below) |
| `delete_cue_stacks` | Stacks by id — `deleteCueStackRows`, shared with the REST delete, so the entries, their anchors and the stack's set go as there and the cues stay; the answer counts `cuesLeftLoose`. A running stack is refused rather than deactivated under the operator; `dryRun` answers what would go; `projectId` |
| `delete_cues` | Cues by id — `deleteCuesChecked`, shared with `POST /cues/delete` (cue model plan D8): all or none, refused without `force` with `code: CUE_IN_USE` and every use (`uses`: entries, pads, slots, anchors, bindings); with it each cue goes with them, its holds let go where it is live. `dryRun` answers the uses; `projectId` |
| `copy_cues` | Cues of `fromProjectId` into the tool's project (`projectId`, else the current one), loose or at the end of `stackId` — `copyCuesFrom` and the cue copier, shared with `POST /cues/copy-from`, answering its `copied` and `unresolved` (below); `dryRun` |
| `list_fixture_types` | `FixtureTypeRegistry.allTypes` with a text filter: the vocabulary a patch list is matched against. A type whose length is set per install (a lightstrip) is marked `acceptsLength` with its `defaultLengthM`; a type hung with a lantern from the library (the generic dimmer) is marked `acceptsLantern`, and the tool schema lists the library's ids and each kind's default; a type with loadable settings (the Source Four Revolution) carries `loadable` — each loadable setting, what its slots `take` (`GEL` · `GOBO` · `GOBO_OR_GEL`) and each slot with its stock colour or gobo, the open hole left out. `properties: true` adds each type's settable properties (name, kind, category, a setting's option names) and a multi-head type's `cellProperties` — the names `create_look`'s values take |
| `get_patch` | Stage, regions, riggings, universes, every patch (address, `headNumber` where set, groups, rigging, placement, `lengthM` where set, and `alsoAt` — a paired dimmer's other lanterns, or the other sides of a lightstrip run, each with its own `lengthM`) and groups. Every placed fixture and `alsoAt` lantern also carries `world` — its x/y/z composed through its rigging's pose (`show/StageCoords.kt`'s `worldPosition`, the backend copy of the frontend's `worldPositionLighting`, so the model is told the position the Stage view draws), rounded to the millimetre and absent when x or y is unset; a moving fixture with a stored locate carries `locate`, its pan and tilt bytes per unit (`docs/fixtures-engineering.md` §"Stored locate"); a coloured fixture carries `colour`, and `colourOwner: "show"` where it is this show's recolour of a venue fixture (§"Fixture colours" there) |
| `patch_fixtures` | Bulk patch, **upsert by key**, `dryRun`; creates missing universes (ARTNET, no address) and groups; takes `headNumber` (the source console's head number — absent leaves it, `null` clears, unique in the project as the patch will stand, so a list that swaps two numbers goes through) and the same placement fields as `place_fixtures`, `alsoAt` and the lantern and focus included, and `media`, `locate` and `colour` the same way; a re-patch to another type drops stored media the new type cannot hold, on the fixture and each of its placements, and keeps only the stored locate of units the new type pans and tilts |
| `delete_groups` | Delete groups by name; the fixtures stay patched. A group that still has members is refused unless `force`, so a typo cannot take apart a group looks and cues address. Shares `deleteFixtureGroupRows` with the REST delete (memberships and busk rig tiles go with it) |
| `unpatch_fixtures` | Remove fixtures by key, and whole universes (`{universe, subnet?}`) with every patch on them, `dryRun` (`ai/PatchEditTools.kt`). Shares `deleteOwnPatch` / `deleteOwnUniverse` with the REST deletes, so memberships, busk rig tiles, extra placements, cue events and the universe's machine overrides go as there; the groups stay. Never refused for a reference: `namedBy` lists every cue, Look, palette, surface binding and script still naming a removed key (`ai/RigKeyReferences.kt`), whose rows stay and show as a missing fixture |
| `rename_fixtures` | Change keys, `{key, newKey}` rows, `dryRun`. `patch_fixtures` cannot, since the key is its identity. Unlike the REST `PUT`, which sweeps a renamed patch's busk cell tiles and leaves every other reference dangling, it **rewrites** the project's own references — cue rows, ad-hoc effects and layer targets, Look rows (their cell `elementKey` too) and effects, palette rows, surface bindings and busk cell tiles — a cell's `<key>.<cell>` by its prefix, so `foh-1` never matches `foh-10`, and a stored key that is itself a patched fixture's is never read as another's cell; scripts are only named (`scriptsNotRewritten`). Keys go through a temporary value first, so a swap passes the unique index. A new key may not hold a `.` |
| `edit_groups` | A group's membership (`members`, the whole ordered list; or `add` / `remove`), name (`newName`, references rewritten as `rename_fixtures` does, scripts named in `scriptsNotRewritten`) or a new group, `dryRun`. A venue fixture the show hides stays in a show group whose members are replaced. `colour` sets the group's colour (`null` clears it); on a show on a venue a row of only `colour` naming a venue group is the show's recolour of it |
| `set_stage` | Stage dimensions plus regions and riggings **upserted by name** (sent fields only), and removals. A field it does not know (`width` for `widthM`) is refused rather than skipped, and the answer carries the stage box as stored. `get_patch` re-reads the project row rather than trusting `ProjectManager.currentProject`, whose columns are the values loaded at the last switch — reading those made every stage-box correction look unsaved. The write fires `projectDetailsChanged` so the Stage view redraws the box |
| `set_scene` | The scene document (stage-view plan session 2, D2): **elements and viewpoints upserted by name**, with `removeElements` / `removeViewpoints` and a `dryRun`. An element row is `set_stage`'s shape — `name`, `kind`, `layer`, `x`/`y`/`z`, `yawDeg`, the three sizes, `finish {colour, pattern, emissive}`, `params` (replaced whole: what it may hold depends on the kind) and `hidden`; a platform's `params.region` names a region, stored as its uuid. A viewpoint row is `name`, `kind`, `eye`/`target` as `{x, y, z}`, `fovDeg`, and for a seat `seating` (omissible when the scene has one) and `seat` (`F6`). `template: "proscenium-hall"` with `templateParams` (hall, stage and opening sizes required; deck height, apron, rows, pitches, first-row distance and a balcony optional) expands server-side into named elements — `Hall`, `Stage house`, `Main stage`, `Proscenium`, `Stalls`, `Balcony` — before the explicit rows, and an explicit row of the same name lays its fields over the template's (`finish` one level down). A change that would leave a stored seat view without its seat is refused unless that view is changed or removed in the same call. In a show on a venue a row of exactly `{name, hidden}` naming a venue element is a **hide** (`ShowHides.setHiddenByUuid`, `HideKind.ELEMENT`) — `true` hides it for the show's run, `false` brings back one the show hides — checked first with `ShowHides.hideRefusal`, so `VENUE_HIDE_IN_USE`'s holders are listed with every other problem; any other row naming a venue element is `VENUE_OWNED`, with a hint naming the hide row, a stack set for a part-show move, and the venue's draft for a change for every show. Shares `validateStageElement` / `validateStageViewpoint` with the REST routes, so the two refuse the same things. **Models** (`docs/fixtures-engineering.md` §"Model objects"): an `OBJECT` with `shape: MODEL` and `params.parts` is how another Claude session makes a set piece no primitive draws — the schema's `MODEL_PARTS_NOTE` is the whole vocabulary — and a row that sends parts but leaves a size out is sized to the parts' bounds; `modelFrom` copies a model (its parts, and its size and finish unless the row states them) from a stored element or an earlier row of the same call |
| `get_scene` | The document back, in `set_scene`'s shape so a row can be corrected and resent: a platform's region by name, a seating's seat count and range, a seat view's `seating`, `seat` and the `seatedEye` it resolves to, a painted element's `paintImages` (each face's `{width, height, hasAlpha}`, or *missing on this machine*), a model's `modelSize` (its parts' bounds, before the fit), and the built-in viewpoints. The read-only extras (`seats`, `seatedEye`, `note`, `paintImages`, `owner`, `modelSize`) are ignored when a row is resent. It names the `project`; a show on a venue gets `venue: {id, name, version, installed, behind, hiddenElements?, note}` (`venueDto`, the Projects list's own read), a venue that its rows are its draft. Read-only |
| `upload_scene_image` | An image to paint on a cloth (scrim plan D11): `{mediaType: image/png \| image/jpeg, base64}` (a pasted `data:` URL's prefix is ignored) into the current project's scene-image store (or `projectId`'s), answering what the REST upload answers — `hash`, `width`, `height`, `hasAlpha`, `mediaType`, plus `projectId` and `scope: "project"`: the store is per project, and the note says so and that `copy_to_project` carries an element's images — through the same `SceneImageStore.store`, so it refuses what the route refuses (the type, a body that is not what it says, more than 8192 px a side read from the header first). Capped at **16 MB decoded**, below the route's 25: the base64 length is checked before anything is decoded, and a bigger image goes in through the element's sheet on the desk. Then `set_scene` names the hash in a `DRAPE`'s or `FLAT`'s `params.paint {front, back}`, with a drape's `fabric`, or in a `MODEL` part's `texture.image`; the schema's DRAPE, FLAT and MODEL lines say so. Stored data only, so **no remote-access gate**, as `set_scene` has none: an image does nothing until an element names it, and an unnamed one is pruned after a week. No fetch by URL (D11) |
| `render_view` | A PNG of a stage viewpoint, drawn by a signed-in desk window (§"`render_view`" below): `viewpoint` a camera (`orbit`, `eye`, `plan`, `front`, `side`), a saved view by name or uuid, or a seat `{seating, seat}`; `width` / `height` 160–1920 and at most 1920 × 1080 pixels in all (default 1280 × 720; one side alone is 16:9 to it, refused if that puts the other out of range); `source` `output` (default), `outputProgrammer`, `programmer` or `nextGo`; `workLights` `true` to lift the dark and check geometry (default `false`, the room as lit). Read-only. `ai/RenderViewTool.kt` |
| `place_fixtures` | Partial placement per key: rigging (by name, `null` detaches), offsets, yaw/pitch/roll (`rollDeg` stands a strip on end), beam, gel, kind, hidden, `lengthM` (only for an `acceptsLength` type — refused by name for any other, `null` clears), and `alsoAt` — a paired dimmer's other lanterns (label, rigging, offsets, yaw/pitch/roll, and a side's own `lengthM`), the whole list replacing the stored one, matched by position so a re-sent lantern keeps its identity; `[]` or `null` clears. The description steers a model to patch a paired circuit once rather than a second fixture at one address. For an `acceptsLantern` type, the row and each `alsoAt` entry also take the **lantern and its focus** (stage-view plan session 7): `lanternType` (a library id; on an `alsoAt` entry null means the fixture's own), `zoomDeg`, `lampRotationDeg`, `shutters` (four `{depth, angleDeg}`, top · bottom · left · right), `gateRotationDeg`, `iris`, `focusSoftness` — absent leaves each, `null` clears — checked by the same `resolvePatchFocus` the REST routes call, so an unknown id, a zoom outside the lantern's range, a kind that contradicts the lantern, or any of them on a DMX type is refused by name with nothing written; a lantern names the kind, so a model sending one need not send `kind`. `get_patch` reports all seven where set, per placement too. For a type with loadable settings, the row and each `alsoAt` entry also take **`media`** (fixture optics plan session 3) — `{slots: {<setting>: {<slot>: {gel} | {gobo} | {}}}}`, naming only the slots that differ from the stock, `{}` an empty slot; on the row absent leaves it and `null` clears, on an `alsoAt` entry it is the lantern's own, layered over the fixture's slot by slot — checked by the same `patchMediaRefusal` the REST routes call, every problem at once and nothing written: an unknown setting or slot, the open hole, an unknown gel (the schema lists the library's codes) or gobo, a gobo in a gel-only slot, any of it on a type with no loadable settings. `get_patch` reports each unit's `media` where set. The row also takes **`locate`** (venue-visit plan S8): the fixture's stored locate, `[{head?, pan, tilt, panFine?, tiltFine?}]` in DMX bytes, replacing the stored list (`null` or `[]` clears it), refused by name for a unit the type does not pan and tilt (`StoredLocate.typeProblems`) and for a fine byte on a unit with no fine channel; `get_patch` reports it. The row also takes **`colour`** (`docs/fixtures-engineering.md` §"Fixture colours"), one of the ten keys or `null`, refused as `COLOUR_UNKNOWN` otherwise. On a show on a venue, a venue fixture's row writes through the facets the show owns (§"Tools on a show on a venue"), and its `colour` is the show's recolour, needing no facet |
| `override_venue_fixtures` | A show's venue fixtures by key: each facet `SHOW` / `VENUE` / `KEEP_MINE`, an `address` override, `colour` (the show's recolour; `null` takes the venue's back) and `hidden`; `dryRun`. The fixture sheet's switches (§"Tools on a show on a venue") |
| `get_prompt_book` | Page count, cover pages, anchors (each its `entryId`, cue, number and stack) and notes; with no book, where to import one |
| `build_cue_stack` | A new stack (or `stackId` to append) of cues in running order: number, name, notes, fade, curve, follow, marker, look or palette layers, its own `values` (current project only), `scenery` (stage-view plan session 8: the elements each cue moves on GO, below), `events` (session 9: the one-shot triggers each cue fires after GO, below), and `at` — its place in the prompt book. A row naming an existing `cueId` **shares** that cue instead, taking only the entry's own `number`, `followSeconds` and `at` (the cue's content fields are refused by name, since they would change it everywhere). A stack-level `scenery` is the stack's *set*, replacing the one stored when given |
| `mark_up_prompt_book` | Cover pages, anchor upserts for existing entries (`entryId`, or `stackId` and `number`; a `cueId` alone is refused, naming no place), and notes (NOTE with tone, FREETEXT, STRIKETHROUGH) |

**Scenery** (stage-view plan session 8). The cue and Look authoring tools carry a `scenery` list —
`create_cue`, `create_look` and `build_cue_stack` (per cue, and the stack's set) — and `set_scenery`
replaces one cue's, stack's or Look's whole list (`cueId` | `stackId` | `lookId`; `[]` clears), so
"close the tabs on the blackout at the end of Act 1" is one call. Those three are show-running
tools (`AiTools`); `build_cue_stack` is a setup tool like the rest of this table. One item is `{element, visible?, open?, trimM?, transitionSeconds?}`: the element by its
`set_scene` **name** (or uuid), the states beside it, and on a cue its own clock (omitted, it moves
with the cue's fade). Each is checked against the element's kind exactly as the REST `PUT
…/scenery` routes check it (`parseToolSceneryList` beside `parseSceneryList`, `models/scenery.kt`):
`open` only on a drawn drape, `trimM` only on a flown piece, an element named twice refused, every
problem at once and nothing written. The schema text says scenery **tracks** — list only what
changes — and that a Look's scenery shows while it is live, above every cue; palettes carry none.

**Moving scenery now** (scenery-programmer plan D15). `move_scenery` is the live counterpart: `{element,
visible?, open?, trimM?, fadeSeconds?, release?}` holds one element's states in the **programmer's
scenery** (`state/ProgrammerScenery.kt`) — the same overlay the programmer's Scenery table and the Stage view write,
the resolver's top tier, above every cue, set and Look — or, with `release: true`, lets it go back to
what the show holds. The element is named as `set_scenery` names one, and checked against its kind by
the same `parseSceneryState`. It moves at the element's `travelS` scaled by how far it goes, or over
`fadeSeconds` when given; with neither it snaps. Blind stages it, Clear releases it, and it writes
nothing stored — `set_scenery` is how a cue or Look learns a move. Current project only.
`get_current_state`'s `programmer` section lists what is held as `scenery: [{element, elementUuid,
visible?, open?, trimM?}]`. **Recording it** (scenery-programmer plan session 3): `record_cue` takes
`scenery` (default true) and writes what the programmer holds into the cue as change rows — only a
piece the cue would not show anyway, a row the cue already has replaced — answering `sceneryWritten`,
`sceneryRemoved` and `sceneryAlreadyTracked`; `include_into_programmer` holds a cue's or Look's own
scenery rows in the programmer (`sceneryIncluded`), a cue row's transition kept, and
`update_from_programmer` with no targets writes them back (`sceneryWritten`). It runs a Look's
**effects** in the programmer as well (`fxSpawned`, each on its own target), and `update_from_programmer` writes an operator's edit back onto the effect it came from
(`fxWritten`) — `docs/lighting-composition-model.md` §"Record / Include / Update". Their descriptions say
so, and that neither the mask nor `targets` narrows scenery. **It needs no remote-access gate**, unlike arming, firing and fixture
commands: scenery is drawn and never output, so moving it reaches nothing physical — no channel, no
motor, nothing in a room the model cannot see.

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

Three decisions shape them:

- **One tool takes a file, and only an image.** The model reads the PDFs and photos in its own
  conversation and passes structured data. The prompt-book PDF comes in through the Prompt Book
  view's import (`/prompt-book`), which hashes it and counts its pages with pdf.js; the backend has
  no PDF parser and this did not add one. `get_prompt_book` and every `at` that meets a book-less
  project say so, naming the desk's public URL when it is known. The exception is
  `upload_scene_image` (scrim plan D11): a painted cloth's image may be one the model made or was
  given, with no desk import to go through, so the tool takes its bytes — base64, PNG or JPEG,
  16 MB at most — into the same content-addressed store the desk's sheet uploads to.
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
  `buskRigChanged` when `delete_groups` or `unpatch_fixtures` takes rig tiles off, `cueListChanged` /
  `cueStackListChanged`, `promptBookChanged`), so the desk's views follow along live. `place_fixtures` writes only the
  metadata columns `METADATA_ONLY_PUT_KEYS` names, so it skips the fixture reload the same way —
  and, like both REST placement paths, refreshes the one thing the running show caches from them,
  the gel (`Fixtures.setPatchMetadata`, which `GET /fixtures` reads).
  A rename (`rename_fixtures`, `edit_groups`' `newName`) also fires the list signal of every kind it
  rewrote, `cuesRecomposed` for the cues, and invalidates the surface bindings; on the current project
  it republishes each rewritten Look and palette (`republishForLookEdit` / `republishForPaletteEdit`)
  and each live cue (`republishCueIfLive`) after the reload, so what is on stage keeps its fixtures.
  The programmer's own slots and effects already running are runtime state keyed by the old key, and
  are left to the operator.
- **A malformed value is refused, never read as a clear.** An explicit `null` clears a field; a
  number field holding a string is a problem like any other. A cue layer naming a Look that is
  not in the project is refused too, although the shared cue write (`writeCueChildren`) drops
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
venue and set elements, and the saved viewpoints — a paragraph, not the document, which
`get_scene` answers. Omitted for a project with none of it.
Since stage-view plan session 6 a rigging of a standing kind (`STANDING_RIGGING_KINDS`: `LEDGE`,
`FLOOR_STAND`) says *units stand on it*, and a *Mounts:* line names each moving head whose
base orientation disagrees with how its rigging carries it — 180 on a ledge is drawn and aimed hung
under it, 0 on a bar stands on top of it, read through roll as the view reads it and silent for a
head on its side (`docs/fixtures-engineering.md` §"Aiming a head at a point").

### Another project than the live one

The setup tools that read or store data take an optional **`projectId`** — `get_patch`,
`patch_fixtures`, `delete_groups`, `unpatch_fixtures`, `rename_fixtures`, `edit_groups`, `set_stage`, `place_fixtures`,
`override_venue_fixtures`, `set_scene`, `get_scene`,
`upload_scene_image`, `get_prompt_book`, `build_cue_stack`, `delete_cue_stacks`, `delete_cues`,
`copy_cues` (its target), and the
show-running `set_scenery` — so a show can be prepared while another runs, as the REST routes'
`withProject` writes allow. `ai/ToolProject.kt`'s `State.toolProject` resolves it (absent is the
current project; a venue version, or an id that names nothing, answers *Project not found*) and
says whether the project is current, which each tool asks before touching the running show: the
patch writers reload the live rig (`DbFixtureLoader.loadFixtures`) and `place_fixtures` refreshes its
metadata cache **only for the current project** — loading another project's patch into the live
rig would replace the running show's fixtures. The broadcasts fire as REST fires them, whichever
project changed. Two stay current-only by the REST routes' own rule: the prompt book
(`mark_up_prompt_book` takes no `projectId`, and `build_cue_stack` refuses `at` in a project that is
not current — and a cue's `values`, which resolve against the loaded rig), and everything that drives output — looks, cues, the programmer, `render_view` — with
`describe_rig`, which reads the loaded rig; `get_patch` is its reader for another project.

**`copy_to_project`** (`VenueSetupTools.copyToProject`) copies scene elements by name, cue stacks by
id with their cues, and Looks by id from one project into another, in one transaction that a
`dryRun` rolls back. Copies are new records with fresh uuids, keeping their names; a name the target
already uses (an element in its scene, a hidden venue element included; a Look; a stack) is refused
with every clash at once. A stack's cues are copied through the one cue copier
(`docs/cues-engineering.md` §"Copying"), which `POST /cues/copy-from` shares: a scenery change finds
the target's element — by uuid between two projects on one venue, else one copied in the same call,
else by name — a cue event its patch by uuid on one venue, else by key and type, a script trigger its
script by name, a speed master by index; a cue layer's Look or palette, and one a `pal:` effect
parameter names, is the target's of that name (`looksLinked`) or is copied along. A platform's
region is found by name. What cannot resolve is listed in `unresolved` rather
than guessed: rows naming a fixture or group the target lacks are kept, reading as missing, as a
venue Update's dead references do; a scenery change, event or trigger that cannot resolve is left
out; prompt-book anchors are never copied. Painted elements bring their images into the target's
store (`SceneImageStore.hydrate`), on a real run only. The copier is hand-written rather than an
export/import subset because the importer only ever populates a whole project; when a portable
column is added to a cue, Look, palette, stack or element, add it to the copier (or, for a stack or
element, here) too.

**`copy_cues`** (`VenueSetupTools.copyCues`) is `POST /cues/copy-from` as a tool: `copyCuesFrom`
(`routes/cueCopyFrom.kt`) in one transaction a `dryRun` rolls back, so the copier, the placement
(each copy unnumbered at the end of `stackId`, else loose) and the answer — `copied
[{fromCueId, cueId, entryId?}]`, `unresolved [{problem, in}]`, the Looks and palettes copied and
linked — are the route's. Its target is the tool's `projectId`, current or not; the holds restamp and
the busk pads' announcement are the live show's, so they follow only a copy into the current project.

### Tools on a live venue

When the project a tool writes is a venue (venue plan session 2) — the live one, or the one a
project-scoped tool names (`McpProtocol.PROJECT_SCOPED_TOOLS`: its `projectId`, `copy_to_project`'s
`toProjectId`; any other tool's stray `projectId` is not read, so it cannot steer the guard) —
`McpProtocol.venueToolRefusal` holds the tools to the REST routes' rules, since a tool writes with
no route in between. `extract_venue` and `publish_venue` are an admin's wherever they point. On a
venue, `record_cue`, `record_look` and `update_from_programmer` are refused for everyone — Record and Update are off in a
venue (D11) — and an **operator's** stored write is refused, as `VENUE_ADMIN_ONLY` refuses it over
REST (D3): the tools in `VENUE_STORED_WRITE_TOOLS` (patching, the scene, the stage, Looks, cues,
palettes, park) and `aim_fixtures` when it is told to `saveAsPalette`. Tools that act on the rig as
it stands — apply, GO, aim, tempo, and the programmer's set, clear, Blind and palette apply — and the
reads pass. A venue version needs no rule: it is never
live, `list_projects` leaves versions out, and `switch_project` and every `projectId` answer one as
not found — but `attach_to_venue`, which refuses a version, as a venue, `NOT_A_STANDALONE_SHOW`.

### Tools on a show on a venue

When the live project is a show **on a venue** (venue plan session 3), its rig is its own rows plus
the pinned version's (`RigScope`), and the venue's records are the venue's. The setup tools refuse a
write to one, every problem at once and before anything is written, with `VENUE_OWNED`: the problem
line leads with the code, the result carries `"code": "VENUE_OWNED"`, and the message
(`VenueRigGuard.ownedProblem`, which names tools where the REST refusal names routes) says to edit
the venue to change it for every show; on a fixture it also names the changes a show can make —
hide it, make a facet its own, or recolour it — and the tool for them (`VENUE_FIXTURE_HINT`).
The writes it covers, by tool:

- `patch_fixtures` — a row whose key is a venue patch's (upsert by key would change the venue's
  row; hide it and patch your own under the key), and a `groups` entry naming a venue group (a
  venue's group never holds a show's fixture, D12);
- `place_fixtures` — a venue patch's `kind` or `stageHidden`, and any field of a facet the show
  does not own (below);
- `delete_groups` — a venue group;
- `unpatch_fixtures` — a venue patch (hide it with `override_venue_fixtures`) or universe;
- `rename_fixtures` — a venue patch, and a new key that is a venue patch's (`KEY_TAKEN_BY_VENUE`);
- `edit_groups` — a venue group (a row of only `colour` is its recolour, which goes ahead), and a new
  name that is one's; a show group may take venue fixtures;
- `set_stage` — the stage's size (the venue's), and a region or rigging set or removed by a venue
  record's name;
- `set_scene` — an element or viewpoint set or removed by a venue record's name, the
  `proscenium-hall` template's rows included — except a row of only `{name, hidden}`, which hides a
  venue element for the show's run or brings it back (the table above).

`unpatch_fixtures`, `rename_fixtures` and `edit_groups` on a venue's **draft** report and rewrite
the venue's own records only. The shows pinned to it keep naming the old key or group until each is
updated, when Update's review lists it under `deadReferences`.

**`override_venue_fixtures`** (`ai/VenueFixtureTools.kt`) is the fixture sheet's *Venue | This show*
switch, *Keep mine* and *Hide in this show*, per venue fixture by key — a hidden one included, so it
can come back. `facets: {FOCUS: SHOW}` makes a facet the show's **whole**, its first value the
venue's (`venueFacetOf`), so turning it on changes nothing drawn or sent and the override's base is
recorded for drift exactly as the sheet's switch records it; one already the show's keeps its value.
`VENUE` drops the override (also *Take venue's* on a drifted one), `KEEP_MINE` answers drift.
`address: {universe, subnet?, startChannel}` re-addresses the unit in this show (it implies
`ADDRESS: SHOW`), checked by the facet route's own `parseFacet`, an overlap answered in `warnings`
(P8). `hidden` goes through `ShowHides`, so `VENUE_HIDE_IN_USE` names what still holds it. A row
lands in the order the sheet allows — shown again, then its facets, then hidden — and the whole call
is one transaction, rolled back on any problem or for a `dryRun`, which still answers each fixture's
resulting `overrides`. An Address, Media or hide change reloads the live rig, as the REST routes do.

**`place_fixtures` then edits what the show owns.** A row naming a venue fixture is split by facet —
rigging and the offsets, angles and length are Position; the lantern, its focus fields,
`beamAngleDeg`, `lampWatts`, `lampKelvin` and `locate` are Focus; `gelCode` and `media` are Media — and each facet it touches must already
be the show's (else `VENUE_OWNED`, naming the `override_venue_fixtures` call that would make it so).
The sent fields are laid over the show's stored value, so a row changes only what it sends, and the
merged value goes through `parseFacet`, so a tool write is refused for exactly what the sheet's save
would be. A Focus write that sends no zoom drops a stored zoom its lantern cannot take — the
patch's and each lantern's — because the facet is checked whole and one stale zoom would otherwise
refuse every edit. `alsoAt` addresses the venue's lanterns in `get_patch`'s order, one entry each
(`{}` leaves one alone): a lantern's count and label are the venue's identity, so a show cannot add,
remove or relabel one, and each entry changes only the fields it sends — unlike an own fixture's
`alsoAt`, which replaces the list.

A show's own records are written as before, and a hidden venue record is not in the rig, so its key
or name is free. **`describe_rig` says whose each record is**: a *Venue* section naming the venue
and the pinned version and saying what `owner=venue` and `overrides=` mean, `owner=venue | show` on
every fixture and group line, `overrides=position,media (drifted)` on a venue fixture whose facets the
show overrides (a drifted or a dormant one marked), and *[venue's]* on the stage paragraph's regions and riggings.
A show **held** behind its venue on output (session 6) gets an *Output is held* paragraph under the Venue
section, and `get_patch` a `venue.held` sentence (session 7): the show composes and draws while every
controller sends zero on every unparked channel, so without it a model reads `apply_cue` as working while the rig stays dark.
Every field either tool shows is the effective one — a fixture's address, position, lantern and media
as the show's overrides leave them. `get_patch` carries `venue: {name, version, note}`, `owner` on
every fixture, universe, rigging and region, `overrides: [{facet, drifted?, dormant?}]` on an overridden
venue fixture, `venue.hiddenFixtures: [{key, name}]` for the venue fixtures the show hides, and
`get_scene` `owner` on every element and viewpoint; the stage size both answer is the venue's. A
standalone show's answers are unchanged. `VenueException` is caught in `SetupTools.executeTool` and
answered with its code, so a rule thrown below a tool reaches the model as the same refusal REST
gives.

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
| `RENDER_INVALID_REQUEST` | An unknown field, a side outside 160–1920 or more than 1920 × 1080 pixels in all, one side alone whose 16:9 partner would be out of range, an unknown source, a `workLights` that is not a boolean, a malformed viewpoint, or an ambiguous seat (several seatings, none named) |
| `RENDER_UNKNOWN_VIEWPOINT` | No camera, saved name or uuid matches; the message lists the cameras and the saved views |
| `RENDER_UNKNOWN_SEAT` | No such seating, no such seat (the message gives the rows and seat range), or a saved seat view whose seat has gone |
| `RENDER_NO_WINDOW` | No eligible window is open |
| `RENDER_BUSY` | Another render held the desk for a whole timeout's worth of waiting |
| `RENDER_TIMEOUT` | The window did not answer within 30 s; the message names it |
| `RENDER_WINDOW_CLOSED` | The window's socket closed before it answered |
| `RENDER_FAILED` | The window answered with a reason — its WebGL context was lost, a read failed, it gave up waiting for the scene, or its frame was refused as too large or not a PNG |

**Work lights** (stage-view menu plan D9): an optional `workLights` boolean, **off by default** so a
capture means the room as lit — which is why a scene built with nothing lit comes back nearly
black, and why the tool's description says to turn them on to check the geometry. On, the window
draws with the Stage view's *Work lights · On* (`frontend/docs/stage-vis-engineering.md` §"Light
lands through one surface shader"): the dark lifted, every pool at its own exposure. Anything but
`true`, `false` or absent is a named problem (`workLights must be true or false`). It rides
`stageRender.request` beside `source`, is echoed in the result's text beside `source`, and is the
**request's alone** — the drawing window's own work lights are never read and never written.

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
- **The request** goes out as `stageRender.request` (`docs/websocket-engineering.md`) — the
  viewpoint, the size, the source, `workLights` (always sent, a boolean) and the timeout — down the
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

Tests: `src/test/kotlin/.../mcp/McpSetupToolsTest.kt` and `McpSceneToolsTest.kt`; the venue split (extract, a show on the venue, `copy_to_project`, `copy_cues`, a hide, `delete_cue_stacks`, `publish_venue`, `projectId` on a project that is not current, and the guards) in `McpVenueSplitToolsTest.kt`; a cue's places (`create_cue` into a stack, a cue shared and twice in one stack addressed by number and by entry, remove, move, `delete_cues`, a shared `build_cue_stack` row, `describe_rig`'s `in:`) in `McpLookCueEditingTest.kt`; `render_view` in `McpRenderViewTest.kt` (the vocabulary, the named errors, and the whole round trip over a real signed-in socket) and `state/StageRenderServiceTest.kt`.

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
