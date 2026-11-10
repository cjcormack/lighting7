package uk.me.cormack.lighting7.midi

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import uk.me.cormack.lighting7.models.DaoBuskPage
import uk.me.cormack.lighting7.models.DaoBuskPages
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntries
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.models.DaoSpeedMaster
import uk.me.cormack.lighting7.models.DaoSpeedMasters
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.routes.buskPageContents
import java.util.UUID

/**
 * What the desk calls everything a binding can name (surface-setup plan D15): records by their
 * names, groups and fixtures by theirs, with each one's colour. Read once per map rebuild, inside a
 * transaction, by [read]; the labels and the *Shows* sentences are written from it here, so no
 * client keeps a copy of the rules.
 */
data class SurfaceNames(
    val fixtures: Map<String, String> = emptyMap(),
    val groups: Map<String, String> = emptyMap(),
    val fixtureColours: Map<String, String> = emptyMap(),
    val groupColours: Map<String, String> = emptyMap(),
    val records: Map<RecordKind, Map<UUID, String>> = emptyMap(),
    /** Master 1's label, what a tempo function with no subject drives. */
    val masterOne: String = "M1",
) {
    fun record(kind: RecordKind, uuid: UUID): String? = records[kind]?.get(uuid)

    /** [on] in words, or null for none. A record that is gone is named by its kind. */
    fun subject(on: Subject): String? = when (on) {
        Subject.None -> null
        Subject.Selection -> "the selection"
        Subject.Slot -> "its slot"
        is Subject.Group -> groups[on.key] ?: on.key
        is Subject.Fixture -> fixtures[on.key] ?: on.key
        is Subject.Record -> record(on.kind, on.uuid) ?: "a missing ${RECORD_WORDS.getValue(on.kind)}"
    }

    /** [subject] as a label begins it: `The selection`. */
    fun subjectWord(on: Subject): String? = subject(on)?.replaceFirstChar { it.uppercase() }

    /** The colour of the group or fixture [on] names, or null. */
    fun colour(on: Subject): String? = when (on) {
        is Subject.Group -> groupColours[on.key]
        is Subject.Fixture -> fixtureColours[on.key] ?: fixtureColours[on.key.substringBefore('.')]
        else -> null
    }

    companion object {
        val EMPTY = SurfaceNames()

        /**
         * Every name in [projectId]. Must run inside a transaction. [elements] names the keys the patch
         * list does not hold — a fixture's heads — from the live rig.
         */
        fun read(projectId: Int, elements: Map<String, String> = emptyMap()): SurfaceNames {
            val rig = RigScope.of(projectId)
            val patches = rig.patches().toList()
            val groupRows = rig.groups().toList()
            val stacks = DaoCueStack.find { DaoCueStacks.project eq projectId }.toList()
            val stackNames = stacks.associate { it.uuid to it.name }
            val stackByRowId = stacks.associateBy { it.id.value }
            val entries = if (stacks.isEmpty()) emptyList() else
                DaoCueStackEntry.find { DaoCueStackEntries.stack inList stacks.map { it.id } }.toList()
            val cues = DaoCue.find { DaoCues.project eq projectId }.toList()
            val cueNames = cues.associate { it.uuid to it.name }
            val cueById = cues.associateBy { it.id.value }
            val entryNames = entries.mapNotNull { entry ->
                val cue = entry.cueId?.let { cueById[it] } ?: return@mapNotNull null
                val stack = stackByRowId[entry.stackId]?.name
                val number = entry.cueNumber?.takeIf { it.isNotBlank() }?.let { "Q$it" } ?: cue.name
                entry.uuid to listOfNotNull(number, stack).joinToString(" · ")
            }.toMap()
            val masters = DaoSpeedMaster.find { DaoSpeedMasters.project eq projectId }.toList()
            val masterLabel = { index: Int, name: String -> "M$index · $name" }
            val pages = DaoBuskPage.find { DaoBuskPages.project eq projectId }.toList()
            val contents = buskPageContents(pages.map { it.id.value })
            val looks = DaoLook.find { DaoLooks.project eq projectId }.associate { it.uuid to it.name }
            val palettes = DaoPalette.find { DaoPalettes.project eq projectId }.associate { it.uuid to it.name }
            val pads = contents.pads.values.mapNotNull { pad ->
                val name = pad.palette?.name ?: pad.look?.name ?: pad.cue?.name ?: pad.cueStack?.name
                name?.let { pad.uuid to it }
            }.toMap()
            val pageByColumn = contents.columns.mapValues { (_, column) -> column.page.name }
            val banks = contents.banks.values.associate { bank ->
                bank.uuid to listOfNotNull(bank.name, pageByColumn[bank.column.id.value]).joinToString(" · ")
            }
            return SurfaceNames(
                fixtures = elements + patches.associate { it.key to rig.nameOf(it) },
                groups = groupRows.associate { it.name to it.name },
                fixtureColours = patches.mapNotNull { p -> rig.colourOf(p)?.let { p.key to it } }.toMap(),
                groupColours = groupRows.mapNotNull { g -> rig.colourOf(g)?.let { g.name to it } }.toMap(),
                records = mapOf(
                    RecordKind.STACK to stackNames,
                    RecordKind.ENTRY to entryNames,
                    RecordKind.CUE to cueNames,
                    RecordKind.LOOK to looks,
                    RecordKind.PALETTE to palettes,
                    RecordKind.PAD to pads,
                    RecordKind.BANK to banks,
                    RecordKind.PAGE to pages.associate { it.uuid to it.name },
                    RecordKind.MASTER to masters.associate { it.uuid to masterLabel(it.masterIndex, it.name) },
                ),
                masterOne = masters.firstOrNull { it.masterIndex == 1 }?.let { masterLabel(1, it.name) } ?: "M1",
            )
        }
    }
}

