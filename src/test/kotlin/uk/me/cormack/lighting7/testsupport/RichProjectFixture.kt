package uk.me.cormack.lighting7.testsupport

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.EffectMode
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.fx.ParameterInfo
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCuePresetApplication
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueSlot
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueTrigger
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFxDefinition
import uk.me.cormack.lighting7.models.DaoFxPreset
import uk.me.cormack.lighting7.models.DaoFxPresetPropertyAssignment
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPaletteEntry
import uk.me.cormack.lighting7.models.PaletteType
import uk.me.cormack.lighting7.fx.paletteRefValue
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoPromptBook
import uk.me.cormack.lighting7.models.DaoPromptBookAnchor
import uk.me.cormack.lighting7.models.DaoPromptBookAnnotation
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.DaoStageRegion
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.FxPresetEffectDto
import uk.me.cormack.lighting7.models.PromptBookRectDto
import uk.me.cormack.lighting7.models.TriggerType
import uk.me.cormack.lighting7.scripts.ScriptType
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.Overrides

/** Name of the project [seedRichProject] creates. */
const val RICH_PROJECT_NAME = "round-trip-rich"

/** SHA-256 the fixture's prompt book claims as its script hash. */
const val RICH_PROJECT_SCRIPT_HASH = "a2c4e6081a2c4e6081a2c4e6081a2c4e6081a2c4e6081a2c4e6081a2c4e60810"

/**
 * Seeds one project populated across **every portable table**, with the interesting nullable
 * and defaulted columns given non-default values.
 *
 * Shared by the three tests that guard the sync/clone contract, and required to stay
 * exhaustive by `SyncCoverageTest`:
 *
 *  * `ProjectRoundTripTest` — export → import → export must be byte-identical, which pins
 *    the *importer* to the exporter.
 *  * `ProjectCloneTest` — clone must reproduce the graph, which pins *clone* to the exporter.
 *  * `SyncCoverageTest` — every table declared portable must produce records here, which pins
 *    the *exporter* (and this fixture) to the schema.
 *
 * Populating non-default values matters as much as covering tables: a field left at its
 * default is omitted from canonical JSON, so a copier that drops it looks correct.
 *
 * @return the new project's id.
 */
