package uk.me.cormack.lighting7.plugins

import uk.me.cormack.lighting7.fixture.TriggerIndex
import uk.me.cormack.lighting7.fixture.CommandIndex

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fx.EffectSpecCoercion
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.models.LayerSourceDto
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProvenanceEntry
import uk.me.cormack.lighting7.fx.PropertyChannelWriter
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.AppliedSource
import uk.me.cormack.lighting7.fx.ProgrammerLayer
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.routes.clearProgrammerCompletely
import uk.me.cormack.lighting7.routes.resolveLayerSource
import uk.me.cormack.lighting7.models.sceneryStateObject
import uk.me.cormack.lighting7.state.ProgrammerScenery
import uk.me.cormack.lighting7.state.State
import java.awt.Color

// ── Inbound messages ────────────────────────────────────────────────────────

@Serializable
sealed class ProgrammerInMessage : InMessage()

/**
 * Set a programmer value on a fixture or group property. [value] uses the same canonical
 * string form as cue assignments ([CueAssignmentResolver.parseAssignmentValue] /
 * [CueAssignmentResolver.PropertyValue.serialize]): `"0".."255"` for sliders and settings,
 * `"#rrggbb"` (+ optional `w`/`a`/`uv` tags) for colours, `"pan,tilt"` for `position`. A programmer
 * value is always a **literal**: a `tmpl:{uuid}` template reference is legal only in an effect
 * parameter, and a dependency on a template is a layer (see [templateColourSource]).
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

/**
 * Hold [state] on one scene element in the programmer (scenery-programmer plan D1) — merged over
 * what the element already holds, moved on [fadeMs] when above 0, else on the piece's own travel.
 * Checked against the element's kind; a refusal answers `programmer.error` naming every problem.
 * An operator gesture of `programmer.set`'s tier, so `FU-AUTH-WS-PER-MESSAGE` does not fire.
 */
@Serializable
@SerialName("programmer.setScenery")
data class ProgrammerSetSceneryInMessage(
    val elementUuid: String,
    val state: JsonObject,
    val fadeMs: Long? = null,
) : ProgrammerInMessage()

/**
 * Let one element go, or — with no [elementUuid] — everything the programmer holds, so the piece
 * returns to what the show below holds. [fadeMs] above 0 is the clock it flies home on; otherwise
 * its own travel.
 */
@Serializable
@SerialName("programmer.clearScenery")
data class ProgrammerClearSceneryInMessage(
    val elementUuid: String? = null,
    val fadeMs: Long? = null,
) : ProgrammerInMessage()

/**
 * Ask what sits under one property, top first — park, the programmer's own effects, each owner's
 * slot, the other effects with whether each is held back, the cue's value, the base
 * (fixture-fx-sheets plan W1). Answered to **this socket only**, with `programmer.keyStack`; never
 * broadcast, since only the asker has the stack open. A group target answers one stack per member.
 *
 * [requestId] is the client's, echoed so a promise can be matched to its answer however many are in
 * flight. A read, so `FU-AUTH-WS-PER-MESSAGE` does not fire.
 */
@Serializable
@SerialName("programmer.keyStack")
data class ProgrammerKeyStackInMessage(
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    val requestId: String? = null,
) : ProgrammerInMessage()

/**
 * Release a whole fixture (its heads included) or group — the fixture sheet's *Release* (W2, D6):
 * every owner's slot on every property but a layer's, the raw-channel sideband on its channels, and
 * the local effects on it, in one pass with one republish at [fadeMs]. Answered to this socket with
 * `programmer.targetCleared`. An operator gesture of `programmer.clearEntry`'s tier, so
 * `FU-AUTH-WS-PER-MESSAGE` does not fire.
 */
@Serializable
@SerialName("programmer.clearTarget")
data class ProgrammerClearTargetInMessage(
    val targetType: String,
    val targetKey: String,
    val fadeMs: Long? = null,
    val requestId: String? = null,
) : ProgrammerInMessage()

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
    /** Property entries swept. Named to match the REST `programmer/clear-all` convention this
     * message is now the sole survivor of (that route is gone; see D5 of the backend sweep). */
    val cleared: Int,
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
     * The canonical literal — the same grammar a stored cue assignment uses, so one client-side
     * parser covers both.
     *
     * Until session 4 this could instead be `ref:{paletteUuid}`, with five sibling fields
     * (`resolvedValue`, `paletteUuid`, `paletteId`, `paletteName`, `paletteResolved`) describing
     * what it pointed at and whether that still covered this target. The `ref:` value grammar
     * retired with them; a layer with a `propertyMask` is what expresses "this property comes from
     * that Look" now, and the programmer reports its layer stack separately.
     */
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

/**
 * What Include last loaded, and therefore what a bare Update writes back to. Null means
 * nothing is staged from a cue.
 */
