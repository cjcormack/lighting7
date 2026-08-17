# Desk accounts

Who can sign in to this desk, what the two roles may do, and how to get back in when
nobody can. Users belong to the **machine**, not to a project: they are never exported,
imported, cloned, or cloud-synced. Two desks mean two user lists, by requirement.

Implementation lives in `auth/` (`AuthService`, `AuthGate`, `Passwords`, `SessionTokens`,
`ResetUrls`, `BreakGlass`), `routes/auth.kt`, `routes/users.kt`, and the three
machine-local tables `users`, `user_sessions`, `password_reset_tokens`. The design
rationale — and the decisions not to re-litigate — is in
[`docs/plans/multi-user-auth-plan.md`](plans/multi-user-auth-plan.md).

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
socket message. Deliberate, not an oversight.

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

## QR password reset

For a user who can't sign in. An admin opens **Install Settings → Users → (the user) →
Reset with a QR code…**; the backend mints a single-use token with a **15 minute** TTL and
returns a LAN-reachable `http://<host>:<port>/reset/<token>` URL, which the sheet renders
as a QR code. The user scans it on their phone, sets a password themselves — the admin
never sees it — and every session that account had is revoked. The admin's sheet flips to
a success state within ~2 s of the redemption.

Only one token is live per account: minting a new one cancels the outstanding one, and
closing the sheet cancels the token on screen. Redemption also **re-enables** a disabled
account, since resetting someone's password means handing the account back.

The URL is built from the request's own `Host` header — an address the admin's browser
just proved reachable. If that host is loopback (browsing the desk's own screen, or
through the Vite dev proxy) it falls back to the mDNS name and then site-local IPv4
addresses, because a QR encoding `localhost` would resolve to the *phone*. Every
alternative is listed under the code as selectable text for a phone that can't reach the
first one. Both devices must be on the same network; there is no cloud round-trip.

`GET`/`POST /api/rest/auth/reset/{token}` are the only auth-exempt reset endpoints. The
admin-side minting and polling endpoints live under `/api/rest/users`, which is admin-only.

## Break-glass: nobody can sign in

Physical access to the desk is the credential — it already implies access to the SQLite
file. Two equivalent triggers:

1. Drop an empty file named **`RESET-ADMIN`** in the app data directory and restart.
2. Start the server with **`-Dlighting7.resetAdmin=true`**.

On the next boot the server creates — or re-enables and re-passwords — the user `admin`
with a fresh 16-character random password, logs it at WARN, and writes it to
**`RESET-ADMIN-PASSWORD.txt`** next to the trigger, which it then deletes. Sign in as
`admin`, fix the account situation, and change that password from the user menu.

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
- You cannot disable or delete **your own** account (409 `SELF_TARGET`). Changing your own
  display name or password is fine — that's what the user menu is for.
- Usernames are stored lowercase and unique; the unique index is the race guard, and a
  duplicate answers 409.
- Passwords must be at least 8 characters and at most 72 UTF-8 **bytes** — bcrypt silently
  truncates beyond that, so a longer password would validate against any input sharing its
  first 72 bytes.

## Cloud sync and support copies

All three tables are `MachineLocal` in `SyncCoverageTest`: nothing here reaches a git
remote, an export, or a clone. Session and reset tokens are stored hashed, so a SQLite
file copied for diagnostics — or sitting next to a sync working tree — contains no
redeemable credential.

## Not in v1

HTTPS and `Secure` cookies (desks run plain LAN HTTP; the QR URL scheme would need
revisiting), CSRF tokens (`SameSite=Lax` plus JSON-only endpoints), account lockout beyond
the login throttle, 2FA, password complexity beyond the length floor, per-user attribution
of edits, an audit log, and per-message WebSocket authorisation.
