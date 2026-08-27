package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.ParkManager
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.show.Fixtures

/**
 * Transmits composed layer state (Layer 2 → Layer 4 → Layer 5 fallback) to the DMX
 * controllers — the publish half of the FX engine, extracted from [FxEngine] (sweep item E1).
 *
 * Two publish shapes share the machinery: [publishCascadeForKeys] repaints a caller-supplied
 * key set (programmer writes/clears, blind toggles), and [publishCueLayerToControllers]
 * walks the before/after diff of a Layer 4 republish. Both skip keys a running effect covers
 * (the tick pass paints those) and fully-parked targets (park wins at transmit regardless).
 */
class CascadePublisher internal constructor(
    private val fixtures: Fixtures,
    private val layerResolver: LayerResolver,
    /** Layer 1 park query — see [FxEngine]'s constructor doc for the semantics. */
    private val parkManager: ParkManager?,
    /**
     * The (fixtureKey, propertyName) pairs running effects cover, cached by the engine on
     * its effect-list epoch — see `FxEngine.coveredByRunningEffects`.
     */
    private val coveredByRunningEffects: () -> Set<Pair<String, String>>,
    private val throttle: FxLogThrottle,
) {
    // The Layer 4 publish lock (the engine's old `cueAssignmentsLock`). Serialises every
    // composed-state publish: [CueAssignmentLayer]'s "mutate map + republish flat snapshot"
    // steps must be atomic against each other, and a cascade publish must not race a
    // concurrent Layer 4 republish or fade-weight update reading the same `cueLayerState`.
    // Tick-loop reads go through [LayerResolver.fallbackFor]'s `@Volatile` snapshot and stay
    // lock-free.
    @PublishedApi internal val lock = Any()

    /**
     * Run [block] under the Layer 4 publish lock. Reentrant (a plain monitor). Inline so the
     * ~62 fps crossfade tick's `updateFadeWeights` doesn't allocate a lambda (and `Ref` boxes
     * for its captured accumulators) per frame.
     */
    internal inline fun <T> locked(block: () -> T): T = synchronized(lock, block)

    /**
     * Transmit the composed cascade fallback (cue layer → programmer → baseline) for each
     * affected (fixtureKey, propertyName) key. Same publish machinery as
     * [publishCueLayerToControllers], scoped to a caller-supplied key set rather than the
     * full Layer 4 diff.
     *
     * Skips keys a currently-running effect covers and fully-parked targets. The
     * effect-covered skip stays valid with the programmer above effects because the tick's
     * reset pass is programmer-aware: it repaints suppressed keys with programmer values
     * within one frame (≤20 ms) — the consequence is that writes/clears on effect-covered
     * keys settle on the next tick and do **not** fade.
     *
     * Takes the publish lock itself, so it doesn't race a concurrent Layer 4 republish or
     * fade-weight update reading the same `cueLayerState`.
     *
     * [fadeMs] > 0 drives the per-channel [uk.me.cormack.lighting7.dmx.TickerState] ramp
     * for the uncovered keys this publish writes; [perKeyFadeMs] overrides it for the keys
     * it names.
     */
    internal fun publishCascadeForKeys(
        keys: Set<CueAssignmentResolver.Key>,
        fadeMs: Long = 0,
        perKeyFadeMs: Map<CueAssignmentResolver.Key, Long> = emptyMap(),
    ): Unit = locked {
        if (keys.isEmpty()) return@locked

        val coveredByEffects = coveredByRunningEffects()

        if (coveredByEffects.isNotEmpty() &&
            keys.all { (it.targetKey to it.propertyName) in coveredByEffects }) {
            return@locked
        }

        val transaction = ControllerTransaction(fixtures.controllers)
        val fixturesWithTx = fixtures.withTransaction(transaction)
        var wrote = false

        for (key in keys) {
            if ((key.targetKey to key.propertyName) in coveredByEffects) continue

            val fixture = try {
                fixturesWithTx.untypedGroupableFixture(key.targetKey)
            } catch (e: Exception) {
                throttle.log("cascade-missing-${key.targetKey}", e) {
                    "FX engine: cascade publish could not find fixture '${key.targetKey}'"
                }
                continue
            }

            val target = inferTargetForProperty(fixture, key) ?: continue
            if (allChannelsParked(target, fixture)) continue

            try {
                val fallback = layerResolver.fallbackFor(target, fixture, key.targetKey)
                target.resetToFallback(fixture, fallback, perKeyFadeMs[key] ?: fadeMs)
                wrote = true
            } catch (e: Exception) {
                throttle.log("cascade-publish-${key.targetKey}.${key.propertyName}", e) {
                    "FX engine: failed to publish cascade for ${key.targetKey}.${key.propertyName}"
                }
            }
        }

        if (wrote) transaction.apply()
    }

    /**
     * Transmit the composed Layer 2 → Layer 4 → Layer 5 fallback for every property whose
     * cue-layer state changed. Without this, cues that contribute only property assignments
     * (no effects) never paint the stage — the tick loop early-returns when no effects are
     * running, and the effect-reset pass is the only other site that writes the composed
     * cascade onto controllers.
     *
     * Walks the union of (fixtureKey, propertyName) keys from the before and after cue-layer
     * snapshots' indexes, without materialising a union key set — crossfade ticks run this
     * per frame. Skips keys a currently-running effect covers (the effect tick will paint
     * them; the set comes from [coveredByRunningEffects], cached until the effect list or
     * fixture register moves) and fully-parked targets (park wins at transmit regardless).
     * Otherwise opens a single [ControllerTransaction] and writes the resolved fallback via
     * [FxTarget.resetToFallback] — same mechanism the tick loops' reset pass uses.
     *
     * Release semantics: when a key is in [before] but not [after],
     * [LayerResolver.fallbackFor] naturally falls through to the programmer (Layer 2, sticky
     * direct writes included) then Layer 5 (baseline), so the channel releases to whatever's
     * underneath rather than to zero.
     *
     * Callers hold the publish lock (via [locked]). The controller write is in-memory
     * buffering on the transaction; the actual transmit-side work is quick enough that
     * running it under the lock is fine — mirrors the pattern in the `updateChannel` handler
     * which also writes through to the controller synchronously.
     *
     * @param honourRowFades whether the winning rows' own `fadeDurationMs` may ramp this
     *   publish — see `CueAssignmentLayer.republishAssignments` for who may pass true.
     */
    internal fun publishCueLayerToControllers(
        before: LayerResolver.CueLayerSnapshot,
        after: LayerResolver.CueLayerSnapshot,
        honourRowFades: Boolean,
    ) {
        val beforeIndex = before.index
        val afterIndex = after.index
        if (beforeIndex.isEmpty() && afterIndex.isEmpty()) return

        val coveredByEffects = coveredByRunningEffects()
        // Empty for every cue whose rows asked for no fade, which is the common case — so the
        // compound-key allocation `publishKey` otherwise avoids is skipped entirely rather than
        // done and discarded.
        val rowFades = if (honourRowFades) after.fadeDurations else emptyMap()

        val transaction = ControllerTransaction(fixtures.controllers)
        val fixturesWithTx = fixtures.withTransaction(transaction)
        var wrote = false

        fun publishKey(
            fixtureKey: String,
            propertyName: String,
            beforeValue: CueAssignmentResolver.PropertyValue?,
            afterValue: CueAssignmentResolver.PropertyValue?,
        ) {
            // Skip keys whose composed Layer 4 value didn't actually change. Crossfade ticks
            // call republish at ~60 fps; mid-fade the eased weight often quantises to the
            // same UByte for several ticks in a row, and any cue not involved in the fade
            // keeps a constant composed value the whole way through. Equality is a cheap
            // data-class check.
            if (beforeValue == afterValue) return
            if ((fixtureKey to propertyName) in coveredByEffects) return

            val typeSource = afterValue ?: beforeValue ?: return
            val target = resolveTargetForCueLayerKey(fixtureKey, propertyName, typeSource)

            try {
                val fixture = fixturesWithTx.untypedGroupableFixture(fixtureKey)
                if (allChannelsParked(target, fixture)) return
                val fallback = layerResolver.fallbackFor(target, fixture, fixtureKey)
                // A released key (`afterValue == null`) snaps: its fade belonged to the row that
                // has just stopped contributing, and what it releases *to* is whatever sits
                // underneath — not something that row gets to time.
                val fadeMs = if (rowFades.isEmpty() || afterValue == null) {
                    0L
                } else {
                    rowFades[CueAssignmentResolver.Key.fixture(fixtureKey, propertyName)] ?: 0L
                }
                target.resetToFallback(fixture, fallback, fadeMs)
                wrote = true
            } catch (e: Exception) {
                throttle.log("layer4-publish-$fixtureKey.$propertyName", e) {
                    "FX engine: failed to publish Layer 4 for $fixtureKey.$propertyName"
                }
            }
        }

        for ((fixtureKey, afterProperties) in afterIndex) {
            val beforeProperties = beforeIndex[fixtureKey]
            for ((propertyName, afterValue) in afterProperties) {
                publishKey(fixtureKey, propertyName, beforeProperties?.get(propertyName), afterValue)
            }
        }
        // Keys present before but released in this publish.
        for ((fixtureKey, beforeProperties) in beforeIndex) {
            val afterProperties = afterIndex[fixtureKey]
            for ((propertyName, beforeValue) in beforeProperties) {
                if (afterProperties?.containsKey(propertyName) != true) {
                    publishKey(fixtureKey, propertyName, beforeValue, null)
                }
            }
        }

        if (wrote) transaction.apply()
    }

    /**
     * Infer the [FxTarget] kind for a cascade publish from the backing DMX property type on
     * [fixture]. Mirrors the type-dispatch that [resolveTargetForCueLayerKey] does from a
     * [CueAssignmentResolver.PropertyValue], but resolves the backing value by name via
     * [PropertyChannelWriter.resolveProperty] instead — the clear path doesn't have a value
     * in hand. Handles [FixtureElement][uk.me.cormack.lighting7.fixture.group.FixtureElement]s
     * as well as whole fixtures.
     *
     * Returns null when the property can't be resolved; caller should skip that key.
     */
    internal fun inferTargetForProperty(
        fixture: GroupableFixture,
        key: CueAssignmentResolver.Key,
    ): FxTarget? {
        if (key.propertyName.equals("position", ignoreCase = true)) {
            if (fixture !is WithPosition) return null
            return PositionTarget(FxTargetRef.fixture(key.targetKey), key.propertyName)
        }
        val resolved = PropertyChannelWriter.resolveProperty(fixture, key.propertyName) ?: return null
        return when (resolved.value) {
            is DmxColour -> ColourTarget(FxTargetRef.fixture(key.targetKey), key.propertyName)
            is DmxFixtureSetting<*> -> SettingTarget(key.targetKey, key.propertyName)
            is DmxSlider -> SliderTarget(key.targetKey, key.propertyName)
            else -> null
        }
    }

    /**
     * Construct the [FxTarget] for a Layer 4 key, deriving target kind from [typeSource].
     * Takes the key's two strings rather than a [CueAssignmentResolver.Key] so the ~62 fps
     * crossfade publish path doesn't materialise a compound key per changed entry.
     */
    private fun resolveTargetForCueLayerKey(
        fixtureKey: String,
        propertyName: String,
        typeSource: CueAssignmentResolver.PropertyValue,
    ): FxTarget = when (typeSource) {
        is CueAssignmentResolver.PropertyValue.Slider ->
            SliderTarget(fixtureKey, propertyName)
        is CueAssignmentResolver.PropertyValue.Colour ->
            ColourTarget(FxTargetRef.fixture(fixtureKey), propertyName)
        is CueAssignmentResolver.PropertyValue.Position ->
            PositionTarget(FxTargetRef.fixture(fixtureKey), propertyName)
        is CueAssignmentResolver.PropertyValue.Setting ->
            SettingTarget(fixtureKey, propertyName)
    }

    /**
     * The (fixture, property) key whose channels include (universe, channel), or null when
     * no property backs the channel. Walks the owning fixture's property catalogue plus the
     * position axes — the same channel set [FxTarget.fallbackFromProgrammer]'s sideband
     * lookups consult.
     */
    fun resolveChannelCoveringKey(universe: Int, channel: Int): CueAssignmentResolver.Key? {
        val mappings = fixtures.getChannelMappings()
        val fixtureKey = mappings[universe]?.get(channel)?.fixtureKey ?: return null
        val fixture = try {
            fixtures.untypedFixture(fixtureKey)
        } catch (_: Exception) {
            return null
        }

        for (prop in fixture.fixtureProperties) {
            val value = try {
                prop.classProperty.call(fixture)
            } catch (_: Exception) {
                continue
            } ?: continue
            when (value) {
                is DmxSlider -> if (value.channelNo == channel) {
                    return CueAssignmentResolver.Key.fixture(fixture.key, prop.name)
                }
                is DmxFixtureSetting<*> -> if (value.channelNo == channel) {
                    return CueAssignmentResolver.Key.fixture(fixture.key, prop.name)
                }
                is DmxColour -> if (
                    channel == value.redSlider.channelNo ||
                    channel == value.greenSlider.channelNo ||
                    channel == value.blueSlider.channelNo
                ) {
                    return CueAssignmentResolver.Key.fixture(fixture.key, prop.name)
                }
            }
        }

        val positionFixture = fixture as? WithPosition
        if (positionFixture != null) {
            val pan = positionFixture.pan as? DmxSlider
            val tilt = positionFixture.tilt as? DmxSlider
            if (pan?.channelNo == channel || tilt?.channelNo == channel) {
                return CueAssignmentResolver.Key.fixture(fixture.key, "position")
            }
        }
        return null
    }

    /**
     * Is every DMX channel backing [target] on [fixture] parked?
     *
     * When true, the caller can skip publish/reset work entirely because [ArtNetController]
     * will overwrite the value at transmit time with the parked value regardless. Partial
     * parking (rare) is treated as "not all parked" — the channels that aren't parked still
     * need their publish path to run.
     */
    internal fun allChannelsParked(
        target: FxTarget,
        fixture: GroupableFixture,
    ): Boolean {
        val pm = parkManager ?: return false
        return target.isPropertyFullyParked(fixture, pm)
    }
}
