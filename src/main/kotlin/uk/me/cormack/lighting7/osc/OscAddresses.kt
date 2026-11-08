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
import uk.me.cormack.lighting7.models.DaoSpeedMaster
import uk.me.cormack.lighting7.models.DaoSpeedMasters
import uk.me.cormack.lighting7.models.entriesOfStack
import uk.me.cormack.lighting7.models.slugOf
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.MasterClock
import uk.me.cormack.lighting7.routes.cueNumberValue
import java.util.UUID

/**
 * The fixed OSC address set (OSC plan D1, P2): every address the desk answers to under
 * [PREFIX], what it takes and does, and the [BindingTarget] it resolves to. One table, which the
 * resolver, the address listing and the tests all read, so §3 of the plan and the code cannot drift.
 *
 * `{stack}`, `{cue}`, `{look}` and `{page}` are slugs ([slugOf]), matched against the lower-cased
 * segment, and a slug two records share is refused as ambiguous rather than guessed (D3). `{n}` is
 * a cue number matched by value, `{bank}` and `{pad}` count from 1 in layout order, and `{master}` is
 * a speed master's index (M2 is `2`).
 */
object OscAddresses {
    const val PREFIX = "/lighting7"

    /** What an address's arguments are. */
    enum class Args(val label: String) {
        NONE("–"),

        /** One number, 0–1; any numeric tag (D9). */
        LEVEL("f 0–1"),

        /** One number read as a flag, non-zero on, or none at all (D9). */
        FLAG_OPTIONAL("i 0/1, optional"),

