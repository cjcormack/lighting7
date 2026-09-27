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
    /** The unit's length is installation-specific rather than a fact of the model — a lightstrip
     *  is cut to the run it is laid along, where a pixel bar is always the bar it is. A type that
     *  sets this takes a per-patch `lengthM` (and per-placement, for a run laid in segments);
     *  [lengthM] above is then only the default drawn until one is set. Every other type refuses
     *  a stored length at the write boundary. See `docs/fixtures-engineering.md` §"Variable-length
     *  fixtures". */
    val acceptsLength: Boolean = false,
    /** Beam shape/edge; [BeamShape.INHERIT]/[BeamEdge.INHERIT] resolve to the
     *  [kind] default in [FixtureTypeRegistry]. */
    val beamShape: BeamShape = BeamShape.INHERIT,
    val beamEdge: BeamEdge = BeamEdge.INHERIT,
)
