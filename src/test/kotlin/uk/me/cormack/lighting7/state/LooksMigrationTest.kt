package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.PaletteCascade
import uk.me.cormack.lighting7.fx.paletteRefValue
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCuePresetApplication
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoFxPreset
import uk.me.cormack.lighting7.models.DaoFxPresetPropertyAssignment
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPaletteEntry
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSpeedMaster
import uk.me.cormack.lighting7.models.FxPresetEffectDto
import uk.me.cormack.lighting7.models.PaletteType
import uk.me.cormack.lighting7.routes.buildCueApplyData
import uk.me.cormack.lighting7.routes.buildCueAssignmentsForCue
import uk.me.cormack.lighting7.routes.buildCueAssignmentsForPreset
import uk.me.cormack.lighting7.routes.toPropertyAssignmentDtos
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The migration that turns FX presets and named palettes into Looks, and cue preset applications
 * into cue layers.
 *
 * This test exists because its absence let two shipping bugs reach a real desk database. Both were
 * uuid-handling: every source uuid was read with `getString` from a column Exposed stores as a
 * 16-byte **blob**, so bytes above 0x7F became U+FFFD, and the results were written back as *text*
 * literals, which SQLite never compares equal to the blob a DAO binds. Between them they destroyed
 * the three things the migration's own design rests on — idempotency, `ref:{uuid}` resolution and
 * sync identity — while reporting success. Hence the paranoia about `typeof(uuid)` below.
 *
 * `RouteIntegrationTest` already ran `runStateMigrations()` against an empty database during setup,
 * so each test seeds legacy rows and then invokes the migration explicitly — the same shape
 * [CollapseShowMigrationTest] uses.
 */
class LooksMigrationTest : RouteIntegrationTest() {

    private fun migrate() = transaction(state.database) { migratePresetsAndPalettesToLooks() }

    private fun project() = DaoProject.findById(projectId)!!

    /** `typeof(uuid)` straight from SQLite, for the one thing a DAO read cannot tell us. */
    private fun uuidStorageTypes(table: String): List<String> = transaction(state.database) {
        val out = mutableListOf<String>()
        exec("SELECT typeof(uuid) AS t FROM $table") { rs -> while (rs.next()) out.add(rs.getString("t")) }
        out
    }

    private fun seedPalette(name: String, entries: List<Triple<String, String, String>>): UUID =
        transaction(state.database) {
            val palette = DaoPalette.new {
                this.project = project()
                this.name = name
                this.type = PaletteType.COLOUR.name
                this.notes = "seeded"
                this.sortOrder = 3
            }
            entries.forEachIndexed { index, (targetType, targetKey, value) ->
                DaoPaletteEntry.new {
                    this.palette = palette
                    this.targetType = targetType
                    this.targetKey = targetKey
                    this.propertyName = "colour"
                    this.value = value
                    this.sortOrder = index
                }
            }
            palette.uuid
        }

    private fun seedPreset(
        name: String,
        fixtureType: String = "hex",
        dimmer: String = "180",
        withEffect: Boolean = true,
    ): Pair<Int, UUID> = transaction(state.database) {
        // Next free index: the seeded project already owns master 1, and a test may seed twice.
        val nextIndex = (DaoSpeedMaster.all().maxOfOrNull { it.masterIndex } ?: 0) + 1
        val master = DaoSpeedMaster.new {
            this.project = project()
            masterIndex = nextIndex; this.name = "Slow $name"; bpm = 60.0; source = "MANUAL"
        }
        val preset = DaoFxPreset.new {
            this.project = project()
            this.name = name
            this.description = "seeded"
            this.fixtureType = fixtureType
            this.palette = listOf("#ff8800")
            this.effects = if (!withEffect) emptyList() else listOf(
                FxPresetEffectDto(
                    effectType = "Pulse", category = "dimmer", propertyName = "dimmer",
                    beatDivision = 0.5, blendMode = "OVERRIDE", distribution = "LINEAR",
                    phaseOffset = 0.25, stepTiming = true,
                    parameters = mapOf("depth" to "0.8"),
                    speedMasterUuid = master.uuid.toString(),
                    rateSpeedMasterUuid = master.uuid.toString(),
                )
            )
        }
        DaoFxPresetPropertyAssignment.new {
            this.preset = preset; propertyName = "dimmer"; value = dimmer
            fadeDurationMs = 750L; sortOrder = 0
        }
        preset.id.value to preset.uuid
    }