        /** One number, a tempo from 20 to 300. */
        BPM("f BPM"),
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
                OscResolution.Press(BindingTarget.CueStackGoTo(stack.uuid.toString(), entry.uuid.toString()), "GO TO ${entry.label} in ${stack.name}", entry.ref())
            }
        },
        Entry("/stack/{stack}/cue/{n}/standby", Args.NONE, "Put cue n on standby", "CueStackStandby", 1) { c, _, l ->
            l.entry(c["stack"], c["n"]) { stack, entry ->
                OscResolution.Press(BindingTarget.CueStackStandby(stack.uuid.toString(), entry.uuid.toString()), "Standby ${entry.label} in ${stack.name}", entry.ref())
            }
        },
        Entry("/stack/{stack}/go", Args.NONE, "GO", "CueStackGo", 1) { c, _, l ->
            l.stack(c["stack"]) { OscResolution.Press(BindingTarget.CueStackGo(it.id, it.uuid.toString()), "GO ${it.name}", it.ref(OscRecordKind.STACK)) }
        },
        Entry("/stack/{stack}/back", Args.NONE, "GO BACK", "CueStackBack", 1) { c, _, l ->
            l.stack(c["stack"]) { OscResolution.Press(BindingTarget.CueStackBack(it.id, it.uuid.toString()), "GO BACK ${it.name}", it.ref(OscRecordKind.STACK)) }
        },
        Entry("/stack/{stack}/pause", Args.NONE, "Cancel the live entry's auto-advance timer", "CueStackPause", 1) { c, _, l ->
            l.stack(c["stack"]) { OscResolution.Press(BindingTarget.CueStackPause(it.id, it.uuid.toString()), "Pause ${it.name}'s follow", it.ref(OscRecordKind.STACK)) }
        },
        Entry("/stack/{stack}/stop", Args.NONE, "Stop the stack, which drops the arm as any stop does", "CueStackStop", 1) { c, _, l ->
            l.stack(c["stack"]) { OscResolution.Press(BindingTarget.CueStackStop(it.uuid.toString()), "Stop ${it.name}", it.ref(OscRecordKind.STACK)) }
        },
        Entry("/stack/{stack}/fx-amount", Args.LEVEL, "The stack's FX amount", "StackFxAmount", 1) { c, v, l ->
            l.stack(c["stack"]) { OscResolution.Level(BindingTarget.StackFxAmount(it.id, it.uuid.toString()), v!!, "FX amount of ${it.name}", it.ref(OscRecordKind.STACK)) }
        },
        Entry("/cue/{cue}/on", Args.NONE, "Take a loose hold; nothing if already held", "LooseCueSet", 1) { c, _, l ->
            l.cue(c["cue"]) { OscResolution.Press(BindingTarget.LooseCueSet(it.uuid.toString(), on = true), "Cue ${it.name} on", it.ref(OscRecordKind.CUE)) }
        },
        Entry("/cue/{cue}/off", Args.NONE, "Let go of a loose hold; nothing if not held", "LooseCueSet", 1) { c, _, l ->
            l.cue(c["cue"]) { OscResolution.Press(BindingTarget.LooseCueSet(it.uuid.toString(), on = false), "Cue ${it.name} off", it.ref(OscRecordKind.CUE)) }
        },
        Entry("/cue/{cue}/press", Args.NONE, "Toggle the loose hold", "FireCue", 1) { c, _, l ->
            l.cue(c["cue"]) { OscResolution.Press(BindingTarget.FireCue(it.id, it.uuid.toString()), "Press cue ${it.name}", it.ref(OscRecordKind.CUE)) }
        },
        Entry("/look/{look}/on", Args.NONE, "The Look on whole; nothing if already on", "LookSet", 1) { c, _, l ->
            l.look(c["look"]) { OscResolution.Press(BindingTarget.LookSet(it.uuid.toString(), on = true), "Look ${it.name} on", it.ref(OscRecordKind.LOOK)) }
        },
        Entry("/look/{look}/off", Args.NONE, "Every layer of the Look off; nothing if already off", "LookSet", 1) { c, _, l ->
            l.look(c["look"]) { OscResolution.Press(BindingTarget.LookSet(it.uuid.toString(), on = false), "Look ${it.name} off", it.ref(OscRecordKind.LOOK)) }
        },
        Entry("/look/{look}/press", Args.NONE, "Toggle the Look", "ApplyLook", 1) { c, _, l ->
            l.look(c["look"]) { OscResolution.Press(BindingTarget.ApplyLook(it.uuid.toString()), "Press Look ${it.name}", it.ref(OscRecordKind.LOOK)) }
        },
        Entry("/busk/{page}/{bank}/{pad}/press", Args.NONE, "Press a pad, on the desk selection", "PressPad", 1) { c, _, l ->
            l.pad(c["page"], c["bank"], c["pad"])
        },
        Entry("/busk/{page}/show", Args.NONE, "Show that busk page", "BuskPageSet", 1) { c, _, l ->
            l.page(c["page"]) { OscResolution.Press(BindingTarget.BuskPageSet(it.uuid.toString()), "Show busk page ${it.name}", it.ref(OscRecordKind.PAGE)) }
        },
        Entry("/blackout", Args.FLAG_OPTIONAL, "Set blackout; toggle with no argument", "BlackoutSet · Blackout", 2) { _, v, _ ->
            when (v) {
                null -> OscResolution.Press(BindingTarget.Blackout, "Toggle blackout")
                0.0 -> OscResolution.Press(BindingTarget.BlackoutSet(on = false), "Blackout off")
                else -> OscResolution.Press(BindingTarget.BlackoutSet(on = true), "Blackout on")
            }
        },
        Entry("/grandmaster", Args.LEVEL, "Grand master level", "GrandMasterLevel", 2) { _, v, _ ->
            OscResolution.Press(BindingTarget.GrandMasterLevel(v!!.coerceIn(0.0, 1.0) * 100.0), "Grand master")
        },
        Entry("/tempo/{master}", Args.BPM, "Set a speed master's tempo (20–300)", "SpeedMasterSetBpm", 2) { c, v, l ->
            l.master(c["master"]) { master ->
                val bpm = v!!
                if (bpm < MasterClock.MIN_BPM || bpm > MasterClock.MAX_BPM) {
                    OscResolution.Bad("Tempo is from ${MasterClock.MIN_BPM.toInt()} to ${MasterClock.MAX_BPM.toInt()} BPM, not ${bpmText(bpm)}")
                } else {
                    OscResolution.Press(BindingTarget.SpeedMasterSetBpm(master.uuid.toString(), bpm), "Tempo of ${master.label}", master.ref())
                }
            }
        },
        Entry("/tempo/{master}/tap", Args.NONE, "One tap", "SpeedMasterTap", 2) { c, _, l ->
            l.master(c["master"]) { OscResolution.Press(BindingTarget.SpeedMasterTap(it.uuid.toString()), "Tap ${it.label}", it.ref()) }
        },
    )

    /**
     * Resolve one received message against the current project, read through [lookup]. An address
     * outside [PREFIX], matching no row, or with the wrong arguments is [OscResolution.Bad].
     */
    fun resolve(address: String, args: List<OscArg>, lookup: OscLookup): OscResolution {
        if (!address.startsWith("$PREFIX/")) return OscResolution.Bad("Not a $PREFIX address")
        val segments = segmentsOf(address)
        for (entry in entries) {
            val captures = match(entry, segments) ?: continue
            val first = args.firstOrNull()?.number()?.takeIf { it.isFinite() }
            val value = when (entry.args) {
                Args.NONE -> null
                Args.LEVEL -> first ?: return OscResolution.Bad("${entry.pattern} wants a number from 0 to 1")
                Args.BPM -> first ?: return OscResolution.Bad("${entry.pattern} wants a tempo from 20 to 300")
                Args.FLAG_OPTIONAL -> when {
                    args.isEmpty() -> null
                    else -> first ?: return OscResolution.Bad("${entry.pattern} wants 0 or 1, or nothing")
                }
            }
            return entry.resolve(captures, value, lookup)
        }
        return OscResolution.Bad("Unknown address")
    }

    /**
     * What [address] reaches now, whatever value it would carry — the arguments a row takes chosen
     * so it resolves — or null when it reaches nothing. Two answers that differ name different
     * records, which is how a received address that moved is found (`docs/osc-engineering.md`
     * §"Addresses that move").
     */
    fun reach(address: String, lookup: OscLookup): OscReach = reachIn(address, lookup)

    /** [reach] for each of [addresses], read through one cache. */
    fun reachAll(addresses: Collection<String>, lookup: OscLookup): Map<String, OscReach> {
        val cached = CachedOscLookup(lookup)
        return addresses.associateWith { reachIn(it, cached) }
    }

    private fun reachIn(address: String, lookup: OscLookup): OscReach {
        val entry = entryOf(address) ?: return OscReach.Gone("Unknown address")
        return when (val resolution = resolve(address, nominalArgs(entry), lookup)) {
            is OscResolution.Press -> OscReach.To(identityOf(address, resolution.record), resolution.reached)
            is OscResolution.Level -> OscReach.To(identityOf(address, resolution.record), resolution.reached)
            is OscResolution.NotFound -> OscReach.Gone(resolution.message)
            is OscResolution.Ambiguous -> OscReach.Gone(resolution.message)
            is OscResolution.Bad -> OscReach.Gone(resolution.message)
        }
    }

    /**
     * Every address the project answers to (the listing, P2): each placeholder filled with every
     * value the project has, and each address so made resolved by [resolve] itself, so the listing
     * can only say what a message would do.
     */
    fun list(lookup: OscLookup): OscAddressListDto {
        val catalog = CachedOscLookup(lookup)
        val addresses = mutableListOf<OscAddressDto>()
        val ambiguous = LinkedHashMap<String, OscAmbiguityDto>()
        for (entry in entries) {
            for (filled in fill(entry, catalog)) {
                val address = PREFIX + "/" + entry.segments.joinToString("/") { seg ->
                    if (seg.startsWith("{")) filled.getValue(seg.trim('{', '}')) else seg
                }
                when (val resolution = resolve(address, nominalArgs(entry), catalog)) {
                    is OscResolution.Press -> addresses += OscAddressDto(
                        address, PREFIX + entry.pattern, entry.args.label, entry.does, resolution.reached, resolution.target, resolution.record,
                    )
                    is OscResolution.Level -> addresses += OscAddressDto(
                        address, PREFIX + entry.pattern, entry.args.label, entry.does, resolution.reached, resolution.target, resolution.record,
                    )
                    is OscResolution.Ambiguous -> {
                        val prior = ambiguous[resolution.message]
                        ambiguous[resolution.message] = OscAmbiguityDto(
                            resolution.message, resolution.records, (prior?.addresses.orEmpty()) + address,
                        )
                    }
                    is OscResolution.NotFound, is OscResolution.Bad -> {}
                }
            }
        }
        return OscAddressListDto(
            prefix = PREFIX,
            patterns = entries.map { OscPatternDto(PREFIX + it.pattern, it.args.label, it.does, it.target, it.session) },
            addresses = addresses,
            ambiguous = ambiguous.values.toList(),
            unaddressed = unaddressed(catalog),
        )
    }

    /** The records no address can name, and why (D3). */
    private fun unaddressed(lookup: OscLookup): List<OscUnaddressedDto> = buildList {
        fun noSlug(kind: OscRecordKind, records: List<OscRecord>) = records.filter { it.slug.isEmpty() }.forEach {
            add(OscUnaddressedDto(kind, it.id, it.uuid.toString(), it.name, "Its name has no letter or digit, so it has no address"))
        }
        val stacks = lookup.stacks()
        noSlug(OscRecordKind.STACK, stacks)
        for (stack in stacks) {
            for (entry in lookup.entries(stack.id).filter { it.number == null }) {
                add(OscUnaddressedDto(OscRecordKind.ENTRY, entry.id, entry.uuid.toString(), "${entry.label} in ${stack.name}", "It has no cue number"))
            }
        }
        noSlug(OscRecordKind.CUE, lookup.cues())
        noSlug(OscRecordKind.LOOK, lookup.looks())
        noSlug(OscRecordKind.PAGE, lookup.pages())
    }

    /** Every way to fill [entry]'s placeholders from [lookup], in the pattern's order. */
    private fun fill(entry: Entry, lookup: OscLookup): List<Map<String, String>> {
        val names = entry.segments.filter { it.startsWith("{") }.map { it.trim('{', '}') }
        var filled = listOf(emptyMap<String, String>())
        for (name in names) {
            filled = filled.flatMap { bound -> candidates(name, bound, lookup).map { bound + (name to it) } }
        }
        return filled
    }

    private fun candidates(name: String, bound: Map<String, String>, lookup: OscLookup): List<String> {
        fun slugs(records: List<OscRecord>) = records.map { it.slug }.filter { it.isNotEmpty() }.distinct()
        fun only(records: List<OscRecord>, slug: String?) = records.filter { it.slug == slug }.singleOrNull()
        return when (name) {
            "stack" -> slugs(lookup.stacks())
            "cue" -> slugs(lookup.cues())
            "look" -> slugs(lookup.looks())
            "page" -> slugs(lookup.pages())
            // A slug two stacks or pages share is listed once as ambiguous, by its stack or page rows.
            "n" -> only(lookup.stacks(), bound["stack"])
                ?.let { stack -> lookup.entries(stack.id).mapNotNull { it.number }.distinct() }.orEmpty()
            "bank" -> only(lookup.pages(), bound["page"])
                ?.let { page -> lookup.banks(page.id).indices.map { (it + 1).toString() } }.orEmpty()
            "pad" -> only(lookup.pages(), bound["page"])
                ?.let { page -> lookup.banks(page.id).getOrNull(bound.getValue("bank").toInt() - 1) }
                ?.let { bank -> bank.pads.indices.map { (it + 1).toString() } }.orEmpty()
            "master" -> lookup.masters().map { it.index.toString() }
            else -> error("No placeholder {$name}")
        }
    }

    /** Arguments [entry] resolves with, for a read that does not care about the value. */
    private fun nominalArgs(entry: Entry): List<OscArg> = when (entry.args) {
        Args.NONE, Args.FLAG_OPTIONAL -> emptyList()
        Args.LEVEL -> listOf(OscArg.Float32(1f))
        Args.BPM -> listOf(OscArg.Float32(120f))
    }

    /**
     * What [address] reaching [record] is, for telling whether it moved: the record it names, never
     * the value a message carries, and for an address that names no record (`/blackout 1`, a toggle
     * without the flag) its row, which no change can move.
     */
    internal fun identityOf(address: String, record: OscRef?): String =
        record?.let { "${it.kind}:${it.uuid}" } ?: (patternOf(address) ?: address)

    /**
     * The table row [address] matches, as its pattern under [PREFIX] — what a trace is named, so a
     * name never carries a show's own words — or null for an address no row matches.
     */
    fun patternOf(address: String): String? = entryOf(address)?.let { PREFIX + it.pattern }

    private fun entryOf(address: String): Entry? {
        if (!address.startsWith("$PREFIX/")) return null
        val segments = segmentsOf(address)
        return entries.firstOrNull { match(it, segments) != null }
    }

    private fun segmentsOf(address: String) = address.removePrefix("$PREFIX/").trimEnd('/').split('/').map { it.lowercase() }

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