@Serializable
data class IncludedTargetDto(
    /** `CUE` or `LOOK`, and which of the id/name sets below is populated. */
    val kind: String,
    /** Null unless [kind] is `CUE`. */
    val cueId: Int? = null,
    val cueStackId: Int? = null,
    val cueName: String? = null,
    val cueNumber: String? = null,
    /**
     * Null unless [kind] is `LOOK`.
     *
     * There were three `palette*` fields beside these, for a `PALETTE` kind that retired with the
     * palette tables in session 4. The KDoc here used to say a Look include was one-way "until the
     * record rewrite lands" — it landed in session 3a, so Update writes a Look back through
     * `updateIncludedLook`.
     */
    val lookId: Int? = null,
    val lookName: String? = null,
)

/**
 * Apply a Look to the programmer as a **layer**, on top of the stack.
 *
 * The busking-pad and picker gesture, and the WS twin of `POST /looks/{id}/toggle` — which stays
 * for the desk's existing call sites. Unlike the retired preset toggle this carries the whole Look:
 * a **bound** row lands on the fixture it names, because a layer has somewhere to put a target set.
 */
@Serializable
@SerialName("programmer.addLayer")
data class ProgrammerAddLayerInMessage(
    /** Exactly one of [lookId] / [templateId]. */
    val lookId: Int? = null,
    val templateId: Int? = null,
    val targets: List<CueTargetDto> = emptyList(),
    val propertyMask: String? = null,
    val blendMode: String? = null,
    val amount: Double? = null,
    val speedMasterUuid: String? = null,
    val rateSpeedMasterUuid: String? = null,
    val fadeMs: Long? = null,
) : ProgrammerInMessage()

@Serializable
@SerialName("programmer.removeLayer")
data class ProgrammerRemoveLayerInMessage(
    val layerId: Int,
    val fadeMs: Long? = null,
) : ProgrammerInMessage()

/** Move a layer to [toIndex] among the non-preview layers. The whole list is renumbered. */
@Serializable
@SerialName("programmer.moveLayer")
data class ProgrammerMoveLayerInMessage(
    val layerId: Int,
    val toIndex: Int,
) : ProgrammerInMessage()

/** Change one layer's fields. A null field means "leave alone", not "clear". */
@Serializable
@SerialName("programmer.patchLayer")
data class ProgrammerPatchLayerInMessage(
    val layerId: Int,
    val enabled: Boolean? = null,
    val amount: Double? = null,
    val propertyMask: String? = null,
    val blendMode: String? = null,
    val targets: List<CueTargetDto>? = null,
    val stomp: Boolean? = null,
    val fadeMs: Long? = null,
) : ProgrammerInMessage()

/** One programmer layer as the desk sees it. */
@Serializable
data class ProgrammerLayerDto(
    val layerId: Int,
    /** What this layer applies — a Look or a template. */
    val source: LayerSourceDto,
    val sortOrder: Int,
    val enabled: Boolean,
    val targets: List<CueTargetDto>,
    val propertyMask: String? = null,
    val blendMode: String,
    val amount: Double,
    val stomp: Boolean,
    val speedMasterUuid: String? = null,
    val rateSpeedMasterUuid: String? = null,
    /** Set when Include minted this from a cue's layer — Update's diff key. */
    val sourceCueLayerId: Int? = null,
)

/**
 * One target a Look or template is applied to, and how much of it: `all` or `some`.
 *
 * `some` only ever describes a **group** — a fixture is covered or it is not — and means the record
 * holds part of that group's heads.
 */
@Serializable
data class ProgrammerAppliedTargetDto(
    val type: String,
    val key: String,
    /** `all` or `some`. */
    val state: String,
)

/**
 * One Look or template and every target it is currently applied to — see
 * [uk.me.cormack.lighting7.fx.ProgrammerLayerStack.appliedState].
 *
 * The **resolved** view of [ProgrammerLayerStateOutMessage.layers]: groups are expanded to their
 * heads and folded back up, so a client asking "is this pad on for what I have selected?" looks its
 * selection up here rather than re-deriving coverage from layer targets it would also have to
 * expand. The layer list stays alongside it because the Layers pane edits layers — this answers a
 * different question about the same stack.
 */
@Serializable
data class ProgrammerAppliedSourceDto(
    val source: LayerSourceDto,
    val targets: List<ProgrammerAppliedTargetDto>,
)

/**
 * The programmer's layer stack.
 *
 * **Broadcast, not unicast**, for the same reason `programmer.includeTarget` is: the programmer is
 * shared, so a second tab reordering the stack must not leave the first showing a stale order.
 */
