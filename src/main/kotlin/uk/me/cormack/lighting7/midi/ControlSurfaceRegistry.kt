package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.midi.devices.XTouchCompactStandard
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation

/**
 * Registry of all known [ControlSurfaceDevice] profiles. Structural twin of
 * [uk.me.cormack.lighting7.fixture.FixtureTypeRegistry] — manual list of classes, lazy
 * introspection, `findAnnotation<T>()`-based metadata extraction.
 *
 * Two validation guarantees, both fail-fast at first access of [allTypes]:
 *   1. **`typeKey` uniqueness** across all registered device classes. Duplicate typeKeys
 *      throw [IllegalStateException] (unlike [FixtureTypeRegistry], which silently
 *      last-writes-wins — surfaces need a hard contract because bindings reference
 *      typeKeys persistently).
 *   2. **`controlId` uniqueness** within each device class. Two controls on the same
 *      device with the same id would make bindings ambiguous.
 *
 * Adding a new device: drop a `.kt` file under `midi/devices/`, annotate the class with
 * [ControlSurfaceType], and add one line to [deviceClasses] below.
 */
object ControlSurfaceRegistry {

    /**
     * All top-level device profile classes. Tests can exercise the validation logic
     * directly via [buildFromClasses] with custom class lists.
     */
    private val deviceClasses: List<KClass<out ControlSurfaceDevice>> = listOf(
        XTouchCompactStandard::class,
    )

    /** Read-only snapshot of a registered [ControlSurfaceDevice] class. */
    data class DeviceTypeInfo(
        val typeKey: String,
        val vendor: String,
        val product: String,
        val portPattern: String,
        val className: String,
        val controls: List<ControlDescriptor>,
        val banks: List<BankDefinition>,
    ) {
        internal val compiledPortPattern: Regex? = if (portPattern.isEmpty()) null else Regex(portPattern)
    }

    /**
     * All registered device types, lazily computed on first access. Validation failures
     * surface here.
     */
    val allTypes: List<DeviceTypeInfo> by lazy { buildFromClasses(deviceClasses) }

    private val typeKeyToClass: Map<String, KClass<out ControlSurfaceDevice>> by lazy {
        deviceClasses.associateBy { requireAnnotation(it).typeKey }
    }

    /**
     * Instantiate a device profile by its [typeKey]. Throws [IllegalArgumentException] if
     * the key is unknown.
     */
    fun instantiate(typeKey: String): ControlSurfaceDevice {
        val klass = typeKeyToClass[typeKey]
            ?: throw IllegalArgumentException("Unknown control surface typeKey: $typeKey")
        return klass.createInstance()
    }

    /**
     * Find the registered device type that matches a [MidiDeviceHandle]. Precedence:
     *   1. If `portPattern` is non-empty, regex-matched against `displayName`.
     *   2. Otherwise `vendor` (case-insensitive equals against the port's `manufacturer`)
     *      AND, if `product` is non-empty, `product` contained in `displayName`
     *      (case-insensitive).
     *
     * Returns the first match, or null if nothing matches.
     */
    fun matchFor(handle: MidiDeviceHandle): DeviceTypeInfo? = allTypes.firstOrNull { it.matches(handle) }

    private fun DeviceTypeInfo.matches(handle: MidiDeviceHandle): Boolean {
        compiledPortPattern?.let { return it.containsMatchIn(handle.displayName) }
        if (vendor.isEmpty() && product.isEmpty()) return false
        val manufacturer = handle.inputPort?.manufacturer ?: handle.outputPort?.manufacturer
        if (vendor.isNotEmpty() && !manufacturer.equals(vendor, ignoreCase = true)) return false
        if (product.isNotEmpty() && !handle.displayName.contains(product, ignoreCase = true)) return false
        return true
    }

    /**
     * Build a validated list of [DeviceTypeInfo] from an arbitrary class list.
     * Exposed so tests can exercise the validation logic without mutating the live
     * [deviceClasses].
     */
    internal fun buildFromClasses(classes: List<KClass<out ControlSurfaceDevice>>): List<DeviceTypeInfo> {
        val result = mutableListOf<DeviceTypeInfo>()
        val seenTypeKeys = mutableMapOf<String, String>()

        for (klass in classes) {
            val annotation = requireAnnotation(klass)
            val typeKey = annotation.typeKey
            val className = klass.simpleName ?: klass.java.name

            val previousClass = seenTypeKeys[typeKey]
            check(previousClass == null) {
                "Duplicate control surface typeKey '$typeKey' on $className — already declared on $previousClass"
            }
            seenTypeKeys[typeKey] = className

            val instance = try {
                klass.createInstance()
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Failed to instantiate control surface class $className for introspection — " +
                        "it must have a no-argument primary constructor. (${e.message})",
                    e,
                )
            }

            val controls = instance.controls
            val seenControlIds = mutableSetOf<String>()
            for (control in controls) {
                check(seenControlIds.add(control.controlId)) {
                    "Duplicate controlId '${control.controlId}' on $className"
                }
            }

            result += DeviceTypeInfo(
                typeKey = typeKey,
                vendor = annotation.vendor,
                product = annotation.product,
                portPattern = annotation.portPattern,
                className = className,
                controls = controls.toList(),
                banks = instance.banks.toList(),
            )
        }

        return result
    }

    private fun requireAnnotation(klass: KClass<out ControlSurfaceDevice>): ControlSurfaceType {
        return klass.findAnnotation<ControlSurfaceType>()
            ?: throw IllegalStateException(
                "Control surface class ${klass.simpleName} is missing @ControlSurfaceType annotation",
            )
    }
}
