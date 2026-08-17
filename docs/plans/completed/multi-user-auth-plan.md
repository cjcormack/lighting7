# Multi-User Authentication — Implementation Plan

> **Document status: COMPLETE (2026-08-17).** All three sessions landed across both
> repos — Session 1 `lighting7` `662a600`, Session 2 `lighting-react` `5bf2845`,
> Session 3 `lighting7` `7c7fb3c` + `lighting-react` `680b484`. Every surface in the
> tables below shipped, including the Session 3 items Session 1 deferred (live WS
> revocation via `AuthService.revocations`). The desk is no longer bootstrap-open: an
> admin account exists, and `RESET-ADMIN` is the way back in.
>
> **Durable engineering reference is [`docs/desk-accounts.md`](../../desk-accounts.md)**
> (roles and where they're enforced, sessions and revocation, the reset flow,
> break-glass, the guards). The narrative below is preserved as a session-by-session
> record and as the rationale for the decisions in §Decisions — read it for *why*,
> read `desk-accounts.md` for *what the code does now*. The frontend shape is
> summarised in `lighting-react/CLAUDE.md` → "Desk accounts".
>
> **Deviations from the plan as written**, all deliberate and all live:
> - **`AuthGate` wraps `BootGate`**, not the reverse §2.2 specifies. `auth/status` is
>   warm-up-exempt, so identity resolves while the show is still compiling FX; a
>   signed-out operator gets the login form immediately instead of a progress bar they
>   can't act on, and lands on the boot overlay after signing in.
> - **Nav role filtering** was added rather than reused: `adminOnly` on `NavItem` plus
>   `filterNavItems(items, isViewingActiveProject, isAdmin)` — §3.6 assumed an existing
>   filter to extend, and there wasn't one.
> - **`MIN_PASSWORD_LENGTH`** was consolidated into `lighting-react/src/lib/passwordPolicy.ts`
>   because five surfaces ask for a password and a form disagreeing with the server
>   would read as a bug in that form.
> - **`/install/users` is a tab** inside `InstallSettings`' `:tab` route, not a route of
>   its own (same as `sync` and `diagnostics`).
> - **Session 1's login throttle** grew an IP-keyed use for reset redemption (§3.4) and a
>   bounded map; the last-admin guard moved *inside* the mutating transaction and counts
>   against the DB, because as a route-level check-then-act two admins demoting each
>   other from two tabs could both pass it.
>
> **One behaviour is shipped but never seen on real hardware**, and it is the only
> outstanding work from this plan: the two-device QR scan — tracked as
> `FU-MANUAL-AUTH-QR-SCAN` in [followups.md](../followups.md). Everything else has
> automated coverage (`AuthServiceTest`, `AuthRoutesTest`, `AuthGateTest`,
> `UsersRoutesTest`, `PasswordResetRoutesTest`, `ResetPasswordPage.test.tsx`).
> Ten further follow-ups — five **Ready** review cuts and five trigger-gated
> deferrals from §"Deliberately out of scope" — live in
> [followups.md](../followups.md) → "Desk accounts".

Desk-local user accounts, password login, roles, and an admin-driven QR password
reset. No cloud identity, no email, no GitHub involvement: users belong to the
**machine**, not to the project, and never leave it.

## Touched repos

- Backend: `/Users/chris/Development/Personal/lighting7` (Kotlin / Ktor 3.5.1, SQLite + Exposed).
- Frontend: `/Users/chris/Development/Personal/lighting-react` (React 19 + RTK Query + React Router v8).

The backend owns identity, sessions, and enforcement. The frontend is a gate plus
three screens plus one admin page. All three new tables are **machine-local** —
they go in `ALL_TABLES`, get a `MachineLocal` disposition in `SyncCoverageTest`,
and are never touched by `ProjectExporter` / `ProjectImporter` / `ProjectCloner`.

**Restart points.** The backend hot-reloads changed method bodies only. New
tables, new routes, new classes and new fields on existing classes all require a
full restart, which may interrupt a live rig — coordinate with the user.
Restarts are needed at the **start of Session 1** and the **start of Session 3**.
Session 2 is frontend-only and needs no backend restart.

---

## What we're adding (data, end-to-end)

### Tables (all `MachineLocal`, all new)