private fun bpmText(bpm: Double): String =
    if (bpm == Math.rint(bpm) && kotlin.math.abs(bpm) < 1e9) bpm.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, bpm)

/** What a received address reaches, or why it reaches nothing. */
sealed interface OscResolution {
    /** A button target; [reached] names the record ("GO TO 31 Storm builds in Act 2"). */
    data class Press(val target: BindingTarget, val reached: String, val record: OscRef? = null) : OscResolution

    /** A continuous target taken whole (D10). */
    data class Level(val target: BindingTarget, val value: Double, val reached: String, val record: OscRef? = null) : OscResolution

    data class NotFound(val message: String) : OscResolution

    /** Two records answer to the address; [message] names both (D3). */
    data class Ambiguous(val message: String, val records: List<OscRef> = emptyList()) : OscResolution

    /** An unknown address or the wrong arguments. */
    data class Bad(val message: String) : OscResolution
}

/** What an address reaches now, from [OscAddresses.reach]. */
sealed interface OscReach {
    /** [identity] is the record it names; [reached] the words a log row says. */
    data class To(val identity: String, val reached: String) : OscReach

    data class Gone(val message: String) : OscReach
}

/** The kinds of record an address can name. */
enum class OscRecordKind { STACK, ENTRY, CUE, LOOK, PAGE, PAD, MASTER }

