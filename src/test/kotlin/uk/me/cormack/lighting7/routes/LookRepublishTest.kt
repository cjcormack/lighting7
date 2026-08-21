package uk.me.cormack.lighting7.routes

import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerValue
import uk.me.cormack.lighting7.fx.paletteRefValue
import uk.me.cormack.lighting7.fx.paletteUuidOrNull
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
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

    /** A live cue whose single row references [lookUuid] on [fixtureKey]. Returns its id. */
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
            DaoCuePropertyAssignment.new {
                this.cue = cue
                targetType = "fixture"; targetKey = fixtureKey
                propertyName = "colour"
                value = lookUuid?.let { paletteRefValue(it) } ?: hex!!
                sortOrder = 0
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
            "the DB scan must narrow to cues that actually hold a ref to this palette",
        )
        assertTrue(literal !in outcome.cuesRepublished)
        assertEquals("#00ff00", cueColour("hex-2").value.toSerializedString(), "untouched")
    }

    @Test
    fun `a programmer ref re-resolves and keeps its reference`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)

        val lookUuid = seedLook("Warm Amber", mapOf("hex-1" to "#ff8800"))
        val fixture = state.show.fixtures.untypedGroupableFixture("hex-1")
        state.show.fxEngine.writeProgrammerProperty(
            ProgrammerOwner.WEB, fixture, "rgbColour",
            CueAssignmentResolver.parseAssignmentValue(
                uk.me.cormack.lighting7.fixture.PropertyCategory.COLOUR, "rgbColour", "#ff8800",
            )!!,
            paletteUuid = lookUuid,
        )

        rewriteLookRows(lookUuid, mapOf("hex-1" to "#0000ff"))
        val outcome = republishForLookEdit(state, lookUuid)

        val slot = state.show.programmerStore.get("hex-1", "rgbColour")!!
        assertEquals(lookUuid, slot.value.paletteUuidOrNull, "it is still a reference")
        assertEquals(
            "#0000ff",
            (slot.value.resolved as CueAssignmentResolver.PropertyValue.Colour).value.toSerializedString(),
            "and it now resolves to the new value",
        )
        assertEquals(1, outcome.programmerKeysRefreshed)
    }

    @Test
    fun `a ref the look stops covering keeps its last value rather than vanishing`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)

        val lookUuid = seedLook("Warm Amber", mapOf("hex-1" to "#ff8800"))
        val fixture = state.show.fixtures.untypedGroupableFixture("hex-1")
        state.show.fxEngine.writeProgrammerProperty(
            ProgrammerOwner.WEB, fixture, "rgbColour",
            CueAssignmentResolver.parseAssignmentValue(
                uk.me.cormack.lighting7.fixture.PropertyCategory.COLOUR, "rgbColour", "#ff8800",
            )!!,
            paletteUuid = lookUuid,
        )

        // Drop the row entirely — the Look now covers nothing for this fixture.
        transaction(state.database) {
            DaoLook.all().single { it.uuid == lookUuid }.rows.forEach { it.delete() }
        }
        val outcome = republishForLookEdit(state, lookUuid)

        val slot = state.show.programmerStore.get("hex-1", "rgbColour")!!
        assertEquals(
            "#ff8800",
            (slot.value.resolved as CueAssignmentResolver.PropertyValue.Colour).value.toSerializedString(),
            "silently dropping an operator's programmer entry mid-show would be worse than a stale value",
        )
        assertIs<ProgrammerValue.Ref>(slot.value, "and it stays a reference, so the sheet can mark it broken")
        assertEquals(1, outcome.programmerKeysUncovered)
        assertEquals(0, outcome.programmerKeysRefreshed)
    }

    @Test
    fun `editing a look while blind stages the value without transmitting it`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)

        val lookUuid = seedLook("Warm Amber", mapOf("hex-1" to "#ff8800"))
        val fixture = state.show.fixtures.untypedGroupableFixture("hex-1")
        state.show.fxEngine.writeProgrammerProperty(
            ProgrammerOwner.WEB, fixture, "rgbColour",
            CueAssignmentResolver.parseAssignmentValue(
                uk.me.cormack.lighting7.fixture.PropertyCategory.COLOUR, "rgbColour", "#ff8800",
            )!!,
            paletteUuid = lookUuid,
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

    @Test
    fun `a cue row referencing a deleted look is skipped rather than lit white`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)

        // The ordering hazard, end to end: the literal colour parser answers white for junk, so a
        // ref that escaped interception would light the fixture instead of reporting a dead row.
        val cueId = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project
                name = "stack"; this.palette = emptyList(); loop = false
                type = CueStackType.STACK.name; sortOrder = 0
            }
            val cue = DaoCue.new {
                this.project = project
                name = "cue"; cueStack = stack; sortOrder = 0
                this.palette = emptyList(); cueType = CueType.STANDARD.name
            }
            DaoCuePropertyAssignment.new {
                this.cue = cue
                targetType = "fixture"; targetKey = "hex-1"
                propertyName = "colour"; value = paletteRefValue(UUID.randomUUID()); sortOrder = 0
            }
            cue.id.value
        }
        val applyData = transaction(state.database) { buildCueApplyData(DaoCue.findById(cueId)!!) }
        applyCue(state, applyData, replaceAll = false)

        assertEquals(
            null,
            state.show.fxEngine.layerResolver
                .currentCueLayerState[CueAssignmentResolver.Key.fixture("hex-1", "rgbColour")],
            "a dangling ref contributes nothing at all — not white",
        )
    }

    @Test
    fun `including a cue keeps its look reference, so a later edit still moves it`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)

        val lookUuid = seedLook("Warm Amber", mapOf("hex-1" to "#ff8800"))
        val cueId = applyCueReferencing("hex-1", lookUuid)

        val applyData = transaction(state.database) { buildCueApplyData(DaoCue.findById(cueId)!!) }
        includeCueIntoProgrammer(
            state, applyData, immediatePresets = emptyList(), mask = null, fadeMs = 0,
        )

        val slot = state.show.programmerStore.get("hex-1", "rgbColour")!!
        assertEquals(
            lookUuid, slot.value.paletteUuidOrNull,
            "Include must carry the reference, not just the literal it resolved to — hardening here " +
                "would both freeze the entry against later look edits and make the next Update " +
                "write a literal back over a row the operator never touched",
        )

        // And the reference is live: editing the palette moves the included entry.
        rewriteLookRows(lookUuid, mapOf("hex-1" to "#0000ff"))
        republishForLookEdit(state, lookUuid)
        assertEquals(
            "#0000ff",
            (state.show.programmerStore.get("hex-1", "rgbColour")!!.value.resolved
                as CueAssignmentResolver.PropertyValue.Colour).value.toSerializedString(),
        )
    }
}
