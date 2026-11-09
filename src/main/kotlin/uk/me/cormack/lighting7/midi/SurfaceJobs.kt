package uk.me.cormack.lighting7.midi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import java.util.UUID

/** The attribute a strip fader and its flash button drive. */
const val STRIP_FADER_ATTRIBUTE: String = "dimmer"

/** A job write the kind, the region or an item refused. */
const val CODE_SURFACE_JOB_INVALID = "SURFACE_JOB_INVALID"

/**
 * One item of a job (surface-setup plan D11): what a slot is filled from — a group or a fixture on a
 * strip, a record by uuid, or a function (a wheel, an encoder page). Stored in order as JSON, a gap
 * as null, so reordering the items moves the controls.
 */
@Serializable
sealed interface JobItem {
    @Serializable
    @SerialName("group")
    data class Group(val key: String) : JobItem

    @Serializable
    @SerialName("fixture")
    data class Fixture(val key: String) : JobItem

    @Serializable
    @SerialName("record")
    data class Record(val kind: RecordKind, @Serializable(with = UuidStringSerializer::class) val uuid: UUID) : JobItem

    /** A function with its subject: a wheel, or an encoder page. */
    @Serializable
    @SerialName("function")
    data class Function(val does: Does, val on: Subject = Subject.None) : JobItem

    /** The subject a slot filled from this item acts on; a function item's own. */
    val subject: Subject
        get() = when (this) {
            is Group -> Subject.Group(key)
            is Fixture -> Subject.Fixture(key)
            is Record -> Subject.Record(kind, uuid)
            is Function -> on
        }
}

/** What an item of a job kind is, as Editor B offers it. */
@Serializable
enum class JobItemKind { GROUP, FIXTURE, STACK, MASTER, PALETTE, LOOK, FUNCTION }

/** The live state a job may follow; a change of it re-derives the job's region (§3 "Jobs"). */
@Serializable
enum class JobSignal { SELECTION, BUSK_PAGE }

/** What a live job reads: the selection's heads in rig order, and the pads of the busk page showing in order. */
data class JobLive(val heads: List<String> = emptyList(), val pads: List<UUID> = emptyList()) {
    companion object {
        val EMPTY = JobLive()
    }
}

/** Everything one slot's derivation reads. */
class JobContext(val items: List<JobItem?>, val live: JobLive, val encoderPage: EncoderBankSelection)

/**
 * A job kind (surface-setup plan D11, §3 "Jobs"): the regions it fits, what its items are, and the
 * binding each slot and role derives. Every derivation that names a subject is a template on
 * [Subject.Slot], filled from the slot's item ([filled]); a kind whose item *is* the function (a
 * wheel, a page, a palette) builds the binding from it.
 *
 * Exhaustive by enum: a new kind has to say what it fits, what it holds and what it derives.
 */
