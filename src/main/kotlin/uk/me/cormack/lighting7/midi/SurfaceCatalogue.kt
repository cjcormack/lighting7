package uk.me.cormack.lighting7.midi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import uk.me.cormack.lighting7.fx.MasterClock
import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.routes.MAX_PROGRAMMER_FADE_SECONDS

/**
 * The function catalogue (surface-setup plan D15): every function an editor offers, read from the
 * [Does] types' own specs, with the words and parameters a form needs to build one. Static per
 * build, served whole as `GET /api/rest/surface-functions`.
 *
 * An **entry** is what the operator picks. Most are one function each; two functions are two
 * entries, because a parameter changes what they are: a grand master with a level is a button that
 * sets it, and a tempo with a BPM is a button that sets that tempo. [entryOf] answers which entry a
 * stored binding is, so a client never matches a binding to an entry itself.
 *
 * A client builds a binding's `does` as `{type: entry.does, …params}`; a parameter left out takes its
 * [CatalogueParam.default] on the desk.
 */
object SurfaceCatalogue {
    /** Every entry, in the order a form lists them within their groups. */
    val entries: List<CatalogueEntry> by lazy { ENTRIES.map { it.toEntry() } }

    private val byId: Map<String, CatalogueEntry> by lazy { entries.associateBy { it.id } }

    fun entry(id: String): CatalogueEntry? = byId[id]

    /** The entry [does] is, or null for a payload this build cannot read. Exhaustive (P3). */
    fun entryOf(does: Does): String? = when (does) {
        is Does.GrandMaster -> if (does.level == null) "grandMaster" else "grandMasterLevel"
        is Does.Tempo -> if (does.bpm == null) "tempo" else "tempoSet"
        is Does.Unknown -> null
        is Does.Level, Does.PageLevel, is Does.Flash, Does.Select, Does.Locate, is Does.Fan, Does.FxSpeed,
        is Does.Clear, is Does.Blind, is Does.Release, Does.Record, Does.Update, is Does.Palette,
        Does.Look, Does.Cue, Does.Pad, Does.PickUp, Does.Place, Does.Drop, Does.Go, Does.Back, Does.Pause,
        Does.Stop, Does.GoTo, Does.Standby, Does.FxAmount, is Does.Fire, Does.Blackout, Does.Tap,
        is Does.TempoScale, is Does.Layer, is Does.EncoderPage, is Does.Undo, is Does.BuskPage,
        is Does.WindowFocus, is Does.WindowSheet, is Does.SelectionStep, Does.Strip,
            -> does.functionId
    }
}

/** One function an editor offers. */
@Serializable
data class CatalogueEntry(
    /** What the operator picks, and what [SurfaceCatalogue.entryOf] answers. */
    val id: String,
    /** The function id a binding built from this entry carries as its `does.type`. */
    val does: String,
    val label: String,
    /** The heading the entry is listed under: Fixtures, Programmer, Playback, Busk, Masters, Tempo, Desk, Strips. */
    val group: String,
    val help: String,
    /** The inputs that reach it: a continuous part (a fader's move, an encoder's turn), a button. Empty for a strip. */
    val inputs: List<ControlKind>,
    /** Whether it sits on a strip slot rather than a control part. */
    val strip: Boolean = false,
    val subjects: List<SubjectKind>,
    val hows: List<How>,
    val defaultHow: How?,
    val writesTo: WritesTo,
    val params: List<CatalogueParam>,
)

/** One parameter of an entry's function, with what a form needs to draw it. */
@Serializable
data class CatalogueParam(
    /** The field's name on `does`. */
    val key: String,
    val label: String,
    val kind: ParamKind,
    val required: Boolean = false,
    /** What the desk takes when the field is left out; null for none. */
    val default: JsonElement? = null,
    /** A choice's values, in order. */
    val options: List<ParamOption> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    /** A record parameter's kind. */
    val record: RecordKind? = null,
)

@Serializable
data class ParamOption(val value: JsonElement, val label: String)

/**
 * How a parameter is drawn: an attribute the subject's heads have, a choice among [CatalogueParam.options],
 * a number, a free text, a record of [CatalogueParam.record], or one of the device's layers.
 */