/** The record an address reaches: what the client's *Copy OSC address* finds it by. */
@Serializable
data class OscRef(val kind: OscRecordKind, val id: Int, val uuid: String)

/** A record an address can name. */
data class OscRecord(val id: Int, val uuid: UUID, val name: String) {
    /** Worked out once a record, since the listing asks for every record's for every address. */
    val slug: String by lazy(LazyThreadSafetyMode.NONE) { slugOf(name) }

    fun ref(kind: OscRecordKind) = OscRef(kind, id, uuid.toString())
}

/** A cue entry of a stack, [number] its canonical cue number or null for none; [label] "31 Storm builds". */
data class OscEntry(val id: Int, val uuid: UUID, val number: String?, val label: String) {
    fun ref() = OscRef(OscRecordKind.ENTRY, id, uuid.toString())
}

/** A speed master: [index] is its display index, M2 is 2. */
data class OscMaster(val id: Int, val uuid: UUID, val index: Int, val name: String) {
    val label: String get() = "M$index $name"
    fun ref() = OscRef(OscRecordKind.MASTER, id, uuid.toString())
}

/** A busk bank and its pads, each named by what it presses. */
data class OscBank(val name: String, val pads: List<OscRecord>)

/**
 * What the resolver reads of the current project (D7). [DbOscLookup] is the desk's; it reads the
 * database inside the caller's transaction, as a REST request does, so a rename is answered by the
 * very next message and nothing needs invalidating.
 */
