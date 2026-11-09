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
        /** The regions a job fills (surface-setup plan D11), if the profile declares any. */
        val jobRegions: List<JobRegion> = emptyList(),
        /** Where the controls sit when drawn as a picture; null means "render the grouped table". */
        val layout: SurfaceLayout? = null,
    ) {
        private val regionsById: Map<String, JobRegion> by lazy { jobRegions.associateBy { it.id } }
        private val places: Map<Pair<String, ControlPart>, JobPlace> by lazy {
            buildMap { for (region in jobRegions) putAll(region.places) }
        }

        fun jobRegion(id: String): JobRegion? = regionsById[id]

        /** Where the control part sits in a job region, or null when no region claims it. */
        fun placeOf(controlId: String, part: ControlPart): JobPlace? = places[controlId to part]

        /** Which control part each inbound event addresses, built once per profile. */
        val inputs: ControlInputIndex by lazy { ControlInputIndex(controls) }

        /** The control [controlId] names, or null for an unknown one. */
        fun control(controlId: String): ControlDescriptor? = controlsById[controlId]

        private val controlsById: Map<String, ControlDescriptor> by lazy { controls.associateBy { it.controlId } }

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

    private val typesByKey: Map<String, DeviceTypeInfo> by lazy { allTypes.associateBy { it.typeKey } }

    /** The registered profile for [typeKey], or null. A map lookup, since the input path asks it per event. */
    fun typeFor(typeKey: String): DeviceTypeInfo? = typesByKey[typeKey]

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

            val controlsById = controls.associateBy { it.controlId }
            validateJobRegions(instance.jobRegions, controlsById, className)
            instance.layout?.let { validateLayout(it, controls, className) }

            result += DeviceTypeInfo(
                typeKey = typeKey,
                vendor = annotation.vendor,
                product = annotation.product,
                portPattern = annotation.portPattern,
                className = className,
                controls = controls.toList(),
                banks = instance.banks.toList(),
                jobRegions = instance.jobRegions.toList(),
                layout = instance.layout,
            )
        }

        return result
    }

    /**
     * Job regions are as load-bearing as [ControlDescriptor.controlId]s: a region id is persisted in a
     * job row and every control it claims resolves through it, so a typo has to fail the build rather
     * than silently unbind a fader mid-show.
     */
    private fun validateJobRegions(
        regions: List<JobRegion>,
        controlsById: Map<String, ControlDescriptor>,
        className: String,
    ) {
        val seenRegionIds = mutableSetOf<String>()
        val claimedBy = mutableMapOf<String, String>()
        for (region in regions) {
            check(seenRegionIds.add(region.id)) { "Duplicate job region id '${region.id}' on $className" }
            check(region.slots.isNotEmpty()) { "Job region '${region.id}' on $className has no slots" }
            val claimedHere = mutableSetOf<Pair<String, ControlPart>>()
            region.slots.forEachIndexed { index, slot ->
                check(slot.controls.isNotEmpty()) { "Slot ${index + 1} of job region '${region.id}' on $className has no role" }
                for ((role, controlId) in slot.controls) {
                    val control = controlsById[controlId]
                    checkNotNull(control) {
                        "Job region '${region.id}' on $className names undeclared control '$controlId' as slot " +
                            "${index + 1}'s ${role.name.lowercase()}"
                    }
                    check(fits(role, control)) {
                        "Job region '${region.id}' on $className names '$controlId' as slot ${index + 1}'s " +
                            "${role.name.lowercase()}, but that control is a ${control::class.simpleName}" +
                            if (role == SlotRole.PUSH) " with no push" else ""
                    }
                    check(claimedHere.add(controlId to role.part)) {
                        "Control '$controlId' on $className has two roles of one part in job region '${region.id}'"
                    }
                    val previous = claimedBy.put(controlId, region.id)
                    check(previous == null || previous == region.id) {
                        "Control '$controlId' on $className is in two job regions — '$previous' and '${region.id}'"
                    }
                }
            }
        }
    }

    private fun fits(role: SlotRole, control: ControlDescriptor): Boolean = when (role) {
        SlotRole.FADER -> control is FaderDescriptor
        SlotRole.ENCODER -> control is EncoderDescriptor
        SlotRole.PUSH -> control is EncoderDescriptor && control.pushNote != null
        SlotRole.SELECT, SlotRole.FLASH, SlotRole.BUTTON -> control is ButtonDescriptor
    }

    /**
     * A declared layout must be complete. A control with no cell would simply vanish from the
     * picture, which is exactly the kind of omission nothing else would ever report.
     */
    private fun validateLayout(
        layout: SurfaceLayout,
        controls: List<ControlDescriptor>,
        className: String,
    ) {
        val declaredIds = controls.mapTo(mutableSetOf()) { it.controlId }
        val placed = mutableMapOf<String, String>()

        for (region in layout.regions) {
            for (cell in region.cells) {
                check(cell.controlId in declaredIds) {
                    "Layout region '${region.name}' on $className places undeclared control '${cell.controlId}'"
                }
                val previousRegion = placed.put(cell.controlId, region.name)
                check(previousRegion == null) {
                    "Control '${cell.controlId}' on $className has two layout cells — " +
                        "in regions '$previousRegion' and '${region.name}'"
                }
            }
        }

        val unplaced = declaredIds - placed.keys
        check(unplaced.isEmpty()) {
            "$className declares a layout but places no cell for ${unplaced.sorted().joinToString(", ") { "'$it'" }}"
        }
    }

    private fun requireAnnotation(klass: KClass<out ControlSurfaceDevice>): ControlSurfaceType {
        return klass.findAnnotation<ControlSurfaceType>()
            ?: throw IllegalStateException(
                "Control surface class ${klass.simpleName} is missing @ControlSurfaceType annotation",
            )
    }
}
