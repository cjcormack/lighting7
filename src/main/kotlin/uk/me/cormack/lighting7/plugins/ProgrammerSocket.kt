package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.Layer3Resolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.PropertyChannelWriter
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.routes.clearProgrammerCompletely
import uk.me.cormack.lighting7.routes.prunePresetToggleWrite
import uk.me.cormack.lighting7.state.State
import java.awt.Color

// ── Inbound messages ────────────────────────────────────────────────────────

@Serializable
sealed class ProgrammerInMessage : InMessage()

/**
 * Set a programmer value on a fixture or group property. [value] uses the same canonical
 * string form as cue assignments ([Layer3Resolver.parseAssignmentValue] /
 * [Layer3Resolver.PropertyValue.serialize]): `"0".."255"` for sliders and settings,
 * `"#rrggbb"` (+ optional `w`/`a`/`uv` tags) or a palette ref (`"P1"`) for colours,
 * `"pan,tilt"` for `position`.
 */
@Serializable
@SerialName("programmer.set")
data class ProgrammerSetInMessage(
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    val value: String,
    val fadeMs: Long? = null,
) : ProgrammerInMessage()

/** Typed colour write — avoids string round-trips for colour pickers. */
@Serializable
@SerialName("programmer.setColour")
data class ProgrammerSetColourInMessage(
    val targetType: String,
    val targetKey: String,
    val propertyName: String = "rgbColour",
    val r: UByte,
    val g: UByte,
    val b: UByte,
    val w: UByte? = null,
    val a: UByte? = null,
    val uv: UByte? = null,
    val fadeMs: Long? = null,
) : ProgrammerInMessage()

/** Typed position write. */
@Serializable
@SerialName("programmer.setPosition")
data class ProgrammerSetPositionInMessage(
    val targetType: String,
    val targetKey: String,
    val pan: UByte,
    val tilt: UByte,
    val fadeMs: Long? = null,
) : ProgrammerInMessage()

/** Clear one programmer entry (all owners) on a fixture or group property. */
@Serializable
@SerialName("programmer.clearEntry")
data class ProgrammerClearEntryInMessage(
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    val fadeMs: Long? = null,
) : ProgrammerInMessage()

/** Release the entire programmer — every owner, property entries and channel sideband. */
@Serializable
@SerialName("programmer.clearAll")
data class ProgrammerClearAllInMessage(
    val fadeMs: Long? = null,
) : ProgrammerInMessage()

/** Gate the programmer's stage contribution without touching the stored state. */
@Serializable
@SerialName("programmer.setBlind")
data class ProgrammerSetBlindInMessage(
    val blind: Boolean,
    val fadeMs: Long? = null,
) : ProgrammerInMessage()

/** Request the full programmer state snapshot. */
@Serializable
@SerialName("programmer.state")
data object ProgrammerStateInMessage : ProgrammerInMessage()

// ── Outbound messages ───────────────────────────────────────────────────────

@Serializable
sealed class ProgrammerOutMessage : OutMessage()

@Serializable
@SerialName("programmer.entryChanged")
data class ProgrammerEntryChangedOutMessage(
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    val value: String,
) : ProgrammerOutMessage()

@Serializable
@SerialName("programmer.entryCleared")
data class ProgrammerEntryClearedOutMessage(
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
) : ProgrammerOutMessage()

@Serializable
@SerialName("programmer.cleared")
data class ProgrammerClearedOutMessage(
    val entryCount: Int,
    /** Programmer-band FX instances swept alongside the values. */
    val effectsCleared: Int = 0,
) : ProgrammerOutMessage()

@Serializable
@SerialName("programmer.blindState")
data class ProgrammerBlindStateOutMessage(
    val blind: Boolean,
) : ProgrammerOutMessage()

/** One property entry in the state snapshot; [value]/[owner]/[touched] are the winning slot's. */
@Serializable
data class ProgrammerEntryDto(
    val targetKey: String,
    val propertyName: String,
    val value: String,
    val owner: String,
    val touched: Boolean,
    val sourceGroup: String? = null,
    /** Every owner holding this property, most recent first. */
    val owners: List<String>,
)

/** One channel-sideband entry in the state snapshot. */
@Serializable
data class ProgrammerChannelDto(
    val universe: Int,
    val channel: Int,
    val value: UByte,
    val owner: String,
    val touched: Boolean,
)

@Serializable
@SerialName("programmer.state")
data class ProgrammerStateOutMessage(
    val blind: Boolean,
    val entries: List<ProgrammerEntryDto>,
    val channels: List<ProgrammerChannelDto>,
) : ProgrammerOutMessage()

@Serializable
@SerialName("programmer.error")
data class ProgrammerErrorOutMessage(
    val message: String,
) : ProgrammerOutMessage()

/**
 * One provenance entry: which layer produced the current winning value for
 * (targetKey, propertyName). Keys nothing covers are omitted (baseline).
 */
@Serializable
data class ProvenanceEntryDto(
    val targetKey: String,
    val propertyName: String,
    /** `PARKED` | `PROGRAMMER` | `EFFECT` | `CUE`. */
    val source: String,
    val cueId: Int? = null,
    val cueStackId: Int? = null,
    val effectId: Long? = null,
)

