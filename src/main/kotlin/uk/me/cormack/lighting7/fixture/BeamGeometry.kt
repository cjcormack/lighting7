package uk.me.cormack.lighting7.fixture

/**
 * How a fixture's beam should be drawn on the stage view.
 *
 * [INHERIT] is an annotation-only sentinel meaning "use the [FixtureKind]
 * default"; it is resolved away in [FixtureTypeRegistry] and never reaches a
 * [FixtureTypeRegistry.FixtureTypeInfo] or the wire.
 */
enum class BeamShape {
    INHERIT,
    /** No cone/pool drawn — body self-glow only (bars, blinders, lasers, effects). */
    NONE,
    /** Circular cone + floor pool (PAR, profile, fresnel, wash, moving head). */
    ROUND,
    /** Elongated sheet along the body's long axis (battens, some blinders). */
    LINEAR,
}

/**
 * Edge hardness of a beam. Anticipatory — the renderer may ignore this initially.
 * [INHERIT] behaves like [BeamShape.INHERIT].
 */
enum class BeamEdge {
    INHERIT,
    HARD,
    SOFT,
}

/**
 * Per-kind defaults for physical bounding size (metres; `lengthM` is the long
 * axis) and beam geometry. Applied when a [FixtureType] annotation leaves a
 * field unset.
 */
data class KindBeamDefaults(
    val lengthM: Double,
    val widthM: Double,
    val heightM: Double,
    val beamShape: BeamShape,
    val beamEdge: BeamEdge,
)

/**
 * Default dimensions + beam geometry for each [FixtureKind].
 *
 * Exhaustive `when` with no `else`: adding a new [FixtureKind] is a compile
 * error here until defaults are supplied — a structural guarantee that every
 * kind resolves to concrete (non-[BeamShape.INHERIT]) values.
 */
fun FixtureKind.beamDefaults(): KindBeamDefaults = when (this) {
    FixtureKind.MOVING_HEAD -> KindBeamDefaults(0.30, 0.30, 0.45, BeamShape.ROUND, BeamEdge.SOFT)
    FixtureKind.SCANNER -> KindBeamDefaults(0.40, 0.25, 0.25, BeamShape.ROUND, BeamEdge.HARD)
    FixtureKind.PROFILE -> KindBeamDefaults(0.55, 0.30, 0.30, BeamShape.ROUND, BeamEdge.HARD)
    FixtureKind.FRESNEL -> KindBeamDefaults(0.35, 0.30, 0.30, BeamShape.ROUND, BeamEdge.SOFT)
    FixtureKind.PAR -> KindBeamDefaults(0.30, 0.22, 0.22, BeamShape.ROUND, BeamEdge.SOFT)
    FixtureKind.WASH -> KindBeamDefaults(0.30, 0.30, 0.35, BeamShape.ROUND, BeamEdge.SOFT)
    FixtureKind.STRIP -> KindBeamDefaults(1.00, 0.08, 0.08, BeamShape.NONE, BeamEdge.SOFT)
    FixtureKind.LASER -> KindBeamDefaults(0.25, 0.20, 0.15, BeamShape.NONE, BeamEdge.HARD)
    FixtureKind.BLINDER -> KindBeamDefaults(0.30, 0.30, 0.15, BeamShape.NONE, BeamEdge.SOFT)
    FixtureKind.EFFECT -> KindBeamDefaults(0.30, 0.30, 0.30, BeamShape.NONE, BeamEdge.SOFT)
    FixtureKind.GENERIC -> KindBeamDefaults(0.25, 0.25, 0.25, BeamShape.NONE, BeamEdge.SOFT)
}