enum class JobKind(
    val id: String,
    val label: String,
    val help: String,
    val item: JobItemKind?,
    /** The most items it holds; null is one per slot. */
    val maxItems: Int? = null,
    val follows: JobSignal? = null,
    /** The catalogue entries a [JobItemKind.FUNCTION] item may be. */
    val entries: List<String> = emptyList(),
) {
    GROUPS("groupStrips", "Groups as channel strips", "Each group on a strip: its level, select, the encoder page and flash.", JobItemKind.GROUP) {
        override fun fits(region: JobRegion) = region.every(SlotRole.FADER)
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext) = itemAt(ctx, slot)?.let { strip(role, ctx.encoderPage)?.filled(it.subject) }
    },
    FIXTURES("fixtureStrips", "Fixtures as channel strips", "Each fixture on a strip: its level, select, the encoder page and flash.", JobItemKind.FIXTURE) {
        override fun fits(region: JobRegion) = region.every(SlotRole.FADER)
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext) = itemAt(ctx, slot)?.let { strip(role, ctx.encoderPage)?.filled(it.subject) }
    },
    HEADS(
        "headStrips", "The selection, one head per strip",
        "The first selected heads, in rig order, one a strip. A select button narrows the selection to that head.",
        null, follows = JobSignal.SELECTION,
    ) {
        override fun fits(region: JobRegion) = region.every(SlotRole.FADER)
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext): Binding? {
            val head = ctx.live.heads.getOrNull(slot) ?: return null
            return strip(role, ctx.encoderPage, select = How.REPLACE)?.filled(Subject.Fixture(head))
        }
    },
    STACKS("stackStrips", "Cue stacks as playbacks", "Fader is the stack's FX amount, flash is GO, select is Back.", JobItemKind.STACK) {
        override fun fits(region: JobRegion) = region.every(SlotRole.FADER)
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext): Binding? {
            val stack = itemAt(ctx, slot) ?: return null
            val does = when (role) {
                SlotRole.FADER -> Does.FxAmount
                SlotRole.FLASH -> Does.Go
                SlotRole.SELECT -> Does.Back
                else -> return null
            }
            return Binding(does, Subject.Slot).filled(stack.subject)
        }
    },
    MASTERS("masterStrips", "Speed masters", "Fader is the master's tempo across 20–300 BPM, flash is Tap.", JobItemKind.MASTER) {
        override fun fits(region: JobRegion) = region.every(SlotRole.FADER)
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext): Binding? {
            val master = itemAt(ctx, slot) ?: return null
            val does = when (role) {
                SlotRole.FADER -> Does.Tempo()
                SlotRole.FLASH -> Does.Tap
                else -> return null
            }
            return Binding(does, Subject.Slot).filled(master.subject)
        }
    },
    WHEELS(
        "wheels", "Wheels on the selection", "Each encoder one function: a level, a colour axis, a fan, FX speed or a tempo.",
        JobItemKind.FUNCTION, entries = SurfaceCatalogue.entries.filter { ControlKind.CONTINUOUS in it.inputs }.map { it.id },
    ) {
        override fun fits(region: JobRegion) = region.wheels()
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext): Binding? {
            if (role != SlotRole.ENCODER) return null
            val wheel = itemAt(ctx, slot) as? JobItem.Function ?: return null
            return Binding(wheel.does, wheel.on)
        }

        override fun refusal(item: JobItem): String? {
            val wheel = item as? JobItem.Function ?: return "a wheel is a function"
            return functionRefusal(wheel, ControlKind.CONTINUOUS)
        }
    },
    PAGED_WHEELS(
        "pagedWheels", "Wheels by encoder page",
        "The first selected heads, in rig order, one an encoder, each on the attribute the encoder page names.",
        null, follows = JobSignal.SELECTION,
    ) {
        override fun fits(region: JobRegion) = region.wheels()
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext): Binding? {
            if (role != SlotRole.ENCODER) return null
            val head = ctx.live.heads.getOrNull(slot) ?: return null
            return Binding(Does.Level(ctx.encoderPage.attribute, ctx.encoderPage.colourAxis), Subject.Fixture(head))
        }
    },
    PALETTES("palettes", "Palettes onto the selection", "Each button presses a palette on the selection.", JobItemKind.PALETTE) {
        override fun fits(region: JobRegion) = region.every(SlotRole.BUTTON)
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext): Binding? {
            if (role != SlotRole.BUTTON) return null
            val palette = itemAt(ctx, slot) as? JobItem.Record ?: return null
            return Binding(Does.Palette(palette.uuid), Subject.Selection)
        }
    },
    BUSK_PAGE(
        "buskPage", "The busk page that's showing", "Its first pads in order. A page button moves it, and the row follows.",
        null, follows = JobSignal.BUSK_PAGE,
    ) {
        override fun fits(region: JobRegion) = region.every(SlotRole.BUTTON)
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext): Binding? {
            if (role != SlotRole.BUTTON) return null
            val pad = ctx.live.pads.getOrNull(slot) ?: return null
            return Binding(Does.Pad, Subject.Record(RecordKind.PAD, pad))
        }
    },
    GOS("gos", "GO for each cue stack", "Each button is a stack's GO.", JobItemKind.STACK) {
        override fun fits(region: JobRegion) = region.every(SlotRole.BUTTON)
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext) =
            if (role == SlotRole.BUTTON) itemAt(ctx, slot)?.let { Binding(Does.Go, Subject.Slot).filled(it.subject) } else null
    },
    LOOKS("looks", "Looks", "Each button puts a Look on, or takes it off.", JobItemKind.LOOK) {
        override fun fits(region: JobRegion) = region.every(SlotRole.BUTTON)
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext) =
            if (role == SlotRole.BUTTON) itemAt(ctx, slot)?.let { Binding(Does.Look, Subject.Slot).filled(it.subject) } else null
    },
    ENCODER_PAGES(
        "encoderPages", "Encoder pages", "Each button points the device's encoders at an attribute, lit while it is the page.",
        JobItemKind.FUNCTION, entries = listOf("encoderPage"),
    ) {
        override fun fits(region: JobRegion) = region.every(SlotRole.BUTTON)
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext): Binding? {
            if (role != SlotRole.BUTTON) return null
            val page = itemAt(ctx, slot) as? JobItem.Function ?: return null
            return Binding(page.does)
        }

        override fun refusal(item: JobItem): String? {
            val page = item as? JobItem.Function ?: return "an encoder page is a function"
            if (page.does !is Does.EncoderPage) return "an encoder page item is an encoder page"
            return functionRefusal(page, ControlKind.BUTTON)
        }
    },
    TRANSPORT(
        "transport", "Transport for one stack", "GO, Back, Stop, Pause, Tap and Clear, in that order, for one stack.",
        JobItemKind.STACK, maxItems = 1,
    ) {
        override fun fits(region: JobRegion) = region.every(SlotRole.BUTTON)
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext): Binding? {
            if (role != SlotRole.BUTTON) return null
            val stack = ctx.items.firstOrNull()
            return when (slot) {
                0 -> stack?.let { Binding(Does.Go, it.subject) }
                1 -> stack?.let { Binding(Does.Back, it.subject) }
                2 -> stack?.let { Binding(Does.Stop, it.subject) }
                3 -> stack?.let { Binding(Does.Pause, it.subject) }
                4 -> Binding(Does.Tap)
                5 -> Binding(Does.Clear())
                else -> null
            }
        }
    },
    PROGRAMMER(
        "programmer", "Programmer keys", "Clear, Blind, Undo, Release, Locate and Clear selection, in that order, on the selection.",
        null,
    ) {
        override fun fits(region: JobRegion) = region.every(SlotRole.BUTTON)
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext): Binding? {
            if (role != SlotRole.BUTTON) return null
            return when (slot) {
                0 -> Binding(Does.Clear())
                1 -> Binding(Does.Blind())
                2 -> Binding(Does.Undo())
                3 -> Binding(Does.Release(), Subject.Selection)
                4 -> Binding(Does.Locate, Subject.Selection)
                5 -> Binding(Does.SelectionStep(SelectionMove.CLEAR))
                else -> null
            }
        }
    },
    GRAND_MASTER("grandMaster", "Grand master and blackout", "The fader is the grand master, its select button blackout.", null) {
        override fun fits(region: JobRegion) = region.slots.size == 1 && region.every(SlotRole.FADER)
        override fun derive(role: SlotRole, slot: Int, ctx: JobContext): Binding? = when {
            slot != 0 -> null
            role == SlotRole.FADER -> Binding(Does.GrandMaster())
            role == SlotRole.SELECT -> Binding(Does.Blackout)
            else -> null
        }
    },
    ;

    /** Whether the kind can fill [region]'s shape. */
    abstract fun fits(region: JobRegion): Boolean

    /** The binding slot [slot]'s [role] derives, or null where it derives none. */
    abstract fun derive(role: SlotRole, slot: Int, ctx: JobContext): Binding?

    /** Why [item] cannot be one of this kind's, beyond its type; null when it can. */
    open fun refusal(item: JobItem): String? = null

    /** Why [items] cannot fill this kind on [region]; null when they can. */
    fun itemsRefusal(region: JobRegion, items: List<JobItem?>): String? {
        if (item == null) return if (items.any { it != null }) "$label holds no items" else null
        val most = maxItems ?: region.slots.size
        if (items.size > most) return "$label on ${region.name} holds at most $most item${if (most == 1) "" else "s"}"
        items.forEachIndexed { index, it ->
            val one = it ?: return@forEachIndexed
            val why = typeRefusal(one) ?: refusal(one)
            if (why != null) return "Item ${index + 1} of $label: $why"
        }
        return null
    }

    private fun typeRefusal(it: JobItem): String? {
        val ok = when (item) {
            JobItemKind.GROUP -> it is JobItem.Group
            JobItemKind.FIXTURE -> it is JobItem.Fixture
            JobItemKind.STACK -> it is JobItem.Record && it.kind == RecordKind.STACK
            JobItemKind.MASTER -> it is JobItem.Record && it.kind == RecordKind.MASTER
            JobItemKind.PALETTE -> it is JobItem.Record && it.kind == RecordKind.PALETTE
            JobItemKind.LOOK -> it is JobItem.Record && it.kind == RecordKind.LOOK
            JobItemKind.FUNCTION -> it is JobItem.Function
            null -> false
        }
        return if (ok) null else "it is not a ${item?.name?.lowercase() ?: "item"}"
    }

    companion object {
        private val byId: Map<String, JobKind> = entries.associateBy { it.id }

        fun of(id: String): JobKind? = byId[id]
    }
}