| Table | File | Columns | Notes |
| --- | --- | --- | --- |
| `users` | `models/users.kt` | `uuid` (javaUUID, autoGenerate), `username` varchar(64) **unique, stored lowercase**, `display_name` varchar(100), `role` varchar(16) (`ADMIN`\|`OPERATOR`), `password_hash` varchar(60), `disabled` bool default false, `created_at_ms` long, `password_changed_at_ms` long, `last_login_at_ms` long? | `enum class UserRole { ADMIN, OPERATOR }` in the same file; stored as varchar to match existing enum-as-varchar convention. |
| `user_sessions` | `models/userSessions.kt` | `token_hash` varchar(64) **unique** (SHA-256 hex), `user_id` reference(`DaoUsers`, onDelete CASCADE), `created_at_ms`, `last_seen_at_ms`, `expires_at_ms`, `revoked_at_ms` long?, `user_agent` varchar(200)?, `client_ip` varchar(45)? | Server-side session table backing the httpOnly cookie. |
| `password_reset_tokens` | `models/passwordResetTokens.kt` (Session 3) | `token_hash` varchar(64) **unique**, `user_id` ref(CASCADE), `created_by_user_id` ref(`DaoUsers`) nullable, `created_at_ms`, `expires_at_ms`, `used_at_ms` long?, `cancelled_at_ms` long? | Single-use, 15 min TTL. |

New-table checklist (per `docs/sync-engineering.md` "How to add a new table"):
add to `ALL_TABLES` in `models/Schema.kt`; record
`Disposition.MachineLocal(...)` in
`src/test/kotlin/.../sync/SyncCoverageTest.kt` (the build fails until you do);
no exporter/importer wiring. `SchemaUtils.createMissingTablesAndColumns` in
`State.initDatabase()` creates them on first boot — no hand-written migration.

### End-to-end surfaces

| Surface | Behaviour |
| --- | --- |
| `GET /api/rest/auth/status` | `{ setupRequired, authenticated, user? }`. Auth-exempt. Drives the whole frontend gate. |
| Cookie `lighting7_session` | httpOnly, `path=/`, `SameSite=Lax`, `secure=false` (LAN HTTP), `maxAge` 30 days. Value = 32 random bytes, base64url. Server stores **SHA-256** of it. |
| REST gate | `intercept(ApplicationCallPipeline.Plugins)` inside `route("/api/rest")` (a sibling of the existing warm-up gate), plus the same gate on the `/kotlin-compiler-server` subtree. |
| WebSocket gate | `/api` upgrade is accepted, then immediately `close(CloseReason(4401, "unauthenticated"))` if no valid session — a browser can read a 4401 close code but cannot read a 401 on the upgrade response. |
| Roles | `ADMIN` — user management, reset tokens, install/cloud-sync/OAuth settings. `OPERATOR` — everything else (all lighting control, all project content). |
| QR reset | Admin mints a single-use token → backend returns a LAN-reachable `http://host:8413/reset/<token>` URL → admin's sheet renders it as a QR (`react-qr-code`, frontend-only) → locked-out user opens it on their phone, sets a new password → all that user's sessions are revoked. |
| Break-glass | Drop a file named `RESET-ADMIN` in the app data dir, restart. Server creates/re-enables user `admin` with a random password, prints it to the console and writes `RESET-ADMIN-PASSWORD.txt`, deletes the trigger. `-Dlighting7.resetAdmin=true` does the same. |

---

## Decisions

Recorded here so no session has to re-litigate them.

1. **Roles: `ADMIN` + `OPERATOR` only.** No per-project or per-fixture
   permissions. Operators can do everything to show content; admins additionally
   manage users, reset tokens, and install-level settings.
2. **httpOnly cookie sessions with a server-side session table**, not JWT.
   Revocation is the whole point (disable a user mid-show, invalidate on reset),
   and a desk has one server — there is nothing to federate. Same-origin means
   the cookie rides both REST and the WS upgrade with zero token plumbing.
3. **Session tokens stored hashed (SHA-256).** One `MessageDigest` per request is
   free next to bcrypt, and the SQLite file gets copied around for diagnostics
   and sits next to cloud-sync working trees; a plaintext live session token in a
   support export is a real leak. Same reasoning for reset tokens.
4. **Sessions are long-lived and sliding, never absolute-expiring mid-show.**
   30-day expiry, refreshed on activity (`expires_at_ms` and `last_seen_at_ms`
   written at most once per hour per session — with `maximumPoolSize=1`, a DB
   write per request is not acceptable). Explicit logout is the normal end.
5. **In-memory session cache, DB as durable store.** `AuthService` keeps a
   `ConcurrentHashMap<tokenHash, SessionRecord>` loaded at startup and
   write-through on mint/revoke, so the per-request check is a map lookup, not a
   query against the single-connection pool.
6. **BCrypt via `at.favre.lib:bcrypt:0.10.2`, cost 12** (~250 ms — a natural
   login throttle). Passwords: min 8 chars; reject > 72 UTF-8 bytes explicitly,
   because bcrypt silently truncates there.