private val RECORD_WORDS = mapOf(
    RecordKind.STACK to "cue stack",
    RecordKind.ENTRY to "cue",
    RecordKind.CUE to "cue",
    RecordKind.LOOK to "Look",
    RecordKind.PALETTE to "palette",
    RecordKind.PAD to "pad",
    RecordKind.BANK to "bank",
    RecordKind.PAGE to "page",
    RecordKind.MASTER to "speed master",
)

/** The labels and sentences a surface's editors draw, written on the desk (P5). */
object SurfaceLabels {
    /** A binding's short label, the one drawn under its control: `Dimmer · Front wash`, `GO · Act 1`. */
    fun label(binding: Binding, names: SurfaceNames, profile: ControlSurfaceRegistry.DeviceTypeInfo? = null): String {
        val on = binding.on
        val s = names.subjectWord(on)
        fun with(word: String) = if (s == null) word else "$word · $s"
        val how = binding.how
        return when (val does = binding.does) {
            is Does.Level -> with(attributeWord(does.attribute) + axisWord(does.axis))
            Does.PageLevel -> with("Page level")
            is Does.Flash -> with(if (does.percent == 100) "Flash" else "Flash ${does.percent}%")
            Does.Select -> with(if (how == How.REPLACE) "Select only" else "Select")
            Does.Locate -> with("Locate")
            is Does.Fan -> with("Fan ${FAN_WORDS.getValue(does.axis)}")
            Does.FxSpeed -> with("FX speed")
            is Does.Clear -> "Clear programmer"
            is Does.Blind -> if (binding.effectiveHow == How.WHILE_HELD) "Blind while held" else "Blind"
            is Does.Release -> with("Release")
            Does.Record -> with("Record")
            Does.Update -> "Update"
            is Does.Palette -> names.record(RecordKind.PALETTE, does.palette) ?: "A missing palette"
            Does.Look -> with(onOff("Look", how))
            Does.Cue -> with(onOff("Cue", how))
            Does.Pad -> with("Pad")
            Does.PickUp -> with("Pick up")
            Does.Place -> with("Place in")
            Does.Drop -> "Let go"
            Does.Go -> with("GO")
            Does.Back -> with("Back")
            Does.Pause -> with("Pause")
            Does.Stop -> with("Stop")
            Does.GoTo -> with("Go to")
            Does.Standby -> with("Standby")
            Does.FxAmount -> with("FX amount")
            is Does.Fire -> with("Fire ${does.trigger}")
            Does.Blackout -> onOff("Blackout", how)
            is Does.GrandMaster -> if (does.level == null) "Grand master" else "Grand master to ${number(does.level)}%"
            is Does.Tempo -> master(
                if (does.bpm == null) "Tempo" else "Tempo ${number(does.bpm)} BPM", on, names,
            )
            Does.Tap -> master("Tap", on, names)
            is Does.TempoScale -> master(if (does.factor < 1) "Tempo ×½" else "Tempo ×2", on, names)
            is Does.Layer -> "Layer ${layerName(profile, does.layer)}"
            is Does.EncoderPage -> "Encoder page · ${attributeWord(does.attribute)}${axisWord(does.axis)}"
            is Does.Undo -> when (does.kind) {
                UndoKind.UNDO -> "Undo"
                UndoKind.REDO -> "Redo"
                UndoKind.PROGRAMMER -> "Programmer undo"
            }
            is Does.BuskPage -> when {
                on is Subject.Record -> "Busk page · $s"
                does.step == 1 -> "Next busk page"
                does.step == -1 -> "Previous busk page"
                else -> "Busk page ${if ((does.step ?: 0) > 0) "+" else ""}${does.step}"
            }
            is Does.WindowFocus -> "Busk focus ${does.focus} · ${does.window}"
            is Does.WindowSheet -> "Busk sheet · ${does.window}"
            is Does.SelectionStep -> if (does.step == SelectionMove.CLEAR) "Clear selection"
                else "Selection · ${SELECTION_MOVE_WORDS.getValue(does.step).lowercase()}"
            is Does.Unknown -> "Unknown (${does.storedType})"
        }
    }

