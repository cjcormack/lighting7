package uk.me.cormack.lighting7.fixture

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class FixtureProperty(val description: String = "")
