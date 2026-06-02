package uk.me.cormack.lighting7.fixture

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class FixtureType(
    val typeKey: String,
    val manufacturer: String = "",
    val model: String = "",
    val acceptsBeamAngle: Boolean = false,
    val acceptsGel: Boolean = false,
    val gelCompactDisplay: CompactDisplayRole = CompactDisplayRole.NONE,
    val kind: FixtureKind = FixtureKind.GENERIC,
    /** Physical bounding size in metres; `lengthM` is the long axis. `-1.0`
     *  means "unset" — resolved to the [kind] default in [FixtureTypeRegistry].
     *  (`0.0` is a legal dimension and `NaN` isn't an annotation constant, so
     *  `-1.0` is the sentinel.) */
    val lengthM: Double = -1.0,
    val widthM: Double = -1.0,
    val heightM: Double = -1.0,
    /** Beam shape/edge; [BeamShape.INHERIT]/[BeamEdge.INHERIT] resolve to the
     *  [kind] default in [FixtureTypeRegistry]. */
    val beamShape: BeamShape = BeamShape.INHERIT,
    val beamEdge: BeamEdge = BeamEdge.INHERIT,
)
