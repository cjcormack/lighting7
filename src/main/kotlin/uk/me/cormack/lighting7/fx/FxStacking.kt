package uk.me.cormack.lighting7.fx

/**
 * Where a stacked FX starts (FX editor plan D6, §3.6): the blend and Amount a bare FX press with
 * `stack: true` takes when it names neither, by the family it lands on. Stacking adds an FX on top of
 * the Local FX already running, so each family starts on the blend that works *with* what is under it:
 * Intensity *Within*, Position *Around*, and Colour and Beam a half-way *Replace*.
 */
object FxStacking {
    /** One family's stacking start. [parameters] are set only where the press names them not. */
    data class Start(
        val blendMode: BlendMode,
        val size: Double,
        val parameters: Map<String, String> = emptyMap(),
    )

    private const val PAN_CENTRE = "panCenter"
    private const val TILT_CENTRE = "tiltCenter"

    /** Position's centres at 128: an *Around* FX moves about what is under it, not about a fixed point. */
    private val CENTRED = mapOf(PAN_CENTRE to "128", TILT_CENTRE to "128")

    /**
     * [family]'s start for an FX declaring [parameters]. *Around* needs a centre to move about, so a
     * Position FX without both (Palette Steps, which outputs where to go) replaces at full, as an
     * unstacked press does.
     */
    fun startFor(family: PropertyMaskGroup, parameters: Set<String>): Start = when (family) {
        PropertyMaskGroup.INTENSITY -> Start(BlendMode.MULTIPLY, 1.0)
        PropertyMaskGroup.POSITION ->
            if (PAN_CENTRE in parameters && TILT_CENTRE in parameters) Start(BlendMode.ADDITIVE, 1.0, CENTRED)
            else Start(BlendMode.OVERRIDE, 1.0)
        PropertyMaskGroup.COLOUR -> Start(BlendMode.OVERRIDE, 0.5)
        PropertyMaskGroup.BEAM -> Start(BlendMode.OVERRIDE, 0.5)
    }
}
