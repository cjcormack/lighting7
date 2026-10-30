package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.dmx.packChannelKey
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.midi.AttributeChannelResolver
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures

/**
 * PROGRAMMER-layer write delegation, extracted from [FxEngine] (sweep item E1).
 *
 * Sticky manual values at property granularity. Callers (web busking, MIDI faders,
 * flash, locate, preset toggles) hand a typed `AttributeValue` plus their
 * `ProgrammerOwner`; the writer stores one property-level slot in [ProgrammerStore]
 * under that owner and publishes via [LayerResolver.fallbackFor]. Clears remove only
 * the caller's own slot; the property falls back to the most recent surviving owner
 * before cascading to the layers below.
 *
 * `fadeMs` is accepted on every write/clear and threaded to the publish; half (a)
 * ignores it (snap), half (b) drives the DmxController ramp for keys no running effect
 * covers.
 */
class ProgrammerWriter internal constructor(
    private val fixtures: Fixtures,
    private val programmerStore: ProgrammerStore,
    private val publisher: CascadePublisher,
    /**
     * Leaving Blind with a fade (effects plan §3.5): the engine grows the programmer band's effects,
     * which Blind held, from nothing over that fade. Called before the gate opens, so no pass paints
     * one at full first.
     */
    private val onBlindLifting: (fadeMs: Long) -> Unit = {},
    /** Provenance hook — programmer moves change provenance winners. */
    private val onProgrammerChanged: () -> Unit,
) {
    /** One entry of a [writeAttributes] batch. */
    data class AttributeWrite(
        val fixture: GroupableFixture,
        val attribute: String,
        val value: CueAssignmentResolver.AttributeValue,
        /** Group name when this entry came from a group control, else null (§7.1). */
        val sourceGroup: String? = null,
    )

    /**
     * Lay a [value] onto the programmer for [attribute] of [fixture] as [owner] and
     * publish immediately. Returns the resolved channel writes so callers can record
     * whether the write landed (empty = the property didn't resolve; nothing was stored).
     *
     * [absorbSideband] drops raw-channel sideband slots under the property's channels so a
     * stale unpark or raw-channel value cannot resurface when this entry clears. Sticky
     * operator writes (web, faders) absorb; momentary owners (flash, locate, presets) pass
     * false so their release still reveals whatever the sideband held.
     *
     * Accepts any [GroupableFixture] — a [Fixture] or a [FixtureElement]. For elements the
     * publish key is the element's own key so subsequent reads see the write.
     */
    fun writeAttribute(
        owner: ProgrammerOwner,
        fixture: GroupableFixture,
        attribute: String,
        value: CueAssignmentResolver.AttributeValue,
        touched: Boolean = true,
        sourceGroup: String? = null,
        absorbSideband: Boolean = true,
        fadeMs: Long = 0,
    ): List<AttributeChannelResolver.ChannelWrite> {
        val writes = AttributeChannelWriter.resolve(fixture, attribute, value)
        if (writes.isEmpty()) return writes
        programmerStore.putValue(
            owner, fixture.targetKey, attribute, ProgrammerValue.Hard(value), touched, sourceGroup,
        )
        if (absorbSideband) absorbSidebandUnder(writes)
        publisher.publishCascadeForKeys(
            setOf(CueAssignmentResolver.Key.fixture(fixture.targetKey, attribute)), fadeMs,
        )
        onProgrammerChanged()
        return writes
    }

    /** Group overload — fan out to every member, tagging slots with the group name (§7.1). */
    fun writeGroupAttribute(
        owner: ProgrammerOwner,
        group: FixtureGroup<*>,
        attribute: String,
        value: CueAssignmentResolver.AttributeValue,
        touched: Boolean = true,
        absorbSideband: Boolean = true,
        fadeMs: Long = 0,
    ): List<AttributeChannelResolver.ChannelWrite> = writeAttributes(
        owner,
        group.fixtures.filterIsInstance<Fixture>().map {
            AttributeWrite(it, attribute, value, sourceGroup = group.name)
        },
        touched = touched,
        absorbSideband = absorbSideband,
        fadeMs = fadeMs,
    ).flatten()

    /**
     * Clear [owner]'s programmer entry for [attribute] on [fixture]. The property
     * cascades back to the most recent surviving owner, the cue layer (if a cue asserts
     * it), or baseline. Accepts any [GroupableFixture].
     */
    fun clearAttribute(
        owner: ProgrammerOwner,
        fixture: GroupableFixture,
        attribute: String,
        fadeMs: Long = 0,
    ): List<AttributeChannelResolver.ChannelWrite> {
        val channels = AttributeChannelWriter.channelsFor(fixture, attribute)
        programmerStore.clear(owner, fixture.targetKey, attribute)
        if (channels.isEmpty()) return channels
        publisher.publishCascadeForKeys(
            setOf(CueAssignmentResolver.Key.fixture(fixture.targetKey, attribute)), fadeMs,
        )
        onProgrammerChanged()
        return channels
    }

    /** Group overload for [clearAttribute]. */
    fun clearGroupAttribute(
        owner: ProgrammerOwner,
        group: FixtureGroup<*>,
        attribute: String,
        fadeMs: Long = 0,
    ): List<AttributeChannelResolver.ChannelWrite> = clearAttributes(
        owner,
        group.fixtures.filterIsInstance<Fixture>().map { it to attribute },
        fadeMs = fadeMs,
    )

    /**
     * Batch counterpart of [writeAttribute]: store every entry, then publish all
     * affected keys under one publish-lock acquisition and one controller
     * transaction. A locate on a large group is hundreds of property writes — issuing them
     * one-by-one would take the lock, rescan the active effects and commit a DMX transaction
     * per property.
     *
     * Returns one channel-write list per input entry (empty where the property didn't
     * resolve — nothing stored for that entry), in input order, so callers can record which
     * entries actually landed.
     *
     * **Stored with the `bundleWithColour` emitters last.** Each put takes the next write seq, so
     * within one batch the input order would decide which of a colour row and its emitter's own
     * row is "newer" — and so what `FxTarget.composeProgrammerOver` puts on the shared W/A/UV
     * channel. One gesture is one contributor, and for one contributor the emitter's own row wins
     * (`CueAssignmentResolver.reconcileBundledEmitters`), so Include of a cue or a template apply
     * leaves the programmer showing what the cue plays back, whatever order its rows were in.
     */
    fun writeAttributes(
        owner: ProgrammerOwner,
        writes: List<AttributeWrite>,
        touched: Boolean = true,
        absorbSideband: Boolean = true,
        fadeMs: Long = 0,
    ): List<List<AttributeChannelResolver.ChannelWrite>> {
        val resolved = writes.map { AttributeChannelWriter.resolve(it.fixture, it.attribute, it.value) }
        val keys = HashSet<CueAssignmentResolver.Key>()
        for (index in emittersLast(writes)) {
            val channelWrites = resolved[index]
            if (channelWrites.isEmpty()) continue
            val write = writes[index]
            programmerStore.putValue(
                owner,
                write.fixture.targetKey,
                write.attribute,
                ProgrammerValue.Hard(write.value),
                touched,
                write.sourceGroup,
            )
            if (absorbSideband) absorbSidebandUnder(channelWrites)
            keys += CueAssignmentResolver.Key.fixture(write.fixture.targetKey, write.attribute)
        }
        if (keys.isNotEmpty()) {
            publisher.publishCascadeForKeys(keys, fadeMs)
            onProgrammerChanged()
        }
        return resolved
    }

    /**
     * Store [writes] as [owner] without publishing, for a caller whose writes leave every key showing
     * what it already shows — a layer's Detach. [beneathTop] puts each **under** whatever owner holds
     * the key ([ProgrammerStore.putBeneathTop]); otherwise each goes on top as [writeAttributes] puts
     * it, emitters last for the same reason. The sideband is never absorbed. The programmer's own
     * readers are told, as for any write.
     */
    fun storeUnpublished(owner: ProgrammerOwner, writes: List<AttributeWrite>, beneathTop: Boolean, touched: Boolean = true) {
        if (writes.isEmpty()) return
        for (index in emittersLast(writes)) {
            val write = writes[index]
            if (beneathTop) {
                programmerStore.putBeneathTop(
                    owner, write.fixture.targetKey, write.attribute, write.value, touched, write.sourceGroup,
                )
            } else {
                programmerStore.putValue(
                    owner, write.fixture.targetKey, write.attribute, ProgrammerValue.Hard(write.value), touched, write.sourceGroup,
                )
            }
        }
        onProgrammerChanged()
    }

    /**
     * [writes]' indices with the `bundleWithColour` emitters last — see [writeAttributes]. A stable
     * partition, and only when the batch holds an emitter at all: the common batch (a locate, a
     * spread) holds none and keeps its own order at the cost of one scan.
     */
    private fun emittersLast(writes: List<AttributeWrite>): List<Int> {
        val emitter = writes.map { bundleRoleOf(it.fixture, it.attribute) == CueAssignmentResolver.BundleRole.EMITTER }
        return if (emitter.none { it }) writes.indices.toList() else writes.indices.sortedBy { if (emitter[it]) 1 else 0 }
    }

    /**
     * Batch counterpart of [clearAttribute]: clear [owner]'s slot for every
     * (fixture, property) pair, then cascade all affected keys back to the surviving owner /
     * cue layer / baseline under one lock acquisition and one controller transaction.
     */
    fun clearAttributes(
        owner: ProgrammerOwner,
        clears: List<Pair<GroupableFixture, String>>,
        fadeMs: Long = 0,
    ): List<AttributeChannelResolver.ChannelWrite> {
        val all = mutableListOf<AttributeChannelResolver.ChannelWrite>()
        val keys = HashSet<CueAssignmentResolver.Key>()
        for ((fixture, attribute) in clears) {
            programmerStore.clear(owner, fixture.targetKey, attribute)
            val channels = AttributeChannelWriter.channelsFor(fixture, attribute)
            if (channels.isEmpty()) continue
            keys += CueAssignmentResolver.Key.fixture(fixture.targetKey, attribute)
            all += channels
        }
        if (keys.isNotEmpty()) {
            publisher.publishCascadeForKeys(keys, fadeMs)
            onProgrammerChanged()
        }
        return all
    }

    /**
     * Release **every** owner's slot on each (fixture, property) pair — the operator
     * "clear this entry" gesture — with one store sweep, one locked cascade publish, and
     * one provenance update. Clearing owner-by-owner would transmit each surviving owner's
     * value as an intermediate step (and, with [fadeMs] > 0, restart the ramp per owner);
     * this releases each property in a single step to whatever sits below the programmer.
     *
     * **[ProgrammerOwner.LAYERS] is exempt, and that exemption is load-bearing.** A layer's
     * contribution is derived state: the next recook — any Look edit, amount change or reorder —
     * rebuilds it from the stack, so clearing it here would come back seconds later and read as the
     * clear having been ignored. "Clear this entry" means *release the local writes on it*; removing
     * a layer's contribution is done by removing the layer. Nothing is lost by skipping it, because
     * with local writes gone the layer slot is what should be showing.
     *
     * [withSideband] also drops the raw channel slots on each pair's channels, for a caller handing
     * the whole value to a layer — a pan drag in the Channels view is part of the position it took.
     */
    fun clearEntries(
        clears: List<Pair<GroupableFixture, String>>,
        fadeMs: Long = 0,
        withSideband: Boolean = false,
    ): List<AttributeChannelResolver.ChannelWrite> {
        val all = mutableListOf<AttributeChannelResolver.ChannelWrite>()
        val keys = HashSet<CueAssignmentResolver.Key>()
        var clearedAny = false
        for ((fixture, attribute) in clears) {
            for (slot in programmerStore.slotsFor(fixture.targetKey, attribute)) {
                if (slot.owner == ProgrammerOwner.LAYERS) continue
                programmerStore.clear(slot.owner, fixture.targetKey, attribute)
                clearedAny = true
            }
            val channels = AttributeChannelWriter.channelsFor(fixture, attribute)
            if (channels.isEmpty()) continue
            keys += CueAssignmentResolver.Key.fixture(fixture.targetKey, attribute)
            all += channels
        }
        if (withSideband) {
            val held = all.filter { programmerStore.getChannelSlot(it.universe.universe, it.channel) != null }
                .map { packChannelKey(it.universe.universe, it.channel) }
            if (held.isNotEmpty()) {
                programmerStore.clearChannelsAbsorbedBy(held)
                clearedAny = true
            }
        }
        if (keys.isNotEmpty()) {
            publisher.publishCascadeForKeys(keys, fadeMs)
        }
        if (clearedAny) onProgrammerChanged()
        return all
    }

    /** What [releaseFixtures] took out of the programmer. */
    data class Release(
        /** Property entries released (one per key, however many owners held it) plus sideband channels. */
        val values: Int,
        /**
         * Every (key, property, owner) **property** slot removed, for the callers that keep
         * bookkeeping per owner (Locate). Sideband slots are not listed: no owner with bookkeeping
         * writes one.
         */
        val releasedSlots: List<Triple<String, String, ProgrammerOwner>>,
    )

    /**
     * Release **everything** the programmer holds on [targets] — every property entry on each key
     * (a fixture's own and each of its heads', when the caller lists them) and every sideband slot on
     * a channel one of them owns — in one sweep, one cascade publish and one provenance update. The
     * fixture sheet's *Release* (`programmer.clearTarget`, fixture-fx-sheets plan W2, D6).
     *
     * [clearEntries]' rule per key, widened from one property to all of them: every owner's slot
     * goes **except [ProgrammerOwner.LAYERS]**, whose contribution is derived and would come back on
     * the next recook — a layer leaves by being removed, not by clearing what it cooked.
     *
     * A sideband channel is released when the property covering it belongs to one of [targets], or
     * when it is one of [footprint]'s — the channels the targets are patched on, which also finds a
     * raw write on a channel no property backs. A covered channel releases through the cascade; an
     * unbacked one has nothing below it and goes to 0, as [clearAll] does.
     */
    fun releaseFixtures(
        targets: Collection<GroupableFixture>,
        footprint: Set<Long> = emptySet(),
        fadeMs: Long = 0,
    ): Release {
        val byKey = targets.associateBy { it.targetKey }
        val keys = HashSet<CueAssignmentResolver.Key>()
        val released = ArrayList<Triple<String, String, ProgrammerOwner>>()
        var values = 0
        for (entry in programmerStore.entries()) {
            val fixture = byKey[entry.fixtureKey] ?: continue
            var any = false
            for (slot in entry.slots) {
                if (slot.owner == ProgrammerOwner.LAYERS) continue
                programmerStore.clear(slot.owner, entry.fixtureKey, entry.attribute)
                released += Triple(entry.fixtureKey, entry.attribute, slot.owner)
                any = true
            }
            if (!any) continue
            values++
            if (AttributeChannelWriter.channelsFor(fixture, entry.attribute).isNotEmpty()) {
                keys += CueAssignmentResolver.Key.fixture(entry.fixtureKey, entry.attribute)
            }
        }

        val sideband = ArrayList<Long>()
        val unbacked = ArrayList<Pair<Int, Int>>()
        for (entry in programmerStore.channelEntries()) {
            val covering = publisher.resolveChannelCoveringKey(entry.universe, entry.channel)
            val packed = packChannelKey(entry.universe, entry.channel)
            val ours = (covering != null && covering.targetKey in byKey) || packed in footprint
            if (!ours) continue
            sideband += packed
            if (covering != null) keys += covering else unbacked += entry.universe to entry.channel
        }
        if (sideband.isNotEmpty()) programmerStore.clearChannelsAbsorbedBy(sideband)
        values += sideband.size

        if (values == 0) return Release(0, emptyList())
        if (keys.isNotEmpty()) publisher.publishCascadeForKeys(keys, fadeMs)
        if (!programmerStore.blind) {
            for ((universe, channel) in unbacked) {
                fixtures.controllerOrNull(Universe(0, universe))?.setValue(channel, 0u, fadeMs)
            }
        }
        onProgrammerChanged()
        return Release(values, released)
    }

    /**
     * Raw-channel write into the programmer's sideband — the compatibility path for
     * `updateChannel` on channels the property model can't lift (position axes, channels
     * with no backing property) and for unpark hand-downs.
     *
     * When [coveringKey] identifies the (fixture, property) whose channels include this one,
     * the key is republished so the sideband value reaches the wire through the normal
     * cascade. When the channel has no backing property at all ([coveringKey] = null),
     * nothing below it exists in the cascade — the value is written straight to the
     * controller.
     */
    fun writeChannel(
        owner: ProgrammerOwner,
        universe: Int,
        channel: Int,
        value: UByte,
        coveringKey: CueAssignmentResolver.Key?,
        touched: Boolean = true,
        fadeMs: Long = 0,
    ) {
        programmerStore.putChannel(owner, universe, channel, value, touched)
        if (coveringKey != null) {
            publisher.publishCascadeForKeys(setOf(coveringKey), fadeMs)
        } else if (!programmerStore.blind) {
            fixtures.controllerOrNull(Universe(0, universe))
                ?.setValue(channel, value, fadeMs)
        }
        onProgrammerChanged()
    }

    /**
     * Sweep the entire programmer — every owner's property entries and the raw-channel
     * sideband — and release everything to the layers below in one pass. The operator
     * escape hatch behind `programmer.clearAll` and `POST /api/rest/programmer/clear-all`.
     *
     * Property-backed state (including sideband slots whose channel a property covers)
     * releases through the normal cascade publish. Sideband channels with no backing
     * property have nothing below them; they release to DMX 0.
     *
     * Returns the number of entries removed (properties + sideband channels). Callers that
     * own toggle bookkeeping (locate, preset toggles) must reset it themselves — see
     * `clearProgrammerCompletely` in `routes/programmer.kt`.
     */
    fun clearAll(fadeMs: Long = 0): Int {
        val keys = HashSet(programmerStore.activeKeys())
        val channelEntries = programmerStore.channelEntries()
        val count = programmerStore.size + channelEntries.size
        if (count == 0) return 0

        // Map each sideband channel to the property that covers it (so it releases through
        // the cascade) or remember it as unbacked (released to 0 below).
        val unbacked = mutableListOf<Pair<Int, Int>>()
        for (entry in channelEntries) {
            val key = publisher.resolveChannelCoveringKey(entry.universe, entry.channel)
            if (key != null) keys += key else unbacked += entry.universe to entry.channel
        }

        programmerStore.clearAll()

        if (keys.isNotEmpty()) {
            publisher.publishCascadeForKeys(keys, fadeMs)
        }
        for ((universe, channel) in unbacked) {
            fixtures.controllerOrNull(Universe(0, universe))
                ?.setValue(channel, 0u, fadeMs)
        }
        onProgrammerChanged()
        return count
    }

    /**
     * Set the programmer's blind gate and republish every key it holds so the change lands
     * on stage: entering blind releases programmer-held properties to the layers below;
     * exiting restores the staged values. The stored programmer state is untouched either
     * way. [fadeMs] rides the same publish plumbing as clears (snap in half (a)).
     *
     * The programmer's effects follow (effects plan §3.5): Blind holds them at once, as it holds
     * the band, and leaving Blind grows them from nothing over [fadeMs], like a press.
     */
    fun setBlind(blind: Boolean, fadeMs: Long = 0) {
        if (programmerStore.blind == blind) return
        if (!blind) onBlindLifting(fadeMs)
        programmerStore.blind = blind

        val keys = HashSet(programmerStore.activeKeys())
        val unbacked = mutableListOf<ProgrammerStore.ChannelEntryView>()
        for (entry in programmerStore.channelEntries()) {
            val key = publisher.resolveChannelCoveringKey(entry.universe, entry.channel)
            if (key != null) keys += key else unbacked += entry
        }
        if (keys.isNotEmpty()) {
            publisher.publishCascadeForKeys(keys, fadeMs)
        }
        // Unbacked sideband channels have no cascade below them: blind writes 0, unblind
        // restores the sideband value.
        for (entry in unbacked) {
            val value = if (blind) {
                0u.toUByte()
            } else {
                (entry.slots.firstOrNull()?.value?.resolved as? CueAssignmentResolver.AttributeValue.Slider)?.value
                    ?: continue
            }
            fixtures.controllerOrNull(Universe(0, entry.universe))
                ?.setValue(entry.channel, value, fadeMs)
        }
        onProgrammerChanged()
    }

    /**
     * Publish everything the programmer holds, as it holds it — every property key and every
     * sideband channel, through the same routes [setBlind] takes. For a store that arrived with its
     * contents already in it: a same-project reload hands the outgoing show's [ProgrammerStore] to
     * its successor, whose controllers have never heard it. Snaps; the successor's controllers start
     * from the outgoing show's last frame, so a value the reload did not move does not move. Blind,
     * the cascade publish leaves the programmer out on its own and an unbacked channel is left as
     * the carried frame has it.
     */
    fun republishAll() {
        val keys = HashSet(programmerStore.activeKeys())
        val unbacked = mutableListOf<ProgrammerStore.ChannelEntryView>()
        for (entry in programmerStore.channelEntries()) {
            val key = publisher.resolveChannelCoveringKey(entry.universe, entry.channel)
            if (key != null) keys += key else unbacked += entry
        }
        if (keys.isNotEmpty()) publisher.publishCascadeForKeys(keys)
        if (!programmerStore.blind) {
            for (entry in unbacked) {
                val value = (entry.slots.firstOrNull()?.value?.resolved as? CueAssignmentResolver.AttributeValue.Slider)?.value
                    ?: continue
                fixtures.controllerOrNull(Universe(0, entry.universe))?.setValue(entry.channel, value, 0)
            }
        }
        onProgrammerChanged()
    }

    /** Drop all sideband slots under the given channel writes — see [writeAttribute]. */
    private fun absorbSidebandUnder(writes: List<AttributeChannelResolver.ChannelWrite>) {
        programmerStore.clearChannelsAbsorbedBy(
            writes.map { packChannelKey(it.universe.universe, it.channel) }
        )
    }

    /**
     * Republish programmer keys whose stored values were rewritten *in place* — today, a Look edit
     * re-cooking the programmer's layer stack.
     *
     * The public door onto [CascadePublisher.publishCascadeForKeys] for callers that mutated
     * [ProgrammerStore] directly rather than through a `write*` entry point, and so
     * have nothing to publish from. Emits provenance the same way those do.
     */
    fun republishKeys(keys: Set<CueAssignmentResolver.Key>, fadeMs: Long = 0) {
        if (keys.isEmpty()) return
        publisher.publishCascadeForKeys(keys, fadeMs)
        onProgrammerChanged()
    }

    /**
     * As [republishKeys], but with a **per-key** ramp: [fadeMs] is the default and
     * [perKeyFadeMs] overrides it for the keys it names.
     *
     * One call rather than one per distinct fade, because a gesture is one transaction: a Look
     * whose colour row fades over 2 s beside a snapping dimmer row is still one arrival, and
     * splitting it would let the 50 Hz tick run between the halves and put them in different
     * ArtNet frames. See `ProgrammerLayerStack.recook`, its only caller.
     */
    fun republishKeys(
        keys: Set<CueAssignmentResolver.Key>,
        perKeyFadeMs: Map<CueAssignmentResolver.Key, Long>,
        fadeMs: Long = 0,
    ) {
        if (keys.isEmpty()) return
        publisher.publishCascadeForKeys(keys, fadeMs, perKeyFadeMs)
        onProgrammerChanged()
    }

    /** Whether a programmer write of one property would reach the wire, and if not, why. */
    enum class Publishability {
        /** The property resolves to channels and at least one of them is unparked. */
        PUBLISHABLE,

        /** Every channel backing the property is parked — park wins at transmit. */
        PARK_MASKED,

        /** The property has no DMX-backed channels on this fixture; a write is a no-op. */
        UNRESOLVED,
    }

    /**
     * Would a programmer write of [attribute] on [fixture] actually reach the wire?
     *
     * This is the public, resolved-by-name form of the two guards
     * [CascadePublisher.publishCascadeForKeys] applies per key —
     * [CascadePublisher.inferTargetForAttribute] returning null, and
     * [CascadePublisher.allChannelsParked] — *in that order and via the same helpers*, so a
     * caller that pre-filters on this can never disagree with what the publish then does.
     * Resolving park through [FxTarget.isAttributeFullyParked] rather than enumerating channels
     * directly matters: [ColourTarget] scopes its extended white/amber/UV channels by
     * `bundleWithColour`, which is not the same set [AttributeChannelWriter.channelsFor]
     * enumerates by trait.
     *
     * Locate is the caller: it must know whether writing a property can achieve anything
     * before recording a toggle write for it — an unpublishable write would strand a
     * bookkeeping row for a value that never reached the wire.
     */
    fun publishability(
        fixture: GroupableFixture,
        attribute: String,
    ): Publishability {
        val key = CueAssignmentResolver.Key.fixture(fixture.targetKey, attribute)
        val target = publisher.inferTargetForAttribute(fixture, key) ?: return Publishability.UNRESOLVED
        if (AttributeChannelWriter.channelsFor(fixture, attribute).isEmpty()) {
            // A property whose descriptor exists but is not DMX-backed (e.g. `position` on a
            // Hue-backed head): `writeAttributes` resolves zero channels for it.
            return Publishability.UNRESOLVED
        }
        return if (publisher.allChannelsParked(target, fixture)) {
            Publishability.PARK_MASKED
        } else {
            Publishability.PUBLISHABLE
        }
    }
}

/**
 * `memberFixtureKey → groupKey` for every group in [targets], so a writer that fans a
 * group-scoped gesture out to members can still stamp `sourceGroup` on each programmer slot.
 *
 * Without the hint, Record sees N unrelated fixture entries and emits N cue rows where the
 * operator wrote one group row. Groups are visited in sorted key order and the first group
 * claiming a member wins, so overlapping groups produce a stable (if arbitrary) hint rather
 * than one that flips between calls.
 */
internal fun groupHintsForTargets(
    fixtures: Fixtures,
    targets: List<TargetRef>,
): Map<String, String> {
    val groupKeys = targets.filterIsInstance<TargetRef.Group>().map { it.key }.distinct().sorted()
    if (groupKeys.isEmpty()) return emptyMap()
    val out = HashMap<String, String>()
    for (groupKey in groupKeys) {
        val group = try {
            fixtures.untypedGroup(groupKey)
        } catch (_: IllegalStateException) {
            continue
        }
        for (member in group.fixtures) out.putIfAbsent(member.targetKey, groupKey)
    }
    return out
}
