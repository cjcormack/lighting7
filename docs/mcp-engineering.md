# MCP server

The desk is an [MCP](https://modelcontextprotocol.io) server, so Claude — on a phone through a
claude.ai connector, in Claude Code, or in the desktop app — can drive the lights with the same
tools the in-app AI chat has. The decisions behind the shape below are recorded in the project's
`decisions/mcp-auth-and-scripts.md`; this doc is how it is built.

## Two listeners

The MCP server is **not** on port 8413. `mcp/McpServerModule.kt` starts a second, separate Netty
server (`startMcpServer`, called from `Application.module` and stopped on `ApplicationStopping`)
that serves exactly:

| Path | What |
|------|------|
| `GET /` | A one-line "this is a lighting desk's MCP endpoint" |
| `GET /.well-known/oauth-protected-resource[/mcp]` | RFC 9728 metadata |
| `GET /.well-known/oauth-authorization-server[/mcp]` | RFC 8414 metadata |
| `POST /oauth/register` | RFC 7591 dynamic client registration |
| `GET`/`POST /oauth/authorize` | The sign-in-and-consent page |
| `POST /oauth/token` | Code exchange and refresh |
| `POST /oauth/revoke` | RFC 7009 |
| `POST /mcp` | The MCP endpoint (streamable HTTP, JSON responses) |

Nothing else. The UI, REST, the WebSocket, scripts and Swagger stay on 8413, which is LAN-only.
That split is the security boundary: a tunnel that points at the MCP port cannot reach the desk's
API even if every check below it were wrong. `McpServerTest` pins that `/api/rest/…` is a 404 on
this listener.

It binds `127.0.0.1:8414` by default, because the expected way in is a tunnel running on the
desk machine. If it fails to bind, it logs and the desk carries on without it.

### Config (`local.conf`, machine-local)

```hocon
mcp {
    enabled = true
    host = "127.0.0.1"
    port = 8414
    # The URL clients reach the listener at, through the tunnel. Goes into the OAuth metadata
    # and the 401 challenge, so it must be exactly what the client typed (no trailing /mcp).
    publicUrl = "https://desk.example.ts.net"
    # Extra OAuth redirect URIs to accept, comma separated. claude.ai / claude.com and http
    # loopback (Claude Code, the desktop app) are already allowed.
    extraRedirectUris = ""
}
```

`publicUrl` is per machine, never synced: it names this desk's tunnel. With it unset the desk
advertises `http://localhost:8414`, which is right for Claude Code on the desk machine and wrong
for anything else.

## Exposing it

A claude.ai connector (which is how iPhone and iPad reach it) is called from Anthropic's servers,
so the listener must be reachable from the internet over HTTPS. Either of:

- **Tailscale Funnel**: `tailscale funnel --bg 8414`, then `publicUrl` is the
  `https://<machine>.<tailnet>.ts.net` it prints.
- **Cloudflare Tunnel**: `cloudflared tunnel --url http://localhost:8414` (or a named tunnel with
  a stable hostname), then `publicUrl` is that hostname.

Then add it:

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
  loopback on any port, and `mcp.extraRedirectUris`. Clients are capped at 200; ones older than
  a day with no grant are pruned. An unknown client or unlisted redirect gets an error **page**,
  never a redirect, so the endpoint can't be used to bounce a browser anywhere.
- **PKCE S256 only**; `plain` and a missing challenge are refused. The RFC 8707 `resource`
  parameter, when sent, must name this server (`<publicUrl>/mcp` or `<publicUrl>`).
- **Sign-in** is the desk's own: `AuthService.verifyCredentials`, the same throttle, dummy
  verify and disabled check the login uses. On top of it, this page only has a **lockout** — ten
  failures in fifteen minutes and it answers 429 until the window passes. It is on this page
  alone because this page is the one on the internet; the LAN login keeps its throttle only, so a
  stranger hammering the tunnel cannot lock the crew out of the desk.
- **The consent page** is server-rendered HTML with no script, `X-Frame-Options: DENY`,
  `frame-ancestors 'none'`, `no-store` and `no-referrer`. It posts a form, which is the one place
  the desk takes a form body. That does not reopen the CSRF hole `docs/desk-accounts.md`
  describes: this listener has no cookies, the POST carries the password itself, and the
  `request_id` it names is single-use and bound to an allowlisted redirect.
- **Codes** live one minute, in memory, hashed, and are single-use: a code presented a second
  time is refused.
- **Tokens** are opaque: a one-hour access token and a thirty-day refresh token, stored as SHA-256
  hashes in `mcp_oauth_grants` and cached in memory. Refresh **rotates** both. Presenting the
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
batches, and 405s `GET`/`DELETE /mcp`. The `Origin` header, when present, must be the public
URL's origin, claude.ai, claude.com or loopback (DNS-rebinding protection).

### Tools

`describe_rig` plus `AiTools.mcpTools`, which is every chat tool **except
`run_lighting_script`**. A script runs arbitrary Kotlin inside the desk's JVM, so reaching it
through a tunnel would make a leaked token a shell on the desk machine; every other tool is a
bounded operation on the show.

`describe_rig` returns what the chat puts in its system prompt each turn (`ai/RigBriefing.kt`,
shared with `AiService`): fixtures, groups, the effect library, what is running, speed masters,
Looks, colour templates, cues and stacks. The chat gets that for free; an MCP client gets
nothing it does not ask for, so the `instructions` sent at `initialize` tell the model to call it
first, along with the composition rules (`RigBriefing.keyConcepts(scriptTool = false)`).

Tools act on the desk's **current** project, as the chat's do. Before the show is warm a call
answers `isError` with "still starting". `describe_rig` and `get_current_state` carry
`readOnlyHint`.

## Tests

`src/test/kotlin/.../mcp/McpServerTest.kt` mounts `mcpModule` directly and runs the whole
connect: discovery, registration, sign-in, code exchange, `initialize`, `tools/list`, a real tool
call reaching the master clock, refresh rotation and reuse, the redirect allowlist, the lockout,
deny, a bootstrap desk, revocation on disable and password change, and the Connected apps REST
pair. Nothing is tested against the real claude.ai.