@Serializable
@SerialName("programmer.layerState")
data class ProgrammerLayerStateOutMessage(
    val layers: List<ProgrammerLayerDto>,
    /**
     * The same stack resolved to per-target applied state. Additive and defaulted, and
     * `@EncodeDefault(ALWAYS)` for the same reason the connect frame's twin carries it: an empty
     * stack is a real state, and it must reach the client as "nothing is applied" rather than as
     * a field an older server did not send.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val applied: List<ProgrammerAppliedSourceDto> = emptyList(),
) : ProgrammerOutMessage()

@Serializable
@SerialName("programmer.state")
data class ProgrammerStateOutMessage(
    val blind: Boolean,
    val entries: List<ProgrammerEntryDto>,
    val channels: List<ProgrammerChannelDto>,
    val lastIncluded: IncludedTargetDto? = null,
    /**
     * The Look-layer stack, most significant last. Additive and defaulted, so a client that knows
     * nothing about layers decodes this frame exactly as before — the layers' *values* already
     * reach it through [entries], attributed to the `layers` owner.
     */
    val layers: List<ProgrammerLayerDto> = emptyList(),
    /**
     * The same stack resolved to per-target applied state, so a fresh connection can light its
     * pads without a second round trip — the connect-frame twin of
     * [ProgrammerLayerStateOutMessage.applied].
     *
     * `@EncodeDefault(ALWAYS)`, for the reason [ProvenanceStateOutMessage.programmerRevision]
     * spells out: the WS `Json` has `encodeDefaults = false`, so an **empty** list would vanish
     * from the wire and arrive as "field absent" — which a client can only read as an older
     * server. Empty is a real and common state here (a desk that has just started holds no
     * layers), and this frame is the *resync* path: a client that kept its last `applied`
     * because the field was missing would leave every busk pad ringed against an empty
     * programmer, and the press meant to clear one would add a layer instead.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val applied: List<ProgrammerAppliedSourceDto> = emptyList(),
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

/** One element the programmer holds: the states it sets (only those), as `scenery.state` writes them. */
@Serializable
data class ProgrammerSceneryEntryDto(
    val elementUuid: String,
    val state: JsonObject,
)

/**
 * The programmer's scenery (scenery-programmer plan D1): every element it holds, in the order first
 * held. `StateFlow`-backed ([uk.me.cormack.lighting7.state.ProgrammerScenery.flow]), so the
 * subscription is the connect snapshot and every change is broadcast to every tab — the programmer
 * is shared. [elements] is `@EncodeDefault(ALWAYS)`: an empty overlay is a real state (Clear just
 * ran), and must arrive as "nothing held", not as a field an older desk did not send.
 *
 * [changedSinceInclude] (scenery-programmer plan session 3) is how many held elements Update would
 * write that the included cue or Look does not already say — the scenery half of the source strip's
 * dirty count, which a client cannot diff for itself: the include target and this frame travel on
 * two flows, so nothing orders them. Absent until an Include, a Record or an Update set a baseline.
 */
@Serializable
@SerialName("programmer.sceneryState")
data class ProgrammerSceneryStateOutMessage(
    val projectId: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val elements: List<ProgrammerSceneryEntryDto> = emptyList(),
    val changedSinceInclude: Int? = null,
) : ProgrammerOutMessage()

internal fun ProgrammerScenery.Snapshot.toMessage() = ProgrammerSceneryStateOutMessage(
    projectId = projectId,
    elements = elements.map { (uuid, held) -> ProgrammerSceneryEntryDto(uuid.toString(), sceneryStateObject(held.state)) },
    changedSinceInclude = changedSinceInclude,
)

/** One parked channel on a `PARK` layer of [KeyStackLayerDto]. */
@Serializable
data class KeyStackParkedChannelDto(val universe: Int, val channel: Int, val value: Int)

/**
 * One layer of a property's stack — see `ProvenanceService.keyStack`. [kind] is `PARK`,
 * `PROGRAMMER_EFFECT`, `PROGRAMMER`, `EFFECT`, `CUE` or `BASE`, and decides which of the optional
 * fields are present; every layer carries [onStage]. Absent fields are null (the socket's Json
 * encodes no default), so a client reads absent as "not this kind's".
 */
@Serializable
data class KeyStackLayerDto(
    val kind: String,
    /** Whether this layer contributes to what the rig shows on the property now. */
    val onStage: Boolean,
    /** The literal it holds, in the cue-assignment grammar — a programmer slot, the cue's value, the base. */
    val value: String? = null,
    /** `PARK`: the parked channels. */
    val parkedChannels: List<KeyStackParkedChannelDto>? = null,
    /** `PROGRAMMER`: the owner (`web`, `surface`, `layers`…) and how long ago its slot was written. */
    val owner: String? = null,
    val ageMs: Long? = null,
    /** `PROGRAMMER`: present on a raw-channel sideband slot, naming the channel it holds. */
    val universe: Int? = null,
    val channel: Int? = null,
    /** The two effect kinds: the instance, its type and display name, its timing and its masters. */
    val effectId: Long? = null,
    val effectType: String? = null,
    val effectName: String? = null,
    val beatDivision: Double? = null,
    val blendMode: String? = null,
    val running: Boolean? = null,
    val speedMasterUuid: String? = null,
    val rateSpeedMasterUuid: String? = null,
    /** The two effect kinds: running, but not painting this key — `FxEngine`'s own suppression answer. */
    val heldBack: Boolean? = null,
    val cueId: Int? = null,
    val cueStackId: Int? = null,
    /** A programmer layer's id or a cue layer's — whichever produced this layer. */
    val layerId: Int? = null,
    val layerSource: LayerSourceDto? = null,
)

/** One member's (or the one fixture's) stack in a `programmer.keyStack` answer. */
@Serializable
data class KeyStackDto(
    val targetKey: String,
    val layers: List<KeyStackLayerDto>,
)

/**
 * The answer to `programmer.keyStack`, to the asking socket only. [stacks] holds one entry for a
 * fixture and one per member for a group (a member the property does not resolve on is absent).
 * [error] is set, and [stacks] empty, when the target does not resolve — carried here rather than
 * as `programmer.error` so the asker's promise is answered rather than left to time out.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("programmer.keyStack")
data class ProgrammerKeyStackOutMessage(
    val requestId: String?,
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    /** The programmer is blind: its slots are listed, none is on stage, and it holds nothing back. */
    val blind: Boolean,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val stacks: List<KeyStackDto> = emptyList(),
    val error: String? = null,
) : ProgrammerOutMessage()