interface OscLookup {
    /** The cue stacks, never a separator. */
    fun stacks(): List<OscRecord>

    /** [stackId]'s cue entries in list order, never a marker. */
    fun entries(stackId: Int): List<OscEntry>

    fun cues(): List<OscRecord>
    fun looks(): List<OscRecord>
    fun pages(): List<OscRecord>

    /** [pageId]'s banks in layout order, each with its pads in order. */
    fun banks(pageId: Int): List<OscBank>

    fun masters(): List<OscMaster>
}

/** [inner] read once per question: the listing asks the same questions for every address. */
private class CachedOscLookup(private val inner: OscLookup) : OscLookup {
    private val stacks by lazy { inner.stacks() }
    private val cues by lazy { inner.cues() }
    private val looks by lazy { inner.looks() }
    private val pages by lazy { inner.pages() }
    private val masters by lazy { inner.masters() }
    private val entries = HashMap<Int, List<OscEntry>>()
    private val banks = HashMap<Int, List<OscBank>>()

    override fun stacks() = stacks
    override fun entries(stackId: Int) = entries.getOrPut(stackId) { inner.entries(stackId) }
    override fun cues() = cues
    override fun looks() = looks
    override fun pages() = pages
    override fun banks(pageId: Int) = banks.getOrPut(pageId) { inner.banks(pageId) }
    override fun masters() = masters
}

