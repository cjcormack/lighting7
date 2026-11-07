package uk.me.cormack.lighting7.osc

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.models.DaoBuskBank
import uk.me.cormack.lighting7.models.DaoBuskBanks
import uk.me.cormack.lighting7.models.DaoBuskColumn
import uk.me.cormack.lighting7.models.DaoBuskColumns
import uk.me.cormack.lighting7.models.DaoBuskPad
import uk.me.cormack.lighting7.models.DaoBuskPads
import uk.me.cormack.lighting7.models.DaoBuskPage
import uk.me.cormack.lighting7.models.DaoBuskPages
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.entriesOfStack
import uk.me.cormack.lighting7.models.slugOf
import uk.me.cormack.lighting7.routes.cueNumberValue
import java.math.BigDecimal
import java.util.UUID

/**
 * The fixed OSC address set (OSC plan D1, P2): every address the desk answers to under
 * [PREFIX], what it takes and does, and the [BindingTarget] it resolves to. One table, which the
 * resolver, the address listing and the tests all read, so §3 of the plan and the code cannot drift.
 *
 * `{stack}`, `{cue}`, `{look}` and `{page}` are slugs ([slugOf]), matched against the lower-cased
 * segment, and a slug two records share is refused as ambiguous rather than guessed (D3). `{n}` is
 * a cue number matched by value, `{bank}` and `{pad}` count from 1 in layout order.
 */
object OscAddresses {
    const val PREFIX = "/lighting7"

    /** What an address's arguments are. */
    enum class Args(val label: String) {
        NONE("–"),

        /** One number, 0–1; any numeric tag (D9). */
        LEVEL("f 0–1"),
    }

    /** One row of the table. [resolve] runs against the current project through [OscLookup]. */
    class Entry internal constructor(
        val pattern: String,
        val args: Args,
        val does: String,
        val target: String,
        val session: Int,
        internal val resolve: (Captures, Double?, OscLookup) -> OscResolution,
    ) {
        internal val segments: List<String> = pattern.trim('/').split('/')
    }

    /** An address's placeholders, by name, lower-cased. */
    class Captures internal constructor(private val values: Map<String, String>) {
        operator fun get(name: String): String = values.getValue(name)
    }

