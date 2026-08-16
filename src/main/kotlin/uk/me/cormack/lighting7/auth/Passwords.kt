package uk.me.cormack.lighting7.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import org.slf4j.LoggerFactory

/**
 * Production bcrypt cost. ~250 ms per hash/verify on desk hardware — slow enough to be
 * a natural login throttle, fast enough not to annoy. Tests pin `auth.bcryptCost=4`
 * via `testAppConfig()` so the suite doesn't pay 250 ms per seeded user.
 */
const val DEFAULT_BCRYPT_COST = 12

private val logger = LoggerFactory.getLogger("Passwords")

/**
 * Password hashing and policy. Cost is injectable so tests can run fast; anything
 * below 10 logs a WARN at construction so a fast cost can't silently reach production.
 */
class Passwords(private val cost: Int = DEFAULT_BCRYPT_COST) {
    init {
        if (cost < 10) {
            logger.warn("bcrypt cost {} is below the production floor of 10 — fine for tests, not for a real desk", cost)
        }
    }

    /**
     * A real hash of an unguessable throwaway value, used by [dummyVerify] so a login
     * against an unknown username burns the same bcrypt time as a wrong password —
     * response timing must not leak whether an account exists. Lazy: at cost 12 this
     * is ~250 ms, which must not run inside State construction at boot; the first
     * unknown-username login pays it instead, where a delay is the intended behaviour.
     */
    private val dummyHash: String by lazy { hash(SessionTokens.newToken().take(32)) }

    fun hash(plain: String): String =
        BCrypt.withDefaults().hashToString(cost, plain.toCharArray())

    fun verify(plain: String, hash: String): Boolean =
        BCrypt.verifyer().verify(plain.toCharArray(), hash.toCharArray()).verified

    /** Burn one bcrypt verification without learning anything — see [dummyHash]. */
    fun dummyVerify() {
        verify("not-the-password", dummyHash)
    }

    /**
     * Throws [PasswordPolicyException] for passwords under 8 characters or over 72 UTF-8
     * **bytes** — bcrypt silently truncates at 72 bytes, so a longer password would
     * validate against any input sharing its first 72 bytes. Bytes, not chars: multibyte
     * characters hit the limit sooner than `length` suggests.
     */
    fun validatePolicy(plain: String) {
        if (plain.length < 8) {
            throw PasswordPolicyException("Password must be at least 8 characters")
        }
        if (plain.toByteArray(Charsets.UTF_8).size > 72) {
            throw PasswordPolicyException("Password must be at most 72 bytes of UTF-8 (bcrypt truncates beyond that)")
        }
    }
}
