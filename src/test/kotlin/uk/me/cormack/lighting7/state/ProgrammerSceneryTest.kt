package uk.me.cormack.lighting7.state

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import uk.me.cormack.lighting7.models.DrapeOperation
import uk.me.cormack.lighting7.models.DrapeParams
import uk.me.cormack.lighting7.models.DrapeRole
import uk.me.cormack.lighting7.models.ElementStates
import uk.me.cormack.lighting7.models.FlatParams
import uk.me.cormack.lighting7.models.ObjectParams
import uk.me.cormack.lighting7.models.ObjectShape
import uk.me.cormack.lighting7.models.SceneryElementInfo
import uk.me.cormack.lighting7.models.StageElementKind
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The programmer's scenery overlay (scenery-programmer plan D1) on its own: a write checked against
 * its element's kind and merged over what the element holds, a release of one or all, Clear's fade,
 * and a project switch dropping everything.
 */
class ProgrammerSceneryTest {
    private val tabs = SceneryElementInfo(1, UUID.randomUUID(), "House tabs", StageElementKind.DRAPE, DrapeParams(DrapeRole.TABS, DrapeOperation.DRAW))
    private val moon = SceneryElementInfo(2, UUID.randomUUID(), "Moon", StageElementKind.OBJECT, ObjectParams(ObjectShape.DISC, flies = true))
    private val cloth = SceneryElementInfo(3, UUID.randomUUID(), "Cloth", StageElementKind.DRAPE, DrapeParams(DrapeRole.BACKCLOTH, DrapeOperation.FLY))
    private val flat = SceneryElementInfo(4, UUID.randomUUID(), "Flat", StageElementKind.FLAT, FlatParams())
    private val legs = SceneryElementInfo(5, UUID.randomUUID(), "Legs", StageElementKind.DRAPE, DrapeParams(DrapeRole.LEG, DrapeOperation.DEAD))
    private val project1 = listOf(tabs, moon, cloth, flat, legs).associateBy { it.uuid }
    private val overlay = ProgrammerScenery { projectId -> if (projectId == 1) project1 else emptyMap() }.apply { attach(1) }

    private fun state(open: Double? = null, trimM: Double? = null, visible: Boolean? = null): JsonObject = buildJsonObject {
        open?.let { put("open", it) }
        trimM?.let { put("trimM", it) }
        visible?.let { put("visible", it) }
    }

    private fun held() = overlay.flow.value.elements

    @Test
    fun `a write holds the element, and a second merges over the first with the latest fade`() {
        assertEquals(emptyList(), overlay.set(tabs.uuid, state(visible = false), fadeMs = 2000))
        assertEquals(emptyList(), overlay.set(tabs.uuid, state(open = 0.4), fadeMs = null))
        assertEquals(emptyList(), overlay.set(moon.uuid, state(trimM = 3.0), fadeMs = 0))
        assertEquals(ProgrammerScenery.Held(ElementStates(visible = false, open = 0.4), null), held()[tabs.uuid])
        assertEquals(listOf(tabs.uuid, moon.uuid), held().keys.toList(), "in the order first held")
        assertEquals(1, overlay.flow.value.projectId)
    }

    @Test
    fun `a key the element's kind cannot take is refused by name, every problem at once, and holds nothing`() {
        val moonOpen = overlay.set(moon.uuid, state(open = 0.5), null)
        assertTrue(moonOpen.single().contains("open is a drawn drape's") && "'Moon'" in moonOpen.single(), moonOpen.toString())
        assertTrue(overlay.set(tabs.uuid, state(trimM = 2.0), null).single().contains("trimM is a flown piece's"))
        assertTrue(overlay.set(flat.uuid, state(open = 0.0), null).single().contains("open is a drawn drape's"))
        assertTrue(overlay.set(legs.uuid, state(trimM = 1.0), null).single().contains("trimM is a flown piece's"))
        // A flown drape takes a trim, not an opening; anything takes visible.
        assertTrue(overlay.set(cloth.uuid, state(open = 1.0), null).single().contains("open is a drawn drape's"))
        assertEquals(emptyList(), overlay.set(cloth.uuid, state(trimM = 4.0), null))
        assertEquals(emptyList(), overlay.set(flat.uuid, state(visible = false), null))

        val many = overlay.set(UUID.randomUUID(), buildJsonObject { put("open", 3.0); put("colour", "red") }, fadeMs = -1)
        assertTrue(many.any { "fadeMs must be between" in it }, many.toString())
        assertTrue(many.any { "names no stage element in this project" in it }, many.toString())
        assertTrue(many.any { "open must be between 0.0 and 1.0" in it }, many.toString())
        assertTrue(many.any { "unknown state 'colour'" in it }, many.toString())
        assertTrue(overlay.set(tabs.uuid, JsonObject(emptyMap()), null).single().contains("names no state"))
        assertEquals(setOf(cloth.uuid, flat.uuid), held().keys)
    }

