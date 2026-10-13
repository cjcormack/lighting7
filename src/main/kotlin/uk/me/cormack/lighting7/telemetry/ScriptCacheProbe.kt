package uk.me.cormack.lighting7.telemetry

/** Which cache answered the last script compile on this thread, for the span that covers it. */
object ScriptCacheProbe {
    enum class Source {
        /** The process's own cache of compiled FX scripts. */
        MEMORY,

        /** A compiled jar from `<appDataDir>/script-cache`. */
        DISK,

        /** Neither: the Kotlin compiler ran. */
        COMPILED,
    }

    private val last = ThreadLocal<Source?>()

    fun record(source: Source) = last.set(source)

    /** What answered since the last [take] on this thread, or null if nothing was recorded. */
    fun take(): Source? = last.get().also { last.remove() }

    /** As a span attribute: `memory`, `disk`, `compiled` or `unknown`. */
    fun tag(source: Source?): String = source?.name?.lowercase() ?: "unknown"
}