private fun JobRegion.every(role: SlotRole): Boolean = slots.all { it.has(role) }

/** A region of encoders: a strip's encoder is its strip's, so a region with faders is no wheels'. */
private fun JobRegion.wheels(): Boolean = every(SlotRole.ENCODER) && slots.none { it.has(SlotRole.FADER) }

private fun itemAt(ctx: JobContext, slot: Int): JobItem? = ctx.items.getOrNull(slot)

/** A strip's template, on [Subject.Slot]: the fader and flash on the dimmer, the encoder on the device's encoder page. */
private fun strip(role: SlotRole, page: EncoderBankSelection, select: How? = null): Binding? = when (role) {
    SlotRole.FADER -> Binding(Does.Level(STRIP_FADER_ATTRIBUTE), Subject.Slot)
    SlotRole.SELECT -> Binding(Does.Select, Subject.Slot, select)
    // The page's axis rides along: it is the encoder, and only the encoder, that a colour axis reaches
    // on a strip — the fader and flash are the dimmer whatever the page says.
    SlotRole.ENCODER -> Binding(Does.Level(page.attribute, page.colourAxis), Subject.Slot)
    SlotRole.FLASH -> Binding(Does.Flash(STRIP_FADER_ATTRIBUTE), Subject.Slot)
    SlotRole.BUTTON, SlotRole.PUSH -> null
}

/** [this] template with its [Subject.Slot] filled by [subject]. */
fun Binding.filled(subject: Subject): Binding = if (on == Subject.Slot) copy(on = subject) else this

private fun functionRefusal(item: JobItem.Function, input: ControlKind): String? {
    val does = item.does
    if (does is Does.Unknown) return "it names a function this desk cannot read"
    val spec = does.spec
    if (input !in spec.inputs) return "${does.functionId} does not take a ${input.name.lowercase()}"
    if (item.on.subjectKind !in spec.on) return "${does.functionId} does not act on ${item.on.subjectKind.name.lowercase()}"
    return does.refusal(item.on)
}

/** A job's items as stored: a JSON array, a gap as null. */
private val ITEMS = ListSerializer(JobItem.serializer().nullable)

fun encodeJobItems(items: List<JobItem?>): String = BindingJson.encodeToString(ITEMS, items)

/** [raw] as items, or null when this build cannot read them; the row is then kept byte for byte. */
fun decodeJobItems(raw: String): List<JobItem?>? = try {
    BindingJson.decodeFromString(ITEMS, raw)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
