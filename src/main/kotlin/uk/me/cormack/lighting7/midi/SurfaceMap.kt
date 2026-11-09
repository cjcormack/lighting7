package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.models.describeAssignmentHealth

/**
 * The served picture (surface-setup plan D15): what every control part of a device type resolves
 * to on each layer, labelled and judged on the desk, so a browser draws it and keeps no copy of the
 * resolve order, the kind tables or the label rules (P5).
 */
@Serializable
data class SurfaceMapFrame(
    val projectId: Int,
    val deviceTypeKey: String,
    /**
     * Each layer the profile declares, and `""` — no layer: what fires on a device on none, the rows
     * on both layers, and a profile with no layers' only entry — → `"controlId:PART"` → its part.
     */
    val layers: Map<String, Map<String, ResolvedPart>>,
    /** Every job stored for the type, its items named by name (Editor B). */
    val jobs: List<SurfaceJobView> = emptyList(),
)

/** Where a part's binding comes from: its own row, its region's job, or nothing. */
@Serializable
enum class PartSource { DIRECT, JOB, NONE }

/** One stored job as Editor B draws it. */
@Serializable
data class SurfaceJobView(
    val id: Int,
    val region: String,
    /** The layer it is stored on, null for both. */
    val bank: String? = null,
    val kind: String,
    /** The kind's words; null for a kind this build cannot read. */
    val kindLabel: String? = null,
    /** Each item in order, a gap as null. Empty for a kind with no items, or items this build cannot read. */
    val items: List<SurfaceJobItemView?> = emptyList(),
    /** Whether this build can read it: an unreadable job is kept and derives nothing. */
    val readable: Boolean = true,
)

/** One job item, with its name, colour and health. */
@Serializable
data class SurfaceJobItemView(
    val item: JobItem,
    val label: String,
    val colour: String? = null,
    val health: AssignmentHealth = AssignmentHealth.Ok,
    val healthText: String? = null,
)

/** One control part on one layer, as the editors draw it. */
@Serializable
data class ResolvedPart(
    val from: PartSource,
    /** The row's id; null on a part a job derives. */
    val bindingId: Int? = null,
    /** The layer the row, or the job, is stored on, null for both. */
    val bank: String? = null,
    /** The job a [PartSource.JOB] part is derived from, its region, and its slot (0-based). */
    val jobId: Int? = null,
    val jobRegion: String? = null,
    val item: Int? = null,
    /** Where a [PartSource.JOB] part comes from, in words: `Groups as channel strips · item 3`. */
    val jobLabel: String? = null,
    /** On a [PartSource.DIRECT] part, what the job it beats would derive there, in the same words. */
    val beats: String? = null,
    val does: Does? = null,
    val on: Subject? = null,
    val how: How? = null,
    /** The catalogue entry [does] is ([SurfaceCatalogue.entryOf]); null for an unreadable row. */
    val entry: String? = null,
    val takeoverPolicy: String? = null,
    val label: String? = null,
    val subjectLabel: String? = null,
    /** The colour of the group or fixture it drives. */
    val colour: String? = null,
    val writesTo: WritesTo? = null,
    /** What the control shows, in a sentence. */
    val shows: String? = null,
    val health: AssignmentHealth = AssignmentHealth.Ok,
    val healthText: String? = null,
)

/** Builds [SurfaceMapFrame]s; pure over the service's cache and a names snapshot. */
object SurfaceMaps {
    private val NONE = ResolvedPart(PartSource.NONE)

    /** The layers [profile] declares, then null: no layer. */
    fun layersOf(profile: ControlSurfaceRegistry.DeviceTypeInfo): List<String?> =
        profile.banks.map { it.id } + null

    /** The part key a frame and the slot write share. */
    fun partKey(controlId: String, part: ControlPart): String = "$controlId:${part.name}"

    fun build(
        projectId: Int,
        profile: ControlSurfaceRegistry.DeviceTypeInfo,
        service: ControlSurfaceBindingService,
        names: SurfaceNames,
        encoderPage: EncoderBankSelection,
    ): SurfaceMapFrame {
        val layers = layersOf(profile).associate { layer ->
            val parts = LinkedHashMap<String, ResolvedPart>()
            for (control in profile.controls) {
                for (part in ControlPart.entries) {
                    if (control.inputFor(part) == null) continue
                    val resolved = service.resolve(projectId, profile.typeKey, control.controlId, layer, encoderPage, part)
                    val described = resolved?.let { describe(it, control, names, profile) } ?: NONE
                    parts[partKey(control.controlId, part)] = if (resolved?.job == null && resolved != null) {
                        val beaten = service.jobBindingAt(projectId, profile.typeKey, control.controlId, layer, encoderPage, part)
                        described.copy(beats = beaten?.let { jobPhrase(it) })
                    } else {
                        described
                    }
                }
            }
            (layer ?: "") to parts
        }
        return SurfaceMapFrame(projectId, profile.typeKey, layers, jobViews(service.jobs(projectId).filter { it.deviceTypeKey == profile.typeKey }, names))
    }

    /** A job hit in words: `Groups as channel strips · item 3`; a slot a kind fills by position or from live state, by its slot. */
    fun jobPhrase(resolved: ControlSurfaceBindingService.ResolvedBinding): String? {
        val hit = resolved.job ?: return null
        val kind = hit.kind?.label ?: return null
        val word = if (hit.kind.item == null || hit.kind.maxItems != null) "slot" else "item"
        return "$kind · $word ${hit.slot + 1}"
    }