7. **Bootstrap-open mode: while zero users exist, the gate passes everything**
   and `auth/status` reports `setupRequired: true`. This is what makes Session 1
   independently shippable and leaves the ~40 existing route integration tests
   green without edits (they never create users). A fresh desk logs a startup
   warning: `no users configured — the API is unauthenticated until an admin is
   created`. The SPA forces the setup screen, so the window is short.
8. **Static SPA files are never gated.** The gate lives inside
   `route("/api/rest")` and on `/kotlin-compiler-server`; `staticFiles`/
   `staticResources` at `/` stay open so the login and reset pages can load
   (the SPA fallback to `index.html` is also what serves `/reset/<token>` on
   the phone).
9. **`/kotlin-compiler-server/*` is gated too.** It compiles arbitrary Kotlin on
   a LAN-reachable port; leaving it open would make authentication decorative.
10. **WS: authentication only, no per-message role checks in v1.** Every socket
    message is available to any authenticated user. Explicitly a decision, not an
    oversight — role-scoped WS commands are a follow-up.
11. **QR rendering is frontend-only** (`react-qr-code`, pure SVG, no canvas, no
    new backend dep). The backend returns the URL; the browser draws it. Avoids
    pulling ZXing into the fat jar for one sheet.
12. **The QR URL is built from the request's `Host` header**, so it points at
    whatever address the admin's browser already proved reachable (mDNS name or
    IP). If that host is loopback, fall back to the mDNS name and the site-local
    IPv4 addresses; the response carries `alternateUrls` and the sheet shows the
    URL as selectable text under the QR.
13. **CSRF: `SameSite=Lax` plus JSON-only endpoints, no CSRF token in v1.** Lax
    withholds the cookie from cross-site POSTs, and a JSON `Content-Type` forces
    a preflight that no CORS policy answers (the app is same-origin).
14. **Live WS revocation ships in Session 3**, via a revocation flow the socket
    subscribes to. Session 1 has no way to revoke another user's session anyway
    (only self-logout exists), so the socket-at-upgrade check is sufficient there.
15. **Break-glass is a file drop in the app data dir**, not a CLI flag alone.
    Windows `.msi` installs are launched by double-click; a file drop works with
    Explorer/Finder. Physical access to the desk already means access to the
    SQLite file, so this adds no meaningful exposure.

---

## Phase / Session 1 — Backend foundation (lighting7)

**Restart required** (new tables, new classes, new routes, new `SocketScope`
fields). Ship this first; behaviour is unchanged until a user exists
(bootstrap-open).

All backend routes needed by Session 2 are included here, so Session 2 needs no
restart.

### 1.1 Dependency

`build.gradle.kts` — add `implementation("at.favre.lib:bcrypt:0.10.2")` (one
transitive: `at.favre.lib:bytes`), with a comment naming the cost factor and
where hashing lives.

### 1.2 Tables

- `models/users.kt` — `DaoUsers` / `DaoUser` + `enum class UserRole`. Mirror the
  doc-comment style of `models/installs.kt`, stating machine-local and
  never-synced.
- `models/userSessions.kt` — `DaoUserSessions` / `DaoUserSession`.
- `models/Schema.kt` — append `DaoUsers, DaoUserSessions` to `ALL_TABLES`.
- `src/test/kotlin/.../sync/SyncCoverageTest.kt` — add
  `DaoUsers to Disposition.MachineLocal("desk-local user accounts; never leave this machine")`
  and `DaoUserSessions to Disposition.MachineLocal("live login sessions for this desk")`.

### 1.3 Auth core

New package `src/main/kotlin/uk/me/cormack/lighting7/auth/`:

- `Passwords.kt` — `hash(plain)` (BCrypt cost 12), `verify(plain, hash)`,
  `validatePolicy(plain)` throwing `PasswordPolicyException` for < 8 chars or
  > 72 UTF-8 bytes, and `dummyVerify()` used on unknown-username logins so
  response time doesn't leak account existence.
- `SessionTokens.kt` — `newToken()` (32 bytes `SecureRandom`, base64url no
  padding), `sha256Hex(token)`. Follow the `SecureRandom`/`Base64` usage already
  in `routes/oauth.kt` and `sync/auth/FileCredentialStore.kt`.
- `AuthExceptions.kt` — `AuthenticationException`, `AuthorizationException`,
  `PasswordPolicyException`.
