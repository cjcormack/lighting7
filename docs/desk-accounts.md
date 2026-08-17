# Desk accounts

Who can sign in to this desk, what the two roles may do, and how to get back in when
nobody can. Users belong to the **machine**, not to a project: they are never exported,
imported, cloned, or cloud-synced. Two desks mean two user lists, by requirement.

Implementation lives in `auth/` (`AuthService`, `AuthGate`, `Passwords`, `SessionTokens`,
`ResetUrls`, `BreakGlass`), `routes/auth.kt`, `routes/users.kt`, and the three
machine-local tables `users`, `user_sessions`, `password_reset_tokens`. Device-login codes
(see "QR sign-in on a phone") are the one credential here with no table — they live in
`AuthService`'s memory, because their lifetime is shorter than a restart. The design
rationale — and the decisions not to re-litigate — is in
[`docs/plans/completed/multi-user-auth-plan.md`](plans/completed/multi-user-auth-plan.md).

## Roles

| | ADMIN | OPERATOR |
| --- | --- | --- |
| All lighting control, all project content | yes | yes |
| User accounts, password resets | yes | no |
| Install settings (`PUT /api/rest/install`), cloud sync, GitHub OAuth | yes | no |

There are no per-project or per-fixture permissions. Enforcement is central:
`ADMIN_ONLY_PREFIXES` and the per-project sync path regex in `auth/AuthGate.kt`, plus
per-handler `call.requireAdmin()` where only some methods are admin-only. The frontend
hides what an operator can't use (the Users tab, the Sync nav entries), but that is a
courtesy — the gate is the enforcement.

WebSocket messages are **not** role-scoped in v1: any authenticated user can send any
socket message. Deliberate, not an oversight — see `FU-AUTH-WS-PER-MESSAGE` in
`docs/plans/followups.md`.

That is about **inbound** messages. Outbound, one family *is* filtered per recipient: the
`ownAccountChanged` frame goes only to sockets belonging to the account that changed (see
"Account edits reach other clients"). That is a fan-out decision, not an authorisation one — the
frames carry nothing an operator may not know.

## A desk with no accounts is unauthenticated

While zero users exist the API gate passes everything and `GET /api/rest/auth/status`
reports `setupRequired: true`. That is what makes a fresh install usable, and the SPA
forces the setup screen so the window is short. The server logs a warning at boot:

```
no users configured — the API is unauthenticated until an admin is created
```

If you see that on a desk you thought was locked, it isn't: check the `users` table.

## Sessions

Login mints a 32-byte random token, returns it in the httpOnly `lighting7_session`
cookie, and stores only its SHA-256 hex. Sessions last 30 days and slide forward on
activity; they never expire mid-show. `AuthService` keeps them in memory and writes the
refresh through at most hourly, because the SQLite pool has exactly one connection.

Revocation is immediate on both transports. A revoked token hash goes out on
`AuthService.revocations`, which every open WebSocket collects — so disabling a user, or
resetting their password, closes their socket with close code **4401** rather than waiting
for their next REST call. The frontend treats 4401 as "your session is gone" and drops to
the login screen instead of reconnecting.

Sessions die when: the user logs out, they change their own password (all *other*
sessions), an admin sets their password or disables or deletes them (all sessions), or a
QR reset is redeemed (all sessions).

## Account edits reach other clients

`revocations` has a sibling. **`AuthService.userChanges`** carries the userId of any account row
that was written — created, renamed, re-roled, enabled, disabled, deleted, re-passworded — and
`plugins/MachineSocket.kt` collects it per connection and turns it into two frames:

- **`userListChanged`** to every socket, which the frontend bridges to the `UserList` / `User`
  tags. Without it a rename reaches only the browser that made it, and a phone signed in by QR or
  a second desk keeps the old name until it happens to refetch.
- **`ownAccountChanged`** to the affected user's own sockets *only*, which invalidates `Auth`. This
  is what makes an admin's rename or re-role land on the device it was about — including
  re-filtering that user's sidebar. Disabling and deleting need no such frame: they revoke
  sessions, so `revocations` closes those sockets 4401 instead.

