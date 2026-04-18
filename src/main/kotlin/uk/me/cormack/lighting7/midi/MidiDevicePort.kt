package uk.me.cormack.lighting7.midi

/** Direction of a MIDI port. */
enum class PortDirection { INPUT, OUTPUT }

/**
 * Descriptor for a single physical MIDI port as reported by the underlying MIDI access layer.
 * `id` is the backend-stable identifier used to re-open the port; `name` and `manufacturer`
 * are the human-readable strings shown in the UI.
 */
data class MidiDevicePort(
    val id: String,
    val name: String,
    val manufacturer: String?,
    val direction: PortDirection,
)

/**
 * A paired input/output device. Most control surfaces expose both directions as a single
 * physical device; this handle groups them. Either side may be null for devices that expose
 * only input (e.g. a MIDI foot-switch) or only output (e.g. a lighting generator).
 *
 * `displayKey` is a stable, human-legible slug used in thread names and logs. It's also the
 * handle identity — two handles with equal display keys are considered the same device.
 */
data class MidiDeviceHandle(
    val displayKey: String,
    val displayName: String,
    val inputPort: MidiDevicePort?,
    val outputPort: MidiDevicePort?,
) {
    init {
        require(inputPort != null || outputPort != null) {
            "MidiDeviceHandle must have at least one of input or output"
        }
    }

    companion object {
        /**
         * Pair up raw [MidiDevicePort]s from one enumeration pass into logical handles.
         *
         * Grouping heuristic: normalise each port's [MidiDevicePort.name] by stripping common
         * suffixes (" in", " out", " port 1", etc.) and lower-casing. Ports with matching
         * normalised names become one handle. Leftover ports form single-direction handles.
         */
        fun pair(ports: List<MidiDevicePort>): List<MidiDeviceHandle> {
            val inputs = ports.filter { it.direction == PortDirection.INPUT }.associateBy { groupKey(it) }.toMutableMap()
            val outputs = ports.filter { it.direction == PortDirection.OUTPUT }.associateBy { groupKey(it) }.toMutableMap()

            val handles = mutableListOf<MidiDeviceHandle>()
            val keys = (inputs.keys + outputs.keys).toSortedSet()
            for (k in keys) {
                val input = inputs.remove(k)
                val output = outputs.remove(k)
                val name = input?.name ?: output?.name ?: k
                handles.add(
                    MidiDeviceHandle(
                        displayKey = k,
                        displayName = name,
                        inputPort = input,
                        outputPort = output,
                    ),
                )
            }
            return handles
        }

        private val suffixRegex = Regex(
            """\s*(?:\b(?:in|out|input|output|midi|port)\b\s*\d*\s*)+$""",
            RegexOption.IGNORE_CASE,
        )

        private fun groupKey(port: MidiDevicePort): String {
            val normalised = port.name
                .replace(suffixRegex, "")
                .trim()
                .lowercase()
                .ifEmpty { port.name.lowercase() }
            return normalised.replace(Regex("[^a-z0-9]+"), "-").trim('-')
                .ifEmpty { port.id }
        }
    }
}