@Serializable
enum class ParamKind { ATTRIBUTE, CHOICE, NUMBER, TEXT, RECORD, LAYER }

private class EntrySpec(
    val id: String,
    val label: String,
    val group: String,
    val help: String,
    /** A function built at every parameter's default, whose spec the entry reports. */
    val sample: Does,
    val params: List<CatalogueParam> = emptyList(),
) {
    fun toEntry(): CatalogueEntry {
        val spec = sample.spec
        return CatalogueEntry(
            id = id,
            does = sample.functionId,
            label = label,
            group = group,
            help = help,
            inputs = spec.inputs.sortedBy { it.ordinal },
            strip = sample is Does.Strip,
            subjects = spec.on.sortedBy { it.ordinal },
            hows = (spec.hows + listOfNotNull(spec.defaultHow)).distinct().sortedBy { it.ordinal },
            defaultHow = spec.defaultHow,
            writesTo = spec.writesTo,
            params = params,
        )
    }
}

private const val FIXTURES = "Fixtures"
private const val PROGRAMMER = "Programmer"
private const val PLAYBACK = "Playback"
private const val BUSK = "Busk"
private const val MASTERS = "Masters"
private const val TEMPO = "Tempo"
private const val DESK = "Desk"
private const val STRIPS = "Strips"

private fun choice(value: String, label: String) = ParamOption(JsonPrimitive(value), label)
private fun choice(value: Double, label: String) = ParamOption(JsonPrimitive(value), label)
private fun choice(value: Int, label: String) = ParamOption(JsonPrimitive(value), label)

private val ATTRIBUTE = CatalogueParam("attribute", "Attribute", ParamKind.ATTRIBUTE, required = true, default = JsonPrimitive("dimmer"))

private val AXIS = CatalogueParam(
    "axis", "Colour axis", ParamKind.CHOICE,
    options = listOf(
        choice("hue", "Hue"), choice("hueFine", "Hue fine"),
        choice("saturation", "Saturation"), choice("brightness", "Brightness"),
    ),
)

/** The words a [SelectionMove] is offered and labelled with. */
internal val SELECTION_MOVE_WORDS: Map<SelectionMove, String> = mapOf(
    SelectionMove.CLEAR to "Clear",
    SelectionMove.NEXT to "Next",
    SelectionMove.PREV to "Previous",
    SelectionMove.ALL to "All",
    SelectionMove.ODD to "Odd",
    SelectionMove.EVEN to "Even",
    SelectionMove.FIRST_HALF to "First half",
    SelectionMove.SECOND_HALF to "Second half",
    SelectionMove.INVERT to "Invert",
    SelectionMove.MASTERS to "Masters",
)

private val PLACEHOLDER_UUID = java.util.UUID(0, 0)

private val FADE = CatalogueParam(
    "fade", "Fade, seconds", ParamKind.NUMBER, default = JsonPrimitive(0.0), min = 0.0, max = MAX_PROGRAMMER_FADE_SECONDS,
)

