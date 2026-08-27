package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.dmx.packChannelKey
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.midi.PropertyChannelResolver
import uk.me.cormack.lighting7.show.Fixtures

/**
 * PROGRAMMER-layer write delegation, extracted from [FxEngine] (sweep item E1).
 *
 * Sticky manual values at property granularity. Callers (web busking, MIDI faders,
 * flash, locate, preset toggles) hand a typed `PropertyValue` plus their
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
    /** Provenance hook — programmer moves change provenance winners. */
    private val onProgrammerChanged: () -> Unit,
) {
    /** One entry of a [writeProperties] batch. */
    data class PropertyWrite(
        val fixture: GroupableFixture,
        val propertyName: String,
        val value: CueAssignmentResolver.PropertyValue,
        /** Group name when this entry came from a group control, else null (§7.1). */
        val sourceGroup: String? = null,
    )

    /**
     * Lay a [value] onto the programmer for [propertyName] of [fixture] as [owner] and
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
    fun writeProperty(
        owner: ProgrammerOwner,
        fixture: GroupableFixture,
        propertyName: String,
        value: CueAssignmentResolver.PropertyValue,
        touched: Boolean = true,
        sourceGroup: String? = null,
        absorbSideband: Boolean = true,
        fadeMs: Long = 0,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val writes = PropertyChannelWriter.resolve(fixture, propertyName, value)
        if (writes.isEmpty()) return writes
        programmerStore.putValue(
            owner, fixture.targetKey, propertyName, ProgrammerValue.Hard(value), touched, sourceGroup,
        )
        if (absorbSideband) absorbSidebandUnder(writes)
        publisher.publishCascadeForKeys(
            setOf(CueAssignmentResolver.Key.fixture(fixture.targetKey, propertyName)), fadeMs,
        )
        onProgrammerChanged()
        return writes
    }

    /** Group overload — fan out to every member, tagging slots with the group name (§7.1). */
    fun writeGroupProperty(
        owner: ProgrammerOwner,
        group: FixtureGroup<*>,
        propertyName: String,
        value: CueAssignmentResolver.PropertyValue,
        touched: Boolean = true,
        absorbSideband: Boolean = true,
        fadeMs: Long = 0,
    ): List<PropertyChannelResolver.ChannelWrite> = writeProperties(
        owner,
        group.fixtures.filterIsInstance<Fixture>().map {
            PropertyWrite(it, propertyName, value, sourceGroup = group.name)
        },
        touched = touched,
        absorbSideband = absorbSideband,
        fadeMs = fadeMs,
    ).flatten()

    /**
     * Clear [owner]'s programmer entry for [propertyName] on [fixture]. The property
     * cascades back to the most recent surviving owner, the cue layer (if a cue asserts
     * it), or baseline. Accepts any [GroupableFixture].
     */
    fun clearProperty(
        owner: ProgrammerOwner,
        fixture: GroupableFixture,
        propertyName: String,
        fadeMs: Long = 0,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val channels = PropertyChannelWriter.channelsFor(fixture, propertyName)
        programmerStore.clear(owner, fixture.targetKey, propertyName)
        if (channels.isEmpty()) return channels
        publisher.publishCascadeForKeys(
            setOf(CueAssignmentResolver.Key.fixture(fixture.targetKey, propertyName)), fadeMs,
        )
        onProgrammerChanged()
        return channels
    }

    /** Group overload for [clearProperty]. */
    fun clearGroupProperty(
        owner: ProgrammerOwner,
        group: FixtureGroup<*>,
        propertyName: String,
        fadeMs: Long = 0,
    ): List<PropertyChannelResolver.ChannelWrite> = clearProperties(
        owner,
        group.fixtures.filterIsInstance<Fixture>().map { it to propertyName },
        fadeMs = fadeMs,
    )

    /**
     * Batch counterpart of [writeProperty]: store every entry, then publish all
     * affected keys under one publish-lock acquisition and one controller
     * transaction. A locate on a large group is hundreds of property writes — issuing them
     * one-by-one would take the lock, rescan the active effects and commit a DMX transaction
     * per property.
     *
     * Returns one channel-write list per input entry (empty where the property didn't
     * resolve — nothing stored for that entry), in input order, so callers can record which
     * entries actually landed.
     */
    fun writeProperties(
        owner: ProgrammerOwner,
        writes: List<PropertyWrite>,
        touched: Boolean = true,
        absorbSideband: Boolean = true,
        fadeMs: Long = 0,
    ): List<List<PropertyChannelResolver.ChannelWrite>> {
        val resolved = writes.map { PropertyChannelWriter.resolve(it.fixture, it.propertyName, it.value) }
        val keys = HashSet<CueAssignmentResolver.Key>()
        for ((index, channelWrites) in resolved.withIndex()) {
            if (channelWrites.isEmpty()) continue
            val write = writes[index]
            programmerStore.putValue(
                owner,
                write.fixture.targetKey,
                write.propertyName,
                ProgrammerValue.Hard(write.value),
                touched,
                write.sourceGroup,
            )
            if (absorbSideband) absorbSidebandUnder(channelWrites)
            keys += CueAssignmentResolver.Key.fixture(write.fixture.targetKey, write.propertyName)
        }
        if (keys.isNotEmpty()) {
            publisher.publishCascadeForKeys(keys, fadeMs)
            onProgrammerChanged()
        }
        return resolved
    }

    /**
     * Batch counterpart of [clearProperty]: clear [owner]'s slot for every
     * (fixture, property) pair, then cascade all affected keys back to the surviving owner /
     * cue layer / baseline under one lock acquisition and one controller transaction.
     */
    fun clearProperties(
        owner: ProgrammerOwner,
        clears: List<Pair<GroupableFixture, String>>,
        fadeMs: Long = 0,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val all = mutableListOf<PropertyChannelResolver.ChannelWrite>()
        val keys = HashSet<CueAssignmentResolver.Key>()
        for ((fixture, propertyName) in clears) {
            programmerStore.clear(owner, fixture.targetKey, propertyName)
            val channels = PropertyChannelWriter.channelsFor(fixture, propertyName)
            if (channels.isEmpty()) continue
            keys += CueAssignmentResolver.Key.fixture(fixture.targetKey, propertyName)
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
     */
    fun clearEntries(
        clears: List<Pair<GroupableFixture, String>>,
        fadeMs: Long = 0,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val all = mutableListOf<PropertyChannelResolver.ChannelWrite>()
        val keys = HashSet<CueAssignmentResolver.Key>()
        var clearedAny = false
        for ((fixture, propertyName) in clears) {
            for (slot in programmerStore.slotsFor(fixture.targetKey, propertyName)) {
                if (slot.owner == ProgrammerOwner.LAYERS) continue
                programmerStore.clear(slot.owner, fixture.targetKey, propertyName)
                clearedAny = true
            }
            val channels = PropertyChannelWriter.channelsFor(fixture, propertyName)
            if (channels.isEmpty()) continue
            keys += CueAssignmentResolver.Key.fixture(fixture.targetKey, propertyName)
            all += channels
        }
        if (keys.isNotEmpty()) {
            publisher.publishCascadeForKeys(keys, fadeMs)
        }
        if (clearedAny) onProgrammerChanged()
        return all
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
     */
    fun setBlind(blind: Boolean, fadeMs: Long = 0) {
        if (programmerStore.blind == blind) return
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
                (entry.slots.firstOrNull()?.value?.resolved as? CueAssignmentResolver.PropertyValue.Slider)?.value
                    ?: continue
            }
            fixtures.controllerOrNull(Universe(0, entry.universe))
                ?.setValue(entry.channel, value, fadeMs)
        }
        onProgrammerChanged()
    }

    /** Drop all sideband slots under the given channel writes — see [writeProperty]. */
    private fun absorbSidebandUnder(writes: List<PropertyChannelResolver.ChannelWrite>) {
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
     * Would a programmer write of [propertyName] on [fixture] actually reach the wire?
     *
     * This is the public, resolved-by-name form of the two guards
     * [CascadePublisher.publishCascadeForKeys] applies per key —
     * [CascadePublisher.inferTargetForProperty] returning null, and
     * [CascadePublisher.allChannelsParked] — *in that order and via the same helpers*, so a
     * caller that pre-filters on this can never disagree with what the publish then does.
     * Resolving park through [FxTarget.isPropertyFullyParked] rather than enumerating channels
     * directly matters: [ColourTarget] scopes its extended white/amber/UV channels by
     * `bundleWithColour`, which is not the same set [PropertyChannelWriter.channelsFor]
     * enumerates by trait.
     *
     * Locate is the caller: it must know whether writing a property can achieve anything
     * before recording a toggle write for it — an unpublishable write would strand a
     * bookkeeping row for a value that never reached the wire.
     */
    fun publishability(
        fixture: GroupableFixture,
        propertyName: String,
    ): Publishability {
        val key = CueAssignmentResolver.Key.fixture(fixture.targetKey, propertyName)
        val target = publisher.inferTargetForProperty(fixture, key) ?: return Publishability.UNRESOLVED
        if (PropertyChannelWriter.channelsFor(fixture, propertyName).isEmpty()) {
            // A property whose descriptor exists but is not DMX-backed (e.g. `position` on a
            // Hue-backed head): `writeProperties` resolves zero channels for it.
            return Publishability.UNRESOLVED
        }
        return if (publisher.allChannelsParked(target, fixture)) {
            Publishability.PARK_MASKED
        } else {
            Publishability.PUBLISHABLE
        }
    }
}