    val entries: List<Entry> = listOf(
        Entry("/stack/{stack}/cue/{n}/go", Args.NONE,
            "GO TO cue n, starting the stack if it is stopped; nothing if n is already live", "CueStackGoTo", 1) { c, _, l ->
            l.entry(c["stack"], c["n"]) { stack, entry ->
                OscResolution.Press(BindingTarget.CueStackGoTo(stack.uuid.toString(), entry.uuid.toString()), "GO TO ${entry.label} in ${stack.name}")
            }
        },
        Entry("/stack/{stack}/cue/{n}/standby", Args.NONE, "Put cue n on standby", "CueStackStandby", 1) { c, _, l ->
            l.entry(c["stack"], c["n"]) { stack, entry ->
                OscResolution.Press(BindingTarget.CueStackStandby(stack.uuid.toString(), entry.uuid.toString()), "Standby ${entry.label} in ${stack.name}")
            }
        },
        Entry("/stack/{stack}/go", Args.NONE, "GO", "CueStackGo", 1) { c, _, l ->
            l.stack(c["stack"]) { OscResolution.Press(BindingTarget.CueStackGo(it.id, it.uuid.toString()), "GO ${it.name}") }
        },
        Entry("/stack/{stack}/back", Args.NONE, "GO BACK", "CueStackBack", 1) { c, _, l ->
            l.stack(c["stack"]) { OscResolution.Press(BindingTarget.CueStackBack(it.id, it.uuid.toString()), "GO BACK ${it.name}") }
        },
        Entry("/stack/{stack}/pause", Args.NONE, "Cancel the live entry's auto-advance timer", "CueStackPause", 1) { c, _, l ->
            l.stack(c["stack"]) { OscResolution.Press(BindingTarget.CueStackPause(it.id, it.uuid.toString()), "Pause ${it.name}'s follow") }
        },
        Entry("/stack/{stack}/stop", Args.NONE, "Stop the stack, which drops the arm as any stop does", "CueStackStop", 1) { c, _, l ->
            l.stack(c["stack"]) { OscResolution.Press(BindingTarget.CueStackStop(it.uuid.toString()), "Stop ${it.name}") }
        },
        Entry("/stack/{stack}/fx-amount", Args.LEVEL, "The stack's FX amount", "StackFxAmount", 1) { c, v, l ->
            l.stack(c["stack"]) { OscResolution.Level(BindingTarget.StackFxAmount(it.id, it.uuid.toString()), v!!, "FX amount of ${it.name}") }
        },
        Entry("/cue/{cue}/on", Args.NONE, "Take a loose hold; nothing if already held", "LooseCueSet", 1) { c, _, l ->
            l.cue(c["cue"]) { OscResolution.Press(BindingTarget.LooseCueSet(it.uuid.toString(), on = true), "Cue ${it.name} on") }
        },
        Entry("/cue/{cue}/off", Args.NONE, "Let go of a loose hold; nothing if not held", "LooseCueSet", 1) { c, _, l ->
            l.cue(c["cue"]) { OscResolution.Press(BindingTarget.LooseCueSet(it.uuid.toString(), on = false), "Cue ${it.name} off") }
        },
        Entry("/cue/{cue}/press", Args.NONE, "Toggle the loose hold", "FireCue", 1) { c, _, l ->
            l.cue(c["cue"]) { OscResolution.Press(BindingTarget.FireCue(it.id, it.uuid.toString()), "Press cue ${it.name}") }
        },
        Entry("/look/{look}/on", Args.NONE, "The Look on whole; nothing if already on", "LookSet", 1) { c, _, l ->
            l.look(c["look"]) { OscResolution.Press(BindingTarget.LookSet(it.uuid.toString(), on = true), "Look ${it.name} on") }
        },
        Entry("/look/{look}/off", Args.NONE, "Every layer of the Look off; nothing if already off", "LookSet", 1) { c, _, l ->
            l.look(c["look"]) { OscResolution.Press(BindingTarget.LookSet(it.uuid.toString(), on = false), "Look ${it.name} off") }
        },
        Entry("/look/{look}/press", Args.NONE, "Toggle the Look", "ApplyLook", 1) { c, _, l ->
            l.look(c["look"]) { OscResolution.Press(BindingTarget.ApplyLook(it.uuid.toString()), "Press Look ${it.name}") }
        },
        Entry("/busk/{page}/{bank}/{pad}/press", Args.NONE, "Press a pad, on the desk selection", "PressPad", 1) { c, _, l ->
            l.pad(c["page"], c["bank"], c["pad"])
        },
        Entry("/busk/{page}/show", Args.NONE, "Show that busk page", "BuskPageSet", 1) { c, _, l ->
            l.page(c["page"]) { OscResolution.Press(BindingTarget.BuskPageSet(it.uuid.toString()), "Show busk page ${it.name}") }
        },
    )

    /**
     * Resolve one received message against the current project, read through [lookup]. An address
     * outside [PREFIX], matching no row, or with the wrong arguments is [OscResolution.Bad].
     */
    fun resolve(address: String, args: List<OscArg>, lookup: OscLookup): OscResolution {
        if (!address.startsWith("$PREFIX/")) return OscResolution.Bad("Not a $PREFIX address")
        val segments = address.removePrefix("$PREFIX/").trimEnd('/').split('/').map { it.lowercase() }
        for (entry in entries) {
            val captures = match(entry, segments) ?: continue
            val value = when (entry.args) {
                Args.NONE -> null
                Args.LEVEL -> args.firstOrNull()?.number()
                    ?: return OscResolution.Bad("${entry.pattern} wants a number from 0 to 1")
            }
            return entry.resolve(captures, value, lookup)
        }
        return OscResolution.Bad("Unknown address")
    }