    private fun seedCue(name: String = "cue-1"): Int = transaction(state.database) {
        val stack = DaoCueStack.new {
            this.project = project(); this.name = "stack-$name"; palette = emptyList()
            loop = false; type = CueStackType.STACK.name; sortOrder = 0
        }
        DaoCue.new {
            this.project = project(); this.name = name; cueStack = stack; sortOrder = 0
            palette = emptyList(); cueType = CueType.STANDARD.name
        }.id.value
    }

    // ─── uuid handling: the two bugs that reached a real database ────────

    @Test
    fun `a palette's uuid survives as a blob, and the DAO lookup finds it`() {
        val paletteUuid = seedPalette("Warm Amber", listOf(Triple("fixture", "hex-1", "#ff8800")))
        assertEquals(listOf("blob"), uuidStorageTypes("palettes"), "Exposed writes uuids as blobs")

        migrate()

        // The corruption bug: getString on a blob mangles every byte above 0x7F, unrecoverably.
        val look = transaction(state.database) {
            DaoLook.find { DaoLooks.uuid eq paletteUuid }.firstOrNull()
        }
        assertNotNull(
            look,
            "the Look must keep the palette's uuid — idempotency, ref: resolution and sync " +
                "identity all key off it, and `DaoLooks.uuid eq` is the exact lookup " +
                "loadLookSnapshot performs",
        )
        assertEquals("Warm Amber", look.name)

        // The storage bug: a text uuid reads back fine through a DAO scan but is invisible to the
        // indexed `eq` above, so this assertion and the one above fail for different reasons.
        assertEquals(
            listOf("blob"), uuidStorageTypes("looks"),
            "written as a blob literal — SQLite never compares a BLOB equal to TEXT",
        )
    }

    @Test
    fun `a uuid with high bytes round-trips exactly`() {
        // Chosen so most bytes are above 0x7F: precisely what getString destroyed.
        val hostile = UUID.fromString("ffeeddcc-bbaa-4998-8877-665544332211")
        transaction(state.database) {
            DaoPalette.new {
                this.project = project(); name = "Hostile"; type = PaletteType.COLOUR.name
                uuid = hostile
            }
        }
        migrate()
        val found = transaction(state.database) {
            DaoLook.find { DaoLooks.uuid eq hostile }.firstOrNull()
        }
        assertNotNull(found, "a uuid full of high bytes must survive byte-for-byte")
        assertEquals(hostile, found.uuid)
    }

    @Test
    fun `running twice creates nothing new`() {
        seedPalette("Warm Amber", listOf(Triple("fixture", "hex-1", "#ff8800")))
        seedPreset("warm-pulse")
        val cueId = seedCue()
        val (presetId, _) = transaction(state.database) {
            DaoFxPreset.all().first().let { it.id.value to it.uuid }
        }
        transaction(state.database) {
            DaoCuePresetApplication.new {
                cue = DaoCue.findById(cueId)!!
                preset = DaoFxPreset.findById(presetId)!!
                targets = listOf(CueTargetDto("fixture", "hex-1"))
                sortOrder = 2
            }
        }

        migrate()
        val first = transaction(state.database) {
            Triple(DaoLook.all().count(), DaoCueLayer.all().count(), DaoLook.all().map { it.rows.count() }.sum())
        }
        migrate()
        val second = transaction(state.database) {
            Triple(DaoLook.all().count(), DaoCueLayer.all().count(), DaoLook.all().map { it.rows.count() }.sum())
        }
        assertEquals(first, second, "idempotent: the uuid index is what makes a second pass a no-op")
    }

    @Test
    fun `rows left by the pre-fix migration are replaced, not duplicated`() {
        // Reproduce exactly what shipped: a Look whose uuid is a *text* literal. It is unreachable
        // at runtime and invisible to the idempotency check, so without the cleanup the repaired
        // pass mints "Warm Amber (2)" beside it.
        val paletteUuid = seedPalette("Warm Amber", listOf(Triple("fixture", "hex-1", "#ff8800")))
        transaction(state.database) {
            exec(
                "INSERT INTO looks (project_id, name, notes, sort_order, editor_fixture_type, palette, uuid) " +
                    "VALUES ($projectId, 'Warm Amber', NULL, 0, NULL, '[]', 'not-a-blob-uuid')"
            )
        }
        assertTrue("text" in uuidStorageTypes("looks"), "precondition: a text-uuid row exists")

        migrate()

        transaction(state.database) {
            val looks = DaoLook.all().toList()
            assertEquals(1, looks.size, "the dead row is removed rather than left beside a duplicate")
            assertEquals("Warm Amber", looks.single().name, "and the name is not suffixed")
            assertEquals(paletteUuid, looks.single().uuid)
        }
        assertEquals(listOf("blob"), uuidStorageTypes("looks"))
    }

