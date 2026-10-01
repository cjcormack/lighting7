package uk.me.cormack.lighting7.fixture

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The parametric body a fixture is drawn as on the Stage view (stage-view plan sessions 6–7; the
 * design record's §"Fixture bodies and the lantern library", item 3). The names are the frontend's
 * `Archetype` in `stage3d/bodies/archetype.ts`, which builds each from parts.
 *
 * [INHERIT] is an annotation-only sentinel — "no body declared" — and never reaches the wire:
 * [FixtureTypeRegistry] answers a null body for it, and the view then chooses one from the kind as
 * it did before a type could say.
 */
@Serializable
enum class BodyArchetype {
    INHERIT,
    @SerialName("profile") PROFILE,
    @SerialName("boxProfile") BOX_PROFILE,
    @SerialName("fresnel") FRESNEL,
    @SerialName("par") PAR,
    @SerialName("flood") FLOOD,
    @SerialName("downlight") DOWNLIGHT,
    @SerialName("mover") MOVER,
    @SerialName("batten") BATTEN,
    @SerialName("blinder") BLINDER,
    @SerialName("effect") EFFECT,
    @SerialName("cannon") CANNON,
    @SerialName("tape") TAPE,
}

/** A mover's head. [INHERIT] leaves it to the view, which reads the type's words for it. */
@Serializable
enum class MoverHead {
    INHERIT,
    @SerialName("spot") SPOT,
    @SerialName("wash") WASH,
    @SerialName("profile") PROFILE,
    @SerialName("bar") BAR,
}

/**
 * A DMX fixture type's **body**, mirroring GDTF's `Model` (the design record's item 6): the
 * archetype, a mover's head, and the lens diameter. The unit's L × W × H stay on [FixtureType]
 * itself, as they were. When [archetype] is [BodyArchetype.INHERIT] — the default — the type
 * declares no body and the view defaults one from the kind, as it always has. A real GLB per type
 * can later arrive through the same slot (D10).
 */
@Target()
@Retention(AnnotationRetention.RUNTIME)
annotation class FixtureBody(
    val archetype: BodyArchetype = BodyArchetype.INHERIT,
    val head: MoverHead = MoverHead.INHERIT,
    /** The lens (or front aperture) diameter in metres; `-1.0` leaves it to the archetype. */
    val lensDiameterM: Double = -1.0,
)

/** A declared body as the registry resolves it and the wire carries it; never an INHERIT archetype. */
@Serializable
data class FixtureBodyInfo(
    val archetype: BodyArchetype,
    /** A mover's head, or null for the view to choose. */
    val head: MoverHead? = null,
    val lensDiameterM: Double? = null,
)

/** The declared body, or null when the annotation leaves it to the kind. */
fun FixtureBody.resolve(): FixtureBodyInfo? {
    if (archetype == BodyArchetype.INHERIT) return null
    return FixtureBodyInfo(
        archetype = archetype,
        head = head.takeIf { it != MoverHead.INHERIT },
        lensDiameterM = lensDiameterM.takeIf { it > 0.0 },
    )
}
