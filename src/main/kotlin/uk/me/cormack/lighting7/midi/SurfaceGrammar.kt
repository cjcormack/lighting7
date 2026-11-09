package uk.me.cormack.lighting7.midi

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.SubselectMode
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The one translate from a binding stored before the grammar (surface-setup plan session 1) to a
 * [Binding] (P4): database step 15 (`state/SurfaceGrammarMigration.kt`), the archive reader
 * (`sync/LegacyNames.kt`) and the REST and WebSocket bridge for the screens that still speak the old
 * union all call [row] or [binding], so a database and an archive written before the grammar arrive
 * at the same bytes.
 *
 * An old payload is a JSON object with a `type` and no `does`, at today's names (step 6 and
 * [uk.me.cormack.lighting7.sync.LegacyNames.bindingPayload] run first). Each old variant maps to a
 * function, a subject and a How: the on/off forms become their toggles with a How of On or Off, a
 * flash's raw max a percentage, a cue or stack named by int alone its uuid when [Ints] can say it.
 * What cannot be translated — a type this build never knew, an int that resolves to nothing, a
 * malformed uuid — answers null, and the caller keeps the row's bytes as they are, which then read
 * as [Does.Unknown].
 *
 * [legacyTarget] is the reverse, for the screens session 3 replaces: a [Binding] in the old union's
 * words, ints included, which only the desk can supply.
 */
object SurfaceGrammar {
    /** What resolves a cue or stack an old row names by int alone, in the row's own project. */
    interface Ints {
        fun stackUuid(id: Int): UUID? = null
        fun cueUuid(id: Int): UUID? = null
    }

    /** No ints resolve: an archive's rows, whose ints are another desk's. */
    object NoInts : Ints

    /** What the reverse needs that a binding no longer carries. */
    interface Ids {
        fun stackId(uuid: UUID): Int? = null
        fun cueId(uuid: UUID): Int? = null

        /** The stack an entry belongs to. */
        fun entryStack(entry: UUID): UUID? = null
    }

    /** A translated row: its new `target_type`, payload and part. */
    data class Row(val targetType: String, val payload: String, val part: ControlPart)

    /** The old variants a fader or encoder turn drove; every other old variant was a button's. */
    private val CONTINUOUS_TYPES = setOf(
        "fixtureAttribute", "groupAttribute", "selectionAttribute", "selectionFan",
        "speedMasterBpm", "grandMasterFader", "stackFxAmount", "fxSpeed",
    )

    /** Whether [payload] is a binding written before the grammar: an object with a `type` and no `does`. */
    fun isOld(payload: JsonObject): Boolean = "type" in payload && "does" !in payload

    /** [isOld] of a stored payload; false for one that is not a JSON object. */
    fun isOld(payload: String): Boolean =
        (runCatching { BindingJson.parseToJsonElement(payload) }.getOrNull() as? JsonObject)?.let(::isOld) ?: false