- `AuthService.kt` — the only place that touches the two tables. Constructed in
  `State` with the `Database`. Members:
  - `hasAnyUser: Boolean` (volatile flag, refreshed on create/delete)
  - `createUser(username, displayName, role, password): UserRecord`
  - `createFirstAdmin(...)` — the setup path; the zero-user check and the insert
    happen **inside one `transaction`**, and the unique index on `username` is
    the ultimate race guard (an `ExposedSQLException` unique violation already
    maps to 409 in `plugins/ErrorHandling.kt`)
  - `login(username, password, userAgent, ip): Pair<UserRecord, String /*raw token*/>`
  - `lookupSession(rawToken): AuthenticatedUser?` — map lookup on
    `sha256Hex(rawToken)`, expiry check, throttled `last_seen_at_ms` /
    `expires_at_ms` refresh (skip the write if the last one was < 1 h ago)
  - `logout(rawToken)`, `revokeAllSessionsFor(userId, exceptTokenHash: String?)`
  - `changeOwnPassword(userId, current, new)` — verifies current, rehashes,
    bumps `password_changed_at_ms`, revokes all *other* sessions
  - `pruneExpiredSessions()` — called once at construction
  - a small in-memory login throttle: per-username failure counter, 1 s delay
    after 5 failures in 5 minutes, cleared on success
  - `data class AuthenticatedUser(userId, uuid, username, displayName, role, sessionTokenHash)`
- `AuthGate.kt` —
  - `val AuthenticatedUserKey = AttributeKey<AuthenticatedUser>("authUser")` and
    `val ApplicationCall.authenticatedUser` / `requireAdmin()` helpers that throw
    `AuthorizationException`
  - `fun Route.installAuthGate(state: State)` — the
    `intercept(ApplicationCallPipeline.Plugins)` body: bootstrap-open
    short-circuit → exempt-path short-circuit → cookie read → `lookupSession` →
    `respond(401, ErrorResponse(...)); finish()` on failure → otherwise stash the
    user in `call.attributes` and, for admin-only prefixes, check the role
  - `private fun isAuthExempt(path: String)`, mirroring `isWarmupExempt` in
    `routes/router.kt`: `/api/rest/status`, `/api/rest/auth/status`,
    `/api/rest/auth/login`, `/api/rest/auth/setup`, and (Session 3)
    `/api/rest/auth/reset/`
  - `private val ADMIN_ONLY_PREFIXES = listOf("/api/rest/users", "/api/rest/cloud-sync/", "/api/rest/oauth/")`
    — plus per-route `requireAdmin()` where method matters (`PUT /api/rest/install`
    is admin-only, `GET` is not)
- `BreakGlass.kt` — `runBreakGlassIfRequested(authService, dataDir)`: if
  `<appDataDir>/RESET-ADMIN` exists or `-Dlighting7.resetAdmin=true`, create or
  re-enable `admin` with a 16-char random password, log it at WARN, write
  `<appDataDir>/RESET-ADMIN-PASSWORD.txt`, delete the trigger file.

### 1.4 Wiring

- `state/State.kt` — after `initDatabase()`, `val authService = AuthService(database)`;
  then `runBreakGlassIfRequested(...)`; log the "no users configured" warning
  when `!authService.hasAnyUser`.
- `routes/router.kt` — inside `route("/api/rest")`, add `installAuthGate(state)`
  immediately **after** the existing warm-up intercept (router.kt:35; a mid-boot
  503 still wins for exempt paths, and an unauthenticated caller can't probe
  readiness of gated routes); register `routeApiRestAuth(state)`. Apply the same
  gate to the `/kotlin-compiler-server` route block.
- `plugins/ErrorHandling.kt` — new `exception<>` arms before the `Throwable`
  catch-all: `AuthenticationException` → 401, `AuthorizationException` → 403,
  `PasswordPolicyException` → 400, all as `ErrorResponse`.
- `plugins/SocketScope.kt` — add `val user: AuthenticatedUser?` constructor
  parameter (nullable so bootstrap-open sockets still work) and expose
  `sessionTokenHash`.
- `plugins/Sockets.kt` — at the top of `webSocket("/api")` (Sockets.kt:51),
  before the boot-progress block: read `call.request.cookies["lighting7_session"]`,
  resolve it, and on failure (with `hasAnyUser == true`)
  `close(CloseReason(4401, "unauthenticated")); return@webSocket`. Pass the
  resolved user into `SocketScope`.

### 1.5 Routes — `routes/auth.kt`

Ktor Resources, matching the nested `data class ... (val parent: X)` convention
used in `routes/projectCueStacks.kt`. DTOs `@Serializable` in the same file,
like `routes/install.kt`. Cookie written with the `Cookie(...)` builder pattern
from `routes/oauth.kt`: `httpOnly = true, secure = false, path = "/",
extensions = mapOf("SameSite" to "Lax"), maxAge = 30 days`.