    /**
     * The table row [address] matches, as its pattern under [PREFIX] — what a trace is named, so a
     * name never carries a show's own words — or null for an address no row matches.
     */
    fun patternOf(address: String): String? {
        if (!address.startsWith("$PREFIX/")) return null
        val segments = address.removePrefix("$PREFIX/").trimEnd('/').split('/').map { it.lowercase() }
        return entries.firstOrNull { match(it, segments) != null }?.let { PREFIX + it.pattern }
    }

    private fun match(entry: Entry, segments: List<String>): Captures? {
        if (entry.segments.size != segments.size) return null
        val captures = HashMap<String, String>()
        for ((pattern, segment) in entry.segments.zip(segments)) {
            if (pattern.startsWith("{")) {
                if (segment.isEmpty()) return null
                captures[pattern.trim('{', '}')] = segment
            } else if (pattern != segment) {
                return null
            }
        }
        return Captures(captures)
    }
}

/** What a received address reaches, or why it reaches nothing. */
sealed interface OscResolution {
    /** A button target; [reached] names the record ("GO TO 31 Storm builds in Act 2"). */
    data class Press(val target: BindingTarget, val reached: String) : OscResolution

    /** A continuous target taken whole (D10). */
    data class Level(val target: BindingTarget, val value: Double, val reached: String) : OscResolution

    data class NotFound(val message: String) : OscResolution

    /** Two records answer to the address; [message] names both (D3). */
    data class Ambiguous(val message: String) : OscResolution

    /** An unknown address or the wrong arguments. */
    data class Bad(val message: String) : OscResolution
}

/** A record an address can name. */
data class OscRecord(val id: Int, val uuid: UUID, val name: String)

/** A cue entry of a stack: [label] is its number and its cue's name, "31 Storm builds". */
data class OscEntry(val id: Int, val uuid: UUID, val label: String)

/**
 * What the resolver reads of the current project (D7). [DbOscLookup] is the desk's; it reads the
 * database inside the caller's transaction, as a REST request does, so a rename is answered by the
 * very next message and nothing needs invalidating.
 */
interface OscLookup {
    /** The cue stacks, never a separator, whose name slugs to [slug]. */
    fun stacks(slug: String): List<OscRecord>

    /** [stackId]'s cue entries numbered [number], by value. */
    fun entries(stackId: Int, number: BigDecimal): List<OscEntry>

    fun cues(slug: String): List<OscRecord>
    fun looks(slug: String): List<OscRecord>
    fun pages(slug: String): List<OscRecord>

    /** [pageId]'s banks in layout order, each with its pads in order. */
    fun banks(pageId: Int): List<OscBank>
}

/** A busk bank and its pads, each named by what it presses. */
data class OscBank(val name: String, val pads: List<OscRecord>)

private fun one(
    found: List<OscRecord>,
    what: String,
    slug: String,
    then: (OscRecord) -> OscResolution,
): OscResolution = when (found.size) {
    0 -> OscResolution.NotFound("No $what `$slug` in this show")
    1 -> then(found.single())
    else -> OscResolution.Ambiguous("`$slug` names ${found.size} ${what}s: ${found.joinToString { it.name }}")
}

private fun OscLookup.stack(slug: String, then: (OscRecord) -> OscResolution) = one(stacks(slug), "stack", slug, then)
private fun OscLookup.cue(slug: String, then: (OscRecord) -> OscResolution) = one(cues(slug), "cue", slug, then)
private fun OscLookup.look(slug: String, then: (OscRecord) -> OscResolution) = one(looks(slug), "Look", slug, then)
private fun OscLookup.page(slug: String, then: (OscRecord) -> OscResolution) = one(pages(slug), "busk page", slug, then)

private fun OscLookup.entry(stackSlug: String, number: String, then: (OscRecord, OscEntry) -> OscResolution): OscResolution {
    val value = cueNumberValue(number) ?: return OscResolution.Bad("`$number` is not a cue number")
    return stack(stackSlug) { stack ->
        val found = entries(stack.id, value)
        when (found.size) {
            0 -> OscResolution.NotFound("No cue $number in ${stack.name}")
            1 -> then(stack, found.single())
            else -> OscResolution.Ambiguous("Cue $number is in ${stack.name} ${found.size} times")
        }
    }
}

