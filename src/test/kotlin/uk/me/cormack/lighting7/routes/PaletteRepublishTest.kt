package uk.me.cormack.lighting7.routes

import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.Layer3Resolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerValue
import uk.me.cormack.lighting7.fx.paletteRefValue
import uk.me.cormack.lighting7.fx.paletteUuidOrNull
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPaletteEntry
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.PaletteType
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The touring feature: editing a palette moves every live look that references it, without
 * re-firing a cue. Drives [republishForPaletteEdit] directly — the routes that call it land in the
 * record/update work, but the behaviour it guarantees is worth pinning on its own.
 */
class PaletteRepublishTest : RouteIntegrationTest() {

    private fun seedHex(key: String, startChannel: Int) =
        LocateTestSupport.seedHex(state, projectId, key, startChannel)

    /** A COLOUR palette holding one entry per (fixtureKey → hex) pair. */
    private fun seedPalette(name: String, entries: Map<String, String>): UUID = transaction(state.database) {
        val palette = DaoPalette.new {
            this.project = DaoProject.findById(projectId)!!
            this.name = name
            this.type = PaletteType.COLOUR.name
        }
        entries.entries.forEachIndexed { index, (fixtureKey, hex) ->
            DaoPaletteEntry.new {
                this.palette = palette
                targetType = "fixture"; targetKey = fixtureKey
                propertyName = "colour"; value = hex; sortOrder = index
            }
        }
        palette.uuid
    }

    /** Overwrite every entry's value in [paletteUuid]. Mirrors what a re-record does. */
    private fun rewritePaletteEntries(paletteUuid: UUID, entries: Map<String, String>) {
        transaction(state.database) {
            val palette = DaoPalette.all().single { it.uuid == paletteUuid }
            palette.entries.forEach { row ->
                entries[row.targetKey]?.let { row.value = it }
            }
        }
    }