private fun one(
    found: List<OscRecord>,
    kind: OscRecordKind,
    what: String,
    slug: String,
    then: (OscRecord) -> OscResolution,
): OscResolution = when (found.size) {
    0 -> OscResolution.NotFound("No $what `$slug` in this show")
    1 -> then(found.single())
    else -> OscResolution.Ambiguous("`$slug` names ${found.size} ${what}s: ${found.joinToString { it.name }}", found.map { it.ref(kind) })
}

private fun List<OscRecord>.slugged(slug: String) = filter { it.slug == slug }

private fun OscLookup.stack(slug: String, then: (OscRecord) -> OscResolution) = one(stacks().slugged(slug), OscRecordKind.STACK, "stack", slug, then)
private fun OscLookup.cue(slug: String, then: (OscRecord) -> OscResolution) = one(cues().slugged(slug), OscRecordKind.CUE, "cue", slug, then)
private fun OscLookup.look(slug: String, then: (OscRecord) -> OscResolution) = one(looks().slugged(slug), OscRecordKind.LOOK, "Look", slug, then)
private fun OscLookup.page(slug: String, then: (OscRecord) -> OscResolution) = one(pages().slugged(slug), OscRecordKind.PAGE, "busk page", slug, then)

private fun OscLookup.master(index: String, then: (OscMaster) -> OscResolution): OscResolution {
    val n = index.toIntOrNull()?.takeIf { it >= 1 } ?: return OscResolution.Bad("Master `$index` is not a number from 1")
    val master = masters().firstOrNull { it.index == n } ?: return OscResolution.NotFound("No speed master M$n in this show")
    return then(master)
}

