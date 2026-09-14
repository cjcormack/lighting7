package uk.me.cormack.lighting7.auth

import java.time.Instant

/**
 * The single precedence ladder every short-lived credential in this package derives its
 * status from — reset tokens ([ResetTokenStatus]) and device logins ([DeviceLoginStatus]).
 *
 * This type never reaches the wire. The two public enums stay separate on purpose (see
 * [DeviceLoginStatus]'s own note: a shared *type* is the first step towards a shared lookup
 * that could redeem one token as the other, and each has its own DTO the frontend
 * discriminates on). What must **not** be duplicated is the ordering — that is the
 * security-relevant part, because it decides whether a dead credential reads as live. So the
 * ladder lives here once, and each enum is a name-preserving projection of it.
 *
 * Two things hold that together, and a precedence change has to survive both:
 *
 * - [asResetTokenStatus] and [asDeviceLoginStatus] are exhaustive `when`s, so adding or
 *   removing a member here fails to compile at *both* sites rather than at neither.
 * - `TokenLifecycleTest` asserts the ladder's order once, and asserts both projections are
 *   name-preserving across every member — so a mis-mapped member fails too.
 *
 * Members are declared **in precedence order, strongest first** — deliberately not in the two
 * public enums' order. A disclaimer that declaration order means nothing would be one more
 * thing to keep true; declaring it to match instead means `ordinal` and `compareTo` agree with
 * [tokenLifecycleAt] for free, so a later "worst status wins" comparison can't quietly get the
 * ladder backwards. Reorder these only as part of reordering the `when` below.
 */
internal enum class TokenLifecycle {
    USED,
    CANCELLED,
    EXPIRED,
    PENDING,
}

/**
 * Where a credential stands at [now], as a pure function of its three timestamps — no
 * receiver, no transaction, no lock, so it reads the same for a persisted Exposed row as for
 * an in-memory entry, and a test can drive it off a fake clock.
 *
 * The order is deliberate and is the thing to think hard about before changing:
 *
 * 1. **[TokenLifecycle.USED] wins over everything.** "Spent" is the most informative thing to
 *    tell an admin's history list about a token, and it is terminal: nothing re-opens it.
 * 2. **[TokenLifecycle.CANCELLED] next.** Cancellation is only ever applied to a token that
 *    was neither used nor cancelled, so the two are mutually exclusive in practice; ordering
 *    them decides only what a row that somehow carries both reads as.
 * 3. **[TokenLifecycle.EXPIRED] last, before live.** Expiry is checked *after* the explicit
 *    end states so a token that was used or revoked keeps saying so once its TTL runs out,
 *    rather than every terminal row decaying into the same "expired".
 *
 * [TokenLifecycle.PENDING] is the only status any caller treats as redeemable, so anything
 * that moves a case *into* it widens what can be spent. Everything else is a display change.
 */
internal fun tokenLifecycleAt(
    usedAt: Instant?,
    cancelledAt: Instant?,
    expiresAt: Instant,
    now: Instant,
): TokenLifecycle = when {
    usedAt != null -> TokenLifecycle.USED
    cancelledAt != null -> TokenLifecycle.CANCELLED
    expiresAt <= now -> TokenLifecycle.EXPIRED
    else -> TokenLifecycle.PENDING
}

/** Project the shared ladder onto the reset-token wire enum. Name-preserving, exhaustive. */
internal fun TokenLifecycle.asResetTokenStatus(): ResetTokenStatus = when (this) {
    TokenLifecycle.PENDING -> ResetTokenStatus.PENDING
    TokenLifecycle.USED -> ResetTokenStatus.USED
    TokenLifecycle.EXPIRED -> ResetTokenStatus.EXPIRED
    TokenLifecycle.CANCELLED -> ResetTokenStatus.CANCELLED
}

/** Project the shared ladder onto the device-login wire enum. Name-preserving, exhaustive. */
internal fun TokenLifecycle.asDeviceLoginStatus(): DeviceLoginStatus = when (this) {
    TokenLifecycle.PENDING -> DeviceLoginStatus.PENDING
    TokenLifecycle.USED -> DeviceLoginStatus.USED
    TokenLifecycle.EXPIRED -> DeviceLoginStatus.EXPIRED
    TokenLifecycle.CANCELLED -> DeviceLoginStatus.CANCELLED
}
