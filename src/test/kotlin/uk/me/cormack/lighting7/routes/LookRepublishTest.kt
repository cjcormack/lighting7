package uk.me.cormack.lighting7.routes

import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerValue
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The touring feature: editing a Look moves every live cue that depends on it, without re-firing a
 * cue. Drives [republishForLookEdit] directly — the routes that call it land in the record/update
 * work, but the behaviour it guarantees is worth pinning on its own.
 *
 * These cues depend on the Look through a `ref:` value rather than through a layer, which is the
 * path that survives until the grammar is retired. The layer path shares the same republish, and is
 * covered where layers are composed.
 */
class LookRepublishTest : RouteIntegrationTest() {

    private fun seedHex(key: String, startChannel: Int) =
        LocateTestSupport.seedHex(state, projectId, key, startChannel)

    /** A bound Look holding one colour row per (fixtureKey → hex) pair. */
    private fun seedLook(name: String, entries: Map<String, String>): UUID = transaction(state.database) {
        val look = DaoLook.new {
            this.project = DaoProject.findById(projectId)!!
            this.name = name
        }
        entries.entries.forEachIndexed { index, (fixtureKey, hex) ->
            DaoLookRow.new {
                this.look = look
                targetType = "fixture"; targetKey = fixtureKey
                propertyName = "colour"; value = hex; sortOrder = index
            }
        }
        look.uuid
    }

    /** Overwrite every row's value in [lookUuid]. Mirrors what a re-record does. */
    private fun rewriteLookRows(lookUuid: UUID, entries: Map<String, String>) {
        transaction(state.database) {
            val look = DaoLook.all().single { it.uuid == lookUuid }
            look.rows.forEach { row ->
                entries[row.targetKey]?.let { row.value = it }
            }
        }
    }

    /**
     * A live cue that depends on [lookUuid] through a **layer**, or holds a literal [hex] row when
     * [lookUuid] is null. Returns its id.
     *
     * Until session 4 the dependency was a cue row whose value was `ref:{lookUuid}`. The `ref:` value
     * grammar retired; a layer naming the Look through a real FK is what makes a cue tour, and it is
     * what `activeCuesReferencingLook` now scans for.
     */
    private fun applyCueReferencing(fixtureKey: String, lookUuid: UUID?, hex: String? = null): Int {
        val cueId = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project
                name = "stack-${System.nanoTime()}"; this.palette = emptyList(); loop = false
                type = CueStackType.STACK.name; sortOrder = 0
            }
            val cue = DaoCue.new {
                this.project = project
                name = "cue-$fixtureKey"; cueStack = stack; sortOrder = 0
                this.palette = emptyList(); cueType = CueType.STANDARD.name
            }
            if (lookUuid == null) {
                DaoCuePropertyAssignment.new {
                    this.cue = cue
                    targetType = "fixture"; targetKey = fixtureKey
                    propertyName = "colour"
                    value = hex!!
                    sortOrder = 0
                }
            } else {
                // Empty targets: the Look's own rows are bound to [fixtureKey], so the layer takes
                // its coverage from them rather than supplying any.
                DaoCueLayer.new {
                    this.cue = cue
                    look = DaoLook.all().single { it.uuid == lookUuid }
                    sortOrder = 0
                    targets = emptyList()
                }
            }
            cue.id.value
        }
        val applyData = transaction(state.database) { buildCueApplyData(DaoCue.findById(cueId)!!) }
        applyCue(state, applyData, replaceAll = false)
        return cueId
    }

    private fun cueColour(fixtureKey: String): CueAssignmentResolver.PropertyValue.Colour {
        val value = state.show.fxEngine.layerResolver
            .currentCueLayerState[CueAssignmentResolver.Key.fixture(fixtureKey, "rgbColour")]
        return assertIs(value)
    }

    @Test
    fun `editing a look moves a live cue's output without re-firing it`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)

        val lookUuid = seedLook("Warm Amber", mapOf("hex-1" to "#ff8800"))
        applyCueReferencing("hex-1", lookUuid)
        assertEquals("#ff8800", cueColour("hex-1").value.toSerializedString())

        rewriteLookRows(lookUuid, mapOf("hex-1" to "#0000ff"))
        val outcome = republishForLookEdit(state, lookUuid)

        assertEquals(
            "#0000ff", cueColour("hex-1").value.toSerializedString(),
            "the live cue picked up the new palette value with no GO",
        )
        assertEquals(1, outcome.cuesRepublished.size)
    }

    @Test
    fun `a live cue that does not reference the look is left alone`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)
        seedHex("hex-2", startChannel = 20)

        val lookUuid = seedLook("Warm Amber", mapOf("hex-1" to "#ff8800"))
        val referencing = applyCueReferencing("hex-1", lookUuid)
        val literal = applyCueReferencing("hex-2", lookUuid = null, hex = "#00ff00")

        rewriteLookRows(lookUuid, mapOf("hex-1" to "#0000ff"))
        val outcome = republishForLookEdit(state, lookUuid)

        assertEquals(
            listOf(referencing), outcome.cuesRepublished,
            "the DB scan must narrow to cues that actually layer this Look",
        )
        assertTrue(literal !in outcome.cuesRepublished)
        assertEquals("#00ff00", cueColour("hex-2").value.toSerializedString(), "untouched")
    }

    @Test
    fun `editing a look while blind stages the value without transmitting it`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)

        val lookUuid = seedLook("Warm Amber", mapOf("hex-1" to "#ff8800"))
        // A programmer *layer*, which is what carries a live dependency on a Look now — this used to
        // be a `writeProgrammerProperty(..., paletteUuid = lookUuid)` ref slot.
        val look = transaction(state.database) {
            DaoLook.all().single { it.uuid == lookUuid }.let { Triple(it.id.value, it.uuid, it.name) }
        }
        state.show.programmerLayerStack.add(
            lookId = look.first, lookUuid = look.second, lookName = look.third,
            targets = emptyList(),
        )
        state.show.fxEngine.setProgrammerBlind(true)

        rewriteLookRows(lookUuid, mapOf("hex-1" to "#0000ff"))
        republishForLookEdit(state, lookUuid)

        // Stored state moves; the stage does not, because the blind gate is consulted at publish.
        val slot = state.show.programmerStore.get("hex-1", "rgbColour")!!
        assertEquals(
            "#0000ff",
            (slot.value.resolved as CueAssignmentResolver.PropertyValue.Colour).value.toSerializedString(),
        )
        assertTrue(state.show.programmerStore.blind)
    }

    // Four tests stood here and all four had the `ref:` value grammar as their subject: a programmer
    // ref re-resolving on a Look edit, a ref the Look stopped covering keeping its last value rather
    // than vanishing, a dangling ref row contributing nothing instead of white, and Include carrying
    // a reference rather than hardening it. None of those states can exist now — a cue or programmer
    // depends on a Look through a layer, and a layer that resolves to nothing simply contributes
    // nothing. The touring behaviour they surrounded is covered above, through layers.
}