    /** A live cue whose single row references [paletteUuid] on [fixtureKey]. Returns its id. */
    private fun applyCueReferencing(fixtureKey: String, paletteUuid: UUID?, hex: String? = null): Int {
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
                value = paletteUuid?.let { paletteRefValue(it) } ?: hex!!
                sortOrder = 0
            }
            cue.id.value
        }
        val applyData = transaction(state.database) { buildCueApplyData(DaoCue.findById(cueId)!!) }
        applyCue(state, applyData, replaceAll = false)
        return cueId
    }

    private fun cueColour(fixtureKey: String): Layer3Resolver.PropertyValue.Colour {
        val value = state.show.fxEngine.layerResolver
            .currentLayer3State[Layer3Resolver.Key.fixture(fixtureKey, "rgbColour")]
        return assertIs(value)
    }

    @Test
    fun `editing a palette moves a live cue's output without re-firing it`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)

        val paletteUuid = seedPalette("Warm Amber", mapOf("hex-1" to "#ff8800"))
        applyCueReferencing("hex-1", paletteUuid)
        assertEquals("#ff8800", cueColour("hex-1").value.toSerializedString())

        rewritePaletteEntries(paletteUuid, mapOf("hex-1" to "#0000ff"))
        val outcome = republishForPaletteEdit(state, paletteUuid)

        assertEquals(
            "#0000ff", cueColour("hex-1").value.toSerializedString(),
            "the live cue picked up the new palette value with no GO",
        )
        assertEquals(1, outcome.cuesRepublished.size)
    }

    @Test
    fun `a live cue that does not reference the palette is left alone`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)
        seedHex("hex-2", startChannel = 20)

        val paletteUuid = seedPalette("Warm Amber", mapOf("hex-1" to "#ff8800"))
        val referencing = applyCueReferencing("hex-1", paletteUuid)
        val literal = applyCueReferencing("hex-2", paletteUuid = null, hex = "#00ff00")

        rewritePaletteEntries(paletteUuid, mapOf("hex-1" to "#0000ff"))
        val outcome = republishForPaletteEdit(state, paletteUuid)

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

        val paletteUuid = seedPalette("Warm Amber", mapOf("hex-1" to "#ff8800"))
        val fixture = state.show.fixtures.untypedGroupableFixture("hex-1")
        state.show.fxEngine.writeProgrammerProperty(
            ProgrammerOwner.WEB, fixture, "rgbColour",
            Layer3Resolver.parseAssignmentValue(
                uk.me.cormack.lighting7.fixture.PropertyCategory.COLOUR, "rgbColour", "#ff8800",
            )!!,
            paletteUuid = paletteUuid,
        )

        rewritePaletteEntries(paletteUuid, mapOf("hex-1" to "#0000ff"))
        val outcome = republishForPaletteEdit(state, paletteUuid)

        val slot = state.show.programmerStore.get("hex-1", "rgbColour")!!
        assertEquals(paletteUuid, slot.value.paletteUuidOrNull, "it is still a reference")
        assertEquals(
            "#0000ff",
            (slot.value.resolved as Layer3Resolver.PropertyValue.Colour).value.toSerializedString(),
            "and it now resolves to the new value",
        )
        assertEquals(1, outcome.programmerKeysRefreshed)
    }

    @Test
    fun `a ref the palette stops covering keeps its last value rather than vanishing`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)

        val paletteUuid = seedPalette("Warm Amber", mapOf("hex-1" to "#ff8800"))
        val fixture = state.show.fixtures.untypedGroupableFixture("hex-1")
        state.show.fxEngine.writeProgrammerProperty(
            ProgrammerOwner.WEB, fixture, "rgbColour",
            Layer3Resolver.parseAssignmentValue(
                uk.me.cormack.lighting7.fixture.PropertyCategory.COLOUR, "rgbColour", "#ff8800",
            )!!,
            paletteUuid = paletteUuid,
        )

        // Drop the entry entirely — the palette now covers nothing for this fixture.
        transaction(state.database) {
            DaoPalette.all().single { it.uuid == paletteUuid }.entries.forEach { it.delete() }
        }
        val outcome = republishForPaletteEdit(state, paletteUuid)

        val slot = state.show.programmerStore.get("hex-1", "rgbColour")!!
        assertEquals(
            "#ff8800",
            (slot.value.resolved as Layer3Resolver.PropertyValue.Colour).value.toSerializedString(),
            "silently dropping an operator's programmer entry mid-show would be worse than a stale value",
        )
        assertIs<ProgrammerValue.Ref>(slot.value, "and it stays a reference, so the sheet can mark it broken")
        assertEquals(1, outcome.programmerKeysUncovered)
        assertEquals(0, outcome.programmerKeysRefreshed)
    }

    @Test
    fun `editing a palette while blind stages the value without transmitting it`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)

        val paletteUuid = seedPalette("Warm Amber", mapOf("hex-1" to "#ff8800"))
        val fixture = state.show.fixtures.untypedGroupableFixture("hex-1")
        state.show.fxEngine.writeProgrammerProperty(
            ProgrammerOwner.WEB, fixture, "rgbColour",
            Layer3Resolver.parseAssignmentValue(
                uk.me.cormack.lighting7.fixture.PropertyCategory.COLOUR, "rgbColour", "#ff8800",
            )!!,
            paletteUuid = paletteUuid,
        )
        state.show.fxEngine.setProgrammerBlind(true)

        rewritePaletteEntries(paletteUuid, mapOf("hex-1" to "#0000ff"))
        republishForPaletteEdit(state, paletteUuid)

        // Stored state moves; the stage does not, because the blind gate is consulted at publish.
        val slot = state.show.programmerStore.get("hex-1", "rgbColour")!!
        assertEquals(
            "#0000ff",
            (slot.value.resolved as Layer3Resolver.PropertyValue.Colour).value.toSerializedString(),
        )
        assertTrue(state.show.programmerStore.blind)
    }

    @Test
    fun `a cue row referencing a deleted palette is skipped rather than lit white`() = testApplication {
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
                .currentLayer3State[Layer3Resolver.Key.fixture("hex-1", "rgbColour")],
            "a dangling ref contributes nothing at all — not white",
        )
    }

    @Test
    fun `including a cue keeps its palette reference, so a later edit still moves it`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)

        val paletteUuid = seedPalette("Warm Amber", mapOf("hex-1" to "#ff8800"))
        val cueId = applyCueReferencing("hex-1", paletteUuid)

        val applyData = transaction(state.database) { buildCueApplyData(DaoCue.findById(cueId)!!) }
        includeCueIntoProgrammer(
            state, applyData, immediatePresets = emptyList(), mask = null, fadeMs = 0,
        )

        val slot = state.show.programmerStore.get("hex-1", "rgbColour")!!
        assertEquals(
            paletteUuid, slot.value.paletteUuidOrNull,
            "Include must carry the reference, not just the literal it resolved to — hardening here " +
                "would both freeze the entry against later palette edits and make the next Update " +
                "write a literal back over a row the operator never touched",
        )

        // And the reference is live: editing the palette moves the included entry.
        rewritePaletteEntries(paletteUuid, mapOf("hex-1" to "#0000ff"))
        republishForPaletteEdit(state, paletteUuid)
        assertEquals(
            "#0000ff",
            (state.show.programmerStore.get("hex-1", "rgbColour")!!.value.resolved
                as Layer3Resolver.PropertyValue.Colour).value.toSerializedString(),
        )
    }
}
