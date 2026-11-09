package uk.me.cormack.lighting7.midi

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import java.util.UUID

/**
 * What a bound control part does (surface-setup plan D1): a function ([does]) with its own
 * parameters, the subject it acts [on], and [how] a button drives it. Stored as JSON in
 * `control_surface_bindings.target_payload` ([BindingJson]) at a slot `(device type, control, part,
 * layer)`; OSC builds them in memory from its address table and never stores one.
 *
 * [how] null is the function's own default ([Spec.defaultHow]); a continuous function has none.
 */
@Serializable
data class Binding(val does: Does, val on: Subject = Subject.None, val how: How? = null) {
    /** The function's id, its `@SerialName` — what the `target_type` column mirrors. */
    val functionId: String get() = does.functionId

    /** [how], else the function's default. */
    val effectiveHow: How? get() = how ?: does.spec.defaultHow
}

/** The part of a control a binding sits on (D4): a fader's move, an encoder's turn, a button's press, or an encoder's push. */
@Serializable
enum class ControlPart { MAIN, PUSH }

/** How a button drives a function (D3). Continuous functions take none. */
@Serializable
enum class How { TOGGLE, WHILE_HELD, ON, OFF, TOGGLE_HOLD_REPLACES, REPLACE }

/** Where a function writes, in one word (D8). */
@Serializable
enum class WritesTo { LOCAL, PLAYBACK, MASTER, TEMPO, DESK }

/** A record a subject names, by uuid. */
@Serializable
enum class RecordKind { STACK, ENTRY, CUE, LOOK, PALETTE, PAD, BANK, PAGE, MASTER }

/** Every kind of subject a [Spec] may accept: the five shapes, with a record split by its kind. */
enum class SubjectKind {
    NONE, SELECTION, GROUP, FIXTURE, SLOT,
    STACK, ENTRY, CUE, LOOK, PALETTE, PAD, BANK, PAGE, MASTER,
    ;

    companion object {
        fun of(record: RecordKind): SubjectKind = valueOf(record.name)
    }
}

/**
 * What a binding acts on (D2). A record is named by uuid only: a malformed uuid fails the decode, so
 * the row reads as [Does.Unknown] rather than as a record that resolves to nothing.
 */
@Serializable
sealed interface Subject {
    val subjectKind: SubjectKind

    @Serializable
    @SerialName("none")
    data object None : Subject {
        override val subjectKind get() = SubjectKind.NONE
    }

    /** The desk selection at the moment of the input. */
    @Serializable
    @SerialName("selection")
    data object Selection : Subject {
        override val subjectKind get() = SubjectKind.SELECTION
    }

    /** A job's slot, filled from the slot's item (session 5). Nothing builds one yet. */
    @Serializable
    @SerialName("slot")
    data object Slot : Subject {
        override val subjectKind get() = SubjectKind.SLOT
    }

    @Serializable
    @SerialName("group")
    data class Group(val key: String) : Subject {
        override val subjectKind get() = SubjectKind.GROUP
    }

    /** A fixture, or one of its elements by its element key. */
    @Serializable
    @SerialName("fixture")
    data class Fixture(val key: String) : Subject {
        override val subjectKind get() = SubjectKind.FIXTURE
    }

    @Serializable
    @SerialName("record")
    data class Record(val kind: RecordKind, @Serializable(with = UuidStringSerializer::class) val uuid: UUID) : Subject {
        override val subjectKind get() = SubjectKind.of(kind)
    }
}

/** A group or fixture subject as a selection target, or null for any other subject. */
fun Subject.asTarget(): CueTargetDto? = when (this) {
    is Subject.Group -> CueTargetDto(TargetRef.Group.TYPE, key)
    is Subject.Fixture -> CueTargetDto(TargetRef.Fixture.TYPE, key)
    else -> null
}

/** [this] subject's uuid when it is a record of [kind], else null. */
fun Subject.recordUuid(kind: RecordKind): UUID? = (this as? Subject.Record)?.takeIf { it.kind == kind }?.uuid

/**
 * One function's contract: where it writes, what it acts on, which inputs reach it, the button
 * behaviours it takes and the one it takes by default. The service refuses a binding the spec does
 * not admit, so dispatch can take the binding as given.
 */