    // ─── The mapping ────────────────────────────────────────────────────

    @Test
    fun `a palette becomes a bound Look and a preset a deferred one`() {
        seedPalette(
            "Warm Amber",
            listOf(Triple("fixture", "hex-1", "#ff8800"), Triple("group", "front-wash", "#ffaa44")),
        )
        seedPreset("warm-pulse", fixtureType = "hex")

        migrate()

        transaction(state.database) {
            val bound = DaoLook.all().single { it.name == "Warm Amber" }
            assertNull(bound.editorFixtureType, "a palette carried no fixture type")
            assertEquals(2, bound.rows.count())
            assertTrue(bound.rows.none { it.isDeferred }, "palette entries named their own targets")
            assertEquals(setOf("hex-1", "front-wash"), bound.rows.map { it.targetKey }.toSet())

            val deferred = DaoLook.all().single { it.name == "warm-pulse" }
            assertEquals("hex", deferred.editorFixtureType, "fixtureType survives as an editor hint")
            assertEquals(listOf("#ff8800"), deferred.palette, "the positional colour list carries over")
            val row = deferred.rows.single()
            assertTrue(row.isDeferred, "a preset row was target-less, so it becomes deferred")
            assertEquals(DEFERRED_TARGET_TYPE, row.targetType)
            assertEquals("180", row.value)
            assertEquals(750L, row.fadeDurationMs)

            // The effects JSON blob becomes real rows, including its speed-master references.
            val effect = deferred.effects.single()
            assertTrue(effect.isDeferred)
            assertEquals("Pulse", effect.effectType)
            assertEquals(0.25, effect.phaseOffset)
            assertEquals(true, effect.stepTiming)
            assertEquals(mapOf("depth" to "0.8"), effect.parameters)
            val master = DaoSpeedMaster.all().single { it.name == "Slow warm-pulse" }
            assertEquals(master.uuid, effect.speedMasterUuid, "the blob's reference becomes a column")
            assertEquals(master.uuid, effect.rateSpeedMasterUuid)
        }
    }

    @Test
    fun `a preset application becomes a layer carrying its timing and masters`() {
        val (presetId, _) = seedPreset("warm-pulse")
        val cueId = seedCue()
        val masterUuid = transaction(state.database) { DaoSpeedMaster.all().first().uuid }
        transaction(state.database) {
            DaoCuePresetApplication.new {
                cue = DaoCue.findById(cueId)!!
                preset = DaoFxPreset.findById(presetId)!!
                targets = listOf(CueTargetDto("group", "front-wash"))
                delayMs = 250L; intervalMs = 500L; randomWindowMs = 125L
                sortOrder = 2
                speedMasterUuid = masterUuid; rateSpeedMasterUuid = masterUuid
            }
        }

        migrate()

        transaction(state.database) {
            val layer = DaoCue.findById(cueId)!!.layers.single()
            assertEquals("warm-pulse", layer.look.name)
            assertEquals(listOf(CueTargetDto("group", "front-wash")), layer.targets)
            assertEquals(250L, layer.delayMs)
            assertEquals(500L, layer.intervalMs)
            assertEquals(125L, layer.randomWindowMs)
            assertEquals(masterUuid, layer.speedMasterUuid)
            assertEquals(masterUuid, layer.rateSpeedMasterUuid)
            assertEquals("OVERRIDE", layer.blendMode)
            assertEquals(1.0, layer.amount)
            assertTrue(layer.enabled)
        }
    }

