package uk.me.cormack.lighting7.launcher

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the launcher does when its backend exits on its own: it exits too, never restarting it. */
class LauncherExitTest {

    @Test
    fun `a backend quit from the desk ends the launcher cleanly`() {
        assertEquals(0, launcherExitCode(0))
    }

    @Test
    fun `a backend that failed ends the launcher with a failure`() {
        assertEquals(1, launcherExitCode(1))
        assertEquals(1, launcherExitCode(137))
        assertEquals(1, launcherExitCode(null))
    }
}
