package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.PropertyChannelWriter
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.isPaletteRefValue
import uk.me.cormack.lighting7.fx.paletteRefValue
import uk.me.cormack.lighting7.fx.parsePaletteRef
import uk.me.cormack.lighting7.fx.resolveAssignmentValueForFixture
import uk.me.cormack.lighting7.fx.paletteUuidOrNull
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
 * string form as cue assignments ([CueAssignmentResolver.parseAssignmentValue] /
 * [CueAssignmentResolver.PropertyValue.serialize]): `"0".."255"` for sliders and settings,
 * `"#rrggbb"` (+ optional `w`/`a`/`uv` tags) or a positional palette ref (`"P1"`) for colours,
 * `"pan,tilt"` for `position`.
 *
 * [value] may also be a **named-palette reference** (`"ref:{paletteUuid}"`) for any property. That
 * form resolves per fixture, so on a group each member can land on a different literal; members the
 * palette doesn't cover are skipped rather than given a neighbour's value.
 *
 * [sourceGroup] is for clients that fan a group-scoped gesture out to member fixtures rather
 * than sending `targetType: "group"` — a group virtual dimmer over heterogeneous members, a
 * Highlight release restoring per-fixture values. It only ever widens the shape Record can
 * emit, and it is validated (see [ProgrammerHandler.validateSourceGroup]), so a client cannot
 * assert a hint it hasn't earned.
 */