The install row (`PUT /api/rest/install`) rides the same band via `State.machineEventsFlow` and
`installChanged`. Both flows are collected **before** the boot warm-up gate in `plugins/Sockets.kt`,
alongside `revocations` and for the same reason: none of it reads `state.show`, a change during
warm-up would be lost rather than delayed, and a desk whose show failed to start is exactly when
you still want account administration working.

Two invariants there, both load-bearing and neither enforced by the type system:

- **The frames carry no user data.** They are payload-free `data object`s. Sockets are open to
  operators while `/api/rest/users` is admin-only, so a payload would leak precisely what that
  gate exists to withhold.
- **`Auth` is never invalidated by a broadcast.** Only the targeted frame may do it. A broadcast
  would have every connected client re-read `auth/status` on every admin edit.

Emission is from the funnels inside `AuthService`, not from the route handlers, which is what
covers `BreakGlass` and the startup admin reset for free. The rule is *every administrative write
to a users row emits* — not every write to the table. `mintSession` is the one exception: it
writes `lastLoginAtMs` and stays silent, because `login` and the device-login redemption are
unauthenticated endpoints and wiring one to a fan-out across every connected client would make it
an amplification surface. So `lastLoginAtMs` in the users list is allowed to be stale, and the
Devices panel misses a *new* sign-in for the same reason (`FU-AUTH-SESSION-LIST-STALENESS`).

The reset-token history is stale for an unrelated reason worth keeping straight: minting or
cancelling a token is not a users-row write at all, so `userChanges` never fires for it. Half of
that gap looks fixable from this flow and isn't — see `FU-AUTH-RESET-TOKEN-STALENESS` before
touching it.

Each session records **how it was created** — `user_sessions.created_via`, `PASSWORD` or `QR`
— and `GET /auth/sessions` reports it, so the devices list in the user menu's Profile sheet
can name a QR sign-in. A QR sign-in is the one entry there that nobody typed a password for, which makes
it the one worth recognising if you don't recognise the device.

## QR password reset

For a user who can't sign in. An admin opens **Install Settings → Users → (the user) →
Reset with a QR code…**; the backend mints a single-use token with a **15 minute** TTL and
returns a LAN-reachable `http://<host>:<port>/reset/<token>` URL, which the sheet renders
as a QR code. The user scans it on their phone, sets a password themselves — the admin
never sees it — and every session that account had is revoked. The admin's sheet flips to
a success state within ~2 s of the redemption.

Only one token is live per account: minting a new one cancels the outstanding one. Redemption
also **re-enables** a disabled account, since resetting someone's password means handing the
account back.

**Closing the sheet does not cancel the link.** It used to, on the reasoning that a QR on
screen for ten seconds shouldn't stay redeemable for fifteen minutes — but that made the flow
brittle exactly where it is needed: the admin couldn't close the sheet to go and do something
else, and an operator slow to reach their phone lost the link for no reason. Visibility
replaced cancellation. The user's detail sheet lists **every** reset link that account has had
— minted at, by whom, and its status (PENDING / USED / EXPIRED / CANCELLED) — with a Cancel
button on the live one. So "there is a redeemable link right now" is a thing you can see and
revoke deliberately, rather than a thing you had to trust a sheet to clean up.

That history is durable: `AuthService` ages reset-token rows out after **30 days** rather than
sweeping spent ones at startup, so "was a link ever minted for this account?" survives a
restart. Contrast the device-login QR below, which made the opposite call for the opposite
risk profile.

The URL is built from the request's own `Host` header — an address the admin's browser
just proved reachable. If that host is loopback (browsing the desk's own screen, or
through the Vite dev proxy) it falls back to the mDNS name and then site-local IPv4
addresses, because a QR encoding `localhost` would resolve to the *phone*. Every
alternative is listed under the code as selectable text for a phone that can't reach the
first one. Both devices must be on the same network; there is no cloud round-trip.

`GET`/`POST /api/rest/auth/reset/{token}` are the only auth-exempt *reset* endpoints (the
device-login pair below is the other exemption). The admin-side minting and polling endpoints
live under `/api/rest/users`, which is admin-only.

## QR sign-in on a phone