/**
 * Full provenance snapshot — pushed on every layer event (programmer mutation, cue
 * republish, effect lifecycle change, park change), never per frame.
 */
@Serializable
@SerialName("provenanceState")
data class ProvenanceStateOutMessage(
    val entries: List<ProvenanceEntryDto>,
) : ProgrammerOutMessage()

// ── Subscriptions ────────────────────────────────────────────────────────────

/** Stream provenance snapshots to the connection. Replay(1) delivers the latest on connect. */
fun setupProgrammerSubscriptions(scope: SocketScope) {
    scope.subscribe(scope.state.show.fxEngine.provenanceFlow) { entries ->
        scope.send(
            ProvenanceStateOutMessage(
                entries.map {
                    ProvenanceEntryDto(
                        targetKey = it.targetKey,
                        propertyName = it.propertyName,
                        source = it.source.name,
                        cueId = it.cueId,
                        cueStackId = it.cueStackId,
                        effectId = it.effectId,
                    )
                },
            )
        )
    }
}

// ── Domain dispatcher ───────────────────────────────────────────────────────

suspend fun handleProgrammer(scope: SocketScope, message: ProgrammerInMessage) {
    val state = scope.state
    val reply: OutMessage = when (message) {
        is ProgrammerSetInMessage -> withTarget(message.targetType, message.targetKey) { target ->
            ProgrammerHandler.set(state, target, message.propertyName, message.value, message.fadeMs ?: 0)
        }
        is ProgrammerSetColourInMessage -> withTarget(message.targetType, message.targetKey) { target ->
            val colour = ExtendedColour(
                Color(message.r.toInt(), message.g.toInt(), message.b.toInt()),
                message.w ?: 0u,
                message.a ?: 0u,
                message.uv ?: 0u,
            )
            ProgrammerHandler.setTyped(
                state, target, message.propertyName,
                Layer3Resolver.PropertyValue.Colour(colour), message.fadeMs ?: 0,
            )
        }
        is ProgrammerSetPositionInMessage -> withTarget(message.targetType, message.targetKey) { target ->
            ProgrammerHandler.setTyped(
                state, target, "position",
                Layer3Resolver.PropertyValue.Position(message.pan, message.tilt), message.fadeMs ?: 0,
            )
        }
        is ProgrammerClearEntryInMessage -> withTarget(message.targetType, message.targetKey) { target ->
            ProgrammerHandler.clearEntry(state, target, message.propertyName, message.fadeMs ?: 0)
        }
        is ProgrammerClearAllInMessage -> {
            val cleared = clearProgrammerCompletely(state, message.fadeMs ?: 0)
            ProgrammerClearedOutMessage(cleared.entryCount, cleared.effectsCleared)
        }
        is ProgrammerSetBlindInMessage -> {
            state.show.fxEngine.setProgrammerBlind(message.blind, message.fadeMs ?: 0)
            ProgrammerBlindStateOutMessage(state.show.programmerStore.blind)
        }
        is ProgrammerStateInMessage -> ProgrammerHandler.stateSnapshot(state)
    }
    scope.send(reply)
}

private inline fun withTarget(
    targetType: String,
    targetKey: String,
    block: (TargetRef) -> OutMessage,
): OutMessage {
    val target = TargetRef.ofOrNull(targetType, targetKey)
        ?: return ProgrammerErrorOutMessage("Unknown targetType '$targetType'")
    return block(target)
}

// ── Handler ─────────────────────────────────────────────────────────────────

object ProgrammerHandler {

    /** Parse [value] against the property's category, then delegate to [setTyped]. */
    fun set(
        state: State,
        target: TargetRef,
        propertyName: String,
        value: String,
        fadeMs: Long,
    ): OutMessage {
        val typed = parseValue(state, target, propertyName, value)
            ?: return ProgrammerErrorOutMessage(
                "Value '$value' doesn't parse for ${target.discriminator} '${target.key}' property '$propertyName'"
            )
        return setTyped(state, target, propertyName, typed, fadeMs)
    }

    /** Write a typed value as a WEB programmer entry and report the stored form. */
    fun setTyped(
        state: State,
        target: TargetRef,
        propertyName: String,
        value: Layer3Resolver.PropertyValue,
        fadeMs: Long,
    ): OutMessage {
        val engine = state.show.fxEngine
        val landed = when (target) {
            is TargetRef.Fixture -> {
                val fixture = try {
                    state.show.fixtures.untypedGroupableFixture(target.key)
                } catch (_: Exception) {
                    return ProgrammerErrorOutMessage("Unknown fixture '${target.key}'")
                }
                engine.writeProgrammerProperty(
                    ProgrammerOwner.WEB, fixture, propertyName, value, fadeMs = fadeMs,
                ).isNotEmpty()
            }
            is TargetRef.Group -> {
                val group = try {
                    state.show.fixtures.untypedGroup(target.key)
                } catch (_: Exception) {
                    return ProgrammerErrorOutMessage("Unknown group '${target.key}'")
                }
                engine.writeProgrammerGroupProperty(
                    ProgrammerOwner.WEB, group, propertyName, value, fadeMs = fadeMs,
                ).isNotEmpty()
            }
        }
        if (!landed) {
            return ProgrammerErrorOutMessage(
                "Property '$propertyName' on ${target.discriminator} '${target.key}' resolves to no DMX channels"
            )
        }
        return ProgrammerEntryChangedOutMessage(
            target.discriminator, target.key, propertyName, value.serialize(),
        )
    }