@Serializable
@SerialName("programmer.set")
data class ProgrammerSetInMessage(
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    val value: String,
    val fadeMs: Long? = null,
    val sourceGroup: String? = null,
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
    /** See [ProgrammerSetInMessage.sourceGroup]. */
    val sourceGroup: String? = null,
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
    /** See [ProgrammerSetInMessage.sourceGroup]. */
    val sourceGroup: String? = null,
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
    /**
     * The canonical literal, or `ref:{paletteUuid}` when this entry references a named palette —
     * the same grammar a stored cue assignment uses, so one client-side parser covers both.
     */
    val value: String,
    /**
     * For a `ref:` entry, the literal it currently resolves to **for this target and property**.
     * Null otherwise. Per-target rather than per-palette, which is load-bearing for position
     * palettes where every head legitimately reads differently.
     */
    val resolvedValue: String? = null,
    /** Set on a `ref:` entry: the referenced palette's identity and name, denormalised. */
    val paletteUuid: String? = null,
    val paletteId: Int? = null,
    val paletteName: String? = null,
    val paletteType: String? = null,
    /**
     * False when the palette still exists but no longer covers this target — the entry keeps its
     * last resolved value (silently dropping an operator's programmer entry mid-show is worse)
     * and the sheet marks it broken.
     */
    val paletteResolved: Boolean? = null,
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

/**
 * What Include last loaded, and therefore what a bare Update writes back to. Null means
 * nothing is staged from a cue.
 */
@Serializable
data class IncludedTargetDto(
    /** `CUE`, `PALETTE` or `LOOK`, and which of the id/name sets below is populated. */
    val kind: String,
    /** Null unless [kind] is `CUE`. */
    val cueId: Int? = null,
    val cueStackId: Int? = null,
    val cueName: String? = null,
    val cueNumber: String? = null,
    /** Null unless [kind] is `PALETTE`. */
    val paletteId: Int? = null,
    val paletteName: String? = null,
    val paletteType: String? = null,
    /**
     * Null unless [kind] is `LOOK`. The client keys "Update is not available for this target" off
     * this arm: Update still writes back through the palette tables, so a Look include is one-way
     * until the record rewrite lands.
     */
    val lookId: Int? = null,
    val lookName: String? = null,
)

@Serializable
@SerialName("programmer.state")
data class ProgrammerStateOutMessage(
    val blind: Boolean,
    val entries: List<ProgrammerEntryDto>,
    val channels: List<ProgrammerChannelDto>,
    val lastIncluded: IncludedTargetDto? = null,
) : ProgrammerOutMessage()

/**
 * Pushed when the include target changes — set by Include or Record, cleared by Clear.
 * Broadcast (not unicast) because the programmer is shared: a second tab's Update button
 * must offer the same target.
 */
@Serializable
@SerialName("programmer.includeTarget")
data class ProgrammerIncludeTargetOutMessage(
    val target: IncludedTargetDto? = null,
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

/**
 * Resolve the store's include target into its wire form, naming the cue.
 *
 * The cue name/number lookup lives here rather than on [ProgrammerStore] deliberately: the
 * store is DB-free and on the 50 Hz read path, and this runs once per include-target change.
 */
internal fun includedTargetDto(state: State, target: uk.me.cormack.lighting7.fx.IncludedTarget?): IncludedTargetDto? {
    if (target == null) return null
    val cue = target.cueId?.let { cueId ->
        try {
            transaction(state.database) { DaoCue.findById(cueId)?.let { it.name to it.cueNumber } }
        } catch (_: Exception) {
            null
        }
    }
    val palette = target.paletteId?.let { paletteId ->
        try {
            transaction(state.database) {
                DaoPalette.findById(paletteId)?.let { it.name to it.type }
            }
        } catch (_: Exception) {
            null
        }
    }
    val lookName = target.lookId?.let { lookId ->
        try {
            transaction(state.database) {
                uk.me.cormack.lighting7.models.DaoLook.findById(lookId)?.name
            }
        } catch (_: Exception) {
            null
        }
    }
    return IncludedTargetDto(
        kind = target.kind.name,
        cueId = target.cueId,
        cueStackId = target.cueStackId,
        cueName = cue?.first,
        cueNumber = cue?.second,
        paletteId = target.paletteId,
        paletteName = palette?.first,
        paletteType = palette?.second,
        lookId = target.lookId,
        lookName = lookName,
    )
}

/** Stream provenance snapshots to the connection. Replay(1) delivers the latest on connect. */
fun setupProgrammerSubscriptions(scope: SocketScope) {
    // StateFlow replays its current value, so a tab opened mid-show sees the live include
    // target immediately rather than only after the next Include.
    scope.subscribe(scope.state.show.programmerStore.lastIncludedTargetFlow) { target ->
        scope.send(ProgrammerIncludeTargetOutMessage(includedTargetDto(scope.state, target)))
    }
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
            ProgrammerHandler.set(
                state, target, message.propertyName, message.value, message.fadeMs ?: 0,
                message.sourceGroup,
            )
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
                CueAssignmentResolver.PropertyValue.Colour(colour), message.fadeMs ?: 0,
                message.sourceGroup,
            )
        }
        is ProgrammerSetPositionInMessage -> withTarget(message.targetType, message.targetKey) { target ->
            ProgrammerHandler.setTyped(
                state, target, "position",
                CueAssignmentResolver.PropertyValue.Position(message.pan, message.tilt), message.fadeMs ?: 0,
                message.sourceGroup,
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
    private val logger = LoggerFactory.getLogger(ProgrammerHandler::class.java)

    /** Parse [value] against the property's category, then delegate to [setTyped]. */
    fun set(
        state: State,
        target: TargetRef,
        propertyName: String,
        value: String,
        fadeMs: Long,
        sourceGroup: String? = null,
    ): OutMessage {
        // A named-palette reference resolves *per fixture*, so it can't go through the
        // single-typed-value path: on a group each member may legitimately get a different
        // literal, which is the entire reason palette entries are per-fixture.
        if (isPaletteRefValue(value)) {
            return setPaletteRef(state, target, propertyName, value, fadeMs, sourceGroup)
        }
        val typed = parseValue(state, target, propertyName, value)
            ?: return ProgrammerErrorOutMessage(
                "Value '$value' doesn't parse for ${target.discriminator} '${target.key}' property '$propertyName'"
            )
        return setTyped(state, target, propertyName, typed, fadeMs, sourceGroup)
    }

    /**
     * Write a named-palette reference as programmer entries, resolving it per fixture.
     *
     * Members the palette doesn't cover are **skipped, not fabricated** — copying one head's
     * value onto another is exactly what per-fixture entries exist to prevent. An error comes back
     * only when *nothing* resolved, so applying a partially-covering palette to a broad selection
     * does the part it can and reports the rest.
     */
    private fun setPaletteRef(
        state: State,
        target: TargetRef,
        propertyName: String,
        value: String,
        fadeMs: Long,
        sourceGroup: String?,
    ): OutMessage {
        val paletteUuid = parsePaletteRef(value)
            ?: return ProgrammerErrorOutMessage("Malformed palette reference '$value'")
        if (state.show.lookRegistry.snapshot(paletteUuid) == null) {
            return ProgrammerErrorOutMessage("Palette $paletteUuid not found")
        }

        val fixtures: List<GroupableFixture> = when (target) {
            is TargetRef.Fixture -> listOfNotNull(
                runCatching { state.show.fixtures.untypedGroupableFixture(target.key) }.getOrNull()
                    ?: return ProgrammerErrorOutMessage("Unknown fixture '${target.key}'"),
            )
            is TargetRef.Group -> runCatching {
                state.show.fixtures.untypedGroup(target.key).fixtures.filterIsInstance<Fixture>()
            }.getOrNull() ?: return ProgrammerErrorOutMessage("Unknown group '${target.key}'")
        }

        val groupName = when (target) {
            is TargetRef.Group -> target.key
            is TargetRef.Fixture -> validateSourceGroup(state, sourceGroup, target.key)
        }

        val writes = fixtures.mapNotNull { fixture ->
            val category = if (propertyName.equals("position", ignoreCase = true)) {
                uk.me.cormack.lighting7.fixture.PropertyCategory.OTHER
            } else {
                PropertyChannelWriter.resolveProperty(fixture, propertyName)?.category
            } ?: return@mapNotNull null
            val resolution = resolveAssignmentValueForFixture(
                state.show.lookRegistry, fixture.targetKey, canonicalPropertyName(propertyName),
                category, value, state.show.fxEngine.getPalette(),
            )
            val resolved = resolution.value ?: return@mapNotNull null
            uk.me.cormack.lighting7.fx.FxEngine.ProgrammerPropertyWrite(
                fixture, propertyName, resolved, sourceGroup = groupName, paletteUuid = paletteUuid,
            )
        }

        if (writes.isEmpty()) {
            return ProgrammerErrorOutMessage(
                "Palette $paletteUuid covers no '$propertyName' value for " +
                    "${target.discriminator} '${target.key}'"
            )
        }

        val landed = state.show.fxEngine
            .writeProgrammerProperties(ProgrammerOwner.WEB, writes, fadeMs = fadeMs)
            .any { it.isNotEmpty() }
        if (!landed) {
            return ProgrammerErrorOutMessage(
                "Property '$propertyName' resolved no channels on ${target.discriminator} '${target.key}'"
            )
        }
        // Report the *reference*, not the literal: the client shows a ref badge off this value.
        return ProgrammerEntryChangedOutMessage(
            target.discriminator, target.key, propertyName, value,
        )
    }

    /** Write a typed value as a WEB programmer entry and report the stored form. */
    fun setTyped(
        state: State,
        target: TargetRef,
        propertyName: String,
        value: CueAssignmentResolver.PropertyValue,
        fadeMs: Long,
        sourceGroup: String? = null,
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
                    ProgrammerOwner.WEB, fixture, propertyName, value,
                    sourceGroup = validateSourceGroup(state, sourceGroup, target.key),
                    fadeMs = fadeMs,
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
            val paletteUuid = top.value.paletteUuidOrNull
            val palette = paletteUuid?.let { state.show.lookRegistry.snapshot(it) }
            ProgrammerEntryDto(
                targetKey = entry.fixtureKey,
                propertyName = entry.propertyName,
                value = if (paletteUuid == null) {
                    top.value.resolved.serialize()
                } else {
                    paletteRefValue(paletteUuid)
                },
                resolvedValue = if (paletteUuid == null) null else top.value.resolved.serialize(),
                paletteUuid = paletteUuid?.toString(),
                paletteId = palette?.lookId,
                paletteName = palette?.name,
                // A Look declares no attribute type — its families are derived from its rows, and
                // one may span several. Nothing sensible to report here any more.
                paletteType = null,
                paletteResolved = if (paletteUuid == null) {
                    null
                } else {
                    palette != null &&
                        state.show.lookRegistry.literalFor(
                            paletteUuid, entry.fixtureKey, entry.propertyName,
                        ) != null
                },
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
                value = (top.value.resolved as? CueAssignmentResolver.PropertyValue.Slider)?.value ?: 0u,
                owner = top.owner.id,
                touched = top.touched,
            )
        }.sortedWith(compareBy({ it.universe }, { it.channel }))
        return ProgrammerStateOutMessage(
            store.blind, entries, channels, includedTargetDto(state, store.lastIncludedTarget),
        )
    }

    /**
     * Accept a client-supplied `sourceGroup` hint only when the named group exists and
     * actually contains [fixtureKey].
     *
     * The hint widens what Record may emit — an unearned one would let a client conjure a
     * group-shaped cue row out of a write that never came from a group control — so it is
     * checked against the patch rather than trusted. A rejected hint is dropped, not an
     * error: the write itself is still valid and the only cost is a more verbose recording.
     */
    private fun validateSourceGroup(state: State, sourceGroup: String?, fixtureKey: String): String? {
        if (sourceGroup == null) return null
        val group = try {
            state.show.fixtures.untypedGroup(sourceGroup)
        } catch (_: Exception) {
            logger.warn("programmer.set: unknown sourceGroup '{}' — hint dropped", sourceGroup)
            return null
        }
        if (group.fixtures.none { it.targetKey == fixtureKey }) {
            logger.warn(
                "programmer.set: sourceGroup '{}' does not contain '{}' — hint dropped",
                sourceGroup, fixtureKey,
            )
            return null
        }
        return sourceGroup
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
    ): CueAssignmentResolver.PropertyValue? {
        if (propertyName.equals("position", ignoreCase = true)) {
            return CueAssignmentResolver.parseAssignmentValue(
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
        return CueAssignmentResolver.parseAssignmentValue(
            category, propertyName, value, palette = state.show.fxEngine.getPalette(),
        )
    }
}