| Method + resource | Auth | Body → response |
| --- | --- | --- |
| `GET /auth/status` | exempt | → `AuthStatusDto(setupRequired, authenticated, user: AuthUserDto?)` |
| `POST /auth/setup` | exempt, 409 once a user exists | `SetupRequest(username, displayName, password)` → sets cookie, `AuthStatusDto` |
| `POST /auth/login` | exempt | `LoginRequest(username, password)` → sets cookie, `AuthStatusDto`; 401 with the same "Incorrect username or password" for wrong-user and wrong-password, 403 when `disabled` |
| `POST /auth/logout` | any | → 204, clears cookie (`maxAge = 0`) and revokes the session row |
| `PUT /auth/password` | any | `ChangePasswordRequest(currentPassword, newPassword)` → 204; revokes the caller's *other* sessions, keeps the current one |
| `GET /auth/sessions` | any | → `List<SessionDto>(id, createdAtMs, lastSeenAtMs, userAgent, current)` |
| `DELETE /auth/sessions` | any | → 204; revokes all sessions except the caller's |

### 1.6 Tests

- `testsupport/AuthTestSupport.kt` — `seedUser(...)`, `loginCookieHeader(...)`,
  a cookie-carrying `HttpClient` builder.
- `auth/AuthServiceTest.kt` — hash/verify round-trip, policy rejections, session
  mint → lookup → expiry → revoke, `changeOwnPassword` revoking siblings but not
  self, throttle behaviour.
- `routes/AuthRoutesTest.kt` — setup-then-second-setup 409; login sets a cookie;
  wrong password 401 with the same message as unknown user; logout clears;
  `PUT /auth/password` invalidates the other session.
- `routes/AuthGateTest.kt` — zero users → arbitrary route answers 200
  (bootstrap-open); with a user → 401 without a cookie, 200 with one; exempt
  paths answer without a cookie in both modes; `/kotlin-compiler-server/*` is
  gated; `PUT /install` is 403 for an operator.
- WS test (existing `testsupport` WS helpers): unauthenticated upgrade closes
  with 4401 once a user exists.
- `SyncCoverageTest` passes with the two new dispositions.

### 1.7 Verification (Session 1)

- `./gradlew test` green — all pre-existing route tests unchanged and passing
  (bootstrap-open is what makes that true).
- Manual, after a coordinated restart: `curl localhost:8413/api/rest/auth/status`
  → `{"setupRequired":true,"authenticated":false}`; the existing UI still works
  end to end (no users yet). Optionally exercise setup via curl and confirm an
  uncookied `GET /api/rest/projects` then 401s. Test break-glass by dropping
  `RESET-ADMIN` and restarting.
- **Note:** once setup is run, the desk is API-protected but the UI is
  auth-unaware until Session 2 — so simply don't run setup until Session 2 lands.

---

## Phase / Session 2 — Frontend authentication (lighting-react)

**No backend restart.** Every endpoint this session calls shipped in Session 1.
Vite proxies `/api` to `:8413`, so the cookie is same-origin in dev and prod alike.

### 2.1 Store plumbing

- `src/store/restApi.ts` — set `credentials: 'same-origin'` explicitly on
  `fetchBaseQuery` (the fetch default, but state it); add tag types `'Auth'`,
  `'UserList'`, `'User'`, `'ResetToken'`; wrap the base query so any 401
  dispatches `restApi.util.invalidateTags(['Auth'])`. Invalidating `Auth` is the
  whole logout mechanism: `AuthGate` re-queries `auth/status`, sees
  `authenticated: false`, and swaps in the login screen.
- `src/store/auth.ts` — types (`AuthStatus`, `AuthUser`, `UserRole`) and
  endpoints: `authStatus` (query, `providesTags: ['Auth']`), `login`, `setup`,
  `logout`, `changePassword`, `sessions`, `revokeOtherSessions`, invalidating
  `Auth` where relevant.
- `src/store/errorToastMiddleware.ts` — add `login`, `setup`, `changePassword`
  to `SILENT_ENDPOINTS` (they render inline `<Alert variant="destructive">`),
  with the file's required justification comment (its test asserts the endpoint
  names exist on `restApi`).

### 2.2 The gate

- `src/AuthGate.tsx` — modelled directly on `src/BootGate.tsx`: full-screen
  centred `Card` while resolving; `<SetupScreen />` when `setupRequired`,
  `<LoginScreen />` when `!authenticated`, `{children}` otherwise. Accepts a
  `bypass?: boolean` prop (see 2.4).
- `src/BootGate.tsx` — gains the same `bypass?: boolean` prop (render children
  immediately when set); it currently takes only `children`.
- `src/App.tsx` — wrap as
  `<BootGate bypass={publicPath}><AuthGate bypass={publicPath}><RouterProvider …/></AuthGate></BootGate>`.
  BootGate outside: the login form doesn't need the show, but the app behind it
  does, and the boot overlay should keep working for an already-logged-in
  operator watching a restart. `auth/status` is warm-up-exempt, so login is
  possible while the show is still booting; the user lands on the boot overlay
  after logging in — the right sequence.