    @Test
    fun `ref rows fold into one masked layer per cue and Look, and the rows go`() {
        val paletteUuid = seedPalette(
            "Warm Amber",
            listOf(Triple("fixture", "hex-1", "#ff8800"), Triple("fixture", "hex-2", "#ffaa44")),
        )
        val cueId = seedCue()
        transaction(state.database) {
            val cue = DaoCue.findById(cueId)!!
            // Two rows, same Look, different fixtures — they must collapse to ONE layer whose
            // targets are exactly these two, or the cue starts asserting every fixture the palette
            // covers.
            DaoCuePropertyAssignment.new {
                this.cue = cue; targetType = "fixture"; targetKey = "hex-1"
                propertyName = "colour"; value = paletteRefValue(paletteUuid); sortOrder = 0
            }
            DaoCuePropertyAssignment.new {
                this.cue = cue; targetType = "fixture"; targetKey = "hex-2"
                propertyName = "colour"; value = paletteRefValue(paletteUuid); sortOrder = 1
            }
            // A literal row must be left completely alone.
            DaoCuePropertyAssignment.new {
                this.cue = cue; targetType = "fixture"; targetKey = "hex-3"
                propertyName = "dimmer"; value = "200"; sortOrder = 2
            }
        }

        migrate()

        transaction(state.database) {
            val cue = DaoCue.findById(cueId)!!
            val layer = cue.layers.single()
            assertEquals("Warm Amber", layer.look.name)
            assertEquals(
                setOf("hex-1", "hex-2"), layer.targets.map { it.key }.toSet(),
                "targets are exactly the fixtures the refs named, not everything the Look covers",
            )
            assertEquals("COLOUR", layer.propertyMask, "and the mask is exactly the properties")

            val remaining = cue.propertyAssignments.toList()
            assertEquals(1, remaining.size, "the ref rows are consumed")
            assertEquals("200", remaining.single().value, "the literal row is untouched")
        }
    }

    @Test
    fun `colliding names are suffixed, because the old indexes allowed them`() {
        // A palette and a preset could both legally be called "Warm": their unique indexes were
        // (project, type, name) and (project, fixtureType, name). A Look's is (project, name).
        seedPalette("Warm", listOf(Triple("fixture", "hex-1", "#ff8800")))
        seedPreset("Warm")

        migrate()

        transaction(state.database) {
            val names = DaoLook.all().map { it.name }.sorted()
            assertEquals(listOf("Warm", "Warm (2)"), names)
        }
    }

    // ─── The golden test the plan asked for (§6) ─────────────────────────