private val ENTRIES = listOf(
    EntrySpec(
        "level", "Level", FIXTURES, "One attribute of the heads it is on, from a fader or an encoder.",
        Does.Level("dimmer"), listOf(ATTRIBUTE, AXIS),
    ),
    EntrySpec(
        "pageLevel", "Level on the encoder page", FIXTURES,
        "The attribute the encoder page of the device it is on names, of the heads it is on.",
        Does.PageLevel,
    ),
    EntrySpec(
        "flash", "Flash", FIXTURES, "While held, an attribute at a share of each head's range; let go, it goes back.",
        Does.Flash("dimmer"),
        listOf(ATTRIBUTE, CatalogueParam("percent", "Level", ParamKind.NUMBER, default = JsonPrimitive(100), min = 0.0, max = 100.0)),
    ),
    EntrySpec("select", "Select", FIXTURES, "Put a group or fixture into the desk selection, or replace the selection with it.", Does.Select),
    EntrySpec("locate", "Locate", FIXTURES, "Locate the heads it is on, or release them when they are all located.", Does.Locate),
    EntrySpec(
        "fan", "Fan", FIXTURES, "Spread the positions of the selection or a group about their centre.",
        Does.Fan(FanAxis.PAN),
        listOf(
            CatalogueParam(
                "axis", "Axis", ParamKind.CHOICE, required = true, default = JsonPrimitive("PAN"),
                options = listOf(choice("PAN", "Pan"), choice("TILT", "Tilt"), choice("ACROSS", "Across the stage"), choice("DEPTH", "Up and down the stage")),
            ),
        ),
    ),
    EntrySpec("fxSpeed", "FX speed", FIXTURES, "The speed of the Local FX on the heads it is on.", Does.FxSpeed),
    EntrySpec(
        "palette", "Palette", FIXTURES, "Press a palette onto the selection.",
        Does.Palette(PLACEHOLDER_UUID),
        listOf(CatalogueParam("palette", "Palette", ParamKind.RECORD, required = true, record = RecordKind.PALETTE)),
    ),
    EntrySpec(
        "fire", "Fire", FIXTURES, "Fire one tube of a one-shot fixture. Needs the desk armed.",
        Does.Fire("output1"),
        listOf(CatalogueParam("trigger", "Trigger", ParamKind.TEXT, required = true)),
    ),
    EntrySpec("clear", "Clear", PROGRAMMER, "Clear the whole programmer, as its Clear does.", Does.Clear(), listOf(FADE)),
    EntrySpec("blind", "Blind", PROGRAMMER, "The programmer's Blind: toggled, or on while held.", Does.Blind(), listOf(FADE)),
    EntrySpec("release", "Release", PROGRAMMER, "Release what the programmer holds on the heads it is on.", Does.Release(), listOf(FADE)),
    EntrySpec(
        "record", "Record", PROGRAMMER,
        "Record the programmer as a new cue at the end of a stack: every touched value, its FX, scenery and tempos.",
        Does.Record,
    ),
    EntrySpec("update", "Update", PROGRAMMER, "Write the programmer back into what was included.", Does.Update),
    EntrySpec("go", "GO", PLAYBACK, "GO on a cue stack.", Does.Go),
    EntrySpec("back", "Back", PLAYBACK, "GO BACK on a cue stack.", Does.Back),
    EntrySpec("pause", "Pause", PLAYBACK, "Cancel a stack's running follow.", Does.Pause),
    EntrySpec("stop", "Stop", PLAYBACK, "Stop a stack over its release time.", Does.Stop),
    EntrySpec("goTo", "Go to", PLAYBACK, "GO TO one cue, starting its stack if stopped.", Does.GoTo),
    EntrySpec("standby", "Standby", PLAYBACK, "Arm one cue as its stack's next GO.", Does.Standby),
    EntrySpec("fxAmount", "FX amount", PLAYBACK, "A stack's FX amount, 0–100 %.", Does.FxAmount),
    EntrySpec("cue", "Cue", PLAYBACK, "Hold a cue loose, above every stack, or let it go.", Does.Cue),
    EntrySpec("look", "Look", PLAYBACK, "Put a Look on whole, or take it off.", Does.Look),
    EntrySpec("pad", "Pad", BUSK, "Press a busk pad, through its bank.", Does.Pad),
    EntrySpec("pickUp", "Pick up", BUSK, "Put a pad's record into the desk's hand.", Does.PickUp),
    EntrySpec("place", "Place", BUSK, "Place what the hand holds on a busk bank.", Does.Place),
    EntrySpec("drop", "Let go", BUSK, "Let go of what the hand holds.", Does.Drop),
    EntrySpec(
        "buskPage", "Busk page", BUSK, "Show one busk page, or step through them.",
        Does.BuskPage(1),
        listOf(CatalogueParam("step", "Step", ParamKind.CHOICE, options = listOf(choice(1, "Next"), choice(-1, "Previous")))),
    ),
    EntrySpec("blackout", "Blackout", MASTERS, "The desk's blackout.", Does.Blackout),
    EntrySpec("grandMaster", "Grand master", MASTERS, "The grand master from a fader, or a button that takes it to 0 and back.", Does.GrandMaster()),
    EntrySpec(
        "grandMasterLevel", "Grand master to a level", MASTERS, "A button that sets the grand master to a level.",
        Does.GrandMaster(100.0),
        listOf(CatalogueParam("level", "Level", ParamKind.NUMBER, required = true, default = JsonPrimitive(100.0), min = 0.0, max = 100.0)),
    ),
    EntrySpec(
        "tempo", "Tempo", TEMPO, "A speed master's tempo from a fader or an encoder, across a window.",
        Does.Tempo(),
        listOf(
            CatalogueParam("minBpm", "From BPM", ParamKind.NUMBER, default = JsonPrimitive(Does.Tempo.DEFAULT_MIN_BPM), min = MasterClock.MIN_BPM, max = MasterClock.MAX_BPM),
            CatalogueParam("maxBpm", "To BPM", ParamKind.NUMBER, default = JsonPrimitive(Does.Tempo.DEFAULT_MAX_BPM), min = MasterClock.MIN_BPM, max = MasterClock.MAX_BPM),
        ),
    ),
    EntrySpec(
        "tempoSet", "Tempo to a BPM", TEMPO, "A button that sets a speed master's tempo.",
        Does.Tempo(bpm = 120.0),
        listOf(CatalogueParam("bpm", "BPM", ParamKind.NUMBER, required = true, default = JsonPrimitive(120.0), min = MasterClock.MIN_BPM, max = MasterClock.MAX_BPM)),
    ),
    EntrySpec("tap", "Tap", TEMPO, "Tap a speed master's tempo.", Does.Tap),
    EntrySpec(
        "tempoScale", "Halve or double", TEMPO, "Halve or double a speed master's tempo.",
        Does.TempoScale(2.0),
        listOf(
            CatalogueParam(
                "factor", "Factor", ParamKind.CHOICE, required = true, default = JsonPrimitive(2.0),
                options = SpeedMasterBank.SCALE_FACTORS.sorted().map { choice(it, if (it < 1) "×½" else "×2") },
            ),
        ),
    ),
    EntrySpec(
        "layer", "Switch layer", DESK, "Switch the layer of the device the button is on.",
        Does.Layer("A"),
        listOf(CatalogueParam("layer", "Layer", ParamKind.LAYER, required = true)),
    ),
    EntrySpec(
        "encoderPage", "Encoder page", DESK, "Point the device's strip encoders at an attribute.",
        Does.EncoderPage("dimmer"), listOf(ATTRIBUTE, AXIS),
    ),
    EntrySpec(
        "undo", "Undo", DESK, "Undo, redo, or the programmer's own undo.",
        Does.Undo(),
        listOf(
            CatalogueParam(
                "kind", "Which", ParamKind.CHOICE, default = JsonPrimitive("UNDO"),
                options = listOf(choice("UNDO", "Undo"), choice("REDO", "Redo"), choice("PROGRAMMER", "Programmer undo")),
            ),
        ),
    ),
    EntrySpec(
        "selection", "Selection", DESK, "Empty the selection, or step through the rig.",
        Does.SelectionStep(SelectionMove.CLEAR),
        listOf(
            CatalogueParam(
                "step", "Step", ParamKind.CHOICE, required = true, default = JsonPrimitive("CLEAR"),
                options = SelectionMove.entries.map { choice(it.name, SELECTION_MOVE_WORDS.getValue(it)) },
            ),
        ),
    ),
    EntrySpec(
        "windowFocus", "Busk focus", DESK, "Set the busk focus of every window with a name.",
        Does.WindowFocus("Desk", "split"),
        listOf(
            CatalogueParam("window", "Window", ParamKind.TEXT, required = true),
            CatalogueParam(
                "focus", "Focus", ParamKind.CHOICE, required = true, default = JsonPrimitive("split"),
                options = listOf(choice("split", "Split"), choice("pads", "Pads"), choice("rig", "Rig")),
            ),
        ),
    ),
    EntrySpec(
        "windowSheet", "Busk sheet", DESK, "Fold or open the busk side sheet of every window with a name.",
        Does.WindowSheet("Desk"),
        listOf(CatalogueParam("window", "Window", ParamKind.TEXT, required = true)),
    ),
    EntrySpec(
        "strip", "Channel strip", STRIPS, "A group or fixture on a whole strip: level, select, the encoder page and flash.",
        Does.Strip,
    ),
)