### 2.3 Screens

- `src/components/auth/LoginScreen.tsx` — Card + `Label`/`Input`/`Button`,
  `await login({...}).unwrap()`, inline `Alert` on failure. Autofocus username;
  remember last username via `usePersistentState('lastUsername')` as a
  convenience.
- `src/components/auth/SetupScreen.tsx` — "Set up this desk": username, display
  name, password + confirm, copy noting the account is stored on this machine
  only and never synced. Calls `setup`.
- `src/components/auth/UserMenu.tsx` — dropdown in the `Layout.tsx` header (next
  to `<ThemeToggle />`, using the existing `@radix-ui/react-dropdown-menu` and
  `@radix-ui/react-avatar` deps): display name + role badge; items *Change
  password…* (opens `ChangePasswordSheet`), *Manage users* (admin only; target
  `/install/users` arrives in Session 3 — link to `/install` until then or land
  the item in Session 3), *Log out*.
- `src/components/auth/ChangePasswordSheet.tsx` — standard sheet per CLAUDE.md
  (`SheetContent className="flex flex-col sm:max-w-md"`, `SheetBody`,
  `SheetFooter`), three fields, `unwrap()` + `toast.success`.

### 2.4 Public-path bypass (prepares Session 3)

`src/App.tsx` computes
`const publicPath = window.location.pathname.startsWith('/reset/')` once at
module scope and threads it into both gates. One line, no consumer yet — Session
3's phone page then needs only a new sibling route, no gate-structure changes.

### 2.5 WebSocket

- `src/api/internalApi.ts` — in `newWs.onclose`, if `ev.code === 4401`, do
  **not** `scheduleReconnect()`; notify subscribers as normal.
- `src/api/lightingApi.ts` — on a 4401 close,
  `store.dispatch(restApi.util.invalidateTags(['Auth']))` so the gate flips to
  the login screen instead of reconnect-looping. After a successful login,
  `AuthGate` triggers a reconnect on mount of its children (guard so it
  early-returns unless the socket is actually closed).

### 2.6 Tests

- `src/store/auth.test.ts` — 401 from any endpoint invalidates `Auth`.
- `src/AuthGate.test.tsx` — renders setup / login / children for the three
  status shapes (existing Vitest + Testing Library patterns).

### 2.7 Verification (Session 2)

- `npm run check` (build + vitest + eslint `--max-warnings 0`).
- Manual E2E against a running backend:
  1. Fresh users table → load `/` → setup screen → create admin → land in the
     app, WS connected, channels move.
  2. Reload → still logged in (cookie survives).
  3. Log out → login screen (verify cookie removal via devtools Application tab
     — httpOnly, so `document.cookie` never shows it).
  4. Wrong password → inline error, no toast spam.
  5. Change own password in a second browser → the first browser's next request
     401s and drops to the login screen; the changing browser stays logged in.
  6. Restart the backend mid-session → boot overlay, then the app returns
     without re-login.

---

## Phase / Session 3 — User management + QR password reset

**Restart required** (new table, new routes). Backend and frontend ship together
here; the QR flow is meaningless without both halves.

### 3.1 Reset-token table

- `models/passwordResetTokens.kt` — as tabulated above; append to
  `models/Schema.kt` `ALL_TABLES`; `SyncCoverageTest` disposition
  `MachineLocal("short-lived local password reset tokens")`.

### 3.2 Backend — users CRUD, `routes/users.kt`

Nested Resources (`/users`, `/users/{id}`, `/users/{id}/password`,
`/users/{id}/reset-tokens`, `/users/{id}/reset-tokens/{tokenId}`).

| Method + resource | Auth | Notes |
| --- | --- | --- |
| `GET /users` | admin | `List<UserDto>` (no hashes, ever) |
| `POST /users` | admin | `NewUser(username, displayName, role, password)` |
| `GET /users/{id}` | admin | |
| `PUT /users/{id}` | admin | `UpdateUser(displayName?, role?, disabled?)`. **Guards:** cannot demote or disable the last enabled admin; cannot disable yourself. On `disabled = true`, `revokeAllSessionsFor(id)` |
| `DELETE /users/{id}` | admin | Same last-admin guard; cannot delete yourself; cascades sessions |
| `PUT /users/{id}/password` | admin | Direct set, for when the user is standing next to you. Revokes all their sessions |
| `POST /users/{id}/reset-tokens` | admin | Mints the token — see 3.3 |
| `GET /users/{id}/reset-tokens/{tokenId}` | admin | `{ status: PENDING\|USED\|EXPIRED\|CANCELLED, expiresAtMs }` — the poll endpoint |
| `DELETE /users/{id}/reset-tokens/{tokenId}` | admin | Cancel (sets `cancelled_at_ms`) — fired when the admin closes the sheet |