For signing **your own** phone or tablet in without typing a password on a touch keyboard.
Any role: this is not an administrative act, which is why its endpoints live under
`/api/rest/auth/` rather than the admin-only `/api/rest/users`. **User menu → Profile… →
Sign-in tab** shows a QR — arriving on that tab is what mints the code, and leaving cancels it,
so there is no button either way. The phone opens a LAN URL, sees whose account it is, taps
*Sign in as X*, and gets a normal 30-day session. The desk's tab flips to a success state
within ~2 s and offers to jump to Devices, where the phone is now a row.

Reuses the reset flow's delivery mechanism wholesale — `auth/ResetUrls.kt` picks the address,
`buildDeviceLoginUrls` only swaps the path — and the decisions **not** to re-litigate:

- **The QR never carries a session token.** A photographed QR would then be a photographed
  30-day cookie. It carries a single-use device-login code that the phone exchanges for a real
  session, and the exchange burns it.
- **Two-minute TTL, and cancelled the moment the code leaves the screen** — leaving the Sign-in
  tab, closing the Profile sheet, or the desk being signed out, which are one mechanism
  front-end side. The opposite of the reset link's behaviour, deliberately, because the risk is
  opposite: a reset token can only ever set a password, whereas this one *is* a way in.
  Cancel-on-leave is the control that matters; the TTL is the backstop. It follows that nothing
  is minted merely by opening Profile — that lands on the Profile tab, and the code exists only
  once somebody has navigated to the tab that is *for* it. Two minutes rather than one because
  the phone has to cold-start a browser and pull the SPA bundle over venue Wi-Fi before it can
  even ask whose code this is.
- **The codes are in memory, not in a table.** Their lifetime is shorter than any restart, so a
  row would buy nothing and cost a write on the single shared connection from a public path —
  plus a hand-rolled delete in `deleteUser`, since `PRAGMA foreign_keys` is OFF and an orphan
  row would fault a public endpoint. Losing them on restart is correct, not a compromise. It
  also means no query exists that could redeem a reset token as a login: different type,
  different store, no shared index.
- **No confirmation step.** Same-LAN plus a physically present crew was judged enough, and the
  peer check enforces the LAN half — loopback, site-local or link-local, refused as 404 so a
  probe from outside learns nothing. (Link-local is in there because a 169.254.x peer is a
  device on this segment whose DHCP lease failed, not an outsider.) The check reads
  `origin.remoteAddress` rather than `remoteHost`, because the latter can be a resolved
  *hostname* and resolving one would mean a DNS lookup on a public endpoint — and a phone
  refused because that lookup failed. **Installing Ktor's `ForwardedHeaders` would silently
  defeat both that check and the IP throttle beside it**, since a client would then choose its
  own apparent address. What replaces confirmation is *detect and undo*: the desk names the device
  that took the code and offers "that wasn't me — sign out every other device".
- **Redemption needs a tap, and the lookup `GET` does not consume the code.** A QR scanner that
  prefetches, a link preview, or a StrictMode double-render would otherwise burn a single-use
  code before anyone saw the screen — and the person holding the phone should get to see whose
  account they are about to be holding.
- **A disabled account is refused, not re-enabled** — the opposite of a reset redemption. The
  guard lives in `AuthService.mintSession`, so every present and future caller inherits it.

Six interlocks retire a live code, and they are the point of the feature's tests: disabling the
account, deleting it, an admin setting its password, the user changing their own password,
plain **logout**, and **"sign out everywhere else"** — that last being the button someone
presses when they think they have been compromised, so a QR surviving it would defeat the one
action taken to shut an intruder out. Logout is a weaker signal than revoke-all but gets the
same treatment, because a QR left on a screen you have walked away from is a fresh session for
whoever photographs it. Reset links are deliberately *not* cancelled on logout: one can only
ever set a password, and whoever holds it is by definition already locked out.

`GET`/`POST /api/rest/auth/device/{token}` are the public pair. The trailing slash in that
exemption is load-bearing: without it the prefix would also match `/api/rest/auth/device-logins`,
which mints codes, and `AuthGateTest` pins that.

## Break-glass: nobody can sign in

Physical access to the desk is the credential — it already implies access to the SQLite
file. Two equivalent triggers:

1. Drop an empty file named **`RESET-ADMIN`** in the app data directory and restart.
2. Start the server with **`-Dlighting7.resetAdmin=true`**.

