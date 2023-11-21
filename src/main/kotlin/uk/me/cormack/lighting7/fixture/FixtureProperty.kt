package uk.me.cormack.lighting7.fixture

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class FixtureProperty(val name: String)