`routes/router.kt` — register `routeApiRestUsers(state)`. `routes/install.kt` —
`call.requireAdmin()` at the top of the `put<InstallResource>` handler.

### 3.3 Backend — token minting and the URL, `auth/ResetUrls.kt`

`POST /users/{id}/reset-tokens` →
`ResetTokenResponse(id, url, alternateUrls, expiresAtMs, username, displayName)`.

URL construction, in priority order:
1. `call.request.origin.scheme` + the `Host` header verbatim — an address the
   admin's browser already proved reachable, so a phone on the same LAN almost
   certainly can too;
2. if that host is loopback, fall back to
   `http://<MdnsService.deriveServiceName()>.local:<port>`;
3. `alternateUrls` always carries the mDNS name plus every site-local IPv4.
   `MdnsService.pickLanAddresses()` is currently `private`
   (state/MdnsService.kt:137) — widen to `internal` and reuse rather than
   duplicating the virtual-interface filter.

Path: `/reset/<rawToken>` — an SPA route, served by the unauthenticated static
handler's `index.html` fallback. Token: 16 random bytes → base64url (~22 chars),
stored SHA-256, TTL **15 minutes**, single use. Minting a new token for a user
cancels that user's outstanding tokens.

### 3.4 Backend — redemption, in `routes/auth.kt` (auth-exempt)

`@Resource("/auth/reset/{token}")`:

- `GET` → `{ username, displayName, expiresAtMs }`; **410 Gone** for
  used/expired/cancelled, **404** for unknown. Lookup by hash (index probe, not
  a scan).
- `POST` `{ newPassword }` → 204. One transaction: re-check token live, apply
  password policy, rehash, bump `password_changed_at_ms`, mark `used_at_ms`,
  `revokeAllSessionsFor(userId)`.
- Add `/api/rest/auth/reset/` to `isAuthExempt`. Do **not** exempt the
  admin-side `/users/**` token endpoints.
- Rate limit: reuse the `AuthService` throttle keyed on client IP for
  `POST /auth/reset/*` (5 attempts / 5 min) — cheap insurance on an open
  endpoint.

### 3.5 Backend — live session revocation (deferred from Session 1)

`AuthService` gains `val revocations: SharedFlow<String /*tokenHash*/>`, emitted
from `revokeAllSessionsFor` and `logout`. In `plugins/Sockets.kt`, after the
scope is built:
`scope.subscribe(state.authService.revocations) { hash -> if (hash == scope.user?.sessionTokenHash) close(CloseReason(4401, "session revoked")) }`.
~15 lines; it's what makes "disable this user" take effect on a socket that is
already streaming.

### 3.6 Frontend — user administration

- `package.json` — add `react-qr-code` (pure SVG, no canvas, ~10 kB).
- `src/store/users.ts` — `users`, `user`, `createUser`, `updateUser`,
  `deleteUser`, `setUserPassword`, `createResetToken`, `resetTokenStatus`,
  `cancelResetToken`; tags `UserList` / `User` / `ResetToken`.
- `src/store/passwordReset.ts` — the two public endpoints (`resetTokenInfo`,
  `redeemResetToken`).
- `src/routes/InstallSettings.tsx` — add `"users"` to `TABS` +
  `<TabsTrigger value="users">Users</TabsTrigger>` rendering `<UsersTab />`.
  Render the trigger only for admins; `UsersTab` shows a "requires an
  administrator account" empty state otherwise (backend is the real enforcement).
- `src/components/users/UsersTab.tsx` — list (display name, username, role
  badge, disabled state, last login) + *Add user*.
- `src/components/users/CreateUserSheet.tsx`, `UserDetailSheet.tsx` — standard
  sheets per CLAUDE.md; detail sheet holds Edit (display name, role, disabled),
  *Reset password…* (opens the QR sheet), *Set password directly*, Delete with a
  confirmation `Dialog`.
- `src/components/users/ResetQrSheet.tsx` — modelled on
  `src/components/cloudSync/DeviceFlowModal.tsx`: on open, `createResetToken`;
  render `<QRCode value={url} />` + the URL as selectable text with copy button,
  alternates disclosure, countdown to `expiresAtMs`, 2 s poll on
  `resetTokenStatus` (the `DeviceFlowModal.schedulePoll` idiom). `USED` →
  success ("<name> set a new password"); `EXPIRED` → offer a new token; closing
  the sheet fires `cancelResetToken`.
- `src/routes/ResetPasswordPage.tsx` — the phone page. Registered in `App.tsx`
  as a **sibling of `Layout`** (no sidebar/header, mobile-first single column):
  `{ path: "/reset/:token", element: <ResetPasswordPage /> }`. Bypasses both
  gates via the 2.4 `publicPath` flag. States: loading → "Set a new password for
  **<name>**" (password + confirm) → success ("You can now log in on the desk"),
  with distinct copy for expired/used/unknown tokens.