    @Test
    fun `release lets one element go, or every one, and only a fade above 0 is remembered for the move home`() {
        overlay.set(tabs.uuid, state(open = 0.0), null)
        overlay.set(moon.uuid, state(trimM = 3.0), null)
        overlay.set(cloth.uuid, state(trimM = 1.0), null)

        assertEquals(1, overlay.release(moon.uuid))
        assertEquals(0, overlay.release(moon.uuid), "already let go")
        assertEquals(setOf(tabs.uuid, cloth.uuid), held().keys)
        assertEquals(emptyMap(), overlay.readForResolve(takeReleases = true).second, "no fade: it flies home on its travel")

        assertEquals(2, overlay.clear(fadeMs = 3000))
        assertEquals(emptyMap(), held())
        val (_, releases) = overlay.readForResolve(takeReleases = false)
        assertEquals(mapOf(tabs.uuid to 3000L, cloth.uuid to 3000L), releases, "Clear's fade, for the move home")
        assertEquals(releases, overlay.readForResolve(takeReleases = true).second, "a preview reads without taking")
        assertEquals(emptyMap(), overlay.readForResolve(takeReleases = true).second, "taken once")

        // Held again before a resolve read the release: the stale clock goes.
        overlay.set(tabs.uuid, state(open = 0.2), null)
        overlay.release(tabs.uuid, fadeMs = 1500)
        overlay.set(tabs.uuid, state(open = 0.3), null)
        assertEquals(emptyMap(), overlay.readForResolve(takeReleases = true).second)
    }

    @Test
    fun `a project switch drops everything held, and the new project's scene is the one checked`() {
        overlay.set(tabs.uuid, state(open = 0.0), null)
        overlay.release(tabs.uuid, fadeMs = 2000)
        overlay.set(moon.uuid, state(trimM = 3.0), null)

        overlay.attach(2)
        assertEquals(ProgrammerScenery.Snapshot(2, emptyMap()), overlay.flow.value)
        assertEquals(emptyMap(), overlay.readForResolve(takeReleases = true).second, "the old project's clocks go too")
        assertTrue(overlay.set(moon.uuid, state(trimM = 3.0), null).single().contains("names no stage element in this project"))

        overlay.attach(null)
        assertEquals(listOf("No project is loaded"), overlay.set(moon.uuid, state(trimM = 3.0), null))
    }

    @Test
    fun `forget drops elements the scene no longer has, only for the project it read`() {
        overlay.set(tabs.uuid, state(open = 0.0), null)
        overlay.set(moon.uuid, state(trimM = 3.0), null)
        overlay.forget(projectId = 2, uuids = listOf(tabs.uuid))
        assertEquals(setOf(tabs.uuid, moon.uuid), held().keys, "a read of another project's scene forgets nothing")
        overlay.forget(projectId = 1, uuids = listOf(tabs.uuid))
        assertEquals(setOf(moon.uuid), held().keys)
    }

    @Test
    fun `include replaces each named element whole, keeps a cue row's clock through a later move, and the baseline follows the target`() {
        overlay.set(moon.uuid, state(visible = false), null)
        overlay.set(tabs.uuid, state(open = 0.2), null)
        assertEquals(null, overlay.flow.value.changedSinceInclude, "no baseline before an Include")

        val rows = listOf(ProgrammerScenery.Included(moon.uuid, ElementStates(trimM = 3.0), 4000))
        val loaded = overlay.include(1, rows, fadeMs = 1500)
        assertEquals(1, loaded)
        assertEquals(null, overlay.flow.value.changedSinceInclude, "the baseline is the include target's, set by the caller")
        overlay.includedBaseline(1, rows)
        assertEquals(ProgrammerScenery.Held(ElementStates(trimM = 3.0), 1500, 4000), held()[moon.uuid], "replaced, not merged over visible")
        assertEquals(ElementStates(open = 0.2), held()[tabs.uuid]?.state, "an element the source does not name keeps what it holds")
        assertEquals(1, overlay.flow.value.changedSinceInclude, "the tabs are not the source's")

        overlay.set(moon.uuid, state(trimM = 5.0), null)
        assertEquals(4000L, held()[moon.uuid]?.transitionMs, "a later move keeps the row's own clock")
        assertEquals(2, overlay.flow.value.changedSinceInclude)

        overlay.rebaseline(1, written = true)
        assertEquals(0, overlay.flow.value.changedSinceInclude)
        overlay.rebaseline(1, written = false)
        assertEquals(2, overlay.flow.value.changedSinceInclude)
        overlay.release(null)
        assertEquals(0, overlay.flow.value.changedSinceInclude, "a release is not something Update writes")

        assertEquals(0, overlay.include(2, listOf(ProgrammerScenery.Included(moon.uuid, ElementStates(trimM = 3.0), null)), null), "another project's rows")
        overlay.attach(2)
        assertEquals(null, overlay.flow.value.changedSinceInclude, "a project switch drops the baseline")
    }
}