    private fun jobViews(jobs: List<ControlSurfaceBindingService.StoredJob>, names: SurfaceNames): List<SurfaceJobView> =
        jobs.map { job ->
            SurfaceJobView(
                id = job.id,
                region = job.region,
                bank = job.bank,
                kind = job.kindId,
                kindLabel = job.kind?.label,
                items = job.items.orEmpty().mapIndexed { index, item ->
                    item?.let {
                        val health = job.healthAt(index)
                        SurfaceJobItemView(
                            item = it,
                            label = SurfaceLabels.itemLabel(it, names),
                            colour = names.colour(it.subject),
                            health = health,
                            healthText = health.takeIf { h -> h !is AssignmentHealth.Ok }?.let(::describeAssignmentHealth),
                        )
                    }
                },
                readable = job.kind != null && job.items != null,
            )
        }

    private fun describe(
        resolved: ControlSurfaceBindingService.ResolvedBinding,
        control: ControlDescriptor?,
        names: SurfaceNames,
        profile: ControlSurfaceRegistry.DeviceTypeInfo,
    ): ResolvedPart {
        val binding = resolved.binding
        val known = binding.does !is Does.Unknown
        val hit = resolved.job
        return ResolvedPart(
            from = if (hit != null) PartSource.JOB else PartSource.DIRECT,
            bindingId = resolved.id.takeIf { hit == null },
            bank = resolved.bank,
            jobId = hit?.jobId,
            jobRegion = hit?.region,
            item = hit?.slot,
            jobLabel = jobPhrase(resolved),
            does = binding.does,
            on = binding.on,
            how = binding.how,
            entry = SurfaceCatalogue.entryOf(binding.does),
            takeoverPolicy = resolved.takeoverPolicy?.name,
            label = SurfaceLabels.label(binding, names, profile),
            subjectLabel = names.subjectWord(binding.on),
            colour = names.colour(binding.on),
            writesTo = if (known) binding.does.spec.writesTo else null,
            shows = SurfaceLabels.shows(binding, control, names),
            health = resolved.health,
            healthText = resolved.health.takeIf { it !is AssignmentHealth.Ok }?.let(::describeAssignmentHealth),
        )
    }
}

/**
 * Every device type's map for the current project, and the reverse index read from it (`surfaceUses.state`);
 * [SurfaceMapService.maps] is null before the first build.
 */
data class SurfaceMapsState(
    val projectId: Int,
    val frames: Map<String, SurfaceMapFrame>,
    val uses: SurfaceUsesFrame = SurfaceUsesFrame(projectId, emptyMap()),
)

/**
 * Keeps [maps] current: rebuilt off the input path, at most once per [debounceMs], whenever a
 * binding, an encoder page, health, a record's name or the project moves — each of which calls
 * [invalidate].
 *
 * Only the types in use are built: those [attachedTypes] names, those holding a binding in the
 * project, and those already built, so a type whose last binding goes, or which the next project
 * does not use, still gets the frame that empties it.
 */
class SurfaceMapService(
    private val bindingService: ControlSurfaceBindingService,
    private val projectIdProvider: () -> Int?,
    private val namesFor: (Int) -> SurfaceNames,
    /** The encoder page a job's encoder is labelled with on [deviceTypeKey]: the first attached device's. */
    private val encoderPageFor: (deviceTypeKey: String) -> EncoderBankSelection,
    private val types: () -> List<ControlSurfaceRegistry.DeviceTypeInfo> = { ControlSurfaceRegistry.allTypes },
    /** The device types attached now; null builds every type. */
    private val attachedTypes: () -> Set<String>? = { null },
    private val debounceMs: Long = 50,
) {
    private val logger = LoggerFactory.getLogger(SurfaceMapService::class.java)
    private val _maps = MutableStateFlow<SurfaceMapsState?>(null)
    val maps: StateFlow<SurfaceMapsState?> = _maps.asStateFlow()
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO + CoroutineName("SurfaceMapService")) {
            dirty.trySend(Unit)
            for (unit in dirty) {
                delay(debounceMs)
                runCatching { rebuild() }.onFailure { logger.warn("Surface map rebuild failed: {}", it.message) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Something a map reads moved; rebuild soon. */
    fun invalidate() {
        dirty.trySend(Unit)
    }

    /** Build now, on the caller's thread. */
    fun rebuild() {
        val projectId = projectIdProvider() ?: return
        val frames = buildFrames(projectId)
        val profiles = types().associateBy { it.typeKey }
        _maps.value = SurfaceMapsState(projectId, frames, SurfaceUses.build(projectId, frames, profiles::get))
    }

    /** [deviceTypeKey]'s map for [projectId], built now; null for a type the desk does not know. */
    fun frameFor(projectId: Int, deviceTypeKey: String): SurfaceMapFrame? {
        val profile = types().firstOrNull { it.typeKey == deviceTypeKey } ?: return null
        return SurfaceMaps.build(projectId, profile, bindingService, namesFor(projectId), encoderPageFor(deviceTypeKey))
    }

    fun buildFrames(projectId: Int): Map<String, SurfaceMapFrame> {
        val names = namesFor(projectId)
        val inUse = attachedTypes()?.let { attached ->
            attached + bindingService.list(projectId).map { it.deviceTypeKey } + bindingService.jobs(projectId).map { it.deviceTypeKey } +
                _maps.value?.frames?.keys.orEmpty()
        }
        return types().filter { inUse == null || it.typeKey in inUse }.associate { profile ->
            profile.typeKey to SurfaceMaps.build(projectId, profile, bindingService, names, encoderPageFor(profile.typeKey))
        }
    }
}
