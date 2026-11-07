package uk.me.cormack.lighting7.models

private val NOT_SLUG = Regex("[^a-z0-9]+")

/**
 * A key from a display name: "FOH 1 (SL)" → "foh-1-sl". Lower case, every run of anything but ASCII
 * letters and digits one `-`, trimmed, at most 100 characters — so a name with no ASCII letter or
 * digit slugs to nothing. The MCP setup tools mint fixture keys with it, and OSC addresses name
 * stacks, cues, Looks and busk pages by it (`osc/OscAddresses.kt`).
 */
fun slugOf(name: String): String = name.lowercase().replace(NOT_SLUG, "-").trim('-').take(100)