/** A local effect `programmer.clearTarget` left running because it drives heads outside the target. */
@Serializable
data class TargetClearedPartialDto(
    val effectId: Long,
    val effectType: String,
    /** The effect's own target — a group, or a fixture the released target only part-covers. */
    val targetKey: String,
    val isGroupTarget: Boolean,
    val propertyName: String,
)

/**
 * The answer to `programmer.clearTarget`, to the asking socket only: [values] property entries and
 * sideband channels released, [effects] local effects stopped, and [partial] the local effects that
 * overlap the target but drive heads outside it, left running and named so the operator can be told.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("programmer.targetCleared")
data class ProgrammerTargetClearedOutMessage(
    val requestId: String?,
    val targetType: String,
    val targetKey: String,
    val values: Int,
    val effects: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val partial: List<TargetClearedPartialDto> = emptyList(),
    val error: String? = null,
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
    /**
     * The Look layer that won this key, when one did — so the desk can answer "why is this fixture
     * this colour?" by naming *Warm Wash* rather than *a cue*.
     *
     * Additive and defaulted, and not a new `source` value: a client that doesn't know about layers
     * keeps reading `source` exactly as before. See `ProvenanceEntry`.
     */
    val layerId: Int? = null,
    /**
     * What that layer applies. One nested value rather than the `lookId`/`lookName` pair it
     * replaces, because a layer can now apply a template and a field called `lookName` holding a
     * template's name is a lie no compiler can catch.
     */
    val layerSource: LayerSourceDto? = null,
    /**
     * For a cue-won colour whose W / A / UV an emitter's own row supplied, that emitter's winner,
     * one per emitter — so the colour is not credited with bytes another cue or layer asserted.
     * Omitted when empty (the content converter does not encode defaults). See
     * `ProvenanceEntry.bundled`.
     */
    val bundled: List<BundledProvenanceDto> = emptyList(),
)

/** One entry of [ProvenanceEntryDto.bundled]. */
@Serializable
data class BundledProvenanceDto(
    val propertyName: String,
    val cueId: Int? = null,
    val cueStackId: Int? = null,
    val layerId: Int? = null,
    val layerSource: LayerSourceDto? = null,
)

/**
 * Full provenance snapshot — pushed on every layer event (programmer mutation, cue
 * republish, effect lifecycle change, park change), coalesced to at most one per 50 ms.
 * A crossfade's weight ticks are layer events too, so a running fade republishes this at
 * up to ~20 Hz — which is why [programmerRevision] exists.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("provenanceState")
data class ProvenanceStateOutMessage(
    val entries: List<ProvenanceEntryDto>,
    /**
     * Monotonic counter of triggers that could have moved the programmer's value set. A
     * crossfade weight tick does not bump it, so the client refetches `programmer.state`
     * only when the revision moved — instead of ~10×/s for the whole fade. Carried on every
     * frame (not a per-frame flag) so the broadcast flow's DROP_OLDEST behaviour under a
     * slow collector cannot lose the signal. Additive and defaulted: a server that omits it
     * (or a client that ignores it) degrades to refetch-on-every-frame, the old behaviour.
     *
     * `@EncodeDefault(ALWAYS)` is load-bearing, not decoration: the WS content converter's
     * `Json` has `encodeDefaults = false`, so without it this field vanishes from the wire
     * on every frame while the counter is still at its 0 default — server startup, and any
     * frame before the first non-fade trigger — and the client's "field missing" branch
     * (written for an *older* server) reads that as unsupported and refetches every frame,
     * silently reproducing the storm this field exists to stop.
     * See [uk.me.cormack.lighting7.fx.ProvenanceUpdate].
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val programmerRevision: Long = 0,
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
        lookId = target.lookId,
        lookName = lookName,
    )
}

/**
 * Stream provenance, include-target and layer-stack snapshots to the connection. Replay(1) on all
 * three delivers the latest on connect.
 */