    /**
     * Clear every owner's slot for one property in a single release: one store sweep, one
     * cascade publish (so [fadeMs] produces one clean ramp to the below-value rather than
     * a restarted ramp per owner), and the subsystem toggle bookkeeping pruned to match
     * the LOCATE / preset slots this takes out from under their managers.
     */
    fun clearEntry(
        state: State,
        target: TargetRef,
        propertyName: String,
        fadeMs: Long,
    ): OutMessage {
        val engine = state.show.fxEngine
        val store = state.show.programmerStore
        val fixtures: List<GroupableFixture> = when (target) {
            is TargetRef.Fixture -> {
                val fixture = try {
                    state.show.fixtures.untypedGroupableFixture(target.key)
                } catch (_: Exception) {
                    return ProgrammerErrorOutMessage("Unknown fixture '${target.key}'")
                }
                listOf(fixture)
            }
            is TargetRef.Group -> {
                val group = try {
                    state.show.fixtures.untypedGroup(target.key)
                } catch (_: Exception) {
                    return ProgrammerErrorOutMessage("Unknown group '${target.key}'")
                }
                group.fixtures.filterIsInstance<Fixture>()
            }
        }

        // Snapshot the owners being released before the sweep — the toggle subsystems'
        // bookkeeping must be pruned for the LOCATE / preset slots this clear removes,
        // or their state would keep claiming writes that are gone (a "located" fixture
        // that is no longer located).
        val released = fixtures.flatMap { f ->
            store.slotsFor(f.targetKey, propertyName).map { f.targetKey to it.owner }
        }

        engine.clearProgrammerEntries(fixtures.map { it to propertyName }, fadeMs)

        for ((fixtureKey, owner) in released) {
            when {
                owner == ProgrammerOwner.LOCATE ->
                    state.show.locateManager.pruneWrite(fixtureKey, propertyName)
                owner.id.startsWith("preset:") ->
                    prunePresetToggleWrite(owner.id, fixtureKey, propertyName)
            }
        }
        return ProgrammerEntryClearedOutMessage(target.discriminator, target.key, propertyName)
    }

    fun stateSnapshot(state: State): ProgrammerStateOutMessage {
        val store = state.show.programmerStore
        val entries = store.entries().map { entry ->
            val top = entry.slots.first()
            ProgrammerEntryDto(
                targetKey = entry.fixtureKey,
                propertyName = entry.propertyName,
                value = top.value.resolved.serialize(),
                owner = top.owner.id,
                touched = top.touched,
                sourceGroup = top.sourceGroup,
                owners = entry.slots.map { it.owner.id },
            )
        }.sortedWith(compareBy({ it.targetKey }, { it.propertyName }))
        val channels = store.channelEntries().map { entry ->
            val top = entry.slots.first()
            ProgrammerChannelDto(
                universe = entry.universe,
                channel = entry.channel,
                value = (top.value.resolved as? Layer3Resolver.PropertyValue.Slider)?.value ?: 0u,
                owner = top.owner.id,
                touched = top.touched,
            )
        }.sortedWith(compareBy({ it.universe }, { it.channel }))
        return ProgrammerStateOutMessage(store.blind, entries, channels)
    }

    /**
     * Parse a canonical value string for (target, property): `position` is the synthetic
     * pan/tilt pair; everything else resolves its category from the fixture's property
     * catalogue (via the first member for groups). Palette refs (`P1`…) resolve against the
     * live global palette.
     */
    private fun parseValue(
        state: State,
        target: TargetRef,
        propertyName: String,
        value: String,
    ): Layer3Resolver.PropertyValue? {
        if (propertyName.equals("position", ignoreCase = true)) {
            return Layer3Resolver.parseAssignmentValue(
                uk.me.cormack.lighting7.fixture.PropertyCategory.OTHER, propertyName, value,
            )
        }
        val fixture = when (target) {
            is TargetRef.Fixture -> try {
                state.show.fixtures.untypedGroupableFixture(target.key)
            } catch (_: Exception) {
                return null
            }
            is TargetRef.Group -> try {
                state.show.fixtures.untypedGroup(target.key).fixtures.firstOrNull() as? Fixture
            } catch (_: Exception) {
                return null
            } ?: return null
        }
        val category = PropertyChannelWriter.resolveProperty(fixture, propertyName)?.category
            ?: return null
        return Layer3Resolver.parseAssignmentValue(
            category, propertyName, value, palette = state.show.fxEngine.getPalette(),
        )
    }
}
