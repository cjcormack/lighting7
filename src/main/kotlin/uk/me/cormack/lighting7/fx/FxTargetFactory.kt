package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.property.Slider

/**
 * The one place a client-supplied property *name* becomes an [FxTarget].
 *
 * Four near-identical copies of this dispatch used to live in the route layer — `lightFx.kt`,
 * `lightGroups.kt` and two in `projectCuesHelpers.kt` — and sweep item A11 was present in all
 * four: `"pan"` fell through to the arbitrary-slider branch and produced a [SliderTarget], which
 * drops the [FxOutput.Position] every position effect emits. No light, no error, no log.
 *
 * Callers pass the effect's [FxOutputType] where they know it (all of them do, one way or
 * another), which lets the dispatch do two things a name alone cannot:
 *
 * - Coerce `pan`/`tilt` to a [PositionTarget] for a POSITION effect. This is a **repair for
 *   already-persisted rows** — a Look or cue child recorded while A11 was live carries
 *   `propertyName: "pan"`, and narrowing the effect metadata cannot reach it. It is not a licence
 *   for dishonest `compatibleProperties`: `FxRegistrationTargetCompatibilityTest` asserts every
 *   built-in's metadata resolves correctly *without* the hint.
 * - Leave `pan`/`tilt` alone for a SLIDER effect. A sine wave on the pan axis by itself is
 *   legitimate and keeps resolving to `SliderTarget("pan")`.
 *
 * This function never throws. Cue, Look and Include spawning all route through here at fire time,
 * and a show must not die because one recorded row names a property a fixture has since lost —
 * an unresolvable name degrades to a [SettingTarget] that finds nothing, exactly as before.
 * Rejecting a mismatch is the *routes'* job (see `requireOutputTypeMatch`), and reporting one
 * everywhere else is [FxInstance]'s.
 */
object FxTargetFactory {

    /**
     * Resolve a target on a single fixture.
     *
     * @param fixtureKey the fixture's key
     * @param propertyName the client-supplied property name, in any case
     * @param outputType the effect's output type, when the caller knows it
     * @param fixture the resolved fixture, or null when it can't be found — only the fallback
     *   branch reads it, to tell a slider property from a setting
     */
    fun forFixture(
        fixtureKey: String,
        propertyName: String,
        outputType: FxOutputType?,
        fixture: Fixture?,
    ): FxTarget = resolve(FxTargetRef.fixture(fixtureKey), propertyName, outputType, fixture)

    /**
     * Resolve a target on a group. Identical dispatch to [forFixture]; the fallback branch
     * reflects on [firstMember], since a group's members are homogeneous by the time a group
     * effect is applied (`groupSupportsProperty` has already required the property of all of
     * them).
     */
    fun forGroup(
        groupName: String,
        propertyName: String,
        outputType: FxOutputType?,
        firstMember: Fixture?,
    ): FxTarget = resolve(FxTargetRef.group(groupName), propertyName, outputType, firstMember)

    private fun resolve(
        ref: FxTargetRef,
        propertyName: String,
        outputType: FxOutputType?,
        fixture: Fixture?,
    ): FxTarget {
        val lower = propertyName.lowercase()

        // A11: both axes of a POSITION effect's output, addressed by one of its axes.
        // Canonicalise to the synthetic "position" property every other path already
        // special-cases. Ahead of the dispatch rather than inside it, so `pan`/`tilt` on a
        // SLIDER effect keeps falling through to the reflective branch below untouched — a sine
        // wave on the pan axis alone is a legitimate thing to ask for.
        if (outputType == FxOutputType.POSITION && (lower == "pan" || lower == "tilt")) {
            return PositionTarget(ref)
        }

        return when (lower) {
            "dimmer", "uv" -> SliderTarget(ref, lower)
            "colour", "color", "rgbcolour" -> ColourTarget(ref)
            "position" -> PositionTarget(ref)
            else -> {
                val prop = fixture?.fixtureProperties?.find { it.name == propertyName }
                val propValue = prop?.classProperty?.call(fixture)
                if (propValue is Slider) {
                    SliderTarget(ref, propertyName)
                } else {
                    SettingTarget(ref, propertyName)
                }
            }
        }
    }
}

/**
 * Reject an effect whose output type its target cannot apply, at the REST boundary.
 *
 * [FxTarget.acceptedOutputType] explains why this matters: the apply would return silently and the
 * operator would get no light and no error. Throws [IllegalArgumentException] deliberately —
 * `lightGroups.kt` maps [IllegalStateException] to 404 (group not found) and only other
 * exceptions to 400, so an ISE here would report the wrong status.
 *
 * Only the routes call this. Cue/Look/Include spawn paths must not throw at fire time; they get
 * [FxInstance]'s warn instead.
 */
fun requireOutputTypeMatch(effect: Effect, target: FxTarget) {
    if (effect.outputType == target.acceptedOutputType) return
    throw IllegalArgumentException(
        "Effect '${effect.name}' outputs ${effect.outputType} but property " +
            "'${target.propertyName}' takes ${target.acceptedOutputType} — the output would be " +
            "discarded. Check the effect's compatibleProperties."
    )
}
