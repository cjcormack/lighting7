package uk.me.cormack.lighting7.midi

/**
 * Marks a [ControlSurfaceDevice] subclass as a known control-surface profile.
 *
 * Discovered reflectively at registry load time by [ControlSurfaceRegistry]. Mirrors the
 * [uk.me.cormack.lighting7.fixture.FixtureType] pattern used for DMX fixture discovery.
 *
 * Matching precedence at device-attach time (see [ControlSurfaceRegistry.matchFor]):
 *   1. If [portPattern] is non-empty, regex-matched against `MidiDeviceHandle.displayName`.
 *   2. Otherwise [vendor] is compared case-insensitively against the port's `manufacturer`
 *      and [product] must be contained in the handle's `displayName`.
 *
 * @property typeKey Stable identifier persisted in bindings. Must be unique across all
 *   registered device classes.
 * @property vendor Manufacturer name as reported by the MIDI backend's port metadata.
 *   Empty means no vendor check.
 * @property product Human-readable product name. When non-empty, used as a case-insensitive
 *   `contains` match against the handle's `displayName`.
 * @property portPattern Optional regex matched against `MidiDeviceHandle.displayName`. When
 *   non-empty it takes precedence over [vendor] / [product]. Useful when the OS-level port
 *   name drifts from the product string.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ControlSurfaceType(
    val typeKey: String,
    val vendor: String = "",
    val product: String = "",
    val portPattern: String = "",
)
