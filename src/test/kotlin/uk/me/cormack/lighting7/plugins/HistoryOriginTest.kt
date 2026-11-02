package uk.me.cormack.lighting7.plugins

import io.ktor.http.HttpMethod
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The doors' names for a step and their keys for a drag. */
class HistoryOriginTest {

    @Test
    fun `every frame-rate write has a key per target, and a one-off has none`() {
        assertEquals("set|fixture:hex-1|dimmer", socketCoalesceKey(ProgrammerSetInMessage("fixture", "hex-1", "dimmer", "10")))
        assertEquals("updateChannel|0:12", socketCoalesceKey(UpdateChannelInMessage(0, 12, 5u, fadeTime = 0)))
        assertEquals("patchLayer|4", socketCoalesceKey(ProgrammerPatchLayerInMessage(4, amount = 0.5)))
        assertEquals("setScenery|e-1", socketCoalesceKey(ProgrammerSetSceneryInMessage("e-1", JsonObject(emptyMap()))))
        assertEquals("updateFx|9", socketCoalesceKey(UpdateFxInMessage(9)))
        assertNull(socketCoalesceKey(ProgrammerClearAllInMessage()))
    }

    @Test
    fun `a REST step is named by its method and path`() {
        assertEquals("Fan", restHistoryLabel("/api/rest/projects/1/programmer/fan"))
        assertEquals("Press Look", restHistoryLabel("/api/rest/projects/1/looks/5/toggle"))
        assertEquals("Stop FX", restHistoryLabel("/api/rest/fx/12", HttpMethod.Delete))
        assertEquals("Delete look", restHistoryLabel("/api/rest/projects/1/looks/5", HttpMethod.Delete))
        assertEquals("Change FX", restHistoryLabel("/api/rest/fx/12", HttpMethod.Put))
    }
}