    /**
     * What the control shows for [binding], in a sentence (D6): a motor or ring reads Local first,
     * a button names its light. [control] says which picture the sentence is about.
     */
    fun shows(binding: Binding, control: ControlDescriptor?, names: SurfaceNames): String {
        val s = names.subject(binding.on) ?: "it"
        val continuous = when (control) {
            is FaderDescriptor -> if (control.hasMotor) "The motor shows" else "The fader picks up from"
            is EncoderDescriptor -> "The ring shows"
            else -> "Shows"
        }
        return when (val does = binding.does) {
            is Does.Level -> "$continuous what Local holds for $s's ${attributeWord(does.attribute).lowercase()}${axisWord(does.axis)}, " +
                "or the stage where Local holds nothing." +
                if (binding.on == Subject.Selection) " It goes dark while the selected heads differ." else ""
            Does.PageLevel -> "$continuous what Local holds for $s's attribute on the encoder page, " +
                "or the stage where Local holds nothing." +
                if (binding.on == Subject.Selection) " It goes dark while the selected heads differ." else ""
            is Does.Fan -> "$continuous the fan amount. The centre is no fan."
            Does.FxSpeed -> "$continuous the speed of the first Local FX on $s."
            Does.FxAmount -> "$continuous $s's FX amount."
            is Does.Tempo -> if (does.bpm == null) {
                "$continuous ${masterName(binding.on, names)}'s tempo across ${number(does.minBpm)}–${number(does.maxBpm)} BPM."
            } else {
                NO_LIGHT
            }
            is Does.GrandMaster -> when {
                does.level != null -> NO_LIGHT
                control is ButtonDescriptor -> "Lit while the grand master is up."
                else -> "$continuous the grand master."
            }
            Does.Select -> "Lit while all of $s is selected." +
                if (binding.effectiveHow == How.TOGGLE_HOLD_REPLACES) " Hold for half a second to select only it." else ""
            is Does.Flash -> "Lit while held. Lets go to whatever was under it."
            Does.Locate -> "Lit while $s is located."
            is Does.Blind -> "Lit while the programmer is Blind."
            is Does.Palette -> "Lit while ${names.record(RecordKind.PALETTE, does.palette) ?: "the palette"} covers the selection."
            Does.Look -> "Lit while $s is on."
            Does.Cue -> "Lit while a press holds $s."
            Does.Pad -> "Lit while $s is on."
            Does.Go -> "Lit while $s is running."
            Does.Blackout -> if (binding.how == How.OFF) "Lit while blackout is off." else "Lit while blackout is on."
            is Does.EncoderPage -> "Lit while ${attributeWord(does.attribute).lowercase()} is the encoder page."
            is Does.BuskPage -> if (binding.on is Subject.Record) "Lit while $s is showing." else NO_LIGHT
            Does.PickUp, Does.Place, Does.Drop, Does.Back, Does.Pause, Does.Stop, Does.GoTo, Does.Standby, is Does.Fire,
            Does.Tap, is Does.TempoScale, is Does.Layer, is Does.Undo, is Does.WindowFocus, is Does.WindowSheet,
            is Does.SelectionStep, is Does.Clear, is Does.Release, Does.Record, Does.Update, is Does.Unknown,
                -> NO_LIGHT
        }
    }