On the next boot the server creates — or re-enables and re-passwords — the user `admin`
with a fresh 16-character random password, logs it at WARN, and writes it to
**`RESET-ADMIN-PASSWORD.txt`** next to the trigger, which it then deletes. Sign in as
`admin`, fix the account situation, and change that password from the user menu's Profile sheet.

Notes:

- The password file is written **before** the account is touched. An installed desk
  launched by double-click has no console, so that file is the only durable copy; a failed
  write leaves the account untouched and the trigger armed for a retry.
- The **JVM flag repeats on every boot** it is present for. Remove it after recovering —
  a WARN says so.
- A failed break-glass logs at ERROR and lets the server boot rather than boot-looping the
  recovery mechanism.
- The app data directory is the same one holding `lighting7.db` (see `State.appDataDir`).

## Guards worth knowing about

The routes refuse to create a desk that only break-glass can repair:

- The **last enabled admin** cannot be demoted, disabled, or deleted (409 `LAST_ADMIN`).

The self guards are a different kind of thing — not about recoverability, but about footguns
with no legitimate use. All answer 409 `SELF_TARGET`. On **your own** account you cannot:

- **disable or delete it** — you'd lock yourself out of the desk you're standing at;
- **change its role** — a self-demotion costs you your own administration surfaces mid-session
  and needs the *other* admin to undo. It used to succeed whenever a second admin existed;
- **mint a password-reset QR for it** — that would put a link which re-passwords an admin
  account on the desk's own screen, for anyone passing to photograph.

Changing your own display name or password is fine — that's what the user menu's Profile sheet
is for. Both routes it uses, `PUT /api/rest/auth/profile` and `PUT /api/rest/auth/password`, are
authenticated but **any role**: they match neither `ADMIN_ONLY_PREFIXES` nor the exempt list, so
an operator can maintain their own account without an admin. The rename deliberately lives here
rather than as a self-exception inside admin-only `PUT /users/{id}` — `isAdminOnly` is a plain
prefix list, and a carve-out inside one of its prefixes would mean the list no longer describes
its own subtree.

The two differ in consequence, which is why they are separate controls in the sheet: a rename
revokes nothing, while a password change revokes every *other* session and retires any live
device-login code. A rename also needs no current password, so the sheet must never gate one
behind the other.

Note the self guards are checked **before** the last-admin one, so a last admin demoting
themselves sees `SELF_TARGET` rather than `LAST_ADMIN`. Over HTTP that makes every arm of the
last-admin guard unreachable — a caller is always an enabled admin, so a *different* user can
never be the last one — which is why `UsersRoutesTest` exercises it against the service
directly. Same outcome either way: the desk keeps an admin who can sign in.

Other guards:

- Usernames are stored lowercase and unique; the unique index is the race guard, and a
  duplicate answers 409.
- Passwords must be at least 8 characters and at most 72 UTF-8 **bytes** — bcrypt silently
  truncates beyond that, so a longer password would validate against any input sharing its
  first 72 bytes.

## Cloud sync and support copies

All three tables are `MachineLocal` in `SyncCoverageTest`: nothing here reaches a git
remote, an export, or a clone. Session and reset tokens are stored hashed, so a SQLite
file copied for diagnostics — or sitting next to a sync working tree — contains no
redeemable credential. Device-login codes aren't in the database at all.

## Not in v1

HTTPS and `Secure` cookies (desks run plain LAN HTTP; the QR URL scheme would need
revisiting), CSRF tokens, account lockout beyond the login throttle, 2FA, password complexity
beyond the length floor, per-user attribution of edits, an audit log, and per-message
WebSocket authorisation.

The CSRF answer is `SameSite=Lax` **plus every state-changing endpoint requiring a JSON body**,
and the second half is load-bearing rather than incidental: a cross-origin auto-submitting form
POST needs no preflight, so a public POST that accepted a form encoding would let any page
drive a victim's browser through it. That is why `POST /auth/device/{token}` takes a body it
barely reads, and why `plugins/ErrorHandling.kt` maps `ContentTransformationException` to 400 —
so refusing a form post reads as "no" rather than as a server fault.
