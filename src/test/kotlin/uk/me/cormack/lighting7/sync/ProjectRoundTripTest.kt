package uk.me.cormack.lighting7.sync

import uk.me.cormack.lighting7.fixture.locate.UnitLocate
import uk.me.cormack.lighting7.fixture.media.FittedSlot
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.AddressFacet
import uk.me.cormack.lighting7.models.DaoShowOverride
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.FocusFacet
import uk.me.cormack.lighting7.models.MediaFacet
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.PositionFacet
import uk.me.cormack.lighting7.models.decodeFacet
import uk.me.cormack.lighting7.models.facetElement
import uk.me.cormack.lighting7.sync.dto.ShowOverrideJson
import uk.me.cormack.lighting7.routes.deleteProjectRows
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.sync.dto.ProjectJson
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.sync.dto.ShowHideJson
import uk.me.cormack.lighting7.sync.dto.ShowParkReleaseJson
import uk.me.cormack.lighting7.sync.dto.ParkedChannelJson
import uk.me.cormack.lighting7.models.VenueParks
import uk.me.cormack.lighting7.sync.dto.VenueVersionJson
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.venue.VenueDiff
import uk.me.cormack.lighting7.venue.VenueVersions
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.sync.dto.CueAttributeAssignmentJson
import uk.me.cormack.lighting7.sync.dto.CueAdHocFxJson
import uk.me.cormack.lighting7.sync.dto.CueLayerJson
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueAdHocFx
import uk.me.cormack.lighting7.models.DaoCueAdHocFxEntity
import uk.me.cormack.lighting7.fx.ElementMode
import uk.me.cormack.lighting7.fx.FxSpecCoercion
import java.util.UUID
import uk.me.cormack.lighting7.fx.loadLookSnapshot
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.RICH_PROJECT_PULSE_RUN
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.dto.BuskPageJson
import uk.me.cormack.lighting7.sync.dto.BuskRigRowJson
import uk.me.cormack.lighting7.sync.dto.CueJson
import uk.me.cormack.lighting7.sync.dto.CueSlotJson
import uk.me.cormack.lighting7.sync.dto.CueStackJson
import uk.me.cormack.lighting7.models.moveInDarkSettings
import uk.me.cormack.lighting7.sync.dto.LookJson
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.sync.dto.FixturePatchJson
import uk.me.cormack.lighting7.sync.dto.RiggingJson
import uk.me.cormack.lighting7.sync.dto.StageElementJson
import uk.me.cormack.lighting7.sync.dto.StageRegionJson
import uk.me.cormack.lighting7.sync.dto.StageViewpointJson
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uk.me.cormack.lighting7.sync.dto.InstallsJson
import uk.me.cormack.lighting7.sync.dto.PaletteJson
import uk.me.cormack.lighting7.sync.dto.UniverseConfigJson
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.RICH_PROJECT_DETACHED_FROM
import uk.me.cormack.lighting7.testsupport.RICH_PROJECT_NAME
import uk.me.cormack.lighting7.testsupport.RICH_PROJECT_SCENE_IMAGES
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import uk.me.cormack.lighting7.testsupport.assertExportsEqual
import uk.me.cormack.lighting7.testsupport.readExportFiles
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 1 verification: build a project, export it, wipe the DB, import it, re-export, and
 * assert byte-for-byte identical output. This exercises FK-by-UUID rewriting, canonical
 * determinism, and topological insert order in one scenario.
 *
 * Note what this does and doesn't pin down: because it compares two *exports*, it catches
 * the importer dropping something the exporter wrote, but a field missing from both sides is
 * missing symmetrically and passes. `SyncCoverageTest` guards the exporter side.
 */
class ProjectRoundTripTest {

    private lateinit var state: State
    private lateinit var exportDirA: Path
    private lateinit var exportDirB: Path

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        exportDirA = Files.createTempDirectory("sync-export-a-")
        exportDirB = Files.createTempDirectory("sync-export-b-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { exportDirA.toFile().deleteRecursively() }
        runCatching { exportDirB.toFile().deleteRecursively() }
    }

    @Test
    fun `round-trip preserves project byte-for-byte`() {
        val projectId = seedRichProject(state)

        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)

