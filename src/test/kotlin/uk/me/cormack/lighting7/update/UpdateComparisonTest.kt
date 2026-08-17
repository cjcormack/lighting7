package uk.me.cormack.lighting7.update

import org.junit.Test
import kotlin.test.assertEquals

class UpdateComparisonTest {
    @Test
    fun `a newer release is offered`() {
        assertEquals(UpdateAvailability.UPDATE_AVAILABLE, compareVersions("1.1.0", "v1.2.0"))
        assertEquals(UpdateAvailability.UPDATE_AVAILABLE, compareVersions("1.1.0", "v2.0.0"))
        assertEquals(UpdateAvailability.UPDATE_AVAILABLE, compareVersions("1.1.0", "v1.1.1"))
    }

    @Test
    fun `the same version is up to date`() {
        assertEquals(UpdateAvailability.UP_TO_DATE, compareVersions("1.1.0", "v1.1.0"))
        // Build metadata must not manufacture a phantom update, or the desk would reinstall
        // the same release forever.
        assertEquals(UpdateAvailability.UP_TO_DATE, compareVersions("1.1.0", "v1.1.0+sha.abc"))
    }

    @Test
    fun `a local build newer than the latest release reports AHEAD not an update`() {
        assertEquals(UpdateAvailability.AHEAD, compareVersions("1.3.0", "v1.2.0"))
        assertEquals(UpdateAvailability.AHEAD, compareVersions("1.4.0-rc.1", "v1.3.0"))
    }

    @Test
    fun `a released version supersedes its own release candidate`() {
        assertEquals(UpdateAvailability.UPDATE_AVAILABLE, compareVersions("1.3.0-rc.1", "v1.3.0"))
    }

    /**
     * The load-bearing property. Tags are hand-typed, and the consequence of a false
     * UPDATE_AVAILABLE is restarting a lighting desk to install something arbitrary — so
     * anything unparseable has to land on UNKNOWN, on either side of the comparison.
     */
    @Test
    fun `fails closed to UNKNOWN when either side is unparseable`() {
        listOf(
            null to "v1.2.0",
            "1.1.0" to null,
            "" to "v1.2.0",
            "1.1.0" to "latest",
            "1.1.0" to "v1.2.3-hotfix wednesday",
            "1.1.0" to "release-2026-08",
            "unknown" to "v1.2.0",
            null to null,
        ).forEach { (current, latest) ->
            assertEquals(
                UpdateAvailability.UNKNOWN,
                compareVersions(current, latest),
                "current='$current' latest='$latest' should be UNKNOWN",
            )
        }
    }

    @Test
    fun `the unknown-version sentinel never offers an update against a garbage tag`() {
        assertEquals(
            UpdateAvailability.UNKNOWN,
            compareVersions(BuildInfo.UNKNOWN_VERSION, "not-a-tag"),
        )
    }
}