data class Spec(
    val writesTo: WritesTo,
    val on: Set<SubjectKind>,
    val inputs: Set<ControlKind>,
    val hows: Set<How> = emptySet(),
    val defaultHow: How? = null,
)

/** What dispatch hands a function: one input, already turned from a [How] into an operation. */
sealed interface Op {
    /** A fader or encoder position, 0..127. [encoder] is asked only by FX speed. */
    data class Move(val value7Bit: UByte, val encoder: Boolean = false) : Op

    /** A value taken whole (OSC plan D10): a percentage, an amount or a tempo, in the function's own unit. */
    data class Set(val value: Double) : Op

    /** A press: the state on, or a stateless function's press. */
    data object On : Op
    data object Off : Op
    data object Toggle : Op

    /** A select button held, or a Replace press. */
    data object Replace : Op
}

/**
 * What a function did with one input: its [outcome], and for a While held press, the [release] that
 * undoes exactly what it wrote.
 */
data class Acted(val outcome: ActionOutcome, val release: (() -> Unit)? = null)

/** What a function acts through: the actions, the live surface state, and where the input came from. */
class SurfaceContext(
    val actions: SurfaceActions,
    val bankState: ActiveBankState,
    val encoderBankState: EncoderBankState,
    val origin: SurfaceDispatch.Origin,
)

/** The codec for a stored [Binding]. Stable discriminator `type`, defaults omitted. */
val BindingJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/** A [UUID] as its string; a malformed one is a [SerializationException], so a row holding it reads as unknown. */
object UuidStringSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("uk.me.cormack.lighting7.midi.Uuid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): UUID {
        val raw = decoder.decodeString()
        return try {
            UUID.fromString(raw)
        } catch (_: IllegalArgumentException) {
            throw SerializationException("'$raw' is not a uuid")
        }
    }
}

/** A stored payload as a [Binding], or [Does.Unknown] keeping [payload] verbatim when this build cannot read it. */
fun decodeBinding(payload: String, storedType: String): Binding = try {
    BindingJson.decodeFromString(Binding.serializer(), payload)
} catch (_: SerializationException) {
    Binding(Does.Unknown(storedType, payload))
} catch (_: IllegalArgumentException) {
    // A function's own `init` guard (a BPM window, a level) refusing a payload written under another rule.
    Binding(Does.Unknown(storedType, payload))
}

/** The payload column: an unknown binding is written back byte for byte. */
fun Binding.encodePayload(): String =
    (does as? Does.Unknown)?.rawPayload ?: BindingJson.encodeToString(Binding.serializer(), this)

/**
 * The input a control part feeds: a fader's or encoder's turn is continuous, a button's press and an
 * encoder's push are buttons. Null for a part the control does not have — a fader's push, a bank
 * button's anything (it switches the layer before any binding is resolved).
 */
fun ControlDescriptor.inputFor(part: ControlPart): ControlKind? = when (this) {
    is FaderDescriptor -> if (part == ControlPart.MAIN) ControlKind.CONTINUOUS else null
    is EncoderDescriptor -> when (part) {
        ControlPart.MAIN -> ControlKind.CONTINUOUS
        ControlPart.PUSH -> if (pushNote != null) ControlKind.BUTTON else null
    }
    is ButtonDescriptor -> if (part == ControlPart.MAIN) ControlKind.BUTTON else null
    is BankButtonDescriptor -> null
}

/**
 * The part a binding on [descriptor] sits on when the writer names none: an encoder's push for a
 * function that takes only buttons, else the main part. The migration (step 15) and the archive's
 * translate place an old row by this rule, and a write that names no part is placed by it too.
 */
fun inferPart(descriptor: ControlDescriptor?, does: Does): ControlPart =
    if (descriptor is EncoderDescriptor && descriptor.pushNote != null &&
        ControlKind.CONTINUOUS !in does.spec.inputs && ControlKind.BUTTON in does.spec.inputs
    ) ControlPart.PUSH else ControlPart.MAIN

/**
 * The part Learn binds: the part the operator [touched] when [does] takes its input, else where
 * [inferPart] places it, so turning an encoder to name it still binds a GO to its push.
 */
fun learntPart(descriptor: ControlDescriptor?, touched: ControlPart, does: Does): ControlPart {
    val input = descriptor?.inputFor(touched)
    return if (input != null && input in does.spec.inputs) touched else inferPart(descriptor, does)
}