fun setupProgrammerSubscriptions(scope: SocketScope) {
    // Connect snapshots. Only `lastIncludedTargetFlow` below is a StateFlow; `layersFlow` and
    // `provenance.flow` are replay-1 `SharedFlow`s, which have nothing to replay on a desk where
    // no layer has been touched and nothing has moved a value — so neither can be the connect
    // frame for its family. `programmer.state` covers two of the three: it carries `lastIncluded`
    // and `layers` alongside the entries and the channel sideband, which had no connect frame at
    // all and are why the client asked on open. Provenance has no such carrier, so it gets its
    // own explicit push.
    //
    // On a warm desk the replayed frames then arrive too, so `programmer.includeTarget`,
    // `programmer.layerState` and `provenanceState` can each be seen twice at connect. Harmless:
    // all three are idempotent, and the client already coalesces repeated provenance frames into
    // one debounced refetch.
    scope.sendSnapshot {
        send(ProgrammerHandler.stateSnapshot(state))
        send(buildProvenanceStateMessage(
            state.show.fxEngine.provenance.compute(),
            programmerRevision = state.show.fxEngine.provenance.currentProgrammerRevision,
        ))
    }

    // StateFlow replays its current value, so a tab opened mid-show sees the live include
    // target immediately rather than only after the next Include.
    scope.subscribe(scope.state.show.programmerStore.lastIncludedTargetFlow) { target ->
        scope.send(ProgrammerIncludeTargetOutMessage(includedTargetDto(scope.state, target)))
    }
    // The layer stack is shared state, so every tab gets every change — not only the tab that made
    // it, which is all the unicast reply from `handleProgrammer` covers. Without this, a mutation
    // that moved no value (a layer whose targets don't match its bound Look's rows asserts nothing)
    // pushed no `provenanceState` either, and other tabs kept a stale layer list indefinitely.
    scope.subscribe(scope.state.show.programmerStore.layersFlow) { layers ->
        // Resolved from the emitted list rather than from the store, so the frame's `applied`
        // describes the very stack its `layers` carry even if another mutation has landed since.
        scope.send(ProgrammerLayerStateOutMessage(
            layers.map { it.toDto() },
            scope.state.show.programmerLayerStack.appliedState(layers).map { it.toDto() },
        ))
    }
    scope.subscribe(scope.state.show.fxEngine.provenance.flow) { update ->
        scope.send(buildProvenanceStateMessage(update.entries, programmerRevision = update.programmerRevision))
    }
    // The programmer's scenery: a StateFlow, so this is its connect snapshot as well as its broadcast.
    scope.subscribe(scope.state.programmerScenery.flow) { scope.send(it.toMessage()) }
}

/**
 * Shared by the connect snapshot and the live `provenance.flow` subscription. A fresh tab
 * has never seen a revision, so whatever value its first frame carries re-arms the refetch —
 * the connect snapshot just passes the current one.
 */
private fun buildProvenanceStateMessage(entries: List<ProvenanceEntry>, programmerRevision: Long) =
    ProvenanceStateOutMessage(
        programmerRevision = programmerRevision,
        entries = entries.map {
            ProvenanceEntryDto(
                targetKey = it.targetKey,
                propertyName = it.propertyName,
                source = it.source.name,
                cueId = it.cueId,
                cueStackId = it.cueStackId,
                effectId = it.effectId,
                layerId = it.layerId,
                layerSource = it.layerSource?.toDto(),
                bundled = it.bundled.map { b ->
                    BundledProvenanceDto(
                        propertyName = b.propertyName,
                        cueId = b.cueId,
                        cueStackId = b.cueStackId,
                        layerId = b.layerId,
                        layerSource = b.layerSource?.toDto(),
                    )
                },
            )
        },
    )

private fun ProgrammerLayer.toDto() = ProgrammerLayerDto(
    layerId = layerId,
    source = source.toDto(),
    sortOrder = sortOrder,
    enabled = enabled,
    targets = targets,
    propertyMask = propertyMask,
    blendMode = blendMode,
    amount = amount,
    stomp = stomp,
    speedMasterUuid = speedMasterUuid?.toString(),
    rateSpeedMasterUuid = rateSpeedMasterUuid?.toString(),
    sourceCueLayerId = sourceCueLayerId,
)