    /**
     * A job item in words, as Editor B lists it: a group, fixture or record by its name, a wheel or an
     * encoder page as its function — a level by its attribute or colour axis (`Saturation`), `Fan pan`,
     * `Tempo · M2 · Chase` — naming its subject only where it is not the selection.
     */
    fun itemLabel(item: JobItem, names: SurfaceNames): String = when (item) {
        is JobItem.Group -> names.groups[item.key] ?: item.key
        is JobItem.Fixture -> names.fixtures[item.key] ?: item.key
        is JobItem.Record -> names.record(item.kind, item.uuid) ?: "A missing ${RECORD_WORDS.getValue(item.kind)}"
        is JobItem.Function -> when (val does = item.does) {
            is Does.Level -> (axisWord(does.axis).trim().replaceFirstChar { it.uppercase() }.ifEmpty { attributeWord(does.attribute) }) +
                (names.subjectWord(item.on)?.takeIf { item.on != Subject.Selection }?.let { " · $it" } ?: "")
            is Does.EncoderPage -> attributeWord(does.attribute) + axisWord(does.axis)
            else -> label(Binding(does, item.on.takeIf { it != Subject.Selection } ?: Subject.None), names)
        }
    }

    /** A control part in words, for a list of where something is bound: `X-Touch Compact · Button 36 · Layer A`. */
    fun controlName(deviceTypeKey: String, controlId: String, part: ControlPart, bank: String?): String {
        val profile = ControlSurfaceRegistry.typeFor(deviceTypeKey)
        val control = profile?.control(controlId)?.label ?: controlId
        val pushed = if (part == ControlPart.PUSH) "$control push" else control
        val layer = bank?.let { "Layer ${layerName(profile, it)}" } ?: "both layers".takeIf { profile?.banks?.isEmpty() != true }
        return listOfNotNull(profile?.product ?: deviceTypeKey, pushed, layer)
            .joinToString(" · ")
    }

    /** A layer by the name the device prints on it (`A`), else its id. */
    fun layerName(profile: ControlSurfaceRegistry.DeviceTypeInfo?, bank: String): String =
        profile?.banks?.firstOrNull { it.id == bank }?.name ?: bank

    private fun master(word: String, on: Subject, names: SurfaceNames) = "$word · ${masterName(on, names)}"

    private fun masterName(on: Subject, names: SurfaceNames) =
        if (on == Subject.None) names.masterOne else names.subject(on) ?: names.masterOne

    private fun onOff(word: String, how: How?) = when (how) {
        How.ON -> "$word on"
        How.OFF -> "$word off"
        else -> word
    }

    private fun number(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString() else value.toString()

    private const val NO_LIGHT = "No light."

    private val FAN_WORDS = mapOf(
        FanAxis.PAN to "pan", FanAxis.TILT to "tilt", FanAxis.ACROSS to "across", FanAxis.DEPTH to "depth",
    )
}

/** An attribute name in words: `dimmer` → `Dimmer`, `rgbColour` → `Colour`, `zoomFine` → `Zoom fine`. */
fun attributeWord(attribute: String): String = ATTRIBUTE_WORDS[attribute] ?: attribute
    .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
    .lowercase()
    .replaceFirstChar { it.uppercase() }

private val ATTRIBUTE_WORDS = mapOf(
    "rgbColour" to "Colour",
    "uv" to "UV",
    "position" to "Position",
)

private fun axisWord(axis: ColourAxis?): String = when (axis) {
    null, ColourAxis.HUE -> ""
    ColourAxis.HUE_FINE -> " hue fine"
    ColourAxis.SATURATION -> " saturation"
    ColourAxis.BRIGHTNESS -> " brightness"
}
