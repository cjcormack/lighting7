package uk.me.cormack.lighting7.midi

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.exposed.v1.core.eq
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoControlSurfaceBindings
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSurfaceJob
import java.util.UUID

private val logger = LoggerFactory.getLogger("SurfaceStripJobs")

/**
 * The one translate from channel strips to jobs: database step 17
 * (`state/SurfaceJobsMigration.kt`) and the archive importer both call [convert], so a database and an
 * archive written before jobs arrive at the same rows.
 *
 * A strip is a binding row at a strip id (`strip-3`, `strip-master`) naming a group or a fixture.
 * Per device type, layer and job region, the strips become **one job**: *Groups as channel strips*
 * where most name a group, else *Fixtures as channel strips*, each strip its slot's item and an empty
 * strip a gap. A strip naming the other kind becomes what it derived, as bindings of its own on its
 * controls — the fader and flash on the dimmer, the select button, the encoder on the encoder page —
 * unless a binding of the control's own already beat it there. A strip row whose subject cannot be
 * read is dropped, since nothing could resolve it. Its takeover policy is not carried: a job's
 * controls take their own default.
 *
 * Deterministic: a job's uuid is made from its strips' and a binding's from its strip and control, so
 * two desks translating one archive write the same records.
 */
object SurfaceStripJobs {
    /** The fader each strip id led with, by device type: the X-Touch Compact is the only profile with strips. */
    private val STRIP_FADERS: Map<String, Map<String, String>> = mapOf(
        "x-touch-compact-standard" to (1..8).associate { "strip-$it" to "fader-$it" } + ("strip-master" to "fader-9"),
    )

    /** One strip row as stored. */
    data class StripRow(val uuid: UUID, val deviceTypeKey: String, val controlId: String, val bank: String?, val payload: String)

    /** A slot a binding of its own holds: `(device type, control, part, layer)`. */
    data class Slot(val deviceTypeKey: String, val controlId: String, val part: ControlPart, val bank: String?)

    data class NewJob(val uuid: UUID, val deviceTypeKey: String, val region: String, val bank: String?, val kind: JobKind, val items: List<JobItem?>)

    data class NewBinding(val uuid: UUID, val slot: Slot, val binding: Binding)

    /** What the translate does to one project's rows. */
    data class Plan(
        val jobs: List<NewJob> = emptyList(),
        val bindings: List<NewBinding> = emptyList(),
        /** The strip rows the plan replaces, and the ones it drops unread. */
        val removed: List<UUID> = emptyList(),
        val unreadable: List<StripRow> = emptyList(),
    )

    /** Whether `(deviceTypeKey, controlId)` is a strip id. */
    fun isStrip(deviceTypeKey: String, controlId: String): Boolean = STRIP_FADERS[deviceTypeKey]?.containsKey(controlId) == true

    /** The plan for one project's [strips], beside the slots its own bindings hold. Pure. */
    fun plan(strips: List<StripRow>, own: Set<Slot>): Plan {
        val unreadable = mutableListOf<StripRow>()
        class Placed(val row: StripRow, val region: JobRegion, val slot: Int, val subject: Subject)
        val placed = strips.mapNotNull { row ->
            val profile = ControlSurfaceRegistry.typeFor(row.deviceTypeKey)
            val fader = STRIP_FADERS[row.deviceTypeKey]?.get(row.controlId)
            val place = fader?.let { profile?.placeOf(it, ControlPart.MAIN) }
            val region = place?.let { profile?.jobRegion(it.region) }
            val subject = subjectOf(row.payload)
            if (place == null || region == null || subject == null) {
                unreadable += row
                null
            } else {
                Placed(row, region, place.slot, subject)
            }
        }
        val jobs = mutableListOf<NewJob>()
        val bindings = mutableListOf<NewBinding>()
        for ((key, group) in placed.groupBy { Triple(it.row.deviceTypeKey, it.row.bank, it.region.id) }) {
            val (deviceTypeKey, bank, _) = key
            val region = group.first().region
            val groups = group.count { it.subject is Subject.Group }
            val kind = if (groups * 2 >= group.size) JobKind.GROUPS else JobKind.FIXTURES
            val fits = { p: Placed -> if (kind == JobKind.GROUPS) p.subject is Subject.Group else p.subject is Subject.Fixture }
            val items = arrayOfNulls<JobItem>(region.slots.size)
            for (p in group.sortedBy { it.row.uuid }) {
                if (fits(p)) {
                    if (items[p.slot] == null) items[p.slot] = itemOf(p.subject)
                    continue
                }
                for ((role, controlId) in region.slots[p.slot].controls) {
                    val binding = asOwn(role, p.subject) ?: continue
                    val slot = Slot(deviceTypeKey, controlId, role.part, bank)
                    val beaten = slot in own || (bank != null && slot.copy(bank = null) in own)
                    if (beaten) continue
                    bindings += NewBinding(uuidOf("surface-strip:${p.row.uuid}:$controlId"), slot, binding)
                }
            }
            val held = group.filter(fits).map { it.row.uuid.toString() }.sorted()
            if (held.isNotEmpty()) {
                jobs += NewJob(
                    uuidOf("surface-job:" + held.joinToString(",")), deviceTypeKey, region.id, bank, kind,
                    items.toList().dropLastWhile { it == null },
                )
            }
        }
        return Plan(jobs, bindings, strips.map { it.uuid }, unreadable)
    }