    /**
     * Coverage preservation (§6 of the plan): for one cue, every `(fixture, property)` the
     * pre-migration builders composed must compose to the **same value** after migration. Nothing
     * the cue asserted may be lost or altered.
     *
     * Stated as "nothing lost" rather than a flat equality, because the post-migration map is a
     * strict *superset* — and the extra key is worth understanding rather than asserting around.
     * A `ref:{uuid}` naming a **palette** cannot resolve once the resolver reads Looks instead of
     * palettes, so that row is skipped in the "before" reading and covered in the "after" one.
     * That is a **test artefact, not a production window**: `runStateMigrations` runs at startup
     * before the show initialises, so a real desk never composes a cue in the pre-migration state.
     * This test is simply able to look at a state production skips through.
     *
     * The fixture is built so no key is *contested*, which is the case equivalence is meaningful
     * for. The plan documents one intended divergence where a key is contested, and
     * `layered intensity flips from HTP max to later-wins` pins that separately rather than letting
     * a blanket equality hide it.
     */
    @Test
    fun `cook after migration reproduces what the old builders composed`() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 20)
        LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")

        val paletteUuid = seedPalette("Warm Amber", listOf(Triple("fixture", "hex-2", "#ff8800")))
        val (presetId, _) = seedPreset("warm-pulse", dimmer = "180")
        val cueId = seedCue()
        transaction(state.database) {
            val cue = DaoCue.findById(cueId)!!
            // Fixture-level, so it beats the group-derived preset row on hex-1 both before
            // (specificity) and after (local-wins) — the same answer by two different rules.
            DaoCuePropertyAssignment.new {
                this.cue = cue; targetType = "fixture"; targetKey = "hex-1"
                propertyName = "dimmer"; value = "100"; sortOrder = 0
            }
            DaoCuePropertyAssignment.new {
                this.cue = cue; targetType = "fixture"; targetKey = "hex-2"
                propertyName = "colour"; value = paletteRefValue(paletteUuid); sortOrder = 1
            }
            DaoCuePresetApplication.new {
                this.cue = cue
                preset = DaoFxPreset.findById(presetId)!!
                targets = listOf(CueTargetDto("group", "front-wash"))
                sortOrder = 2
            }
        }

        // BEFORE: exactly what `buildCombinedCueLayerRows` did — cue rows concatenated with each
        // immediate preset's rows, then resolved.
        val before = transaction(state.database) {
            val cue = DaoCue.findById(cueId)!!
            val applyData = buildCueApplyData(cue)
            val cascade = PaletteCascade(global = state.show.fxEngine.getPalette())
            val preset = DaoFxPreset.findById(presetId)!!
            val cueOwn = buildCueAssignmentsForCue(
                state.show.fixtures, applyData, cascade, state.show.lookRegistry,
            )
            val presetRows = buildCueAssignmentsForPreset(
                state.show.fixtures, cueId, applyData.let { 1 },
                presetId, preset.toPropertyAssignmentDtos(),
                listOf(CueTargetDto("group", "front-wash")),
                cascade = cascade.copy(preset = listOf()),
                lookRegistry = state.show.lookRegistry,
            )
            CueAssignmentResolver().resolve(cueOwn + presetRows)
        }
        assertTrue(before.isNotEmpty(), "the pre-migration composition must be non-trivial")

        migrate()

        // AFTER: the cooked layer stack plus local rows.
        val after = transaction(state.database) {
            val applyData = buildCueApplyData(DaoCue.findById(cueId)!!)
            CueAssignmentResolver().resolve(
                uk.me.cormack.lighting7.routes.buildCombinedCueLayerRows(state, cueId, applyData)
            )
        }

        // Nothing lost, nothing altered.
        for ((key, value) in before) {
            assertEquals(
                value, after[key],
                "coverage preservation: $key must compose to the same value after migration",
            )
        }

        // And the one addition, named explicitly so a future change to it fails here.
        val colourKey = CueAssignmentResolver.Key.fixture("hex-2", "rgbColour")
        assertNull(
            before[colourKey],
            "precondition: a palette-targeted ref cannot resolve once the resolver reads Looks",
        )
        val recovered = after[colourKey] as? CueAssignmentResolver.PropertyValue.Colour
        assertNotNull(recovered, "migration turns the palette into a Look, so the ref resolves again")
        assertEquals(java.awt.Color(255, 136, 0), recovered.value.color, "#ff8800, per the palette")

        assertEquals(
            before.keys + colourKey, after.keys,
            "the recovered colour is the *only* difference — no key appears from nowhere",
        )
    }

    @Test
    fun `layered intensity flips from HTP max to later-wins, as designed`() {
        // The one documented behaviour change. Before: the cue's own row and the preset's row sat
        // at the same priority and HTP took max(), so a dim cue row lost to a bright preset. After:
        // the local layer wins outright. Pinned so the flip is a decision, not a surprise.
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

        val (presetId, _) = seedPreset("bright", dimmer = "255", withEffect = false)
        val cueId = seedCue()
        transaction(state.database) {
            val cue = DaoCue.findById(cueId)!!
            DaoCuePropertyAssignment.new {
                this.cue = cue; targetType = "fixture"; targetKey = "hex-1"
                propertyName = "dimmer"; value = "40"; sortOrder = 0
            }
            DaoCuePresetApplication.new {
                this.cue = cue
                preset = DaoFxPreset.findById(presetId)!!
                targets = listOf(CueTargetDto("fixture", "hex-1"))
                sortOrder = 1
            }
        }

        val key = CueAssignmentResolver.Key.fixture("hex-1", "dimmer")

        val before = transaction(state.database) {
            val applyData = buildCueApplyData(DaoCue.findById(cueId)!!)
            val preset = DaoFxPreset.findById(presetId)!!
            val rows = buildCueAssignmentsForCue(
                state.show.fixtures, applyData, PaletteCascade.EMPTY, state.show.lookRegistry,
            ) + buildCueAssignmentsForPreset(
                state.show.fixtures, cueId, 1, presetId, preset.toPropertyAssignmentDtos(),
                listOf(CueTargetDto("fixture", "hex-1")),
                lookRegistry = state.show.lookRegistry,
            )
            CueAssignmentResolver().resolve(rows)[key]
        }
        assertEquals(
            CueAssignmentResolver.PropertyValue.Slider(255u), before,
            "before: HTP max() let the brighter preset row win over the cue's own 40",
        )

        migrate()

        val after = transaction(state.database) {
            val applyData = buildCueApplyData(DaoCue.findById(cueId)!!)
            CueAssignmentResolver().resolve(
                uk.me.cormack.lighting7.routes.buildCombinedCueLayerRows(state, cueId, applyData)
            )[key]
        }
        assertEquals(
            CueAssignmentResolver.PropertyValue.Slider(40u), after,
            "after: the local layer wins outright — the intended change, and the one an operator " +
                "is most likely to be surprised by",
        )
    }
}
