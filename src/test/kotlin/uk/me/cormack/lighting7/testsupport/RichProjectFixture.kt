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
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplateEffect
import uk.me.cormack.lighting7.models.DaoTemplateRow
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookEffect
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueSlot
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueTrigger
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFxDefinition
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoPromptBook
import uk.me.cormack.lighting7.models.DaoPromptBookAnchor
import uk.me.cormack.lighting7.models.DaoPromptBookAnnotation
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.DaoSpeedMaster
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.models.DaoStageRegion
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.LookEffectSpec
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

    // 3 speed masters with every optional column set to a non-default value — master 1 is
    // the protected global master and would normally sit at its defaults, so the fixture
    // seeds masters 1 and 2 both off-default to keep the canonical-JSON round-trip honest.
    DaoSpeedMaster.new {
        this.project = project
        masterIndex = 1; name = "House Tempo"
        bpm = 128.0; source = SpeedMasterSource.TAP.name
        notes = "tapped at soundcheck"
        // Master 1 may not follow, so it carries the usage instead of the ratio.
        usageCategory = "dimmer"
    }
    val slowMaster = DaoSpeedMaster.new {
        this.project = project
        masterIndex = 2; name = "Slow Wash"
        bpm = 64.0; source = SpeedMasterSource.MANUAL.name
        notes = "half-time position waves"
        usageCategory = "position"
        // No target: the pre-follow-target spelling of "follows master 1", which still has to
        // survive a round trip as the null it is.
        followNum = 1; followDen = 2
    }
    // A third master following master 2 rather than master 1 — the follow *target* is a uuid
    // reference like any other, so it has to be exported, re-pointed by ExportUuidRemapper on
    // clone, and imported. A chain also proves the exporter doesn't quietly flatten one.
    DaoSpeedMaster.new {
        this.project = project
        masterIndex = 3; name = "Crawl"
        bpm = 32.0; source = SpeedMasterSource.MANUAL.name
        notes = "half of the half"
        followNum = 1; followDen = 2
        followTargetUuid = slowMaster.uuid
    }

    // 2 looks covering both targeting modes, because they exercise different code paths.
    //
    // Every optional field is set to a NON-default value on purpose: canonical JSON omits
    // defaults entirely, so a field left at its default is invisible to the round-trip and clone
    // tests and they would pass vacuously.

    // A *bound* look — rows naming their own targets, the shape a palette migrated into and the
    // shape Record produces. Carries both a fixture row and a group row so the
    // expansion-and-specificity path is exercised end to end.
    val boundLook = DaoLook.new {
        this.project = project
        name = "Warm Amber"
        notes = "act one wash"
        sortOrder = 3
    }
    DaoLookRow.new {
        look = boundLook; targetType = "fixture"; targetKey = "hex-1"
        propertyName = "colour"; value = "#ff8800"; sortOrder = 0
        fadeDurationMs = 750L
    }
    DaoLookRow.new {
        look = boundLook; targetType = "group"; targetKey = "front-wash"
        propertyName = "colour"; value = "#ffaa44"; sortOrder = 1
    }
    DaoLookRow.new {
        look = boundLook; targetType = "fixture"; targetKey = "hex-3"
        propertyName = "position"; value = "120,64"; sortOrder = 2
    }

    // An **effects-only** look, the shape an FX preset migrated into. Its rows would once have been
    // deferred too; session 3 moved that half of the entity out to `templates` (see the template
    // below), so what survives here is the effect — where a deferred target still means "fan over
    // whatever the layer points at".
    val effectsLook = DaoLook.new {
        this.project = project
        name = "warm-pulse"
        notes = "warm pulse"
        sortOrder = 1
    }
    DaoLookEffect.new {
        look = effectsLook; targetType = DEFERRED_TARGET_TYPE; targetKey = ""
        effectType = "Pulse"; category = "dimmer"; propertyName = "dimmer"
        beatDivision = 0.5; blendMode = "OVERRIDE"; distribution = "LINEAR"
        phaseOffset = 0.25
        elementMode = "ALL"
        elementFilter = "ODD"
        stepTiming = true
        parameters = mapOf("depth" to "0.8")
        // A real column now rather than a field inside a JSON blob, but the reference still has
        // to be remapped for a clone to point at its own master.
        speedMasterUuid = slowMaster.uuid
        rateSpeedMasterUuid = slowMaster.uuid
        sortOrder = 0
    }

    // A **generic** template: one deferred row, one family, an intent rather than a literal. This is
    // the half of the old deferred look that is not a look at all.
    val colourTemplate = DaoTemplate.new {
        this.project = project
        name = "amber-key"
        notes = "warm key light"
        sortOrder = 0
        fadeDurationMs = 1_500L
    }
    DaoTemplateRow.new {
        template = colourTemplate; targetType = DEFERRED_TARGET_TYPE; targetKey = ""
        propertyName = "rgbColour"; value = "#ff9d4a;policy=extract"; sortOrder = 0
    }

    // An **effect** template: no rows, one target-less effect (fx-templates D1–D3). Seeded with a
    // non-default value on every optional field, because canonical JSON omits defaults — a field
    // left at its default is invisible to the round-trip and clone tests, so a copier that drops it
    // would look correct.
    //
    // Its colour parameter names `colourTemplate` as `tmpl:{uuid}` (D12's allowed direction), which
    // is what proves `ExportUuidRemapper` rewrites a reference *inside a parameters map* on clone —
    // the analogue of the retired `ref:{boundLook.uuid}` case.
    val effectTemplate = DaoTemplate.new {
        this.project = project
        name = "amber-breathe"
        notes = "slow warm breathe on the selection"
        sortOrder = 2
        // Deliberately null: an effect has no arrival, so an effect template has no fade. Left as
        // the one default here so a copier inventing a fade for it is caught.
        fadeDurationMs = null
    }
    DaoTemplateEffect.new {
        template = effectTemplate
        effectType = "ColourPulse"; category = "colour"; propertyName = "rgbColour"
        beatDivision = 2.0; blendMode = "MAX"; distribution = "CENTER_OUT"
        phaseOffset = 0.125
        elementMode = "FLAT"
        elementFilter = "EVEN"
        stepTiming = false
        parameters = mapOf("colours" to "tmpl:${colourTemplate.uuid}", "depth" to "0.6")
        speedMasterUuid = slowMaster.uuid
        rateSpeedMasterUuid = slowMaster.uuid
    }

    // A **per-fixture** template — a focus position, where each head holds its own degrees. Seeded
    // as well as the generic one because the two row shapes are the case the library labels
    // *Generic* / *Per fixture*, and a copier that handles one and not the other must be caught.
    val positionTemplate = DaoTemplate.new {
        this.project = project
        name = "downstage-centre"
        sortOrder = 1
    }
    DaoTemplateRow.new {
        template = positionTemplate; targetType = "fixture"; targetKey = "hex-1"
        propertyName = "position"; value = "deg:12,-8"; sortOrder = 0
    }
    DaoTemplateRow.new {
        template = positionTemplate; targetType = "fixture"; targetKey = "hex-2"
        propertyName = "position"; value = "deg:-14,-8"; sortOrder = 1
    }

    // 2 cue stacks, 3 cues, with property assignments + ad-hoc + preset apps + triggers
    val stack1 = DaoCueStack.new {
        this.project = project; name = "show-1"; loop = false
        type = CueStackType.STACK.name; sortOrder = 0
    }
    val stack2 = DaoCueStack.new {
        this.project = project; name = "show-2"; loop = true
        type = CueStackType.STACK.name; sortOrder = 2
    }
    val cue1 = DaoCue.new {
        this.project = project; name = "open"; cueStack = stack1; sortOrder = 0
        // Every optional cue field set, so a copier that skips one is caught.
        fadeDurationMs = 1000L
        cueNumber = "1.5"
        notes = "house to half, then go"
        cueType = CueType.STANDARD.name
        stomp = true
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
    // There used to be a third row here holding `ref:{boundLook.uuid}`, seeded to prove
    // ExportUuidRemapper rewrote the reference on a clone. The `ref:` value grammar retired in
    // session 4 and a cue row can only hold a literal, so the case no longer exists to cover —
    // a cue depends on a Look through a `DaoCueLayer` FK now, and the two layers below are what
    // exercise the clone path.
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
        speedMasterUuid = slowMaster.uuid
        rateSpeedMasterUuid = slowMaster.uuid
    }
    // A timed layer over the deferred look — the shape a timed preset application migrated into,
    // carrying all three timing fields plus both speed-master overrides.
    DaoCueLayer.new {
        cue = cue1; look = effectsLook
        targets = listOf(CueTargetDto("group", "front-wash"))
        sortOrder = 2
        delayMs = 250L
        intervalMs = 500L
        randomWindowMs = 125L
        speedMasterUuid = slowMaster.uuid
        rateSpeedMasterUuid = slowMaster.uuid
    }
    // A layer applying a **template** rather than a look, so the polymorphic referent is exercised
    // on both arms — an export or clone that carried only `look_id` would lose this one silently.
    DaoCueLayer.new {
        cue = cue1; template = colourTemplate
        targets = listOf(CueTargetDto("fixture", "hex-2"))
        sortOrder = 4
        propertyMask = "COLOUR"
    }
    // A second layer exercising every field the first leaves at its default: disabled, masked,
    // non-OVERRIDE blend, partial amount, stomp.
    DaoCueLayer.new {
        cue = cue1; look = boundLook
        targets = listOf(CueTargetDto("fixture", "hex-1"))
        sortOrder = 3
        enabled = false
        propertyMask = "COLOUR"
        blendMode = "MULTIPLY"
        amount = 0.5
        stomp = true
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
    }
    DaoCue.new {
        this.project = project; name = "finale"; cueStack = stack2; sortOrder = 0
        cueType = CueType.MARKER.name
    }

    // separator between the two stacks (replaces the old show MARKER entry); the stacks'
    // sortOrder values above define show order.
    DaoCueStack.new {
        this.project = project; name = "intermission"; label = "intermission"
        loop = false; type = CueStackType.SEPARATOR.name; sortOrder = 1
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
