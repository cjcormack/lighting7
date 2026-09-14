package uk.me.cormack.lighting7.auth

import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * The guard for the one thing [ResetTokenStatus] and [DeviceLoginStatus] share: the ordering
 * they are derived by.
 *
 * Those two enums used to each own a byte-for-byte identical `when`, in unrelated flows, with
 * nothing comparing them — so deciding (say) that cancellation should outrank redemption could
 * be applied to one and missed on the other, and a dead credential would read as live on the
 * side that was missed. The ladder now lives once in [tokenLifecycleAt]; this asserts its
 * order, and asserts both projections still track it member for member.
 *
 * A deliberate precedence change is therefore a two-line edit here plus the helper, which is
 * the point: it becomes a decision someone records rather than a drift nobody notices.
 */
class TokenLifecycleTest {
    private val now: Instant = Instant.ofEpochSecond(1_700_000_000)
    private val past: Instant = now.minusSeconds(60)
    private val future: Instant = now.plusSeconds(60)

    @Test
    fun `live token with time left is pending`() {
        assertEquals(
            TokenLifecycle.PENDING,
            tokenLifecycleAt(usedAt = null, cancelledAt = null, expiresAt = future, now = now),
        )
    }

    @Test
    fun `expiry is exact at the boundary`() {
        // `expiresAt <= now` — a token expiring exactly now is already dead, never redeemable
        // for one more instant.
        assertEquals(
            TokenLifecycle.EXPIRED,
            tokenLifecycleAt(usedAt = null, cancelledAt = null, expiresAt = now, now = now),
        )
        assertEquals(
            TokenLifecycle.PENDING,
            tokenLifecycleAt(usedAt = null, cancelledAt = null, expiresAt = now.plusMillis(1), now = now),
        )
    }

    @Test
    fun `used outranks cancelled, expired and live`() {
        assertEquals(
            TokenLifecycle.USED,
            tokenLifecycleAt(usedAt = past, cancelledAt = past, expiresAt = past, now = now),
        )
        assertEquals(
            TokenLifecycle.USED,
            tokenLifecycleAt(usedAt = past, cancelledAt = null, expiresAt = future, now = now),
        )
    }

    @Test
    fun `cancelled outranks expired and live`() {
        assertEquals(
            TokenLifecycle.CANCELLED,
            tokenLifecycleAt(usedAt = null, cancelledAt = past, expiresAt = past, now = now),
        )
        assertEquals(
            TokenLifecycle.CANCELLED,
            tokenLifecycleAt(usedAt = null, cancelledAt = past, expiresAt = future, now = now),
        )
    }

    @Test
    fun `an explicit end state survives the TTL running out`() {
        // The reason expiry is checked last: the admin's history list should keep saying "used"
        // or "revoked" for a spent row rather than every terminal row decaying into "expired".
        assertEquals(
            TokenLifecycle.USED,
            tokenLifecycleAt(usedAt = past, cancelledAt = null, expiresAt = past, now = now),
        )
        assertEquals(
            TokenLifecycle.CANCELLED,
            tokenLifecycleAt(usedAt = null, cancelledAt = past, expiresAt = past, now = now),
        )
    }

    @Test
    fun `only pending is redeemable`() {
        // Every caller gates on `== PENDING`, so this is the line that matters: exactly one
        // member may be treated as live, and it is the one reached when nothing has happened
        // to the token yet.
        val redeemable = TokenLifecycle.entries.filter {
            it == tokenLifecycleAt(usedAt = null, cancelledAt = null, expiresAt = future, now = now)
        }
        assertEquals(listOf(TokenLifecycle.PENDING), redeemable)
    }

    @Test
    fun `declaration order is precedence order`() {
        // The enum declares its members strongest-first so `ordinal` and `compareTo` agree with
        // the ladder, instead of a KDoc disclaiming that they don't. That only holds while the
        // two are kept in step, so pin it — and pin it against actual behaviour, not just
        // against a repeated literal: with every flag set at once the winner must be whichever
        // member is declared first, and likewise for the pair below it.
        assertEquals(
            listOf(
                TokenLifecycle.USED,
                TokenLifecycle.CANCELLED,
                TokenLifecycle.EXPIRED,
                TokenLifecycle.PENDING,
            ),
            TokenLifecycle.entries.toList(),
        )
        assertEquals(
            TokenLifecycle.entries.first(),
            tokenLifecycleAt(usedAt = past, cancelledAt = past, expiresAt = past, now = now),
        )
        assertEquals(
            TokenLifecycle.entries.first { it != TokenLifecycle.USED },
            tokenLifecycleAt(usedAt = null, cancelledAt = past, expiresAt = past, now = now),
        )
    }

    @Test
    fun `both wire enums project the ladder name for name`() {
        // Not a tautology: it is what stops a mis-mapped member turning one ladder rung into a
        // different status on one side only. Both public enums are @Serializable, so their
        // member names are also the wire contract lighting-react discriminates on.
        for (lifecycle in TokenLifecycle.entries) {
            assertEquals(lifecycle.name, lifecycle.asResetTokenStatus().name)
            assertEquals(lifecycle.name, lifecycle.asDeviceLoginStatus().name)
        }
        assertEquals(TokenLifecycle.entries.size, ResetTokenStatus.entries.size)
        assertEquals(TokenLifecycle.entries.size, DeviceLoginStatus.entries.size)
    }
}
