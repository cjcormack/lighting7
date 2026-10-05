package uk.me.cormack.lighting7.fixture

import kotlinx.serialization.Serializable

/**
 * How fast a type's mechanics move when nothing slows them — `@FixtureType(travel = Travel(…))`,
 * fixture optics plan D14. **Drawn, never output**: the Stage view eases what it draws toward the
 * DMX value at these rates, so a head swings and a scroller winds rather than snapping; the desk
 * sends every value exactly as composed, and nothing on the backend reads this.
 *
 * Each family is optional, and one left unset (`-1`, the sentinel, as on [FixtureType.fieldDeg])
 * snaps in the view as it always did — an LED head's colour, say, which is electronic.
 *
 * - [panDegPerS] / [tiltDegPerS] — the head's fastest pan and tilt, in degrees a second. A move of
 *   `d` degrees takes `d / rate`.
 * - [beamMs] — the time a beam channel takes across its whole DMX range: focus, zoom, iris, frost,
 *   a gobo wheel and its index, the framing shutters. A move of part of the range takes that part.
 * - [colourMs] — the same for a colour wheel, a gel scroller and the colour filters (a second
 *   wheel, a media frame's wing). A wheel or a string passes through the slots between.
 *
 * The fixture's own **timing channels** ([FixtureProperty.timing]) stretch these: a non-zero value
 * on one replaces the rate with a duration for the families it names. Few manuals publish speeds,
 * so most of these are estimates (D15), marked at their source and listed in
 * `FU-MANUAL-S8-TRAVEL`. See `docs/fixtures-engineering.md` §"Travel and timing channels".
 */
@Target()
@Retention(AnnotationRetention.RUNTIME)
annotation class Travel(
    val panDegPerS: Double = -1.0,
    val tiltDegPerS: Double = -1.0,
    val beamMs: Int = -1,
    val colourMs: Int = -1,
)

/** A declared [Travel] as the registry resolves it and the wire carries it: sentinels as nulls. */
@Serializable
data class TravelInfo(
    val panDegPerS: Double? = null,
    val tiltDegPerS: Double? = null,
    val beamMs: Int? = null,
    val colourMs: Int? = null,
)

/** The declared travel, or null when the annotation declares no family at all. */
fun Travel.resolve(): TravelInfo? {
    val info = TravelInfo(
        panDegPerS = panDegPerS.takeIf { it > 0.0 },
        tiltDegPerS = tiltDegPerS.takeIf { it > 0.0 },
        beamMs = beamMs.takeIf { it > 0 },
        colourMs = colourMs.takeIf { it > 0 },
    )
    return info.takeUnless { it == TravelInfo() }
}

/**
 * Which families a fixture **timing channel** stretches (`@FixtureProperty(timing = …)`, fixture
 * optics plan D14) — the Source Four Revolution's Focus, Colour and Beam Timing (manual p14–16 [10–12]).
 * [NONE] is the annotation's sentinel for "not a timing channel", as [Blade.NONE] is for "no blade".
 *
 * - [POSITION] — pan and tilt (ETC's "focus": where the light is pointed, not the lens).
 * - [BEAM] — the beam family [Travel.beamMs] covers.
 * - [COLOUR] — the colour family [Travel.colourMs] covers.
 * - [ALL] — every family.
 */
enum class TimingRole {
    NONE,
    POSITION,
    BEAM,
    COLOUR,
    ALL;

    fun serialized(): String? = if (this == NONE) null else name
}
