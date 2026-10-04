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
    /** The body the Stage view draws — archetype, a mover's head, lens diameter ([FixtureBody]).
     *  Absent, the view defaults it from the [kind]. */
    val body: FixtureBody = FixtureBody(),
    /** A patch of this type names a **lantern** from the library (`fixture/lantern/`) and carries
     *  its focus — shutters, gate, iris, focus, zoom — per placement. Only a conventional dimmer
     *  does: a DMX type carries its own [body], and a fixture whose optics are on channels drives
     *  them from its looks (D14). Every other type refuses the fields at the write boundary. See
     *  `docs/fixtures-engineering.md` §"Lanterns and focus". */
    val acceptsLantern: Boolean = false,
    /** How fast the beam goes soft away from its focal plane (fixture-optics plan D9): the Stage
     *  view's blur, in field radii, per unit of relative focus error `|f − d| / f`. Larger is
     *  softer — a wide lens holds less depth of focus than a narrow one. `-1.0` means "unset" and
     *  the view uses its family's constant (`DEPTH_OF_FIELD` in `stage3d/bodies/archetype.ts`).
     *  Only a type with a FOCUS channel is ever drawn by it. See `docs/fixtures-engineering.md`
     *  §"@FixtureType". */
    val depthOfField: Double = -1.0,
    /** A **fixed lens's** full beam angle in degrees (fixture-optics plan D3) — the Fusion 100's 10°,
     *  the Scantastic's 11°. The Stage view's beam angle is the zoom channel's, else the patch's
     *  `beamAngleDeg`, else this, else the family's default: a type with a ZOOM channel never needs
     *  it. `-1.0` means "unset", as for [depthOfField]. See `docs/fixtures-engineering.md`
     *  §"Beam vocabulary". */
    val fieldDeg: Double = -1.0,
)
