package uk.me.cormack.lighting7.midi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The reverse index of the served map (surface-setup plan D15): for each group, fixture and record,
 * every control part that acts on it, so a record's *Bound on* chip and *Assign to surface…* read
 * the desk's answer rather than resolving bindings themselves (P5).
 *
 * Read from the map frames, so a part counts exactly when the map says it resolves to something on
 * that subject — its own row or its region's job. A record named in a function's parameter (a
 * palette's press) counts as well as its subject.
 */
@Serializable
data class SurfaceUsesFrame(
    val projectId: Int,
    /** [SurfaceUses.subjectKey] → every part acting on it, in profile order. */
    val uses: Map<String, List<SurfaceUse>>,
)

/** Where a slot is, in words, and what assigning to it would change: `Replaces GO · Act 1.` */
@Serializable
data class SurfaceSlotPreview(val place: String, val replaces: List<String>)

/** One control part acting on a subject. */
@Serializable
data class SurfaceUse(
    val deviceTypeKey: String,
    /** The profile's product name: `X-Touch Compact`. */
    val deviceLabel: String,
    val controlId: String,
    val part: ControlPart,
    /** The layer it acts on, null for every layer (and on a profile with none). */
    val layer: String? = null,
    /** Where it is, in words: `Button 36 · A`, `Encoder 9 push`. */
    val label: String,
    /** What it does there, the map's label: `GO · Act 1`. */
    val does: String? = null,
    /** True when the part's region's job derives it rather than a row of its own. */
    val fromJob: Boolean = false,
)

object SurfaceUses {
    /**
     * The key a subject is indexed under: `group:<key>`, `fixture:<key>` or `record:<KIND>:<uuid>`.
     * Null for a subject that names nothing (none, the selection, a slot).
     */
    fun subjectKey(subject: Subject?): String? = when (subject) {
        is Subject.Group -> "group:${subject.key}"
        is Subject.Fixture -> "fixture:${subject.key}"
        is Subject.Record -> recordKey(subject.kind, subject.uuid.toString())
        Subject.None, Subject.Selection, Subject.Slot, null -> null
    }

    fun recordKey(kind: RecordKind, uuid: String): String = "record:${kind.name}:$uuid"

    /** Every key [part] acts on: its subject's, and each record its function names in a parameter. */
    fun keysOf(part: ResolvedPart): List<String> {
        val does = part.does ?: return emptyList()
        val recordParams = part.entry?.let(SurfaceCatalogue::entry)?.params.orEmpty().filter { it.kind == ParamKind.RECORD && it.record != null }
        val fromParams = if (recordParams.isEmpty()) emptyList() else {
            val fields = BindingJson.encodeToJsonElement(Does.serializer(), does) as? JsonObject
            recordParams.mapNotNull { param ->
                val value = (fields?.get(param.key) as? JsonPrimitive)?.takeIf { it.isString }?.content
                value?.let { recordKey(param.record!!, it) }
            }
        }
        return (listOfNotNull(subjectKey(part.on)) + fromParams).distinct()
    }

    fun build(projectId: Int, frames: Map<String, SurfaceMapFrame>, profileOf: (String) -> ControlSurfaceRegistry.DeviceTypeInfo?): SurfaceUsesFrame {
        val uses = LinkedHashMap<String, MutableList<SurfaceUse>>()
        for ((typeKey, frame) in frames) {
            val profile = profileOf(typeKey) ?: continue
            val layers = profile.banks.map { it.id }.ifEmpty { listOf("") }
            // (key, part key, what it does, from a job) → the layers it acts on, in the order first met.
            val found = LinkedHashMap<Found, MutableSet<String>>()
            for (control in profile.controls) {
                for (part in ControlPart.entries) {
                    if (control.inputFor(part) == null) continue
                    val partKey = SurfaceMaps.partKey(control.controlId, part)
                    val onSomeLayer = HashSet<String>()
                    for (layer in layers) {
                        val resolved = frame.layers[layer]?.get(partKey) ?: continue
                        if (resolved.from == PartSource.NONE) continue
                        for (key in keysOf(resolved)) {
                            onSomeLayer += key
                            found.getOrPut(Found(key, control, part, resolved.label, resolved.from == PartSource.JOB)) { LinkedHashSet() } += layer
                        }
                    }
                    // What fires on a device on no layer, where no declared layer has it.
                    val noLayer = frame.layers[""]?.get(partKey)
                    if (profile.banks.isNotEmpty() && noLayer != null && noLayer.from != PartSource.NONE) {
                        for (key in keysOf(noLayer)) {
                            if (key in onSomeLayer) continue
                            found.getOrPut(Found(key, control, part, noLayer.label, noLayer.from == PartSource.JOB)) { LinkedHashSet() } += NO_LAYER
                        }
                    }
                }
            }
            for ((at, onLayers) in found) {
                val list = uses.getOrPut(at.key) { mutableListOf() }
                val everyLayer = profile.banks.isEmpty() || onLayers.size == layers.size || NO_LAYER in onLayers
                val shown = if (everyLayer) listOf(null) else layers.filter { it in onLayers }
                for (layer in shown) {
                    list += SurfaceUse(
                        deviceTypeKey = typeKey,
                        deviceLabel = profile.product,
                        controlId = at.control.controlId,
                        part = at.part,
                        layer = layer,
                        label = placeLabel(at.control, at.part, profile.banks.firstOrNull { it.id == layer }),
                        does = at.does,
                        fromJob = at.fromJob,
                    )
                }
            }
        }
        return SurfaceUsesFrame(projectId, uses)
    }

    /** `Button 36`, `Encoder 9 push`, then the layer's name when it is one layer's: `Button 36 · A`. */
    fun placeLabel(control: ControlDescriptor, part: ControlPart, layer: BankDefinition?): String {
        val where = if (part == ControlPart.PUSH) "${control.label} push" else control.label
        return if (layer == null) where else "$where · ${layer.name}"
    }

    /**
     * What assigning to `(controlId, part, layer)` changes, as [frame] has it now: the binding it
     * replaces, a layer's own binding that keeps beating it, a binding on both layers it beats on one,
     * and a job it takes the control from. [SurfaceSlotPreview.replaces] is empty when nothing acts there.
     */
    fun slotPreview(
        frame: SurfaceMapFrame?,
        profile: ControlSurfaceRegistry.DeviceTypeInfo,
        controlId: String,
        part: ControlPart,
        layer: String?,
    ): SurfaceSlotPreview {
        val control = profile.control(controlId)
        val place = control?.let { placeLabel(it, part, profile.banks.firstOrNull { b -> b.id == layer }) } ?: controlId
        val key = SurfaceMaps.partKey(controlId, part)
        val layers = profile.banks.ifEmpty { listOf(null) }
        val viewed = if (layer == null) layers else layers.filter { it?.id == layer }
        val lines = LinkedHashSet<String>()
        for (bank in viewed) {
            val resolved = frame?.layers?.get(bank?.id ?: "")?.get(key) ?: continue
            if (resolved.from == PartSource.NONE) continue
            val label = resolved.label ?: "a binding"
            lines += when {
                resolved.from == PartSource.JOB -> "Takes it out of ${resolved.jobLabel ?: "its job"} ($label)."
                resolved.bank == layer -> "Replaces $label."
                layer == null -> "Layer ${bank?.name} keeps its own: $label."
                else -> "Beats $label on Layer ${bank?.name}; every other layer keeps it."
            }
        }
        return SurfaceSlotPreview(place, lines.toList())
    }

    private const val NO_LAYER = ""

    private data class Found(val key: String, val control: ControlDescriptor, val part: ControlPart, val does: String?, val fromJob: Boolean)
}
