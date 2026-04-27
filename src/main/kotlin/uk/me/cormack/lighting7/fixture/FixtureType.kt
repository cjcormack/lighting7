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
)