    /**
     * Translate [project]'s strip rows into jobs, in the caller's transaction, and answer what was
     * done. A project with no strip row is left alone.
     */
    fun convert(project: DaoProject): Plan {
        val rows = DaoControlSurfaceBinding.find { DaoControlSurfaceBindings.project eq project.id }.toList()
        val (strips, own) = rows.partition { isStrip(it.deviceTypeKey, it.controlId) }
        if (strips.isEmpty()) return Plan()
        val plan = plan(
            strips.map { StripRow(it.uuid, it.deviceTypeKey, it.controlId, it.bank, it.targetPayload) },
            own.mapTo(HashSet()) { Slot(it.deviceTypeKey, it.controlId, ControlPart.entries.firstOrNull { p -> p.name == it.part } ?: ControlPart.MAIN, it.bank) },
        )
        strips.forEach { it.delete() }
        val existingJobs = project.surfaceJobs.toList()
        for (job in plan.jobs) {
            val row = existingJobs.firstOrNull { it.deviceTypeKey == job.deviceTypeKey && it.region == job.region && it.bank == job.bank }
                ?: DaoSurfaceJob.new {
                    this.project = project
                    uuid = job.uuid
                    deviceTypeKey = job.deviceTypeKey
                    region = job.region
                    bank = job.bank
                }
            row.kind = job.kind.id
            row.items = encodeJobItems(job.items)
        }
        for (new in plan.bindings) {
            DaoControlSurfaceBinding.new {
                this.project = project
                uuid = new.uuid
                deviceTypeKey = new.slot.deviceTypeKey
                controlId = new.slot.controlId
                part = new.slot.part.name
                bank = new.slot.bank
                targetType = new.binding.functionId
                targetPayload = new.binding.encodePayload()
            }
        }
        for (job in plan.jobs) {
            logger.info(
                "Surface jobs: '{}' — {} on {} {} become {} ({} items)",
                project.name, job.deviceTypeKey, job.region, job.bank?.let { "layer $it" } ?: "both layers", job.kind.label, job.items.count { it != null },
            )
        }
        for (new in plan.bindings) {
            logger.info("Surface jobs: '{}' — {} {} keeps its strip's binding as its own: {}", project.name, new.slot.controlId, new.slot.part, new.binding.functionId)
        }
        for (row in plan.unreadable) {
            logger.warn("Surface jobs: '{}' — strip row {} on {} named nothing this build can read and is dropped", project.name, row.uuid, row.controlId)
        }
        return plan
    }

    /** A strip's subject from either stored shape: the grammar's `{does: {type: strip}, on}`, or the union's `{type: strip, target}`. */
    private fun subjectOf(payload: String): Subject? {
        val json = runCatching { BindingJson.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
        val does = json["does"] as? JsonObject
        val subject = if (does != null) {
            if ((does["type"] as? JsonPrimitive)?.contentOrNull != "strip") return null
            json["on"]?.let { runCatching { BindingJson.decodeFromJsonElement(Subject.serializer(), it) }.getOrNull() }
        } else {
            if ((json["type"] as? JsonPrimitive)?.contentOrNull != "strip") return null
            val target = json["target"] as? JsonObject ?: return null
            val key = (target["key"] as? JsonPrimitive)?.contentOrNull ?: return null
            when ((target["type"] as? JsonPrimitive)?.contentOrNull) {
                "group" -> Subject.Group(key)
                "fixture" -> Subject.Fixture(key)
                else -> null
            }
        }
        return subject?.takeIf { it is Subject.Group || it is Subject.Fixture }
    }

    private fun itemOf(subject: Subject): JobItem? = when (subject) {
        is Subject.Group -> JobItem.Group(subject.key)
        is Subject.Fixture -> JobItem.Fixture(subject.key)
        else -> null
    }

    /** What a strip's [role] derived, as a binding of the control's own: the encoder follows the page. */
    private fun asOwn(role: SlotRole, on: Subject): Binding? = when (role) {
        SlotRole.FADER -> Binding(Does.Level(STRIP_FADER_ATTRIBUTE), on)
        SlotRole.SELECT -> Binding(Does.Select, on)
        SlotRole.ENCODER -> Binding(Does.PageLevel, on)
        SlotRole.FLASH -> Binding(Does.Flash(STRIP_FADER_ATTRIBUTE), on)
        SlotRole.BUTTON, SlotRole.PUSH -> null
    }

    private fun uuidOf(seed: String): UUID = UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8))
}