        assertExportsEqual(exportDirA, exportDirB, installsShapeOnly = true)
    }

    /**
     * The archive in the glossary's words (terminology plan sessions 4 and 6, `formatVersion` 39): no
     * folder, field or value the renames moved is written, and `project.json` no longer says the FX
     * library.
     */
    @Test
    fun `a v39 export says no old name`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val files = readExportFiles(exportDirA)
        assertTrue(files.keys.none { path -> listOf("templates/", "cueAdHocEffects/", "cuePropertyAssignments/").any { path.startsWith(it) } })
        assertTrue(files.keys.any { it.startsWith("palettes/") } && files.keys.any { it.startsWith("cueAdHocFx/") })
        val old = listOf(
            "\"effectType\"", "\"category\"", "\"propertyName\"", "\"beatDivision\"", "\"distribution\"", "\"stepTiming\"",
            "\"nShot\"", "\"blocks\"", "\"groups\"", "\"templateUuid\"", "\"propertyMask\"", "\"usage\"", "\"effectId\"",
            "\"effectMode\"", "\"compatibleProperties\"", "\"defaultStepTiming\"", "\"effects\"", "\"effect\"", "\"fxLibrary\"",
            "tmpl:", "pressTemplate", "stackEffectSize", "\"CENTER_OUT\"", "\"EDGES_IN\"", "\"order\": \"LINEAR\"",
            "fixtureProperty", "groupProperty", "selectionProperty", "panCenter", "tiltCenter",
        )
        for ((path, text) in files) {
            if (!path.endsWith(".json")) continue
            for (name in old) assertTrue(name !in text, "$path says $name")
        }
        assertEquals(FormatVersionJson(formatVersion = 45, minReader = 43), canonicalDecode(FormatVersionJson.serializer(), files.getValue("formatVersion.json")))
    }

    /**
     * A standalone show's origin (venue-attach plan D1) travels in `project.json` and imports back.
     */
    @Test
    fun `a detached show's origin exports in project json and round-trips`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val project = canonicalDecode(ProjectJson.serializer(), Files.readString(exportDirA.resolve("project.json")))
        assertEquals(RICH_PROJECT_DETACHED_FROM.first.toString(), project.detachedFromVenueUuid)
        assertEquals(RICH_PROJECT_DETACHED_FROM.second, project.detachedFromVersion)
        assertNull(project.venueUuid)

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        transaction(state.database) {
            val p = DaoProject[imported.projectId]
            assertEquals(RICH_PROJECT_DETACHED_FROM.first, p.detachedFromVenueUuid)
            assertEquals(RICH_PROJECT_DETACHED_FROM.second, p.detachedFromVersion)
        }
    }

    @Test
    fun `a show on a venue exports no origin`() {
        val onVenue = seedShowOnVenue(state)
        ProjectExporter(state).export(onVenue.showId, exportDirA)
        val text = Files.readString(exportDirA.resolve("project.json"))
        assertFalse(text.contains("detachedFrom"), "a show on a venue records no origin: $text")
    }

    /**
     * The byte-for-byte test above already proves the importer keeps every field the exporter
     * writes. What it cannot prove is that a *value* template stays free of the new key: a field
     * missing from both sides is missing symmetrically and passes. So this asserts on the export
     * text directly, the way the machine-local address test does.
     */
    @Test
    fun `templates carry an effect only when they hold one`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val templates = Files.list(exportDirA.resolve("palettes")).use { stream ->
            stream.toList().map { canonicalDecode(PaletteJson.serializer(), Files.readString(it)) }
        }

        val fxPalette = templates.single { it.name == "amber-breathe" }
        assertEquals("amber-breathe", fxPalette.name)
        assertTrue(fxPalette.rows.isEmpty(), "an effect template holds no rows (D1)")
        val effect = fxPalette.fx!!
        assertEquals("ColourWave", effect.fxType)
        assertEquals("colour", effect.family)
        // The off-default fields, which are the ones a copier can silently drop: canonical JSON
        // omits defaults, so a field left at its default is invisible to the round trip.
        assertEquals(0.125, effect.phaseOffset)
        assertEquals("REVERSE", effect.order)
        assertEquals("MAX", effect.blendMode)
        assertEquals("EVEN", effect.elementFilter)
        assertEquals("CYCLE", effect.timing)
        assertTrue(effect.parameters.getValue("colours").startsWith("pal:"))

        // The other two palettes but the step chase hold values, and `explicitNulls = false` must keep the key out
        // of their documents entirely rather than writing `"fx": null`.
        val valueDocs = Files.list(exportDirA.resolve("palettes")).use { stream ->
            stream.toList()
                .map { Files.readString(it) }
                .filter { !it.contains("amber-breathe") && !it.contains("steps-chase") }
        }
        assertEquals(2, valueDocs.size)
        assertTrue(
            valueDocs.none { it.contains("\"fx\"") },
            "a value palette must not carry the fx key at all",
        )
    }

    /**
     * D7's *Seconds* (formatVersion 29) travels on all three stored effect shapes — a Look's effect,
     * a palette's effect and a cue's ad-hoc effect. Asserted on the export text for the reason the
     * template test above gives: a field both sides dropped would still round-trip byte for byte.
     */
    @Test
    fun `effects on Seconds export the flag on every stored shape`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        fun <T> docs(folder: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
            Files.walk(exportDirA.resolve(folder)).use { stream ->
                stream.toList().filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                    .map { canonicalDecode(serializer, Files.readString(it)) }
            }

        val lookFx = docs("looks", uk.me.cormack.lighting7.sync.dto.LookJson.serializer())
            .single { it.name == "warm-pulse" }.fx.single()
        assertTrue(lookFx.seconds, "a Look's effect keeps Seconds")
        val paletteFx = docs("palettes", PaletteJson.serializer()).single { it.name == "amber-breathe" }.fx!!
        assertTrue(paletteFx.seconds, "a palette's effect keeps Seconds")
        val adHoc = openPulse(exportDirA)
        assertTrue(adHoc.seconds, "a cue's ad-hoc effect keeps Seconds")

        val format = canonicalDecode(
            uk.me.cormack.lighting7.sync.dto.FormatVersionJson.serializer(),
            Files.readString(exportDirA.resolve("formatVersion.json")),
        )
        assertTrue(format.formatVersion >= 29, "Seconds syncs from v29")
        assertTrue(SUPPORTED_FORMAT_VERSION >= 29, "and this desk reads v29")
    }

    /**
     * Size, direction and runs (effects plan session 1, formatVersion 30) travel on all three stored
     * effect shapes, each at the non-default `RichProjectFixture` gives it. Asserted on the export
     * text for the reason the Seconds test above gives.
     */
    @Test
    fun `FX amount, direction and runs export on every stored shape`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        fun <T> docs(folder: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
            Files.walk(exportDirA.resolve(folder)).use { stream ->
                stream.toList().filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                    .map { canonicalDecode(serializer, Files.readString(it)) }
            }

        val lookFx = docs("looks", uk.me.cormack.lighting7.sync.dto.LookJson.serializer())
            .single { it.name == "warm-pulse" }.fx.single()
        assertEquals(Triple(0.4, "BACKWARD", 2), Triple(lookFx.amount, lookFx.direction, lookFx.runs))
        val paletteFx = docs("palettes", PaletteJson.serializer()).single { it.name == "amber-breathe" }.fx!!
        assertEquals(Triple(0.75, "BOUNCE", 3), Triple(paletteFx.amount, paletteFx.direction, paletteFx.runs))
        val adHoc = openPulse(exportDirA)
        assertEquals(Triple(0.6, "BACKWARD", 1), Triple(adHoc.amount, adHoc.direction, adHoc.runs))

        val format = canonicalDecode(
            uk.me.cormack.lighting7.sync.dto.FormatVersionJson.serializer(),
            Files.readString(exportDirA.resolve("formatVersion.json")),
        )
        assertTrue(format.formatVersion >= 30, "size, direction and runs sync from v30")
    }

    /**
     * The spread's shape (effects plan session 2, formatVersion 33) travels on all three stored effect
     * shapes, each at the non-default `RichProjectFixture` gives it (the byte-for-byte round trip
     * above is what proves the importer keeps it), and a default shape writes no key.
     */
    @Test
    fun `the spread's shape exports on every stored shape and round-trips`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        fun <T> docs(folder: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
            Files.walk(exportDirA.resolve(folder)).use { stream ->
                stream.toList().filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                    .map { canonicalDecode(serializer, Files.readString(it)) }
            }
        data class Shape(val spread: Double, val wings: Int, val segments: Int, val parts: Int)

        val lookFx = docs("looks", uk.me.cormack.lighting7.sync.dto.LookJson.serializer())
            .single { it.name == "warm-pulse" }.fx.single()
        assertEquals(Shape(0.5, 2, 3, 4), Shape(lookFx.spread, lookFx.wings, lookFx.segments, lookFx.parts))
        val paletteFx = docs("palettes", PaletteJson.serializer()).single { it.name == "amber-breathe" }.fx!!
        assertEquals(
            Shape(1.5, 3, 2, 2),
            Shape(paletteFx.spread, paletteFx.wings, paletteFx.segments, paletteFx.parts),
        )
        val adHoc = openPulse(exportDirA)
        assertEquals(Shape(0.25, 2, 4, 3), Shape(adHoc.spread, adHoc.wings, adHoc.segments, adHoc.parts))

        val format = canonicalDecode(
            uk.me.cormack.lighting7.sync.dto.FormatVersionJson.serializer(),
            Files.readString(exportDirA.resolve("formatVersion.json")),
        )
        assertTrue(format.formatVersion >= 33, "the spread's shape syncs from v33")
        assertTrue(SUPPORTED_FORMAT_VERSION >= 33, "and this desk reads it")

        // A default shape writes no key, so a show that shapes nothing exports as before.
        val lookText = Files.walk(exportDirA.resolve("looks")).use { stream ->
            stream.toList().filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }.map { Files.readString(it) }
        }
        assertTrue(lookText.size > 1, "the fixture has more than the one shaped Look")
        assertEquals(1, lookText.count { "\"wings\"" in it }, "only the shaped Look carries the keys")
    }

    /** A stored FX whose spread counts its steps (fx-settings plan D6) exports `spreadUnit`, and only it. */
    @Test
    fun `a STEP spread exports its unit and round-trips`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        val looks = exportDocs(exportDirA, "looks", uk.me.cormack.lighting7.sync.dto.LookJson.serializer())
        val chase = looks.single { it.name == "Steps chase" }.fx.single()
        assertEquals("STEP", chase.spreadUnit)
        assertEquals(2.0, chase.spread)
        assertTrue(looks.filter { it.name != "Steps chase" }.flatMap { it.fx }.all { it.spreadUnit == null }, "a CYCLE spread writes no key")
        val paletteFx = exportDocs(exportDirA, "palettes", PaletteJson.serializer()).single { it.name == "steps-chase" }.fx!!
        assertEquals("STEP" to 1.5, paletteFx.spreadUnit to paletteFx.spread)
        val cueFx = exportDocs(exportDirA, "cueAdHocFx", CueAdHocFxJson.serializer()).single { it.spreadUnit != null }
        assertEquals("STEP" to 3.0, cueFx.spreadUnit to cueFx.spread)

        val format = canonicalDecode(
            uk.me.cormack.lighting7.sync.dto.FormatVersionJson.serializer(),
            Files.readString(exportDirA.resolve("formatVersion.json")),
        )
        assertEquals(43, format.minReader, "a v42 reader would run the chase on the cycle")
        assertTrue(SUPPORTED_FORMAT_VERSION >= 43)

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        val stored = transaction(state.database) {
            uk.me.cormack.lighting7.models.DaoLook.find {
                (uk.me.cormack.lighting7.models.DaoLooks.project eq imported.projectId) and
                    (uk.me.cormack.lighting7.models.DaoLooks.name eq "Steps chase")
            }.single().effects.single().spreadUnit
        }
        assertEquals("STEP", stored)
        val importedUnits = transaction(state.database) {
            val palette = uk.me.cormack.lighting7.models.DaoPalette.find {
                (uk.me.cormack.lighting7.models.DaoPalettes.project eq imported.projectId) and
                    (uk.me.cormack.lighting7.models.DaoPalettes.name eq "steps-chase")
            }.single()
            val paletteUnit = uk.me.cormack.lighting7.models.DaoPaletteFxEntity.find {
                uk.me.cormack.lighting7.models.DaoPaletteFx.palette eq palette.id
            }.single().spreadUnit
            val cueUnits = DaoCueAdHocFxEntity.all().mapNotNull { it.spreadUnit }
            paletteUnit to cueUnits
        }
        assertEquals("STEP" to listOf("STEP"), importedUnits)
    }

    /**
     * A Look's FX in apply order (FX editor plan §3.5): the archive lists them by `sortOrder`, each
     * carrying it, and an import gives them back in that order, not the order they were made in.
     */
    @Test
    fun `a Look's FX order exports and round-trips`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val stacked = Files.walk(exportDirA.resolve("looks")).use { stream ->
            stream.toList().filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                .map { canonicalDecode(LookJson.serializer(), Files.readString(it)) }
        }.single { it.name == "stacked" }
        assertEquals(listOf("Flicker" to 0, "Wave" to 1), stacked.fx.map { it.fxType to it.sortOrder })

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null).projectId
        assertEquals(listOf("Flicker", "Wave"), stackedLookFx(imported))
    }

    /** An archive FX naming no element mode, as every one before v40 might, imports naming none and runs as Each fixture. */
    @Test
    fun `an archive FX with no element mode imports as Each fixture`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        val file = Files.walk(exportDirA.resolve("cueAdHocFx")).use { stream -> stream.toList() }
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
            .single { canonicalDecode(CueAdHocFxJson.serializer(), Files.readString(it)).elementMode == "WHOLE" }
        val fx = canonicalDecode(CueAdHocFxJson.serializer(), Files.readString(file))
        Files.writeString(file, canonicalEncode(CueAdHocFxJson.serializer(), fx.copy(elementMode = null)))
        assertTrue("elementMode" !in Files.readString(file))

        wipeDatabase()
        ProjectImporter(state).import(exportDirA, nameOverride = null)
        val stored = transaction(state.database) {
            DaoCueAdHocFxEntity.find { DaoCueAdHocFx.uuid eq UUID.fromString(fx.uuid) }.single().elementMode
        }
        assertEquals(null, stored)
        assertEquals(ElementMode.PER_FIXTURE, FxSpecCoercion.Lenient.elementMode(stored) { "test" })
    }

    /** An archive whose Look FX carry no order, as every Look did before Record wrote one, reads them in file order. */
    @Test
    fun `Look FX with no order import in file order`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        val file = Files.walk(exportDirA.resolve("looks")).use { stream -> stream.toList() }
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
            .single { canonicalDecode(LookJson.serializer(), Files.readString(it)).name == "stacked" }
        val look = canonicalDecode(LookJson.serializer(), Files.readString(file))
        val unordered = look.fx.reversed().map { it.copy(sortOrder = 0) }
        Files.writeString(file, canonicalEncode(LookJson.serializer(), look.copy(fx = unordered)))
        assertTrue("sortOrder" !in Files.readString(file), "no FX in the file says an order")

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null).projectId
        assertEquals(listOf("Wave", "Flicker"), stackedLookFx(imported))
    }

    /** The "stacked" Look's FX types as a press spawns them: by `sortOrder`, ties in id order. */
    private fun stackedLookFx(projectId: Int): List<String> {
        val uuid = transaction(state.database) {
            DaoLook.find { (DaoLooks.project eq projectId) and (DaoLooks.name eq "stacked") }.single().uuid
        }
        return assertNotNull(loadLookSnapshot(state.database, uuid)).effects.map { it.effect.fxType }
    }

    /**
     * An effect over a selection (effects plan session 3, formatVersion 34) exports with its
     * `selection` target and the ordered list as its key, on both stored shapes that hold a target.
     */
    @Test
    fun `an effect over a selection exports its ordered list and round-trips`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        fun <T> docs(folder: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
            Files.walk(exportDirA.resolve(folder)).use { stream ->
                stream.toList().filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                    .map { canonicalDecode(serializer, Files.readString(it)) }
            }

        val lookFx = docs("looks", uk.me.cormack.lighting7.sync.dto.LookJson.serializer())
            .single { it.name == "Warm Amber" }.fx.single()
        assertEquals("selection", lookFx.targetType)
        assertEquals("""[{"key":"hex-3","type":"fixture"},{"key":"front-wash","type":"group"}]""", lookFx.targetKey)
        val adHoc = docs("cueAdHocFx", uk.me.cormack.lighting7.sync.dto.CueAdHocFxJson.serializer())
            .single { it.targetType == "selection" }
        assertEquals("""[{"key":"front-wash","type":"group"},{"key":"hex-4","type":"fixture"}]""", adHoc.targetKey)

        val format = canonicalDecode(
            uk.me.cormack.lighting7.sync.dto.FormatVersionJson.serializer(),
            Files.readString(exportDirA.resolve("formatVersion.json")),
        )
        assertTrue(format.formatVersion >= 34, "a selection target syncs from v34")
        assertTrue(SUPPORTED_FORMAT_VERSION >= 34, "and this desk reads it")
    }

    /**
     * v35: a cue's speed masters travel embedded in the cue, each master by uuid, and come back as they
     * went; a cue that sets none writes no key, and a clone re-points the uuids at the clone's masters.
     */
    @Test
    fun `a cue's speed masters export embedded in their cue and round-trip`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val masters = Files.list(exportDirA.resolve("speedMasters")).use { stream ->
            stream.toList().map { canonicalDecode(uk.me.cormack.lighting7.sync.dto.SpeedMasterJson.serializer(), Files.readString(it)) }
        }.associate { it.masterIndex to it.uuid }
        val cueTexts = Files.list(exportDirA.resolve("cues")).use { stream -> stream.toList().map { Files.readString(it) } }
        val cue = cueTexts.map { canonicalDecode(CueJson.serializer(), it) }.single { it.name == "open" }
        assertEquals(listOf(masters.getValue(1) to 132.0, masters.getValue(2) to 96.5), cue.speedMasters.map { it.speedMasterUuid to it.bpm })
        assertEquals(1, cueTexts.count { "\"speedMasters\"" in it }, "a cue that sets none writes no key")
        val format = canonicalDecode(
            uk.me.cormack.lighting7.sync.dto.FormatVersionJson.serializer(),
            Files.readString(exportDirA.resolve("formatVersion.json")),
        )
        assertTrue(format.formatVersion >= 35, "a cue's speed masters sync from v35")
        assertTrue(SUPPORTED_FORMAT_VERSION >= 35, "and this desk reads them")

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val back = Files.list(exportDirB.resolve("cues")).use { stream ->
            stream.toList().map { canonicalDecode(CueJson.serializer(), Files.readString(it)) }
        }.single { it.name == "open" }
        assertEquals(cue.speedMasters, back.speedMasters)

        val clone = uk.me.cormack.lighting7.sync.ProjectCloner(state).clone(imported.projectId, "Clone", null)
        transaction(state.database) {
            val project = uk.me.cormack.lighting7.models.DaoProject[clone.projectId]
            val cloneMasters = uk.me.cormack.lighting7.models.speedMastersOf(project).associate { it.masterIndex to it.uuid.toString() }
            val copied = uk.me.cormack.lighting7.models.DaoCue.find {
                (uk.me.cormack.lighting7.models.DaoCues.project eq project.id) and (uk.me.cormack.lighting7.models.DaoCues.name eq "open")
            }.single()
            assertEquals(listOf(cloneMasters.getValue(1), cloneMasters.getValue(2)), copied.speedMasters.map { it.speedMasterUuid })
        }
    }

    @Test
    fun `a stack's Move in dark exports on its stack and round-trips, and a stack left off writes nothing`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val texts = Files.list(exportDirA.resolve("cueStacks")).use { stream -> stream.toList().map { Files.readString(it) } }
        val stacks = texts.map { canonicalDecode(CueStackJson.serializer(), it) }.associateBy { it.name }
        val moved = stacks.getValue("show-2")
        assertEquals(true, moved.moveInDark)
        assertEquals("LATE", moved.moveInDarkWhen)
        assertEquals(2500L, moved.moveInDarkDelayMs)
        assertEquals(500L, moved.moveInDarkTimeMs)
        assertEquals(listOf("POSITION"), moved.moveInDarkFamilies)
        assertEquals(1, texts.count { "moveInDark" in it }, "a stack left off at the defaults writes no key")
        val format = canonicalDecode(
            uk.me.cormack.lighting7.sync.dto.FormatVersionJson.serializer(),
            Files.readString(exportDirA.resolve("formatVersion.json")),
        )
        assertEquals(45, format.formatVersion)
        assertEquals(45, SUPPORTED_FORMAT_VERSION)

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        transaction(state.database) {
            val byName = uk.me.cormack.lighting7.models.DaoCueStack.find {
                uk.me.cormack.lighting7.models.DaoCueStacks.project eq imported.projectId
            }.associateBy { it.name }
            val settings = byName.getValue("show-2").moveInDarkSettings
            assertEquals(
                uk.me.cormack.lighting7.models.MoveInDarkSettings(
                    true, uk.me.cormack.lighting7.models.MoveInDarkWhen.LATE,
                    java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(500),
                    setOf(uk.me.cormack.lighting7.fx.AttributeFamily.POSITION),
                ),
                settings,
            )
            assertEquals(uk.me.cormack.lighting7.models.MoveInDarkSettings.OFF, byName.getValue("show-1").moveInDarkSettings)
        }

        val clone = uk.me.cormack.lighting7.sync.ProjectCloner(state).clone(imported.projectId, "Clone", null)
        transaction(state.database) {
            val copied = uk.me.cormack.lighting7.models.DaoCueStack.find {
                (uk.me.cormack.lighting7.models.DaoCueStacks.project eq clone.projectId) and
                    (uk.me.cormack.lighting7.models.DaoCueStacks.name eq "show-2")
            }.single()
            assertEquals(true, copied.moveInDarkSettings.on, "a clone keeps what is stored")
        }
    }

    /** An archive written before v45 carries no Move in dark: its stacks import off. */
    @Test
    fun `a stack from an older archive imports with Move in dark off`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        Files.list(exportDirA.resolve("cueStacks")).use { stream ->
            stream.toList().forEach { file ->
                val json = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(file)) as kotlinx.serialization.json.JsonObject
                Files.writeString(file, kotlinx.serialization.json.JsonObject(json.filterKeys { !it.startsWith("moveInDark") }).toString())
            }
        }
        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        transaction(state.database) {
            val stacks = uk.me.cormack.lighting7.models.DaoCueStack.find {
                uk.me.cormack.lighting7.models.DaoCueStacks.project eq imported.projectId
            }.toList()
            assertTrue(stacks.isNotEmpty())
            assertTrue(stacks.all { it.moveInDarkSettings == uk.me.cormack.lighting7.models.MoveInDarkSettings.OFF })
        }
    }

    /**
     * A paired dimmer's other lanterns travel inside their patch's document, in order, with the
     * rigging named by uuid. The byte-for-byte test proves the importer keeps what the exporter
     * writes; this pins what the exporter writes — and that a patch with none carries no key.
     */
    @Test
    fun `extra placements export embedded in their patch in order`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val docs = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { Files.readString(it) }
        }
        val patches = docs.map { canonicalDecode(FixturePatchJson.serializer(), it) }
        val hex2 = patches.single { it.key == "hex-2" }
        assertEquals(listOf("SR", null), hex2.extraPlacements.map { it.label })
        val sr = hex2.extraPlacements.first()
        assertEquals(3.5, sr.stageX)
        assertEquals(180.0, sr.baseYawDeg)
        assertEquals(-15.0, sr.basePitchDeg)
        val foh = Files.list(exportDirA.resolve("riggings")).use { stream ->
            stream.toList().map { canonicalDecode(RiggingJson.serializer(), Files.readString(it)) }
        }.single { it.name == "FOH Truss" }
        assertEquals(foh.uuid, sr.riggingUuid, "a placement names its rigging by uuid")
        assertEquals(null, hex2.extraPlacements[1].riggingUuid)

        val paired = setOf("hex-2", "ring-1", "adv2-1", "rev-1")
        val unpaired = docs.filter { doc -> paired.none { doc.contains("\"$it\"") } }
        assertEquals(patches.size - paired.size, unpaired.size)
        assertTrue(
            unpaired.none { it.contains("\"extraPlacements\"") },
            "a patch with no extra placements must not carry the key at all",
        )
    }

    /**
     * v16: a head number travels on its patch, and an unnumbered patch carries no key — so an
     * export of a rig with no numbers is v15's byte for byte.
     */
    @Test
    fun `a patch exports its head number, and an unnumbered one carries no key`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        fun heads(dir: Path): Map<String, Int?> =
            Files.list(dir.resolve("fixturePatches")).use { stream ->
                stream.toList().map { canonicalDecode(FixturePatchJson.serializer(), Files.readString(it)) }
            }.associate { it.key to it.headNumber }
        val exported = heads(exportDirA)
        assertEquals(mapOf("hex-1" to 104, "hex-2" to 103, "hex-3" to 102, "hex-4" to null), exported.filterKeys { it.startsWith("hex-") })
        val unnumbered = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { Files.readString(it) }
        }.filter { it.contains("\"hex-4\"") }
        assertFalse(unnumbered.single().contains("\"headNumber\""), "an unnumbered patch must not carry the key at all")

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        assertEquals(exported, heads(exportDirB), "the importer keeps every head number")
    }

    /**
     * v15: a lightstrip's installed length travels on its patch, and a segment's own length on its
     * placement. A patch without one carries no key, so a length-less export is v14's byte for byte.
     */
    @Test
    fun `a variable-length fixture exports its length and its segments' lengths`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val docs = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { Files.readString(it) }
        }
        val ring = docs.map { canonicalDecode(FixturePatchJson.serializer(), it) }.single { it.key == "ring-1" }
        assertEquals(8.5, ring.lengthM)
        assertEquals(12.25, ring.extraPlacements.single().lengthM)
        assertTrue(
            docs.filter { !it.contains("\"ring-1\"") }.none { it.contains("\"lengthM\"") },
            "a patch with no length must not carry the key at all",
        )

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val back = Files.list(exportDirB.resolve("fixturePatches")).use { stream ->
            stream.toList().map { canonicalDecode(FixturePatchJson.serializer(), Files.readString(it)) }
        }.single { it.key == "ring-1" }
        assertEquals(8.5, back.lengthM, "the importer keeps the patch's length")
        assertEquals(12.25, back.extraPlacements.single().lengthM, "and the segment's")
    }

    /** v17: a body's roll, on the patch and on a placement, and absent where it is unset. */
    @Test
    fun `a rolled fixture exports its roll and its segments' rolls`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val docs = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { Files.readString(it) }
        }
        val ring = docs.map { canonicalDecode(FixturePatchJson.serializer(), it) }.single { it.key == "ring-1" }
        assertEquals(-12.5, ring.baseRollDeg)
        assertEquals(90.0, ring.extraPlacements.single().baseRollDeg)
        assertTrue(
            docs.filter { !it.contains("\"ring-1\"") }.none { it.contains("\"baseRollDeg\"") },
            "a patch with no roll must not carry the key at all",
        )

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val back = Files.list(exportDirB.resolve("fixturePatches")).use { stream ->
            stream.toList().map { canonicalDecode(FixturePatchJson.serializer(), Files.readString(it)) }
        }.single { it.key == "ring-1" }
        assertEquals(-12.5, back.baseRollDeg, "the importer keeps the patch's roll")
        assertEquals(90.0, back.extraPlacements.single().baseRollDeg, "and the segment's")
    }

    /**
     * v19: a lantern and its focus, on the patch and on a placement, and absent where unset — a
     * patch with no lantern carries none of the keys, so a lantern-less export is v18's byte for byte.
     */
    @Test
    fun `a lantern and its focus export on the patch and its placement`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val docs = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { Files.readString(it) }
        }
        val adv2 = docs.map { canonicalDecode(FixturePatchJson.serializer(), it) }.single { it.key == "adv2-1" }
        assertEquals("s4-zoom-25-50", adv2.lanternType)
        assertEquals(31.5, adv2.zoomDeg)
        assertEquals(listOf(0.28, 0.05, 0.35, 0.0), adv2.shutters!!.map { it.depth })
        assertEquals(-12.5, adv2.shutters[2].angleDeg)
        assertEquals(7.5, adv2.gateRotationDeg)
        assertEquals(0.8, adv2.iris)
        assertEquals(0.25, adv2.focusSoftness)
        assertEquals(750, adv2.lampWatts)
        assertEquals(3000, adv2.lampKelvin)
        val sr = adv2.extraPlacements.single()
        assertEquals("par64-cp62", sr.lanternType)
        assertEquals(45.0, sr.lampRotationDeg)
        assertEquals(0.9, sr.focusSoftness)
        for (key in listOf(
            "lanternType", "zoomDeg", "lampRotationDeg", "shutters", "gateRotationDeg", "iris", "focusSoftness",
            "lampWatts", "lampKelvin",
        )) {
            assertTrue(
                docs.filter { !it.contains("\"adv2-1\"") }.none { it.contains("\"$key\"") },
                "a patch with no lantern must not carry $key",
            )
        }

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val back = Files.list(exportDirB.resolve("fixturePatches")).use { stream ->
            stream.toList().map { canonicalDecode(FixturePatchJson.serializer(), Files.readString(it)) }
        }.single { it.key == "adv2-1" }
        assertEquals(adv2, back, "the importer keeps the lantern and every focus field, on the patch and the placement")
    }

    /**
     * v31: a stored locate on the patch, and absent where unset — a patch with none carries no key, so
     * an export of a rig with no stored locate is v29's byte for byte.
     */
    @Test
    fun `a stored locate exports on the patch and imports back`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val docs = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { Files.readString(it) }
        }
        val rev = docs.map { canonicalDecode(FixturePatchJson.serializer(), it) }.single { it.key == "rev-1" }
        assertEquals(listOf(UnitLocate(head = null, pan = 140, tilt = 96, panFine = 12, tiltFine = 200)), rev.locate)
        assertTrue(docs.filter { !it.contains("\"rev-1\"") }.none { it.contains("\"locate\"") }, "a patch with none carries no key")
        assertTrue(SUPPORTED_FORMAT_VERSION >= 31, "stored locate travels from v31")

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val back = Files.list(exportDirB.resolve("fixturePatches")).use { stream ->
            stream.toList().map { canonicalDecode(FixturePatchJson.serializer(), Files.readString(it)) }
        }.single { it.key == "rev-1" }
        assertEquals(rev.locate, back.locate, "the importer keeps the stored locate")
    }

    /**
     * v22: fitted media, on the patch and on a placement, and absent where unset — a patch with
     * nothing fitted carries no key, so an export of a rig with nothing fitted is v21's byte for byte.
     */
    @Test
    fun `fitted media exports on the patch and its placement`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val docs = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { Files.readString(it) }
        }
        val rev = docs.map { canonicalDecode(FixturePatchJson.serializer(), it) }.single { it.key == "rev-1" }
        val media = assertNotNull(rev.media)
        assertEquals(FittedSlot(gel = "R26"), media.slot("gelScroller", "L201_FULL_CT_BLUE"))
        assertEquals(FittedSlot(), media.slot("gelScroller", "R02_BASTARD_AMBER"), "an empty frame travels as fitted")
        assertEquals(FittedSlot(gobo = "breakup"), media.slot("fbWheelPos", "SLOT_1"))
        val sl = rev.extraPlacements.single()
        assertEquals(FittedSlot(gel = "R80"), sl.media?.slot("gelScroller", "L201_FULL_CT_BLUE"))
        assertEquals(FittedSlot(gel = "L202"), sl.media?.slot("mediaFrame", "IN"))
        assertTrue(
            docs.filter { !it.contains("\"rev-1\"") }.none { it.contains("\"media\"") },
            "a patch with nothing fitted must not carry media",
        )

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val back = Files.list(exportDirB.resolve("fixturePatches")).use { stream ->
            stream.toList().map { canonicalDecode(FixturePatchJson.serializer(), Files.readString(it)) }
        }.single { it.key == "rev-1" }
        assertEquals(rev, back, "the importer keeps the fitted media, on the patch and the placement")
    }

    /**
     * v10: a busk page travels as one document with columns, banks and pads nested, and every
     * structural field written even at zero. The byte-for-byte test proves the importer keeps what
     * the exporter writes; this pins what the exporter writes.
     */
    @Test
    fun `busk pages export nested with every position written`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val docs = Files.list(exportDirA.resolve("buskPages")).use { stream -> stream.toList().map { Files.readString(it) } }
        val doc = docs.single()
        val page = canonicalDecode(BuskPageJson.serializer(), doc)
        assertEquals("act-one", page.name)
        assertEquals(2, page.sortOrder, "the off-default position must survive the export")
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0), page.columns.map { it.row to it.sortOrder })
        assertTrue(doc.contains("\"row\": 0"), "a zero position is written, not omitted")
        assertTrue(doc.contains("\"solo\": false"), "a false solo is written, not omitted")
        val banks = page.columns.flatMap { it.banks }
        assertEquals(listOf("keys", "moves", "cues", "fx"), banks.map { it.name })
        assertEquals(listOf("WRAP", "COLUMN", "WRAP", "WRAP"), banks.map { it.flow })
        assertEquals(8, banks.sumOf { it.pads.size })
        val keys = banks.first()
        assertTrue(keys.solo)
        assertEquals(listOf("paletteUuid", "lookUuid", "cueUuid"), keys.pads.map { pad ->
            listOfNotNull(pad.paletteUuid?.let { "paletteUuid" }, pad.lookUuid?.let { "lookUuid" }, pad.cueUuid?.let { "cueUuid" }).single()
        })

        val slots = Files.list(exportDirA.resolve("cueSlots")).use { stream ->
            stream.toList().map { canonicalDecode(CueSlotJson.serializer(), Files.readString(it)) }
        }
        assertEquals(1, slots.count { it.lookUuid != null }, "the Look slot travels")
    }

    /**
     * v12: the rig travels as one document per row with its tiles nested, naming groups and patches
     * by uuid and a cell by its key, every structural field written. The byte-for-byte test proves
     * the importer keeps what the exporter writes; this pins what the exporter writes.
     */
    @Test
    fun `the busk rig exports one document per row with every tile field written`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val rows = Files.list(exportDirA.resolve("buskRig")).use { stream ->
            stream.toList().map { canonicalDecode(BuskRigRowJson.serializer(), Files.readString(it)) }
        }.sortedBy { it.sortOrder }
        assertEquals(listOf("Bars", "Wash"), rows.map { it.name })
        assertEquals(listOf(0, 1), rows.map { it.sortOrder })
        val bars = rows[0]
        assertEquals("WRAP" to 6, bars.flow to bars.width, "the row's layout travels")
        assertEquals("SCROLL" to 12, rows[1].flow to rows[1].width, "a row with no layout of its own is the defaults")
        assertEquals(listOf("HALVES", "PER_CELL", "WHOLE"), bars.tiles.map { it.cellMode })
        assertEquals(listOf(3, null, null), bars.tiles.map { it.cellSplit })
        assertEquals(listOf(null, null, "bar-1.pixel-3"), bars.tiles.map { it.elementKey })
        assertEquals(listOf(null, null, "Pixel 4"), bars.tiles.map { it.label })
        assertTrue(bars.tiles.all { it.patchUuid != null && it.groupUuid == null })
        val wash = rows[1]
        assertEquals("Front", wash.tiles[0].label)
        assertTrue(wash.tiles[0].groupUuid != null && wash.tiles[0].patchUuid == null, "a group tile names its group")
        assertEquals("WHOLE", wash.tiles[1].cellMode)
        val text = Files.readString(Files.list(exportDirA.resolve("buskRig")).use { it.findFirst().get() })
        assertTrue(text.contains("\"sortOrder\": 0"), "a zero position is written, not omitted")
    }

    /**
     * The scene document (v18): each element's params export as a **nested object**, keys sorted,
     * so the clone remapper reaches a platform's `regionUuid` and a diff reads per field; a seat
     * view names its seating by uuid. Pins what the exporter writes, as the rig test above does.
     */
    @Test
    fun `the scene exports its elements with params nested and a seat view naming its seating`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val elements = Files.list(exportDirA.resolve("stageElements")).use { stream ->
            stream.toList().map { canonicalDecode(StageElementJson.serializer(), Files.readString(it)) }
        }.associateBy { it.name }
        assertEquals(setOf("Stalls", "Thrust deck", "House tabs", "Day/night cloth", "Cottage flat", "Garden arch"), elements.keys)
        // A MODEL object's parts travel inside its params, as every kind's params do: no version bump.
        val arch = elements.getValue("Garden arch").params!!
        assertEquals("MODEL", arch["shape"]!!.jsonPrimitive.content)
        assertEquals(4, arch["parts"]!!.jsonArray.size)
        assertEquals("TILE", arch["parts"]!!.jsonArray[0].jsonObject["texture"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        val stalls = elements.getValue("Stalls")
        assertEquals("SEATING" to "VENUE", stalls.kind to stalls.layer)
        assertEquals("B", stalls.params!!["firstRow"]!!.jsonPrimitive.content)
        assertTrue(stalls.hidden && stalls.emissive)
        val tabs = elements.getValue("House tabs")
        assertEquals(1.0, tabs.params!!["states"]!!.jsonObject["open"]!!.jsonPrimitive.double)
        // An optional param (scenery-programmer plan P2): carried in the nested params, no version bump.
        assertEquals(4.5, tabs.params["travelS"]!!.jsonPrimitive.double)
        assertEquals("STAGE_LEFT", tabs.params["drawFrom"]!!.jsonPrimitive.content)

        val regions = Files.list(exportDirA.resolve("stageRegions")).use { stream ->
            stream.toList().map { canonicalDecode(StageRegionJson.serializer(), Files.readString(it)) }
        }
        val thrust = regions.single { it.name == "thrust" }
        assertEquals(thrust.uuid, elements.getValue("Thrust deck").params!!["regionUuid"]!!.jsonPrimitive.content)

        val text = Files.readString(exportDirA.resolve("stageElements/${stalls.uuid}.json"))
        assertTrue(text.contains("\"params\": {"), "params travel as an object, not a string: $text")
        assertTrue(text.indexOf("\"firstRow\"") < text.indexOf("\"rows\""), "params keys are sorted")

        val views = Files.list(exportDirA.resolve("stageViewpoints")).use { stream ->
            stream.toList().map { canonicalDecode(StageViewpointJson.serializer(), Files.readString(it)) }
        }.associateBy { it.name }
        val seat = views.getValue("Row F centre")
        assertEquals("SEAT", seat.kind)
        assertEquals(stalls.uuid to "F5", seat.seatElementUuid to seat.seatId)
        assertEquals(null, seat.eyeX, "a seat view carries no eye of its own")
        assertEquals(1.25, views.getValue("Balcony desk").eyeX)
    }

    /**
     * v20: scenery travels embedded in its owner — a cue, a stack, a Look — each change naming its
     * element by uuid with its states as an object, and only a cue's carrying a transition.
     */
    @Test
    fun `scenery exports embedded in its cue, stack and Look, the element by uuid`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val elements = Files.list(exportDirA.resolve("stageElements")).use { stream ->
            stream.toList().map { canonicalDecode(StageElementJson.serializer(), Files.readString(it)) }
        }.associate { it.name to it.uuid }
        val cue = Files.list(exportDirA.resolve("cues")).use { stream ->
            stream.toList().map { canonicalDecode(CueJson.serializer(), Files.readString(it)) }
        }.single { it.name == "open" }
        assertEquals(listOf(elements["House tabs"], elements["Thrust deck"]), cue.scenery.map { it.elementUuid })
        assertEquals(listOf(4000L, 1500L), cue.scenery.map { it.transitionMs })
        assertEquals(0.0, cue.scenery[0].state["open"]!!.jsonPrimitive.double)
        assertEquals(listOf(0, 1), cue.scenery.map { it.sortOrder })

        val stack = Files.list(exportDirA.resolve("cueStacks")).use { stream ->
            stream.toList().map { canonicalDecode(CueStackJson.serializer(), Files.readString(it)) }
        }.single { it.name == "show-1" }
        assertEquals(listOf(elements["Stalls"], elements["House tabs"]), stack.scenery.map { it.elementUuid })
        assertTrue(stack.scenery.all { it.transitionMs == null }, "a stack's set has no clock")

        val look = Files.list(exportDirA.resolve("looks")).use { stream ->
            stream.toList().map { canonicalDecode(LookJson.serializer(), Files.readString(it)) }
        }.single { it.name == "Warm Amber" }
        assertEquals(0.75, look.scenery.single { it.elementUuid == elements["House tabs"] }.state["open"]!!.jsonPrimitive.double)

        val text = Files.readString(exportDirA.resolve("cues/${cue.uuid}.json"))
        assertTrue(text.contains("\"state\": {"), "states travel as an object, not a string: $text")
    }

    /**
     * A scenery change naming an element the archive does not carry has lost its element, which
     * its delete would have swept on the writing desk: the pull drops it and keeps the rest.
     */
    @Test
    fun `a scenery change naming an element the archive lacks is dropped and its cue survives`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val tabs = Files.list(exportDirA.resolve("stageElements")).use { stream ->
            stream.toList().first { Files.readString(it).contains("\"name\": \"House tabs\"") }
        }
        Files.delete(tabs)

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val cue = Files.list(exportDirB.resolve("cues")).use { stream ->
            stream.toList().map { canonicalDecode(CueJson.serializer(), Files.readString(it)) }
        }.single { it.name == "open" }
        assertEquals(1, cue.scenery.size, "the tabs' change went, the deck's stayed")
        assertEquals(1500L, cue.scenery.single().transitionMs)
    }

    /**
     * An owner says one thing about each element, and the tables hold it to that with a unique
     * index. An archive that says it twice — a hand edit, a merge that kept both sides — keeps the
     * first by order rather than failing the whole import.
     */
    @Test
    fun `a scenery change naming its element a second time is dropped and the first kept`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val cueFile = Files.list(exportDirA.resolve("cues")).use { stream ->
            stream.toList().first { canonicalDecode(CueJson.serializer(), Files.readString(it)).name == "open" }
        }
        val written = canonicalDecode(CueJson.serializer(), Files.readString(cueFile))
        val first = written.scenery.first()
        val again = first.copy(uuid = java.util.UUID.randomUUID().toString(), transitionMs = 9000, sortOrder = written.scenery.size)
        Files.writeString(cueFile, canonicalEncode(CueJson.serializer(), written.copy(scenery = written.scenery + again)))

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val cue = Files.list(exportDirB.resolve("cues")).use { stream ->
            stream.toList().map { canonicalDecode(CueJson.serializer(), Files.readString(it)) }
        }.single { it.name == "open" }
        assertEquals(written.scenery.map { it.elementUuid to it.transitionMs }, cue.scenery.map { it.elementUuid to it.transitionMs })
    }

    /**
     * v21: a cue's events travel embedded in the cue, the patch by uuid, and come back as they went;
     * a spent tube is this machine's and travels nowhere.
     */
    @Test
    fun `cue events export embedded in their cue and round-trip, and tube state stays home`() {
        val projectId = seedRichProject(state)
        transaction(state.database) {
            val cannon = uk.me.cormack.lighting7.models.DaoFixturePatch.find {
                uk.me.cormack.lighting7.models.DaoFixturePatches.key eq "cannon-1"
            }.single()
            uk.me.cormack.lighting7.models.DaoEffectTubeStates.insert {
                it[patchUuid] = cannon.uuid
                it[trigger] = "output1"
                it[spentAt] = uk.me.cormack.lighting7.models.nowUtc()
            }
        }
        ProjectExporter(state).export(projectId, exportDirA)

        val cannonUuid = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { canonicalDecode(FixturePatchJson.serializer(), Files.readString(it)) }
        }.single { it.key == "cannon-1" }.uuid
        val cue = Files.list(exportDirA.resolve("cues")).use { stream ->
            stream.toList().map { canonicalDecode(CueJson.serializer(), Files.readString(it)) }
        }.single { it.name == "open" }
        assertEquals(listOf("output1" to 600L, "output2" to 750L), cue.events.map { it.trigger to it.offsetMs })
        assertTrue(cue.events.all { it.patchUuid == cannonUuid })
        assertEquals(listOf(0, 1), cue.events.map { it.sortOrder })
        // Binary-aware: the scene images (v23) are bytes, read as a digest.
        val everything = readExportFiles(exportDirA).values.joinToString("\n")
        assertTrue("spentAt" !in everything && "spent_at" !in everything, "tube state never travels")

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val back = Files.list(exportDirB.resolve("cues")).use { stream ->
            stream.toList().map { canonicalDecode(CueJson.serializer(), Files.readString(it)) }
        }.single { it.name == "open" }
        assertEquals(cue.events, back.events)
    }

    /**
     * An event naming a patch the archive lacks, a trigger its type does not have, or a tube a second
     * time is dropped with the cue kept; and a pre-v21 archive's row naming a trigger is stripped.
     */
    @Test
    fun `an event that no longer makes sense is dropped, and a stored trigger row is stripped on import`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val cueFile = Files.list(exportDirA.resolve("cues")).use { stream ->
            stream.toList().first { canonicalDecode(CueJson.serializer(), Files.readString(it)).name == "open" }
        }
        val written = canonicalDecode(CueJson.serializer(), Files.readString(cueFile))
        val first = written.events.first()
        val bogus = listOf(
            first.copy(uuid = java.util.UUID.randomUUID().toString(), sortOrder = 5),
            first.copy(uuid = java.util.UUID.randomUUID().toString(), trigger = "output9", sortOrder = 6),
            first.copy(uuid = java.util.UUID.randomUUID().toString(), patchUuid = java.util.UUID.randomUUID().toString(), sortOrder = 7),
        )
        Files.writeString(cueFile, canonicalEncode(CueJson.serializer(), written.copy(events = written.events + bogus)))
        // A pre-v21 cue row raising a tube, as the Twin Shot's sliders once allowed.
        val rowDir = exportDirA.resolve("cueAttributeAssignments")
        Files.createDirectories(rowDir)
        val raised = CueAttributeAssignmentJson(
            uuid = java.util.UUID.randomUUID().toString(), cueUuid = written.uuid,
            targetType = "fixture", targetKey = "cannon-1", attribute = "output1", value = "255",
        )
        Files.writeString(rowDir.resolve("${raised.uuid}.json"), canonicalEncode(CueAttributeAssignmentJson.serializer(), raised))

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val back = Files.list(exportDirB.resolve("cues")).use { stream ->
            stream.toList().map { canonicalDecode(CueJson.serializer(), Files.readString(it)) }
        }.single { it.name == "open" }
        assertEquals(written.events.map { it.trigger }, back.events.map { it.trigger })
        val rows = Files.list(exportDirB.resolve("cueAttributeAssignments")).use { stream ->
            stream.toList().map { canonicalDecode(CueAttributeAssignmentJson.serializer(), Files.readString(it)) }
        }
        assertTrue(rows.none { it.attribute == "output1" }, "the raised tube was stripped")
        assertTrue(rows.isNotEmpty(), "the cue's other rows came through")
    }

    /**
     * An archive written before v18 has neither folder: it imports with an empty scene.
     */
    @Test
    fun `an archive without a scene imports with an empty scene`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        exportDirA.resolve("stageElements").toFile().deleteRecursively()
        exportDirA.resolve("stageViewpoints").toFile().deleteRecursively()
        wipeDatabase()

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        transaction(state.database) {
            val project = DaoProject.findById(imported.projectId)!!
            assertTrue(project.stageElements.empty() && project.stageViewpoints.empty())
            assertEquals(2, project.stageRegions.count().toInt(), "the rest of the stage imports")
        }
    }

    /**
     * The importer's wipe order (busk-further plan §3.2): a rig tile is a plain FK onto a group or a
     * patch with no cascade, so replacing a project must sweep the rig **before** it deletes the
     * groups and patches — otherwise the FK blocks them. Proved by replacing a project that holds
     * a rig with an archive that also holds one: the replace succeeds and the rig is the archive's.
     */
    @Test
    fun `replacing a project sweeps its rig before its groups and patches, then imports the archive's`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        ProjectImporter(state).replaceFromWorkingTree(projectId, exportDirA)
        ProjectExporter(state).export(projectId, exportDirB)
        assertExportsEqual(exportDirA, exportDirB, installsShapeOnly = true)
        val rows = Files.list(exportDirB.resolve("buskRig")).use { it.toList() }
        assertEquals(2, rows.size, "the rig came back from the archive, once")
    }

    /** A v11 archive has no `buskRig/`, and imports as an empty rig — today's band (D1). */
    @Test
    fun `an archive without a rig folder imports as an empty rig`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()
        exportDirA.resolve("buskRig").toFile().deleteRecursively()
        Files.writeString(
            exportDirA.resolve("formatVersion.json"),
            Files.readString(exportDirA.resolve("formatVersion.json")).replace("\"formatVersion\": $SUPPORTED_FORMAT_VERSION", "\"formatVersion\": 11"),
        )

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        assertTrue(!Files.exists(exportDirB.resolve("buskRig")) || Files.list(exportDirB.resolve("buskRig")).use { it.count() } == 0L)
    }

    /**
     * A tile naming a group or patch the archive does not carry is an enrichment that has lost its
     * record, so the pull continues without it — the pad's posture — and a row left empty goes too.
     */
    @Test
    fun `a rig tile naming a record the archive lacks is dropped and the rig survives`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val washFile = Files.list(exportDirA.resolve("buskRig")).use { stream ->
            stream.toList().first { Files.readString(it).contains("\"name\": \"Wash\"") }
        }
        val original = Files.readString(washFile)
        val corrupt = original.replaceFirst(
            Regex("\"groupUuid\": \"[0-9a-f-]+\""),
            "\"groupUuid\": \"00000000-0000-0000-0000-000000000000\"",
        )
        assertTrue(corrupt != original, "test sanity: the group tile was rewritten")
        Files.writeString(washFile, corrupt)

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val rows = Files.list(exportDirB.resolve("buskRig")).use { stream ->
            stream.toList().map { canonicalDecode(BuskRigRowJson.serializer(), Files.readString(it)) }
        }
        val wash = rows.single { it.name == "Wash" }
        assertEquals(1, wash.tiles.size, "one tile fewer, nothing else lost")
        assertEquals(3, rows.single { it.name == "Bars" }.tiles.size)
    }

    /**
     * A pad naming a record the archive does not carry is an enrichment that has lost its record,
     * so the pull continues without it — the v9 template-group posture, and the opposite of the cue
     * stack case below.
     */
    @Test
    fun `a busk pad naming a record the archive lacks is dropped and the page survives`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val pageFile = Files.list(exportDirA.resolve("buskPages")).use { it.findFirst().get() }
        val original = Files.readString(pageFile)
        val corrupt = original.replaceFirst(
            Regex("\"lookUuid\": \"[0-9a-f-]+\""),
            "\"lookUuid\": \"00000000-0000-0000-0000-000000000000\"",
        )
        assertTrue(corrupt != original, "test sanity: a Look pad was rewritten")
        Files.writeString(pageFile, corrupt)

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)

        val page = Files.list(exportDirB.resolve("buskPages")).use { stream ->
            canonicalDecode(BuskPageJson.serializer(), Files.readString(stream.findFirst().get()))
        }
        val banks = page.columns.flatMap { it.banks }
        assertEquals(7, banks.sumOf { it.pads.size }, "one pad fewer, nothing else lost")
        assertEquals(listOf("keys", "moves", "cues", "fx"), banks.map { it.name })
        assertEquals(0, banks.first { it.name == "keys" }.pads.count { it.lookUuid != null }, "the dropped pad was the keys bank's Look")
        assertEquals(2, banks.first { it.name == "keys" }.pads.size)
    }

    /**
     * A v10 archive written *before* the cue-stack arm was removed carries slots whose only
     * reference is `cueStackUuid` — an unknown key now, so the slot decodes naming nothing. That
     * one case warns and drops rather than failing the pull: a slot, like a busk pad, is an
     * enrichment of the record it names. Two arms still aborts, which the assign route's own test
     * pins on the write side.
     */
    @Test
    fun `a cue slot naming nothing is dropped and the rest of the archive imports`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        // Stand in for the old shape: strip the one arm this slot has and put the retired key back.
        val slotFiles = Files.list(exportDirA.resolve("cueSlots")).use { it.toList() }
        val lookSlot = slotFiles.single { Files.readString(it).contains("lookUuid") }
        val rewritten = Files.readString(lookSlot).replaceFirst(
            Regex("\"lookUuid\": \"[0-9a-f-]+\""),
            "\"cueStackUuid\": \"00000000-0000-0000-0000-000000000000\"",
        )
        assertTrue(rewritten.contains("cueStackUuid"), "test sanity: the slot was rewritten")
        Files.writeString(lookSlot, rewritten)

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)

        val slots = Files.list(exportDirB.resolve("cueSlots")).use { stream ->
            stream.toList().map { canonicalDecode(CueSlotJson.serializer(), Files.readString(it)) }
        }
        assertEquals(slotFiles.size - 1, slots.size, "one slot fewer, nothing else lost")
        assertEquals(0, slots.count { it.lookUuid != null }, "the dropped slot was the Look's")
        assertTrue(slots.all { it.cueUuid != null }, "every surviving slot still names a cue")
    }

    @Test
    fun `import refuses duplicate project UUID`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        // Project still exists in the DB; importing the same export must refuse.
        val ex = assertFailsWith<ImportError> {
            ProjectImporter(state).import(exportDirA, nameOverride = "different-name")
        }
        assertEquals(io.ktor.http.HttpStatusCode.Conflict, ex.status)
        assertTrue(ex.message?.contains("UUID") == true)
    }

    @Test
    fun `import refuses on name collision`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()
        // Re-create a project with the same name but a different UUID — name collision.
        transaction(state.database) {
            DaoProject.new {
                name = RICH_PROJECT_NAME
                description = "blocker"
                isCurrent = false
            }
        }
        val ex = assertFailsWith<ImportError> {
            ProjectImporter(state).import(exportDirA, nameOverride = null)
        }
        assertEquals(io.ktor.http.HttpStatusCode.Conflict, ex.status)
    }

    @Test
    fun `installs json contains the local install identity on export`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val installsFile = exportDirA.resolve("installs.json")
        val installs = canonicalDecode(InstallsJson.serializer(), Files.readString(installsFile))
        val (localUuid, localFriendlyName) = transaction(state.database) {
            val row = DaoInstall.all().first()
            row.uuid.toString() to row.friendlyName
        }
        assertEquals(1, installs.installs.size, "exactly one local install entry expected")
        assertEquals(localFriendlyName, installs.installs[localUuid])
    }

    @Test
    fun `universe config exporter strips machine-local address field`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val universeDir = exportDirA.resolve("universeConfigs")
        val first = Files.list(universeDir).use { it.findFirst().get() }
        val text = Files.readString(first)
        // address is the machine-local IP per docs/plans/cloud-sync.md — must never be in JSON.
        assertFalse(text.contains("\"address\""), "address field leaked into export: $text")
        // Round-trips without trouble — the DTO doesn't have an address field at all.
        canonicalDecode(UniverseConfigJson.serializer(), text)
    }

    @Test
    fun `import forces isCurrent false even if source project was current`() {
        // The seeded project is current. Export it, wipe, import — imported project must NOT
        // be marked current; otherwise importing would silently reassign which project the user
        // is operating on.
        val projectId = seedRichProject(state)
        transaction(state.database) {
            DaoProject.findById(projectId)!!.isCurrent = true
        }
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        transaction(state.database) {
            assertEquals(false, DaoProject.findById(imported.projectId)!!.isCurrent)
        }
    }

    @Test
    fun `import applies description override`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val imported = ProjectImporter(state).import(
            exportDirA,
            nameOverride = "described",
            descriptionOverride = "a new description",
        )
        transaction(state.database) {
            assertEquals("a new description", DaoProject.findById(imported.projectId)!!.description)
        }
    }

    @Test
    fun `import with a bad cue reference rolls back atomically`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        // Corrupt one property assignment's cueUuid so FK resolution fails mid-import, after the
        // cues, stacks and entries have landed. (A cue file names no stack since v32, and an entry
        // naming a missing stack is dropped rather than refused — `CueStackEntrySyncTest`.)
        val dir = exportDirA.resolve("cueAttributeAssignments")
        val first = Files.list(dir).use { it.findFirst().get() }
        val original = Files.readString(first)
        val corrupt = original.replace(
            Regex("\"cueUuid\": \"[0-9a-f-]+\""),
            "\"cueUuid\": \"00000000-0000-0000-0000-000000000000\""
        )
        assertTrue(corrupt != original, "test sanity: the assignment was rewritten")
        Files.writeString(first, corrupt)

        val ex = assertFailsWith<ImportError> {
            ProjectImporter(state).import(exportDirA, nameOverride = null)
        }
        assertEquals(io.ktor.http.HttpStatusCode.BadRequest, ex.status)
        // No partial state — the project that would have been inserted must be absent.
        transaction(state.database) {
            assertEquals(0, DaoProject.all().count(),
                "import that errored mid-flight should leave DB empty")
        }
    }

    /**
     * v23 (scrim plan P2): a drape's fabric and paint ride its params, and the images they name ride
     * `sceneImages/{sha256}.{png|jpg}` as raw bytes. Only the referenced images travel; an import
     * hydrates them into the new project's store, byte for byte, with the store wiped in between so
     * nothing can pass by being left behind.
     */
    @Test
    fun `painted cloths export their images byte for byte, and an import hydrates them`() {
        val projectId = seedRichProject(state)
        val projectUuid = transaction(state.database) { DaoProject.findById(projectId)!!.uuid.toString() }
        // An upload no element names stays on this machine.
        val orphan = state.sceneImages.store(projectUuid, uk.me.cormack.lighting7.testsupport.scenePng(12, 12, seed = 99), "image/png")
        ProjectExporter(state).export(projectId, exportDirA)

        assertTrue(SUPPORTED_FORMAT_VERSION >= 23, "painted cloths travel from v23")
        val expected = RICH_PROJECT_SCENE_IMAGES.associate { (type, bytes) ->
            "${RecordHasher.sha256Hex(bytes)}.${if (type == "image/png") "png" else "jpg"}" to bytes
        }
        val dir = exportDirA.resolve(RecordHasher.SCENE_IMAGES_DIR)
        val exported = Files.list(dir).use { s -> s.toList() }.associate { it.fileName.toString() to Files.readAllBytes(it) }
        assertEquals(expected.keys, exported.keys, "exactly the referenced images, and not ${orphan.hash}")
        expected.forEach { (name, bytes) -> assertContentEquals(bytes, exported.getValue(name), name) }

        val cloth = Files.list(exportDirA.resolve("stageElements")).use { s -> s.toList() }
            .map { canonicalDecode(StageElementJson.serializer(), Files.readString(it)) }
            .single { it.name == "Day/night cloth" }
        assertEquals("MUSLIN", cloth.params!!["fabric"]!!.jsonPrimitive.content)
        assertEquals(
            setOf("front", "back"),
            cloth.params["paint"]!!.jsonObject.keys,
        )

        wipeDatabase()
        state.sceneImages.deleteProject(projectUuid)
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        assertEquals(projectUuid, imported.projectUuid)
        for ((name, bytes) in expected) {
            val hash = name.substringBefore('.')
            val stored = state.sceneImages.original(projectUuid, hash)
            assertNotNull(stored, "the import hydrated $name")
            assertContentEquals(bytes, Files.readAllBytes(stored.first))
        }
        assertNull(state.sceneImages.original(projectUuid, orphan.hash))

        ProjectExporter(state).export(imported.projectId, exportDirB)
        assertExportsEqual(exportDirA, exportDirB, installsShapeOnly = true)
    }

    /**
     * A v22 archive — no `sceneImages/`, no fabric, no paint — still imports (`minReader` stays 5),
     * and its cloths read as velour, unpainted.
     */
    @Test
    fun `a v22 archive still imports`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()
        exportDirA.resolve(RecordHasher.SCENE_IMAGES_DIR).toFile().deleteRecursively()
        Files.list(exportDirA.resolve("stageElements")).use { s -> s.toList() }.forEach { file ->
            val doc = Json.parseToJsonElement(Files.readString(file)).jsonObject
            val params = doc["params"] as? JsonObject ?: return@forEach
            // A model is newer than v22 too: such an archive never held one.
            if ((params["shape"] as? JsonPrimitive)?.content == "MODEL") {
                Files.delete(file)
                return@forEach
            }
            val stripped = JsonObject(doc + ("params" to JsonObject(params - "fabric" - "paint")))
            Files.writeString(file, stripped.toString())
        }
        Files.writeString(
            exportDirA.resolve("formatVersion.json"),
            Files.readString(exportDirA.resolve("formatVersion.json")).replace("\"formatVersion\": $SUPPORTED_FORMAT_VERSION", "\"formatVersion\": 22"),
        )

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        assertFalse(Files.exists(exportDirB.resolve(RecordHasher.SCENE_IMAGES_DIR)), "no paint, no images")
        val cloth = Files.list(exportDirB.resolve("stageElements")).use { s -> s.toList() }
            .map { canonicalDecode(StageElementJson.serializer(), Files.readString(it)) }
            .single { it.name == "Day/night cloth" }
        assertEquals(setOf("operation", "role", "travelS"), cloth.params!!.keys)
    }

    // ── v37: effect runs (stack-timeline plan session 1) ─────────────────────────

    /**
     * An ad-hoc effect's run key travels on its row and imports back; a row in no run writes no key.
     * Asserted on the export text for the reason the Seconds test gives.
     */
    @Test
    fun `an effect's run key exports on its rows, imports back, and is omitted when null`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val cueNames = exportDocs(exportDirA, "cues", CueJson.serializer()).associate { it.uuid to it.name }
        val effects = exportDocs(exportDirA, "cueAdHocFx", CueAdHocFxJson.serializer())
        val keyed = effects.filter { it.runUuid != null }
        assertEquals(setOf("open", "build"), keyed.map { cueNames.getValue(it.cueUuid) }.toSet(), "one row in each neighbour")
        assertEquals(setOf(RICH_PROJECT_PULSE_RUN.toString()), keyed.map { it.runUuid }.toSet(), "under one key")
        val unkeyed = effects.single { it.runUuid == null && it.spreadUnit == null }
        val unkeyedText = Files.readString(exportDirA.resolve("cueAdHocFx/${unkeyed.uuid}.json"))
        assertFalse(unkeyedText.contains("runUuid"), "a row in no run writes no key: $unkeyedText")
        assertTrue(SUPPORTED_FORMAT_VERSION >= 37, "run keys sync from v37")
        assertTrue(Files.readString(exportDirA.resolve("formatVersion.json")).contains("\"formatVersion\": $SUPPORTED_FORMAT_VERSION"))

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        transaction(state.database) {
            val runs = DaoCue.find { DaoCues.project eq imported.projectId }
                .flatMap { cue -> cue.adHocFx.map { cue.name to it.runUuid } }
                .toSet()
            assertEquals(
                setOf("open" to RICH_PROJECT_PULSE_RUN, "build" to RICH_PROJECT_PULSE_RUN, "open" to null),
                runs,
            )
        }
    }

    /** A v36 archive holds no run keys (C5): it imports with none, and nothing joins its effects. */
    @Test
    fun `a v36 archive imports with no run keys`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()
        Files.list(exportDirA.resolve("cueAdHocFx")).use { s -> s.toList() }.forEach { file ->
            val doc = Json.parseToJsonElement(Files.readString(file)).jsonObject
            Files.writeString(file, JsonObject(doc - "runUuid").toString())
        }
        Files.writeString(
            exportDirA.resolve("formatVersion.json"),
            Files.readString(exportDirA.resolve("formatVersion.json")).replace("\"formatVersion\": $SUPPORTED_FORMAT_VERSION", "\"formatVersion\": 36"),
        )

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        transaction(state.database) {
            val effects = DaoCue.find { DaoCues.project eq imported.projectId }.flatMap { it.adHocFx }
            assertEquals(4, effects.size)
            assertTrue(effects.all { it.runUuid == null }, "an archive before v37 names no run")
        }
    }

    // ── v24: venues sync (venue plan session 4) ──────────────────────────────────

    /**
     * A show on a venue exports its own rows only — its addition, its group, its hides — with its pin
     * in `project.json`, and its links into the venue (the addition's universe, a group member, its
     * cues' events and scenery) by the venue records' uuids. Deleting the show and importing its
     * archive back seeds those links from the pinned snapshot and reproduces the export byte for byte.
     */
    @Test
    fun `a show on a venue round-trips with its pin, its hides and its additions`() {
        val f = seedShowOnVenue(state)
        ProjectExporter(state).export(f.showId, exportDirA)

        assertTrue(SUPPORTED_FORMAT_VERSION >= 24, "venues sync from v24")
        assertTrue(Files.readString(exportDirA.resolve("formatVersion.json")).contains("\"formatVersion\": $SUPPORTED_FORMAT_VERSION"), "the writer stamps the current version")
        val project = canonicalDecode(ProjectJson.serializer(), Files.readString(exportDirA.resolve("project.json")))
        val (venueUuid, snapshotUniverses) = transaction(state.database) {
            DaoProject[f.venueId].uuid to RigScope.of(f.showId).venue!!.let { v ->
                DaoProject[v.snapshotId].universeConfigs.map { it.uuid.toString() }.toSet()
            }
        }
        assertEquals(ProjectKind.SHOW, project.kind)
        assertEquals(venueUuid.toString(), project.venueUuid)
        assertEquals(1, project.venueVersion, "the show stays on v1 though v2 is published")
        assertFalse(Files.readString(exportDirA.resolve("project.json")).contains("\"kind\""), "a show's kind is the default, omitted")

        val hides = Files.list(exportDirA.resolve("showHides")).use { it.toList() }
            .map { canonicalDecode(ShowHideJson.serializer(), Files.readString(it)) }
        assertEquals(listOf(f.hideKind to f.hiddenUuid.toString()), hides.map { it.recordKind to it.recordUuid })

        // Only the show's own rows: the venue's universes and patches are not in its archive, and its
        // addition names a venue universe by uuid.
        assertFalse(Files.exists(exportDirA.resolve("universeConfigs")), "the show owns no universe")
        val patches = Files.list(exportDirA.resolve("fixturePatches")).use { it.toList() }
            .map { canonicalDecode(FixturePatchJson.serializer(), Files.readString(it)) }
        val addition = patches.single()
        assertEquals(f.additionUuid.toString(), addition.uuid)
        assertTrue(addition.universeConfigUuid in snapshotUniverses, "the addition sits on a venue universe")
        assertEquals("L201", addition.gelCode)

        // Session 5: the overrides travel as `showOverrides/{uuid}.json`, naming the venue patch by uuid,
        // each value non-default and each base the venue's v1 value.
        val overrides = Files.list(exportDirA.resolve("showOverrides")).use { it.toList() }
            .map { canonicalDecode(ShowOverrideJson.serializer(), Files.readString(it)) }
            .associateBy { it.facet }
        assertEquals(setOf(PatchFacet.ADDRESS, PatchFacet.POSITION, PatchFacet.MEDIA), overrides.keys)
        assertTrue(overrides.values.all { it.recordUuid == f.overriddenUuid.toString() })
        assertEquals(470, (decodeFacet(PatchFacet.ADDRESS, overrides.getValue(PatchFacet.ADDRESS).value) as AddressFacet).startChannel)
        assertEquals(f.boomUuid.toString(), (decodeFacet(PatchFacet.POSITION, overrides.getValue(PatchFacet.POSITION).value) as PositionFacet).riggingUuid)
        assertEquals("L202", (decodeFacet(PatchFacet.MEDIA, overrides.getValue(PatchFacet.MEDIA).value) as MediaFacet).gelCode)

        // v27: the show's own re-park of a venue park travels as a park, and its release of another
        // as `showParkReleases/{uuid}.json`, by channel; the venue's parks are the version's, not here.
        assertTrue(SUPPORTED_FORMAT_VERSION >= 27, "venue parks sync from v27")
        val parks = Files.list(exportDirA.resolve("parkedChannels")).use { it.toList() }
            .map { canonicalDecode(ParkedChannelJson.serializer(), Files.readString(it)) }
        assertEquals(listOf(Triple(1, 7, 200)), parks.map { Triple(it.universe, it.channel, it.value) })
        val releases = Files.list(exportDirA.resolve("showParkReleases")).use { it.toList() }
            .map { canonicalDecode(ShowParkReleaseJson.serializer(), Files.readString(it)) }
        assertEquals(listOf(0 to 12), releases.map { it.universe to it.channel })
        val effectiveBefore = transaction(state.database) { VenueParks.of(f.showId).effective }

        transaction(state.database) { deleteProjectRows(state, DaoProject[f.showId]) }
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        assertExportsEqual(exportDirA, exportDirB, installsShapeOnly = true)
        transaction(state.database) {
            val show = DaoProject[imported.projectId]
            assertEquals(venueUuid, show.venueUuid)
            assertEquals(1, show.venueVersion)
            assertTrue(RigScope.of(show).isHidden(f.hideKind, f.hiddenUuid))
            assertEquals(effectiveBefore, VenueParks.of(show.id).effective, "the imported show runs the same parks")
            // The imported show's rig lays its overrides over the venue's patch again.
            val rig = RigScope.of(show)
            val overridden = rig.effective(rig.patches().first { it.uuid == f.overriddenUuid })
            assertEquals(470, overridden.startChannel)
            assertEquals("L202", overridden.gelCode)
            assertEquals(f.boomUuid, overridden.rigging?.uuid)
            assertEquals(setOf(PatchFacet.ADDRESS, PatchFacet.POSITION, PatchFacet.MEDIA), overridden.overridden)
        }
    }

    /**
     * A venue's versions travel as its repo's tags (§3.4): desk A pushes v1 and v2, each a tagged
     * commit `vN: note` carrying `venueVersion.json`; a fresh desk refuses the show until it has the
     * venue (`VENUE_MISSING`, naming the venue's repo), imports the venue repo — every tag a snapshot,
     * `venue_versions` rebuilt, the draft the latest — and then imports the show exactly.
     */
    @Test
    fun `a venue's versions travel through its repo and its tags, and a show waits for them`() {
        val root = Files.createTempDirectory("venue-repo-")
        try {
            val bare = root.resolve("hall.git")
            Git.init().setBare(true).setDirectory(bare.toFile()).setInitialBranch("main").call().close()
            val url = bare.toUri().toString()
            runCatching { state.shutdown() }
            IntegrationTestDb.reset()
            state = State(testAppConfig("sync.workingTreeRoot" to root.resolve("desk-a").toString()))

            val f = seedShowOnVenue(state)
            attachRepo(state, f.venueId, url)
            val sync = runVenueSync(state, f.venueId, url)
            assertEquals(SyncOutcome.PUSHED, sync.outcome, sync.message)
            assertEquals(2, sync.pushed)

            val tags = Git.open(bare.toFile()).use { git ->
                git.tagList().call().associate { ref ->
                    val commit = git.repository.parseCommit(git.repository.refDatabase.peel(ref).peeledObjectId ?: ref.objectId)
                    ref.name.removePrefix("refs/tags/") to commit.fullMessage.trim()
                }
            }
            assertEquals(setOf("v1", "v2"), tags.keys)
            assertEquals("v1: Extracted from $RICH_PROJECT_NAME", tags["v1"])
            assertEquals("v2: FOH truss moved stage left", tags["v2"])
            val v2Json = Git.open(bare.toFile()).use { JGitClient.readBlob(it.repository, "refs/tags/v2", "venueVersion.json") }
            val v2 = canonicalDecode(VenueVersionJson.serializer(), assertNotNull(v2Json))
            assertEquals(2, v2.number)
            assertEquals("Chris", v2.publishedBy)

            // What desk A holds, to compare desk B's against.
            val versionsA = transaction(state.database) {
                VenueVersions.versionsOf(f.venueId).map { Triple(it.number, it.note, it.publishedAt) to it.snapshotProject.id.value }
            }
            val recordsA = versionsA.associate { (key, snapshotId) -> key.first to VenueDiff.recordsOf(state, snapshotId) }
            ProjectExporter(state).export(f.showId, exportDirA)
            val venueUuid = transaction(state.database) { DaoProject[f.venueId].uuid }

            // Desk B: a fresh database and working tree.
            runCatching { state.shutdown() }
            IntegrationTestDb.reset()
            state = State(testAppConfig("sync.workingTreeRoot" to root.resolve("desk-b").toString()))

            val missing = assertFailsWith<ImportError> { ProjectImporter(state).import(exportDirA, nameOverride = null) }
            assertEquals(HttpStatusCode.Conflict, missing.status)
            assertEquals("VENUE_MISSING", missing.code)
            assertTrue(venueUuid.toString() in missing.message!!, missing.message)
            assertTrue(url in missing.message!!, "names the venue's repo: ${missing.message}")

            val creds = InMemoryCredentialStore().also { it.set(url, "test-pat") }
            val venue = runBlocking {
                CloudSyncImporter(state, AuthResolver(creds, tokenStore = null, tokenProvider = null)).import(url, null, null)
            }
            transaction(state.database) {
                val project = DaoProject[venue.projectId]
                assertEquals(ProjectKind.VENUE, project.kind)
                assertEquals(venueUuid, project.uuid)
                assertEquals(
                    versionsA.map { it.first },
                    VenueVersions.versionsOf(venue.projectId).map { Triple(it.number, it.note, it.publishedAt) },
                    "venue_versions rebuilt from the tags, notes and times as published",
                )
            }
            val versionsB = transaction(state.database) {
                VenueVersions.versionsOf(venue.projectId).associate { it.number to it.snapshotProject.id.value }
            }
            for ((number, records) in recordsA) {
                val diff = VenueDiff.compare(records, VenueDiff.recordsOf(state, versionsB.getValue(number)))
                assertEquals(0, diff.size, "v$number is the same records on both desks: $diff")
            }
            assertEquals(0, state.venueVersions.draftChanges(venue.projectId), "the draft is the latest version")

            val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
            ProjectExporter(state).export(imported.projectId, exportDirB)
            assertExportsEqual(exportDirA, exportDirB, installsShapeOnly = true)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** A v23 archive — no `kind`, no pin, no `showHides/` — still imports (`minReader` stays 5). */
    @Test
    fun `a v23 archive still imports`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()
        Files.writeString(
            exportDirA.resolve("formatVersion.json"),
            Files.readString(exportDirA.resolve("formatVersion.json")).replace("\"formatVersion\": $SUPPORTED_FORMAT_VERSION", "\"formatVersion\": 23"),
        )
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        transaction(state.database) {
            val project = DaoProject[imported.projectId]
            assertEquals(ProjectKind.SHOW, project.kind)
            assertNull(project.venueUuid)
        }
        ProjectExporter(state).export(imported.projectId, exportDirB)
        // Byte for byte but the version stamp, which this writer moves to its own.
        Files.writeString(
            exportDirA.resolve("formatVersion.json"),
            Files.readString(exportDirB.resolve("formatVersion.json")),
        )
        assertExportsEqual(exportDirA, exportDirB, installsShapeOnly = true)
    }

    /**
     * A Look effect names its own target (absolute-looks plan D2), so a v37 archive's `deferred` one —
     * which an older desk could still write — is dropped on import and logged, its Look kept.
     */
    @Test
    fun `a deferred Look effect in an archive is dropped on import, and logged`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        val lookFile = Files.list(exportDirA.resolve("looks")).use { files ->
            files.toList().single { canonicalDecode(LookJson.serializer(), Files.readString(it)).name == "warm-pulse" }
        }
        val look = canonicalDecode(LookJson.serializer(), Files.readString(lookFile))
        val deferred = look.fx.single().copy(targetType = DEFERRED_TARGET_TYPE, targetKey = "")
        Files.writeString(lookFile, canonicalEncode(LookJson.serializer(), look.copy(fx = listOf(deferred))))
        wipeDatabase()

        val (imported, logs) = captureLogs(ProjectImporter::class.java.name) {
            ProjectImporter(state).import(exportDirA, nameOverride = null)
        }

        transaction(state.database) {
            val looks = DaoLook.find { DaoLooks.project eq imported.projectId }.associateBy { it.name }
            assertTrue(looks.getValue("warm-pulse").effects.empty(), "the deferred effect is dropped, its Look kept")
            assertEquals(1L, looks.getValue("Warm Amber").effects.count(), "a bound effect is untouched")
        }
        assertTrue(logs.any { "'warm-pulse'" in it && deferred.uuid in it && "names no target of its own" in it }, "$logs")
    }

    /**
     * A Look lands whole (absolute-looks plan D1), so a v37 archive's Look layer naming targets — which
     * an older desk could still write — imports naming none and is logged; a palette layer keeps its.
     */
    @Test
    fun `a Look layer's targets in an archive are dropped on import, and logged`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        val layerFiles = Files.list(exportDirA.resolve("cueLayers")).use { it.toList() }
        val lookLayer = layerFiles.first { canonicalDecode(CueLayerJson.serializer(), Files.readString(it)).lookUuid != null }
        val layer = canonicalDecode(CueLayerJson.serializer(), Files.readString(lookLayer))
        Files.writeString(
            lookLayer,
            canonicalEncode(CueLayerJson.serializer(), layer.copy(targets = listOf(CueTargetDto("fixture", "hex-1")))),
        )
        wipeDatabase()

        val (imported, logs) = captureLogs(ProjectImporter::class.java.name) {
            ProjectImporter(state).import(exportDirA, nameOverride = null)
        }

        transaction(state.database) {
            val layers = DaoCueLayer.all().filter { it.cue.project.id.value == imported.projectId }
            assertTrue(layers.filter { it.look != null }.all { it.targets.isEmpty() }, "every Look layer names none")
            assertTrue(layers.any { it.palette != null && it.targets.isNotEmpty() }, "a palette layer keeps its targets")
        }
        assertTrue(logs.any { layer.uuid in it && "named targets" in it }, "$logs")
    }

    /**
     * `showOverrides/` was reserved by session 4's reader, which refused an archive holding one (422);
     * this build reads it (session 5, still v24). An archive with overrides imports, every override kept,
     * and one naming a patch the pinned version lacks is kept too — it overrides nothing until an Update
     * to a version that has the patch.
     */
    @Test
    fun `an archive holding show overrides imports on this build`() {
        val f = seedShowOnVenue(state)
        ProjectExporter(state).export(f.showId, exportDirA)
        val stray = java.util.UUID.randomUUID()
        Files.writeString(
            exportDirA.resolve("showOverrides/$stray.json"),
            canonicalEncode(
                ShowOverrideJson.serializer(),
                ShowOverrideJson(
                    uuid = stray.toString(), recordUuid = java.util.UUID.randomUUID().toString(), facet = PatchFacet.FOCUS,
                    value = facetElement(FocusFacet(iris = 0.4)), base = facetElement(FocusFacet()),
                ),
            ),
        )
        transaction(state.database) { deleteProjectRows(state, DaoProject[f.showId]) }
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        transaction(state.database) {
            val rows = DaoShowOverride.find { DaoShowOverrides.project eq imported.projectId }.toList()
            assertEquals(4, rows.size)
            assertEquals(3, rows.count { it.recordUuid == f.overriddenUuid })
        }
        assertTrue(RESERVED_FOLDERS.isEmpty(), "nothing is reserved any more")
    }

    private fun attachRepo(state: State, projectId: Int, url: String) {
        transaction(state.database) {
            DaoSyncConfig.new {
                project = DaoProject[projectId]
                repoUrl = url
            }
        }
    }

    /** A sync run of venue [venueId] with an in-memory PAT for [url], so the suite never touches a credential file. */
    private fun runVenueSync(state: State, venueId: Int, url: String): SyncRunResult {
        val creds = InMemoryCredentialStore().also { it.set(url, "test-pat") }
        val engine = RemoteSyncEngine(state, AuthResolver(creds, tokenStore = null, tokenProvider = null))
        val (uuid, install) = transaction(state.database) { DaoProject[venueId].uuid to DaoInstall.all().first() }
        return runBlocking { engine.runSync(venueId, uuid, install.uuid, install.friendlyName) }
    }

    private fun <T> exportDocs(dir: Path, folder: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        Files.walk(dir.resolve(folder)).use { stream ->
            stream.toList().filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                .map { canonicalDecode(serializer, Files.readString(it)) }
        }

    /** The rich fixture's Pulse in `open`, every field set; `build`'s runs it on at the defaults. */
    private fun openPulse(dir: Path): CueAdHocFxJson {
        val open = exportDocs(dir, "cues", CueJson.serializer()).single { it.name == "open" }.uuid
        return exportDocs(dir, "cueAdHocFx", CueAdHocFxJson.serializer()).single { it.targetType == "fixture" && it.cueUuid == open }
    }

    private fun wipeDatabase() {
        // Reset to a fresh SQLite file and rebuild the State / schema. Simpler than DELETE
        // cascade because the FK graph is wide; the test just needs an empty DB.
        runCatching { state.shutdown() }
        IntegrationTestDb.reset()
        state = State(testAppConfig())
    }
}
