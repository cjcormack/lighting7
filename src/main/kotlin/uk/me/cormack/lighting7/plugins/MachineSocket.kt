package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Outbound (no inbound) ──────────────────────────────────────────────
//
// Machine-scoped change broadcasts: desk accounts and the install row. Every other list in the
// app self-heals through `FixturesChangeListener` → `BroadcastSocket`, but that interface hangs
// off the per-project `show/Fixtures.kt` and its instance is torn down and re-registered on
// project switch (see `BroadcastSocket.kt`). Accounts and the install row belong to the machine
// and outlive every project, so they get their own collector here rather than a fifteenth method
// on an interface four unrelated subsystems implement.
//
// One file for both because — unlike every other socket domain here — these concerns share a
// *scope* rather than a subject, and they are collected together in the pre-warm-up band of
// `Sockets.kt`. "Machine" is the established word: see `docs/desk-accounts.md` (accounts belong
// to the machine, not to a project) and `MachineLocal` in `SyncCoverageTest`.
//
// Two invariants hold for every message below. **They carry no user data**: these sockets are
// open to operators and `/api/rest/users` is admin-only, so a payload would leak exactly what
// that gate exists to withhold — and the "something changed, refetch" shape is what every
// existing broadcast uses anyway. And **`Auth` is never invalidated by a broadcast**: only the
// affected user's own sockets are told, or a single admin edit would have every connected client
// refetch `auth/status` for nothing.

@Serializable
sealed class MachineOutMessage : OutMessage()

/**
 * A desk account was created, renamed, re-roled, enabled, disabled, deleted or re-passworded.
 * Sent to **every** socket; the frontend bridges it to the `UserList` / `User` tags.
 *
 * Not role-filtered, because it need not be: the frame is a bare discriminator, and the Users
 * tab is the only consumer of that query and skips it for non-admins, so an operator's client
 * has no subscriber and the invalidation is a no-op dispatch there.
 */
@Serializable
@SerialName("userListChanged")
data object UserListChangedOutMessage : MachineOutMessage()

/**
 * *Your* account changed — a rename or a re-role by an admin. Sent only to sockets belonging to
 * the affected user, so it can invalidate `Auth` (display name, role, `setupRequired`) without
 * making every other client refetch its own session.
 *
 * Disabling and deleting are felt through a different mechanism: they revoke sessions, and
 * `AuthService.revocations` closes the socket 4401.
 */
@Serializable
@SerialName("ownAccountChanged")
data object OwnAccountChangedOutMessage : MachineOutMessage()

/** The install row changed — currently only its friendly name. Sent to every socket. */
@Serializable
@SerialName("installChanged")
data object InstallChangedOutMessage : MachineOutMessage()

// ─── Subscriptions ──────────────────────────────────────────────────────

fun setupMachineSubscriptions(scope: SocketScope) {
    scope.subscribe(scope.state.authService.userChanges) { userId ->
        // Own-account frame first. If a demoted admin learns about the list before it learns
        // about itself, it refetches `/users` as an OPERATOR and takes a 403; landing the `Auth`
        // refetch first flips `isAdmin` false, which unsubscribes the list hook so the second
        // frame has nothing left to refetch. Ordering, not a guarantee — but it is free.
        //
        // `scope.user == null` means a bootstrap-open socket, admitted while the desk had zero
        // accounts. Its auth state is exactly what a user change invalidates: `setupRequired`
        // lives in the `Auth` payload, so without this arm a second tab sitting on the setup
        // screen never learns that the first one completed setup, and answers its submit with a
        // 409. Over-firing here is bounded — a desk has anonymous sockets only around first setup.
        //
        // Compare the id, never the role: `scope.user` was resolved once at upgrade time, so its
        // role goes stale the moment somebody is re-roled. The id cannot change.
        if (scope.user == null || userId == scope.user?.userId) {
            scope.send(OwnAccountChangedOutMessage)
        }
        scope.send(UserListChangedOutMessage)
    }

    scope.subscribe(scope.state.machineEventsFlow) { message -> scope.send(message) }
}