private fun OscLookup.pad(pageSlug: String, bank: String, pad: String): OscResolution {
    val bankIndex = bank.toIntOrNull()?.takeIf { it >= 1 } ?: return OscResolution.Bad("Bank `$bank` is not a number from 1")
    val padIndex = pad.toIntOrNull()?.takeIf { it >= 1 } ?: return OscResolution.Bad("Pad `$pad` is not a number from 1")
    return page(pageSlug) { page ->
        val banks = banks(page.id)
        val theBank = banks.getOrNull(bankIndex - 1)
            ?: return@page OscResolution.NotFound("${page.name} has ${banks.size} bank${if (banks.size == 1) "" else "s"}; no bank $bankIndex")
        val thePad = theBank.pads.getOrNull(padIndex - 1)
            ?: return@page OscResolution.NotFound("Bank $bankIndex (${theBank.name}) of ${page.name} has ${theBank.pads.size} pads; no pad $padIndex")
        OscResolution.Press(
            BindingTarget.PressPad(thePad.uuid.toString()),
            "Press ${thePad.name} (pad $padIndex of ${theBank.name} on ${page.name})",
        )
    }
}

/** The desk's [OscLookup]: the current project's rows, read inside the caller's transaction. */
class DbOscLookup(private val projectId: Int) : OscLookup {
    override fun stacks(slug: String) =
        DaoCueStack.find { (DaoCueStacks.project eq projectId) and (DaoCueStacks.type eq "STACK") }
            .filter { slugOf(it.name) == slug }
            .map { OscRecord(it.id.value, it.uuid, it.name) }

    override fun entries(stackId: Int, number: BigDecimal): List<OscEntry> =
        entriesOfStack(org.jetbrains.exposed.v1.core.dao.id.EntityID(stackId, DaoCueStacks))
            .filter { !it.isMarker && it.cueNumber?.let(::cueNumberValue)?.compareTo(number) == 0 }
            .map { entry ->
                val name = entry.cue?.name
                OscEntry(entry.id.value, entry.uuid, listOfNotNull(entry.cueNumber, name).joinToString(" "))
            }

    override fun cues(slug: String) =
        DaoCue.find { DaoCues.project eq projectId }
            .filter { slugOf(it.name) == slug }
            .map { OscRecord(it.id.value, it.uuid, it.name) }

    override fun looks(slug: String) =
        DaoLook.find { DaoLooks.project eq projectId }
            .filter { slugOf(it.name) == slug }
            .map { OscRecord(it.id.value, it.uuid, it.name) }

    override fun pages(slug: String) =
        DaoBuskPage.find { DaoBuskPages.project eq projectId }
            .filter { slugOf(it.name) == slug }
            .map { OscRecord(it.id.value, it.uuid, it.name) }

    override fun banks(pageId: Int): List<OscBank> {
        val columns = DaoBuskColumn.find { DaoBuskColumns.page eq pageId }
            .sortedWith(compareBy<DaoBuskColumn> { it.row }.thenBy { it.sortOrder })
        if (columns.isEmpty()) return emptyList()
        val columnOrder = columns.withIndex().associate { (i, c) -> c.id.value to i }
        val banks = DaoBuskBank.find { DaoBuskBanks.column inList columnOrder.keys.toList() }
            .sortedWith(compareBy<DaoBuskBank> { columnOrder.getValue(it.column.id.value) }.thenBy { it.sortOrder })
        if (banks.isEmpty()) return emptyList()
        val pads = DaoBuskPad.find { DaoBuskPads.bank inList banks.map { it.id.value } }
            .groupBy { it.bank.id.value }
        return banks.map { bank ->
            OscBank(
                bank.name,
                // A malformed pad, naming no record, is drawn nowhere, so it takes no place either.
                pads[bank.id.value].orEmpty().sortedBy { it.sortOrder }.mapNotNull { pad ->
                    val name = pad.palette?.name ?: pad.look?.name ?: pad.cue?.name ?: return@mapNotNull null
                    OscRecord(pad.id.value, pad.uuid, name)
                },
            )
        }
    }
}
