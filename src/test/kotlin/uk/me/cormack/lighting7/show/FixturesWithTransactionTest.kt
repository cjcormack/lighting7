package uk.me.cormack.lighting7.show

import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Tests that FixturesWithTransaction does not expose fixture registration methods.
 * GENERAL scripts receive a FixturesWithTransaction, so this ensures scripts
 * cannot register fixtures — only DbFixtureLoader can.
 */
class FixturesWithTransactionTest {

    @Test
    fun `FixturesWithTransaction does not expose register method`() {
        val methods = Fixtures.FixturesWithTransaction::class.members.map { it.name }
        assertFalse(
            methods.contains("register"),
            "FixturesWithTransaction should not expose a 'register' method — fixture registration is handled by DbFixtureLoader"
        )
    }
}