- `src/navigation.ts` — `users` item, `group: "install"`,
  `parent: "install-settings"`, path `/install/users`; filter it (and the
  existing install-settings children like `sync`) out for operators so Cmd+K
  doesn't offer a 403.
- `src/components/auth/UserMenu.tsx` — point *Manage users* at `/install/users`.

### 3.7 Break-glass documentation

`docs/desk-accounts.md` (or a CLAUDE.md operations section): the `RESET-ADMIN`
file drop, where the generated password lands, that users are machine-local and
excluded from cloud sync, and that a desk with zero users is deliberately
unauthenticated.

### 3.8 Verification (Session 3)

- `./gradlew test` — new `routes/UsersRoutesTest.kt` (CRUD, last-admin guard,
  operator 403, disable revokes sessions) and `routes/PasswordResetRoutesTest.kt`
  (mint → GET info → redeem → sessions gone → second redeem 410; expired 410;
  cancel works; redeem answers without a cookie).
- `npm run check`.
- Manual E2E, two devices:
  1. Admin creates an operator; log in as the operator in a private window;
     Users tab and sync nav entries absent, lighting control works.
  2. As admin: operator's detail sheet → *Reset password…* → scan the QR with a
     phone on the same Wi-Fi → phone shows the operator's name → set a new
     password → admin's sheet flips to "used" within ~2 s.
  3. The operator's old session is dead: next click drops to login, WS closes
     with 4401.
  4. Log in with the phone-set password.
  5. Disable the operator while their socket is open → socket closes immediately
     (3.5).
  6. Browse as admin via `localhost` and mint a token → the QR URL carries the
     mDNS/LAN address, not `localhost`.

---

## Sequencing / risk

- **Why three sessions, not two.** The natural seams are "the backend can
  authenticate" and "the UI can authenticate", and they are where the risk
  changes character. Session 1 changes nothing observable (bootstrap-open), so
  it can be restarted into a live-ish rig with confidence. Session 2 is the
  first time the desk actually locks — it deserves its own live-fire test, is
  frontend-only (no restart), and is trivially revertable. Merging 2 and 3 would
  put "the desk can now lock you out" and "here is how you get back in" behind a
  single large restart-gated change; split, the QR reset lands on a proven login
  path.
- **Why Session 2's backend lives in Session 1.** Change-password and
  session-listing routes are ~60 lines; carrying them early makes Session 2
  restart-free.
- **Highest risk:** the auth gate catching an exempt path (the boot status poll,
  or the static SPA) and bricking the UI. `AuthGateTest` covers each exempt path
  explicitly; the break-glass file drop is the recovery of last resort.
- **Second:** the ~40 existing route integration tests. Bootstrap-open keeps
  them untouched — do not "improve" it into a config flag without budgeting for
  edits across every test file.

## Deliberately out of scope / follow-ups

Each of the first five was promoted to a slugged, trigger-gated item in
[followups.md](../followups.md) → "Desk accounts" when this plan closed out
(2026-08-17), rather than left here to fall off the backlog. The last two are
standing decisions, not backlog: they are recorded in
[`docs/desk-accounts.md`](../../desk-accounts.md) → "Not in v1".

- **Per-user attribution of edits** (`created_by` on cues/presets/patches) —
  `FU-AUTH-ATTRIBUTION`. The `users.uuid` exists so it can be referenced later,
  but attribution touches portable show content, so it is a sync-format question
  (`formatVersion`), not an auth question.
- **Audit log** (logins, resets, user changes) — `FU-AUTH-AUDIT-LOG`. A
  `user_audit` machine-local table is the natural shape.
- **Per-message WS authorisation** (Decision 10) — `FU-AUTH-WS-PER-MESSAGE`.
- **Operator lockdown of specific controls** (patch editing, project delete,
  script editing) — `FU-AUTH-OPERATOR-LOCKDOWN`. If needed,
  `ADMIN_ONLY_PREFIXES` in `auth/AuthGate.kt` is where it changes.
- **HTTPS / secure cookies** — `FU-AUTH-TLS-COOKIES`. Desks run plain HTTP on the
  LAN; `secure = false` is deliberate. A TLS story would revisit the cookie flag,
  the QR URL scheme, and the CSRF answer that `SameSite=Lax` currently carries.
- **CSRF tokens**, **account lockout** beyond the login throttle, **password
  complexity** beyond a length floor, **2FA** — wrong-shaped for a familiar,
  physically-present crew.
- **Multi-desk / shared identity.** Users are per-machine by requirement; two
  desks mean two user lists.