private fun AppliedSource.toDto() = ProgrammerAppliedSourceDto(
    source = source.toDto(),
    // Lower-cased: `all`/`some` is the vocabulary the pads already speak, and it matches the
    // `fixture`/`group` discriminators sitting beside it in the same object.
    targets = targets.map { ProgrammerAppliedTargetDto(it.target.type, it.target.key, it.extent.name.lowercase()) },
)

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
        is ProgrammerKeyStackInMessage -> ProgrammerHandler.keyStack(state, message)
        is ProgrammerClearTargetInMessage -> ProgrammerHandler.clearTarget(state, message)
        is ProgrammerClearAllInMessage -> {
            val cleared = clearProgrammerCompletely(state, message.fadeMs ?: 0)
            ProgrammerClearedOutMessage(cleared.entryCount, cleared.effectsCleared)
        }
        is ProgrammerSetBlindInMessage -> {
            state.show.fxEngine.programmer.setBlind(message.blind, message.fadeMs ?: 0)
            // Blind takes the programmer off stage, its Looks' scenery with it.
            state.sceneryService.onBlindChanged()
            // Blind rehearses every fire and holds the cannons' arm channels down (session 9).
            state.effectsService.onBlindChanged()
            ProgrammerBlindStateOutMessage(state.show.programmerStore.blind)
        }
        is ProgrammerStateInMessage -> ProgrammerHandler.stateSnapshot(state)
        is ProgrammerSetSceneryInMessage -> {
            val uuid = runCatching { java.util.UUID.fromString(message.elementUuid.trim()) }.getOrNull()
            val problems = if (uuid == null) listOf("elementUuid must be a uuid") else
                state.programmerScenery.set(uuid, message.state, message.fadeMs)
            if (problems.isEmpty()) state.programmerScenery.flow.value.toMessage()
            else ProgrammerErrorOutMessage("Scenery refused: ${problems.joinToString("; ")}")
        }
        is ProgrammerClearSceneryInMessage -> {
            val raw = message.elementUuid?.trim()
            val uuid = raw?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            if (raw != null && uuid == null) {
                ProgrammerErrorOutMessage("Scenery refused: elementUuid must be a uuid")
            } else {
                state.programmerScenery.release(uuid, message.fadeMs)
                state.programmerScenery.flow.value.toMessage()
            }
        }

        is ProgrammerAddLayerInMessage -> ProgrammerHandler.addLayer(state, message)
        is ProgrammerRemoveLayerInMessage -> {
            state.show.programmerLayerStack.remove(message.layerId, message.fadeMs ?: 0)
            ProgrammerHandler.layerState(state)
        }
        is ProgrammerMoveLayerInMessage -> {
            state.show.programmerLayerStack.move(message.layerId, message.toIndex)
            ProgrammerHandler.layerState(state)
        }
        is ProgrammerPatchLayerInMessage -> {
            // Same rejection as `programmer.addLayer`: a patched blend reaches `cue_layers` through
            // Record just as an added one does, so it is checked where it enters.
            val problem = EffectSpecCoercion.Strict.problem(blendMode = message.blendMode)
            if (problem != null) {
                ProgrammerErrorOutMessage(problem)
            } else {
                state.show.programmerLayerStack.patch(
                    layerId = message.layerId,
                    enabled = message.enabled,
                    amount = message.amount,
                    propertyMask = message.propertyMask,
                    blendMode = message.blendMode,
                    targets = message.targets,
                    stomp = message.stomp,
                    fadeMs = message.fadeMs ?: 0,
                )
                ProgrammerHandler.layerState(state)
            }
        }
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

    /**
     * The programmer never holds a one-shot trigger (stage-view plan session 9, D15). It could not
     * anyway — a trigger is not a property, so the write would resolve no channels — but this names
     * the reason rather than reporting "resolves to no DMX channels".
     */
    private fun triggerRefusal(state: State, target: TargetRef, propertyName: String): String? =
        TriggerIndex.refusalLive(state.show.fixtures, target.discriminator, target.key, propertyName, "programmer")
            // Nor a fixture command (fixture optics plan session 7).
            ?: CommandIndex.refusalLive(state.show.fixtures, target.discriminator, target.key, propertyName, "programmer")

    /** Parse [value] against the property's category, then delegate to [setTyped]. */
    fun set(
        state: State,
        target: TargetRef,
        propertyName: String,
        value: String,
        fadeMs: Long,
        sourceGroup: String? = null,
    ): OutMessage {
        triggerRefusal(state, target, propertyName)?.let { return ProgrammerErrorOutMessage(it) }
        val typed = parseValue(state, target, propertyName, value)
            ?: return ProgrammerErrorOutMessage(
                "Value '$value' doesn't parse for ${target.discriminator} '${target.key}' property '$propertyName'"
            )
        return setTyped(state, target, propertyName, typed, fadeMs, sourceGroup)
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
        triggerRefusal(state, target, propertyName)?.let { return ProgrammerErrorOutMessage(it) }
        val engine = state.show.fxEngine
        val landed = when (target) {
            is TargetRef.Fixture -> {
                val fixture = try {
                    state.show.fixtures.untypedGroupableFixture(target.key)
                } catch (_: Exception) {
                    return ProgrammerErrorOutMessage("Unknown fixture '${target.key}'")
                }
                engine.programmer.writeProperty(
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
                engine.programmer.writeGroupProperty(
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

        engine.programmer.clearEntries(fixtures.map { it to propertyName }, fadeMs)

        // Locate is the only owner left with bookkeeping to prune. The `preset:{id}` owners went
        // with the toggle machinery: a Look now reaches the programmer as a layer, and
        // `clearProgrammerEntries` deliberately leaves LAYERS slots alone — its contribution is
        // derived state that the next recook rebuilds, so there is nothing here to keep in step.
        for ((fixtureKey, owner) in released) {
            if (owner == ProgrammerOwner.LOCATE) {
                state.show.locateManager.pruneWrite(fixtureKey, propertyName)
            }
        }
        return ProgrammerEntryClearedOutMessage(target.discriminator, target.key, propertyName)
    }

    /**
     * `programmer.keyStack`: the property's stack from `ProvenanceService.keyStacks`, one for a
     * fixture and one per member for a group, all against one snapshot of the desk.
     */
    fun keyStack(state: State, message: ProgrammerKeyStackInMessage): ProgrammerKeyStackOutMessage {
        val provenance = state.show.fxEngine.provenance
        fun answer(stacks: List<KeyStackDto>, error: String? = null) = ProgrammerKeyStackOutMessage(
            requestId = message.requestId,
            targetType = message.targetType,
            targetKey = message.targetKey,
            propertyName = message.propertyName,
            blind = state.show.programmerStore.blind,
            stacks = stacks,
            error = error,
        )
        val target = TargetRef.ofOrNull(message.targetType, message.targetKey)
            ?: return answer(emptyList(), "Unknown targetType '${message.targetType}'")
        val keys: List<String> = when (target) {
            is TargetRef.Fixture -> {
                try {
                    state.show.fixtures.untypedGroupableFixture(target.key)
                } catch (_: Exception) {
                    return answer(emptyList(), "Unknown fixture '${target.key}'")
                }
                listOf(target.key)
            }
            is TargetRef.Group -> try {
                state.show.fixtures.untypedGroup(target.key).fixtures.map { it.targetKey }
            } catch (_: Exception) {
                return answer(emptyList(), "Unknown group '${target.key}'")
            }
        }
        val property = canonicalPropertyName(message.propertyName)
        val engine = state.show.fxEngine
        return answer(provenance.keyStacks(keys, property).map { it.toDto(engine) })
    }

    /**
     * `programmer.clearTarget`: release everything the programmer holds on a fixture (and its
     * heads) or a group (every member, and theirs), plus the local effects on it, in one pass.
     *
     * The effect half is the grid ⌫'s rule moved to the desk ([FxInstance.isLocalEffect], and
     * `cellEffects.effectsToStop`'s *every head, or none*): a local effect is stopped when it
     * targets exactly what is being released or every head it paints is among the released heads,
     * and is **left running and named** in `partial` when it paints some of them and some others —
     * a group effect on one member's release. There is no "stop it on these heads only".
     *
     * Locate's bookkeeping is pruned for the LOCATE slots this takes, as [clearEntry] prunes it.
     * The local effects are stopped *before* the values are released, so the values' one publish
     * fades every key at `fadeMs` — see the order note in the body.
     */
    fun clearTarget(state: State, message: ProgrammerClearTargetInMessage): ProgrammerTargetClearedOutMessage {
        fun answer(values: Int, effects: Int, partial: List<TargetClearedPartialDto>, error: String? = null) =
            ProgrammerTargetClearedOutMessage(
                requestId = message.requestId,
                targetType = message.targetType,
                targetKey = message.targetKey,
                values = values,
                effects = effects,
                partial = partial,
                error = error,
            )
        val target = TargetRef.ofOrNull(message.targetType, message.targetKey)
            ?: return answer(0, 0, emptyList(), "Unknown targetType '${message.targetType}'")
        val fixtures = state.show.fixtures
        val roots: List<GroupableFixture> = when (target) {
            is TargetRef.Fixture -> try {
                listOf(fixtures.untypedGroupableFixture(target.key))
            } catch (_: Exception) {
                return answer(0, 0, emptyList(), "Unknown fixture '${target.key}'")
            }
            is TargetRef.Group -> try {
                fixtures.untypedGroup(target.key).fixtures
            } catch (_: Exception) {
                return answer(0, 0, emptyList(), "Unknown group '${target.key}'")
            }
        }
        // Each root and its heads: a fixture's Release takes its cells with it.
        val heads: List<GroupableFixture> = roots.flatMap { root ->
            listOf(root) + ((root as? uk.me.cormack.lighting7.fixture.group.MultiElementFixture<*>)?.elements ?: emptyList())
        }.distinctBy { it.targetKey }
        val headKeys = heads.mapTo(HashSet()) { it.targetKey }
        // The channels the roots are patched on — what finds a raw write on a channel no property backs.
        val footprint = buildSet {
            for (root in roots) {
                val dmx = root as? uk.me.cormack.lighting7.fixture.DmxFixture ?: continue
                for (offset in 0 until dmx.channelCount) {
                    add(uk.me.cormack.lighting7.dmx.packChannelKey(dmx.universe.universe, dmx.firstChannel + offset))
                }
            }
        }

        val engine = state.show.fxEngine
        val stop = ArrayList<Long>()
        val partial = ArrayList<TargetClearedPartialDto>()
        for (effect in engine.getActiveEffects()) {
            if (!effect.isLocalEffect) continue
            val exact = effect.target.targetKey == target.key &&
                effect.isGroupEffect == (target is TargetRef.Group)
            val painted = engine.fixtureKeysCoveredBy(effect)
            val overlap = painted.count { it in headKeys }
            when {
                exact || (painted.isNotEmpty() && overlap == painted.size) -> stop += effect.id
                overlap > 0 -> partial += TargetClearedPartialDto(
                    effectId = effect.id,
                    effectType = effect.effectTypeId,
                    targetKey = effect.target.targetKey,
                    isGroupTarget = effect.isGroupEffect,
                    propertyName = effect.target.propertyName,
                )
            }
        }
        // Effects first, then the values — `clearProgrammerCompletely`'s order, for its reason. A
        // running effect covers its keys, so a values publish made under it skips them; removing
        // the effect afterwards would then put the layer below back with no fade, half the Release
        // fading and the other half snapping. Stopped first, its keys reset to the programmer value
        // still there, and the one values publish below ramps every key at [fadeMs].
        val stopped = engine.removeEffects(stop)
        val release = engine.programmer.releaseFixtures(heads, footprint, message.fadeMs ?: 0)
        for ((fixtureKey, propertyName, owner) in release.releasedSlots) {
            if (owner == ProgrammerOwner.LOCATE) state.show.locateManager.pruneWrite(fixtureKey, propertyName)
        }
        return answer(release.values, stopped, partial)
    }

    /** The layer stack as the desk sees it, with the resolved applied state beside it. */
    fun layerState(state: State): ProgrammerLayerStateOutMessage {
        val layers = state.show.programmerStore.layers
        return ProgrammerLayerStateOutMessage(
            layers.map { it.toDto() },
            state.show.programmerLayerStack.appliedState(layers).map { it.toDto() },
        )
    }

    /**
     * Add a layer for a stored Look or template.
     *
     * The uuid is looked up here rather than accepted from the client: a layer resolves its source by
     * uuid (int primary keys are re-minted on sync import), but the desk addresses both by id
     * everywhere else, and letting a client supply both invites the two disagreeing.
     */
    fun addLayer(state: State, message: ProgrammerAddLayerInMessage): OutMessage {
        val source = resolveLayerSource(state, message.lookId, message.templateId)
            ?: return ProgrammerErrorOutMessage(
                "programmer.addLayer needs exactly one of lookId / templateId, naming a record that exists",
            )
        // A frame has no 400 to be given, so the strict policy's message becomes the error frame.
        // Left unchecked, a bad blend survives Record into `cue_layers` and the operator then sees
        // a blend the desk does not play — the whole point of rejecting it where it enters.
        EffectSpecCoercion.Strict.problem(blendMode = message.blendMode)?.let {
            return ProgrammerErrorOutMessage(it)
        }

        state.show.programmerLayerStack.add(
            source = source,
            targets = message.targets,
            propertyMask = message.propertyMask,
            blendMode = message.blendMode ?: "OVERRIDE",
            amount = message.amount ?: 1.0,
            speedMasterUuid = speedMasterUuidOrNull(message.speedMasterUuid),
            rateSpeedMasterUuid = speedMasterUuidOrNull(message.rateSpeedMasterUuid),
            fadeMs = message.fadeMs ?: 0,
        )
        return layerState(state)
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
                value = (top.value.resolved as? CueAssignmentResolver.PropertyValue.Slider)?.value ?: 0u,
                owner = top.owner.id,
                touched = top.touched,
            )
        }.sortedWith(compareBy({ it.universe }, { it.channel }))
        val layers = store.layers
        return ProgrammerStateOutMessage(
            store.blind, entries, channels, includedTargetDto(state, store.lastIncludedTarget),
            layers = layers.map { it.toDto() },
            applied = state.show.programmerLayerStack.appliedState(layers).map { it.toDto() },
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
     * catalogue (via the first member for groups). A programmer value is always a literal — a
     * `tmpl:` template reference is refused by [CueAssignmentResolver.parseAssignmentValue] rather
     * than read as white.
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
        val resolved = PropertyChannelWriter.resolveProperty(fixture, propertyName) ?: return null
        return CueAssignmentResolver.parseAssignmentValue(
            resolved.category, propertyName, value,
            settingBacked = resolved.value is uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting<*>,
        )
    }
}

private fun uk.me.cormack.lighting7.fx.KeyStack.toDto(engine: uk.me.cormack.lighting7.fx.FxEngine) = KeyStackDto(
    targetKey = targetKey,
    layers = layers.map { layer ->
        val effect = layer.effect
        val dto = effect?.let { engine.effectDto(it) }
        KeyStackLayerDto(
            kind = layer.kind.name,
            onStage = layer.onStage,
            value = layer.value?.serialize(),
            parkedChannels = layer.parkedChannels.takeIf { it.isNotEmpty() }
                ?.map { KeyStackParkedChannelDto(it.universe, it.channel, it.value.toInt()) },
            owner = layer.owner?.id,
            ageMs = layer.ageMs,
            universe = layer.channel?.first,
            channel = layer.channel?.second,
            effectId = effect?.id,
            effectType = dto?.effectType,
            effectName = effect?.effect?.name,
            beatDivision = dto?.beatDivision,
            blendMode = dto?.blendMode,
            running = dto?.isRunning,
            speedMasterUuid = dto?.speedMasterUuid,
            rateSpeedMasterUuid = dto?.rateSpeedMasterUuid,
            heldBack = if (effect != null) layer.heldBack else null,
            cueId = layer.cueId,
            cueStackId = layer.cueStackId,
            layerId = layer.layerId,
            layerSource = layer.layerSource?.toDto(),
        )
    },
)