fun seedRichProject(state: State): Int = transaction(state.database) {
    val project = DaoProject.new {
        name = RICH_PROJECT_NAME
        description = "exercises every synced table"
        isCurrent = true
        stageWidthM = 12.0
        stageDepthM = 8.0
        stageHeightM = 6.0
    }

    // 2 universes, 4 patches, 2 groups. The address is machine-local (Phase 2 cloud sync)
    // and lives in machine_overrides — set via Overrides.setUniverseAddress, not on the column.
    val u0 = DaoUniverseConfig.new {
        this.project = project
        subnet = 0; universe = 0; controllerType = "MOCK"
    }
    val u1 = DaoUniverseConfig.new {
        this.project = project
        subnet = 0; universe = 1; controllerType = "MOCK"
    }
    Overrides.setUniverseAddress(project.id.value, u0.uuid, "10.0.0.1")

    // 2 riggings — one fully populated (covers all pose fields), one mostly null
    // (covers the omit-null canonical encoder for riggings too).
    val rigFront = DaoRigging.new {
        this.project = project
        name = "FOH Truss"
        kind = "TRUSS"
        positionX = 0.0
        positionY = -2.0
        positionZ = 6.0
        yawDeg = 0.0
        pitchDeg = 0.0
        rollDeg = 0.0
        lengthM = 9.0
        sortOrder = 0
    }
    val rigBoom = DaoRigging.new {
        this.project = project
        name = "Boom-SL"
        sortOrder = 1
    }

    // 2 stage regions — main stage + a thrust extension downstage.
    DaoStageRegion.new {
        this.project = project
        name = "main"
        centerX = 0.0; centerY = 0.0; centerZ = 0.0
        widthM = 12.0; depthM = 8.0; heightM = 0.0
        yawDeg = 0.0
        sortOrder = 0
    }
    DaoStageRegion.new {
        this.project = project
        name = "thrust"
        centerY = -5.0
        widthM = 4.0; depthM = 2.0
        sortOrder = 1
    }

    val patches = (1..4).map { i ->
        DaoFixturePatch.new {
            this.project = project
            universeConfig = if (i <= 2) u0 else u1
            fixtureTypeKey = "hex-fixture"
            key = "hex-$i"; displayName = "Hex $i"; startChannel = i * 10; sortOrder = i
            // Patches 1 & 2 hang from the FOH truss (offsets in its local frame);
            // patch 3 is a free-standing fixture; patch 4 has no geometry at all.
            if (i in 1..2) rigging = rigFront
            if (i == 3) rigging = rigBoom
            if (i <= 3) {
                stageX = (i - 2).toDouble()      // -1, 0, 1
                stageY = 4.5
                stageZ = -2.0 + i * 0.5
                baseYawDeg = if (i == 1) -90.0 else 45.0
                basePitchDeg = if (i == 2) 30.0 else null
            }
            // Optional patch metadata — only on some rows so both the set and unset
            // encodings are exercised.
            if (i == 1) {
                beamAngleDeg = 26
                gelCode = "L201"
                kindOverride = "wash"
            }
            // Patch 4 has no stage geometry at all — the stand-in for the case
            // stageHidden exists for (real DMX, not a stage object).
            if (i == 4) stageHidden = true
        }
    }
    val groupA = DaoFixtureGroup.new { this.project = project; name = "front-wash" }
    DaoFixtureGroupMember.new {
        group = groupA; fixturePatch = patches[0]; sortOrder = 0
    }
    DaoFixtureGroupMember.new {
        group = groupA; fixturePatch = patches[1]; sortOrder = 1; panOffset = 30.0; tiltOffset = -5.0
    }
    val groupB = DaoFixtureGroup.new { this.project = project; name = "back-wash" }
    DaoFixtureGroupMember.new {
        group = groupB; fixturePatch = patches[2]; sortOrder = 0
    }

    // 2 scripts, of different ScriptType — a copier that ignores the column reverts both
    // to GENERAL and changes how they run.
    val script1 = DaoScript.new {
        this.project = project; name = "intro"; script = "// hello\nfixture(\"hex-1\")"
        scriptType = ScriptType.GENERAL
    }
    DaoScript.new {
        this.project = project; name = "fx-pack"; script = "// fx defs"
        scriptType = ScriptType.FX_DEFINITION
    }

    // 1 fx definition
    DaoFxDefinition.new {
        this.project = project
        effectId = "custom-flicker"; name = "Custom Flicker"
        category = "dimmer"; outputType = FxOutputType.SLIDER
        effectMode = EffectMode.STANDARD
        script = "// effect"
        timingSource = TimingSource.BEAT
        defaultStepTiming = true
        compatibleProperties = listOf("dimmer")
        // Non-empty so the importer's decodeFromJsonElement over this column is exercised —
        // left at its emptyList() default the field is omitted from canonical JSON entirely,
        // and a copier that lost every effect's parameter metadata would still pass.
        parameters = listOf(
            ParameterInfo(name = "depth", type = "double", defaultValue = "0.8", description = "flicker depth"),
            ParameterInfo(name = "seed", type = "int", defaultValue = "7"),
        )
    }

    // 1 fx preset with property assignments
    val preset = DaoFxPreset.new {
        this.project = project
        name = "warm-pulse"; fixtureType = "hex-fixture"
        description = "warm pulse"
        effects = listOf(
            FxPresetEffectDto(
                effectType = "Pulse", category = "dimmer",
                propertyName = "dimmer", beatDivision = 0.5,
                blendMode = "OVERRIDE", distribution = "LINEAR",
            )
        )
        palette = listOf("#ff8800")
    }
    DaoFxPresetPropertyAssignment.new {
        this.preset = preset; propertyName = "dimmer"; value = "200"; sortOrder = 0
        fadeDurationMs = 750L
    }
    DaoFxPresetPropertyAssignment.new {
        this.preset = preset; propertyName = "colour"; value = "#ff8800"; sortOrder = 1
        elementKey = "head-1"
    }

    // 2 named palettes of different types, each with non-default notes/sortOrder — a defaulted
    // field is omitted from canonical JSON entirely, so a copier that dropped it would still
    // look correct. The COLOUR palette carries both a fixture row and a group row so the
    // expansion-and-specificity path is exercised end to end.
    val colourPalette = DaoPalette.new {
        this.project = project
        name = "Warm Amber"; type = PaletteType.COLOUR.name
        notes = "act one wash"; sortOrder = 3
    }
    DaoPaletteEntry.new {
        palette = colourPalette; targetType = "fixture"; targetKey = "hex-1"
        propertyName = "colour"; value = "#ff8800"; sortOrder = 0
    }
    DaoPaletteEntry.new {
        palette = colourPalette; targetType = "group"; targetKey = "front-wash"
        propertyName = "colour"; value = "#ffaa44"; sortOrder = 1
    }
    val positionPalette = DaoPalette.new {
        this.project = project
        name = "Downstage Centre"; type = PaletteType.POSITION.name
        notes = "vocal spot"; sortOrder = 1
    }
    DaoPaletteEntry.new {
        palette = positionPalette; targetType = "fixture"; targetKey = "hex-3"
        propertyName = "position"; value = "120,64"; sortOrder = 0
    }
    // Presets share the cue value grammar, so they can hold refs too — same clone guarantee.
    DaoFxPresetPropertyAssignment.new {
        this.preset = preset; propertyName = "colour"
        value = paletteRefValue(colourPalette.uuid); sortOrder = 2
    }

    // 2 cue stacks, 3 cues, with property assignments + ad-hoc + preset apps + triggers
    val stack1 = DaoCueStack.new {
        this.project = project; name = "show-1"; palette = emptyList(); loop = false
        type = CueStackType.STACK.name; sortOrder = 0
    }
    val stack2 = DaoCueStack.new {
        this.project = project; name = "show-2"; palette = emptyList(); loop = true
        type = CueStackType.STACK.name; sortOrder = 2
    }
    val cue1 = DaoCue.new {
        this.project = project; name = "open"; cueStack = stack1; sortOrder = 0
        palette = listOf("#000000"); fadeDurationMs = 1000L
        // Every optional cue field set, so a copier that skips one is caught.
        cueNumber = "1.5"
        notes = "house to half, then go"
        cueType = CueType.STANDARD.name
        stomp = true
        updateGlobalPalette = true
        autoAdvance = true
        autoAdvanceDelayMs = 2500L
        fadeCurve = "SINE_IN_OUT"
    }
    DaoCuePropertyAssignment.new {
        cue = cue1; targetType = "fixture"; targetKey = "hex-1"
        propertyName = "dimmer"; value = "255"; sortOrder = 0
        fadeDurationMs = 1500L
    }
    DaoCuePropertyAssignment.new {
        cue = cue1; targetType = "group"; targetKey = "front-wash"
        propertyName = "position"; value = "120,64"; sortOrder = 1
        moveInDark = true
    }
    // A named-palette reference, stored as `ref:{uuid}` in the opaque value column. This is
    // the row that proves the reference survives a clone: ExportUuidRemapper mints a fresh
    // uuid for the palette and must rewrite this string to match, so the clone's row points
    // at the clone's palette rather than at the original's (or at nothing).
    DaoCuePropertyAssignment.new {
        cue = cue1; targetType = "fixture"; targetKey = "hex-2"
        propertyName = "colour"; value = paletteRefValue(colourPalette.uuid); sortOrder = 2
    }
    DaoCueAdHocEffect.new {
        cue = cue1; targetType = "fixture"; targetKey = "hex-1"
        effectType = "Pulse"; category = "dimmer"; beatDivision = 0.5
        blendMode = "OVERRIDE"; distribution = "LINEAR"
        parameters = mapOf("depth" to "0.8")
        propertyName = "dimmer"
        phaseOffset = 0.25
        elementMode = "ALL"
        elementFilter = "ODD"
        stepTiming = true
        delayMs = 100L
        intervalMs = 200L
        randomWindowMs = 50L
        sortOrder = 3
    }
    DaoCuePresetApplication.new {
        cue = cue1; this.preset = preset; targets = emptyList()
        delayMs = 250L
        intervalMs = 500L
        randomWindowMs = 125L
        sortOrder = 2
    }
    DaoCueTrigger.new {
        cue = cue1; this.script = script1
        triggerType = TriggerType.ACTIVATION; sortOrder = 0
        delayMs = 400L
        intervalMs = 800L
        randomWindowMs = 200L
    }
    DaoCue.new {
        this.project = project; name = "build"; cueStack = stack1; sortOrder = 1
        palette = emptyList()
    }
    DaoCue.new {
        this.project = project; name = "finale"; cueStack = stack2; sortOrder = 0
        palette = emptyList()
        cueType = CueType.MARKER.name
    }

    // separator between the two stacks (replaces the old show MARKER entry); the stacks'
    // sortOrder values above define show order.
    DaoCueStack.new {
        this.project = project; name = "intermission"; label = "intermission"
        palette = emptyList(); loop = false; type = CueStackType.SEPARATOR.name; sortOrder = 1
    }

    // cue slot
    DaoCueSlot.new {
        this.project = project; page = 1; slotIndex = 1; cue = cue1
    }
    DaoCueSlot.new {
        this.project = project; page = 1; slotIndex = 2; cueStack = stack2
    }

    // prompt book with an anchor (FK-by-UUID to a cue) and two annotation kinds
    val promptBook = DaoPromptBook.new {
        this.project = project
        scriptHash = RICH_PROJECT_SCRIPT_HASH
        scriptFileName = "act-one.pdf"
        pageCount = 12
        coverPages = 3
    }
    DaoPromptBookAnchor.new {
        this.promptBook = promptBook; cue = cue1
        region = listOf(PromptBookRectDto(page = 0, x = 0.1, y = 0.2, w = 0.8, h = 0.05))
        label = "LX 1"
    }
    DaoPromptBookAnnotation.new {
        this.promptBook = promptBook; kind = "STRIKETHROUGH"
        region = listOf(PromptBookRectDto(page = 1, x = 0.1, y = 0.5, w = 0.8, h = 0.1))
    }
    DaoPromptBookAnnotation.new {
        this.promptBook = promptBook; kind = "NOTE"
        region = listOf(PromptBookRectDto(page = 2, x = 0.05, y = 0.9, w = 0.4, h = 0.03))
        text = "slow build, watch conductor"; color = "#ffb000"; tone = "warn"
    }

    // parked channels — two on universe 0, one on universe 1, exercises sort + UUID round-trip
    DaoParkedChannel.new {
        this.project = project; universe = 0; channel = 5; value = 128
    }
    DaoParkedChannel.new {
        this.project = project; universe = 0; channel = 12; value = 0
    }
    DaoParkedChannel.new {
        this.project = project; universe = 1; channel = 7; value = 255
    }

    // control surface bindings — one bank-scoped, one global (null bank)
    DaoControlSurfaceBinding.new {
        this.project = project
        deviceTypeKey = "xtouch-mini"; controlId = "fader-1"; bank = "bank-a"
        targetType = "fixtureProperty"
        targetPayload = """{"type":"fixtureProperty","key":"hex-1","property":"dimmer"}"""
        takeoverPolicy = "PICKUP"
        sortOrder = 0
    }
    DaoControlSurfaceBinding.new {
        this.project = project
        deviceTypeKey = "xtouch-mini"; controlId = "button-1"
        targetType = "grandMaster"
        targetPayload = """{"type":"grandMaster"}"""
        sortOrder = 1
    }

    // Wire up the show playhead to the first stack.
    project.activeStackId = stack1.id.value
    project.id.value
}