    /**
     * One stored row in the new grammar: null when [payload] is not an old binding (already
     * translated, or not JSON), or when it cannot be translated — the row is then kept as it is.
     * An encoder holding a button variant becomes its push, read from the device profile.
     */
    fun row(deviceTypeKey: String, controlId: String, payload: String, ints: Ints): Row? {
        val old = runCatching { BindingJson.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
        if (!isOld(old)) return null
        val binding = binding(old, ints) ?: return null
        val descriptor = ControlSurfaceRegistry.typeFor(deviceTypeKey)?.controls?.firstOrNull { it.controlId == controlId }
        return Row(binding.functionId, binding.encodePayload(), partFor(descriptor, old.string("type")))
    }

    /** The part an old variant of [oldType] sits on, on [descriptor]: an encoder's push for a button variant. */
    fun partFor(descriptor: ControlDescriptor?, oldType: String?): ControlPart =
        if (descriptor is EncoderDescriptor && descriptor.pushNote != null && oldType !in CONTINUOUS_TYPES &&
            oldType != "strip"
        ) ControlPart.PUSH else ControlPart.MAIN

    /** [old] as a [Binding], or null when it cannot be translated. */
    fun binding(old: JsonObject, ints: Ints): Binding? = try {
        translate(old, ints)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun translate(old: JsonObject, ints: Ints): Binding? {
        val axis = old.string("colourAxis")?.let { axisOf(it) ?: return null }
        return when (old.string("type")) {
            "fixtureAttribute" -> Binding(Does.Level(old.req("attribute"), axis), Subject.Fixture(old.req("fixtureKey")))
            "groupAttribute" -> Binding(Does.Level(old.req("attribute"), axis), Subject.Group(old.req("groupName")))
            "selectionAttribute" -> Binding(Does.Level(old.req("attribute"), axis), Subject.Selection)
            "cueStackGo" -> Binding(Does.Go, stack(old, ints) ?: return null)
            "cueStackBack" -> Binding(Does.Back, stack(old, ints) ?: return null)
            "cueStackPause" -> Binding(Does.Pause, stack(old, ints) ?: return null)
            "cueStackStop" -> Binding(Does.Stop, record(RecordKind.STACK, old.string("stackUuid")) ?: return null)
            "cueStackGoTo" -> Binding(Does.GoTo, record(RecordKind.ENTRY, old.string("entryUuid")) ?: return null)
            "cueStackStandby" -> Binding(Does.Standby, record(RecordKind.ENTRY, old.string("entryUuid")) ?: return null)
            "stackFxAmount" -> Binding(Does.FxAmount, stack(old, ints) ?: return null)
            "fireCue" -> Binding(Does.Cue, cue(old, ints) ?: return null)
            "looseCueSet" -> Binding(Does.Cue, record(RecordKind.CUE, old.string("cueUuid")) ?: return null, onOff(old) ?: return null)
            "fireTrigger" -> Binding(Does.Fire(old.req("trigger")), Subject.Fixture(old.req("fixtureKey")))
            "flash" -> flash(old)
            "blackout" -> Binding(Does.Blackout)
            "blackoutSet" -> Binding(Does.Blackout, how = onOff(old) ?: return null)
            "grandMasterToggle", "grandMasterFader" -> Binding(Does.GrandMaster())
            "grandMasterLevel" -> Binding(Does.GrandMaster(old.double("level") ?: return null))
            "setBank" -> Binding(Does.Layer(old.req("bank")))
            "speedMasterBpm" -> Binding(
                Does.Tempo(old.double("minBpm") ?: Does.Tempo.DEFAULT_MIN_BPM, old.double("maxBpm") ?: Does.Tempo.DEFAULT_MAX_BPM),
                master(old) ?: return null,
            )
            "speedMasterSetBpm" -> Binding(Does.Tempo(bpm = old.double("bpm") ?: return null), master(old) ?: return null)
            "speedMasterTap" -> Binding(Does.Tap, master(old) ?: return null)
            "speedMasterScale" -> Binding(Does.TempoScale(old.double("factor") ?: return null), master(old) ?: return null)
            "fxSpeed" -> Binding(Does.FxSpeed, Subject.Selection)
            "selectionFan" -> Binding(Does.Fan(fanAxisOf(old.req("axis")) ?: return null), Subject.Selection)
            "selectTarget" -> Binding(
                Does.Select,
                target(old) ?: return null,
                when (old.string("mode")) {
                    null, "toggle" -> null
                    "replace" -> How.REPLACE
                    else -> return null
                },
            )
            "clearSelection" -> Binding(Does.SelectionStep(SelectionMove.CLEAR))
            "locateSelection" -> Binding(Does.Locate, Subject.Selection)
            "strip" -> Binding(Does.Strip, target(old) ?: return null)
            "encoderBankSet" -> Binding(Does.EncoderPage(old.req("attribute"), axis))
            "applyLook" -> Binding(Does.Look, record(RecordKind.LOOK, old.string("lookUuid")) ?: return null)
            "lookSet" -> Binding(Does.Look, record(RecordKind.LOOK, old.string("lookUuid")) ?: return null, onOff(old) ?: return null)
            "pressPalette" -> Binding(Does.Palette(uuidOf(old.string("paletteUuid")) ?: return null), Subject.Selection)
            "pressPad" -> Binding(Does.Pad, record(RecordKind.PAD, old.string("padUuid")) ?: return null)
            "pickUpPad" -> Binding(Does.PickUp, record(RecordKind.PAD, old.string("padUuid")) ?: return null)
            "handPlaceInBank" -> Binding(Does.Place, record(RecordKind.BANK, old.string("bankUuid")) ?: return null)
            "handDrop" -> Binding(Does.Drop)
            "undo" -> Binding(Does.Undo(UndoKind.UNDO))
            "redo" -> Binding(Does.Undo(UndoKind.REDO))
            "programmerUndo" -> Binding(Does.Undo(UndoKind.PROGRAMMER))
            "buskPageNext" -> Binding(Does.BuskPage(1))
            "buskPagePrev" -> Binding(Does.BuskPage(-1))
            "buskPageSet" -> Binding(Does.BuskPage(), record(RecordKind.PAGE, old.string("pageUuid")) ?: return null)
            "buskFocusSet" -> Binding(Does.WindowFocus(old.req("windowName"), old.req("focus")))
            "buskSheetToggle" -> Binding(Does.WindowSheet(old.req("windowName")))
            "selectionNext" -> Binding(Does.SelectionStep(SelectionMove.NEXT))
            "selectionPrev" -> Binding(Does.SelectionStep(SelectionMove.PREV))
            "selectionCells" -> Binding(
                Does.SelectionStep(old.string("mode")?.let(SubselectMode::byName)?.let(SelectionMove::of) ?: return null),
            )
            else -> null
        }
    }

    private fun flash(old: JsonObject): Binding? {
        val inner = old["target"] as? JsonObject ?: return null
        val max = old.int("max") ?: 255
        require(max in 0..255) { "A flash's max is 0..255" }
        val subject = when (inner.string("type")) {
            "fixtureAttribute" -> Subject.Fixture(inner.req("fixtureKey"))
            "groupAttribute" -> Subject.Group(inner.req("groupName"))
            else -> return null
        }
        return Binding(Does.Flash(inner.req("attribute"), (max * 100.0 / 255).roundToInt()), subject)
    }

    /** An old stack variant's stack: its uuid, else its int through [ints]. */
    private fun stack(old: JsonObject, ints: Ints): Subject? = when (val raw = old.string("stackUuid")) {
        null -> old.int("stackId")?.let(ints::stackUuid)?.let { Subject.Record(RecordKind.STACK, it) }
        else -> record(RecordKind.STACK, raw)
    }

    private fun cue(old: JsonObject, ints: Ints): Subject? = when (val raw = old.string("cueUuid")) {
        null -> old.int("cueId")?.let(ints::cueUuid)?.let { Subject.Record(RecordKind.CUE, it) }
        else -> record(RecordKind.CUE, raw)
    }

    /** A tempo variant's master: none for master 1. */
    private fun master(old: JsonObject): Subject? = when (val raw = old.string("masterUuid")) {
        null -> Subject.None
        else -> record(RecordKind.MASTER, raw)
    }

    private fun target(old: JsonObject): Subject? {
        val target = old["target"] as? JsonObject ?: return null
        return when (TargetRef.ofOrNull(target.string("type") ?: return null, target.string("key") ?: return null)) {
            is TargetRef.Group -> Subject.Group(target.req("key"))
            is TargetRef.Fixture -> Subject.Fixture(target.req("key"))
            null -> null
        }
    }

    private fun onOff(old: JsonObject): How? = when (old.boolean("on")) {
        true -> How.ON
        false -> How.OFF
        null -> null
    }

    private fun record(kind: RecordKind, raw: String?): Subject? = uuidOf(raw)?.let { Subject.Record(kind, it) }

    private fun uuidOf(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun axisOf(raw: String): ColourAxis? = ColourAxis.entries.firstOrNull { serialName(it) == raw }

    private fun fanAxisOf(raw: String): FanAxis? = FanAxis.entries.firstOrNull { it.name == raw }

    private fun serialName(axis: ColourAxis): String = when (axis) {
        ColourAxis.HUE -> "hue"
        ColourAxis.HUE_FINE -> "hueFine"
        ColourAxis.SATURATION -> "saturation"
        ColourAxis.BRIGHTNESS -> "brightness"
    }

    /**
     * [binding] in the old union's words, for the screens session 3 replaces. [deviceTypeKey] is the
     * row's device (a layer button named its device), and [button] whether the part is a button's (a
     * grand master without a level was a fader there and a toggle on a button).
     */
    fun legacyTarget(binding: Binding, deviceTypeKey: String, button: Boolean, ids: Ids): JsonObject {
        val on = binding.on
        val how = binding.how
        fun obj(type: String, fill: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
            put("type", type)
            fill()
        }
        fun uuidIn(kind: RecordKind) = on.recordUuid(kind)?.toString()
        fun attributeTarget(attribute: String, axis: ColourAxis?): JsonObject = when (on) {
            is Subject.Fixture -> obj("fixtureAttribute") { put("fixtureKey", on.key); put("attribute", attribute); axis?.let { put("colourAxis", serialName(it)) } }
            is Subject.Group -> obj("groupAttribute") { put("groupName", on.key); put("attribute", attribute); axis?.let { put("colourAxis", serialName(it)) } }
            else -> obj("selectionAttribute") { put("attribute", attribute); axis?.let { put("colourAxis", serialName(it)) } }
        }
        fun masterUuid(builder: kotlinx.serialization.json.JsonObjectBuilder) {
            uuidIn(RecordKind.MASTER)?.let { builder.put("masterUuid", it) }
        }
        fun stackFields(builder: kotlinx.serialization.json.JsonObjectBuilder) {
            val stack = on.recordUuid(RecordKind.STACK)
            builder.put("stackId", stack?.let(ids::stackId) ?: 0)
            stack?.let { builder.put("stackUuid", it.toString()) }
        }
        fun targetOf(): JsonObject = when (on) {
            is Subject.Group -> buildJsonObject { put("type", TargetRef.Group.TYPE); put("key", on.key) }
            is Subject.Fixture -> buildJsonObject { put("type", TargetRef.Fixture.TYPE); put("key", on.key) }
            else -> buildJsonObject { put("type", TargetRef.Fixture.TYPE); put("key", "") }
        }
        return when (val does = binding.does) {
            is Does.Level -> attributeTarget(does.attribute, does.axis)
            is Does.Flash -> obj("flash") {
                put("target", attributeTarget(does.attribute, null))
                put("max", (does.percent * 255.0 / 100).roundToInt())
            }
            Does.Select -> obj("selectTarget") {
                put("target", targetOf())
                put("mode", if (how == How.REPLACE) "replace" else "toggle")
            }
            Does.Locate -> obj("locateSelection")
            is Does.Fan -> obj("selectionFan") { put("axis", does.axis.name) }
            Does.FxSpeed -> obj("fxSpeed")
            is Does.Palette -> obj("pressPalette") { put("paletteUuid", does.palette.toString()) }
            Does.Look -> when (how) {
                How.ON, How.OFF -> obj("lookSet") { put("lookUuid", uuidIn(RecordKind.LOOK) ?: ""); put("on", how == How.ON) }
                else -> obj("applyLook") { put("lookUuid", uuidIn(RecordKind.LOOK) ?: "") }
            }
            Does.Cue -> when (how) {
                How.ON, How.OFF -> obj("looseCueSet") { put("cueUuid", uuidIn(RecordKind.CUE) ?: ""); put("on", how == How.ON) }
                else -> obj("fireCue") {
                    val cue = on.recordUuid(RecordKind.CUE)
                    put("cueId", cue?.let(ids::cueId) ?: 0)
                    cue?.let { put("cueUuid", it.toString()) }
                }
            }
            Does.Pad -> obj("pressPad") { put("padUuid", uuidIn(RecordKind.PAD) ?: "") }
            Does.PickUp -> obj("pickUpPad") { put("padUuid", uuidIn(RecordKind.PAD) ?: "") }
            Does.Place -> obj("handPlaceInBank") { put("bankUuid", uuidIn(RecordKind.BANK) ?: "") }
            Does.Drop -> obj("handDrop")
            Does.Go -> obj("cueStackGo") { stackFields(this) }
            Does.Back -> obj("cueStackBack") { stackFields(this) }
            Does.Pause -> obj("cueStackPause") { stackFields(this) }
            Does.Stop -> obj("cueStackStop") { put("stackUuid", uuidIn(RecordKind.STACK) ?: "") }
            Does.GoTo, Does.Standby -> obj(if (does == Does.GoTo) "cueStackGoTo" else "cueStackStandby") {
                val entry = on.recordUuid(RecordKind.ENTRY)
                put("stackUuid", entry?.let(ids::entryStack)?.toString() ?: "")
                put("entryUuid", entry?.toString() ?: "")
            }
            Does.FxAmount -> obj("stackFxAmount") { stackFields(this) }
            is Does.Fire -> obj("fireTrigger") { put("fixtureKey", (on as? Subject.Fixture)?.key ?: ""); put("trigger", does.trigger) }
            Does.Blackout -> when (how) {
                How.ON, How.OFF -> obj("blackoutSet") { put("on", how == How.ON) }
                else -> obj("blackout")
            }
            is Does.GrandMaster -> when {
                does.level != null -> obj("grandMasterLevel") { put("level", does.level) }
                button -> obj("grandMasterToggle")
                else -> obj("grandMasterFader")
            }
            is Does.Tempo -> if (does.bpm != null) {
                obj("speedMasterSetBpm") { masterUuid(this); put("bpm", does.bpm) }
            } else {
                obj("speedMasterBpm") { masterUuid(this); put("minBpm", does.minBpm); put("maxBpm", does.maxBpm) }
            }
            Does.Tap -> obj("speedMasterTap") { masterUuid(this) }
            is Does.TempoScale -> obj("speedMasterScale") { masterUuid(this); put("factor", does.factor) }
            is Does.Layer -> obj("setBank") { put("deviceTypeKey", deviceTypeKey); put("bank", does.layer) }
            is Does.EncoderPage -> obj("encoderBankSet") { put("attribute", does.attribute); does.axis?.let { put("colourAxis", serialName(it)) } }
            is Does.Undo -> obj(
                when (does.kind) {
                    UndoKind.UNDO -> "undo"
                    UndoKind.REDO -> "redo"
                    UndoKind.PROGRAMMER -> "programmerUndo"
                },
            )
            is Does.BuskPage -> when {
                on is Subject.Record -> obj("buskPageSet") { put("pageUuid", on.uuid.toString()) }
                (does.step ?: 1) < 0 -> obj("buskPagePrev")
                else -> obj("buskPageNext")
            }
            is Does.WindowFocus -> obj("buskFocusSet") { put("windowName", does.window); put("focus", does.focus) }
            is Does.WindowSheet -> obj("buskSheetToggle") { put("windowName", does.window) }
            is Does.SelectionStep -> when (does.step) {
                SelectionMove.CLEAR -> obj("clearSelection")
                SelectionMove.NEXT -> obj("selectionNext")
                SelectionMove.PREV -> obj("selectionPrev")
                else -> obj("selectionCells") { put("mode", does.step.name) }
            }
            Does.Strip -> obj("strip") { put("target", targetOf()) }
            is Does.Unknown -> obj("unknown") { put("targetType", does.storedType); put("rawPayload", does.rawPayload) }
        }
    }

    /** The old union's discriminator of [legacyTarget]'s answer. */
    fun legacyType(target: JsonObject): String = target.string("type") ?: "unknown"

    private fun JsonObject.value(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private fun JsonObject.string(key: String): String? = value(key)?.takeIf { it.isString }?.content

    private fun JsonObject.req(key: String): String = string(key) ?: throw IllegalArgumentException("'$key' is missing")

    private fun JsonObject.double(key: String): Double? = value(key)?.takeUnless { it.isString }?.doubleOrNull

    private fun JsonObject.int(key: String): Int? = value(key)?.takeUnless { it.isString }?.intOrNull

    private fun JsonObject.boolean(key: String): Boolean? = value(key)?.booleanOrNull
}