private fun OscLookup.entry(stackSlug: String, number: String, then: (OscRecord, OscEntry) -> OscResolution): OscResolution {
    val value = cueNumberValue(number) ?: return OscResolution.Bad("`$number` is not a cue number")
    return stack(stackSlug) { stack ->
        val found = entries(stack.id).filter { it.number?.let(::cueNumberValue)?.compareTo(value) == 0 }
        when (found.size) {
            0 -> OscResolution.NotFound("No cue $number in ${stack.name}")
            1 -> then(stack, found.single())
            else -> OscResolution.Ambiguous("Cue $number is in ${stack.name} ${found.size} times", found.map { it.ref() })
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
            thePad.ref(OscRecordKind.PAD),
        )
    }
}

/** The desk's [OscLookup]: [projectId]'s rows, read inside the caller's transaction. */
class DbOscLookup(private val projectId: Int) : OscLookup {
    override fun stacks() =
        DaoCueStack.find { (DaoCueStacks.project eq projectId) and (DaoCueStacks.type eq "STACK") }
            .map { OscRecord(it.id.value, it.uuid, it.name) }

    override fun entries(stackId: Int): List<OscEntry> {
        val entries = entriesOfStack(org.jetbrains.exposed.v1.core.dao.id.EntityID(stackId, DaoCueStacks)).filter { !it.isMarker }
        // The cues' names in one read, rather than one lazy load an entry.
        val cueIds = entries.mapNotNull { it.cueId }.distinct()
        val names = if (cueIds.isEmpty()) emptyMap() else DaoCue.find { DaoCues.id inList cueIds }.associate { it.id.value to it.name }
        return entries.map { entry ->
            val number = entry.cueNumber?.let(::cueNumberValue)?.let { entry.cueNumber }
            OscEntry(entry.id.value, entry.uuid, number, listOfNotNull(entry.cueNumber, entry.cueId?.let(names::get)).joinToString(" "))
        }
    }

    override fun cues() = DaoCue.find { DaoCues.project eq projectId }.map { OscRecord(it.id.value, it.uuid, it.name) }

    override fun looks() = DaoLook.find { DaoLooks.project eq projectId }.map { OscRecord(it.id.value, it.uuid, it.name) }

    override fun pages() = DaoBuskPage.find { DaoBuskPages.project eq projectId }.map { OscRecord(it.id.value, it.uuid, it.name) }

    override fun masters() =
        DaoSpeedMaster.find { DaoSpeedMasters.project eq projectId }
            .sortedBy { it.masterIndex }
            .map { OscMaster(it.id.value, it.uuid, it.masterIndex, it.name) }

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
                    val name = pad.palette?.name ?: pad.look?.name ?: pad.cue?.name ?: pad.cueStack?.name ?: return@mapNotNull null
                    OscRecord(pad.id.value, pad.uuid, name)
                },
            )
        }
    }
}

/** `GET /projects/{id}/osc/addresses`: the table, and every address the project answers to. */
@Serializable
data class OscAddressListDto(
    val prefix: String,
    /** The table itself, every row whatever the project holds. */
    val patterns: List<OscPatternDto>,
    /** Each address a message could be sent to now, in table order. */
    val addresses: List<OscAddressDto>,
    /** Slugs two records share, each with the addresses it makes (D3). */
    val ambiguous: List<OscAmbiguityDto>,
    /** Records no address can name, and why. */
    val unaddressed: List<OscUnaddressedDto>,
)

@Serializable
data class OscPatternDto(val pattern: String, val args: String, val does: String, val target: String, val session: Int)

/** One address: what it does, the record it reaches and the target it dispatches. */
@Serializable
data class OscAddressDto(
    val address: String,
    val pattern: String,
    val args: String,
    val does: String,
    val reaches: String,
    val target: BindingTarget,
    /** Null for an address that names no record (`/blackout`, `/grandmaster`). */
    val record: OscRef? = null,
)

@Serializable
data class OscAmbiguityDto(val message: String, val records: List<OscRef>, val addresses: List<String>)

@Serializable
data class OscUnaddressedDto(val kind: OscRecordKind, val id: Int, val uuid: String, val name: String, val reason: String)
