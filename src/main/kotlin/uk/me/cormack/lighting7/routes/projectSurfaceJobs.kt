package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.request.*
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.history.historyLabel
import uk.me.cormack.lighting7.midi.BindingRefused
import uk.me.cormack.lighting7.midi.ControlSurfaceBindingService
import uk.me.cormack.lighting7.midi.ControlSurfaceRegistry
import uk.me.cormack.lighting7.midi.JobItem
import uk.me.cormack.lighting7.midi.JobItemKind
import uk.me.cormack.lighting7.midi.JobKind
import uk.me.cormack.lighting7.midi.JobSignal
import uk.me.cormack.lighting7.state.State

/**
 * Surface jobs (surface-setup plan D11): what each job region of a device type does, per project.
 *
 *   - `GET    /api/rest/projects/{projectId}/surface-jobs` — every job
 *   - `GET    /api/rest/projects/{projectId}/surface-jobs/{deviceTypeKey}/{region}?layer=`
 *   - `PUT    …/surface-jobs/{deviceTypeKey}/{region}?layer=` `{kind, items}` — the region's job, replacing what it holds
 *   - `DELETE …/surface-jobs/{deviceTypeKey}/{region}?layer=`
 *
 * `layer` absent is both layers. Every write goes through [ControlSurfaceBindingService], which
 * refuses by name (400 `SURFACE_JOB_INVALID`) and pushes the map on.
 */
internal fun Route.routeApiRestProjectSurfaceJobs(state: State) {
    val service = state.controlSurfaceBindingService

    get<ProjectSurfaceJobsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            call.respond(service.jobs(project.id.value).map { it.toDto() })
        }
    }

    get<ProjectSurfaceJobResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val job = service.jobAt(project.id.value, resource.deviceTypeKey, resource.region, resource.layer)
            if (job != null) call.respond(job.toDto())
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("No job on that region"))
        }
    }

    put<ProjectSurfaceJobResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val request = call.receive<PutSurfaceJobRequest>()
            val kind = JobKind.of(request.kind)
            historyLabel("Surface job · ${kind?.label ?: request.kind}")
            try {
                val job = service.putJob(project.id.value, resource.deviceTypeKey, resource.region, resource.layer, request.kind, request.items)
                call.respond(job.toDto())
            } catch (e: BindingRefused) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid job", code = e.code))
            }
        }
    }

    delete<ProjectSurfaceJobResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            historyLabel("Remove surface job")
            if (service.deleteJob(project.id.value, resource.deviceTypeKey, resource.region, resource.layer)) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("No job on that region"))
            }
        }
    }
}

@Resource("/{projectId}/surface-jobs")
data class ProjectSurfaceJobsResource(val projectId: String)

@Resource("/{deviceTypeKey}/{region}")
data class ProjectSurfaceJobResource(
    val parent: ProjectSurfaceJobsResource,
    val deviceTypeKey: String,
    val region: String,
    val layer: String? = null,
)

/** A region's job: its kind and its items in order, a gap as null. */
@Serializable
data class PutSurfaceJobRequest(val kind: String, val items: List<JobItem?> = emptyList())

/** One stored job. [items] is null, and [readable] false, for a job this build cannot read. */
@Serializable
data class SurfaceJobDto(
    val id: Int,
    val uuid: String,
    val deviceTypeKey: String,
    val region: String,
    val layer: String? = null,
    val kind: String,
    val items: List<JobItem?>? = null,
    val readable: Boolean = true,
)

internal fun ControlSurfaceBindingService.StoredJob.toDto() = SurfaceJobDto(
    id = id,
    uuid = uuid.toString(),
    deviceTypeKey = deviceTypeKey,
    region = region,
    layer = bank,
    kind = kindId,
    items = items,
    readable = kind != null && items != null,
)

/** One job kind as Editor B offers it (`GET /surface-job-kinds`). */
@Serializable
data class JobKindDto(
    val id: String,
    val label: String,
    val help: String,
    /** What an item is; null for a kind that holds none. */
    val item: JobItemKind? = null,
    /** The most items it holds; null for one a slot. */
    val maxItems: Int? = null,
    val follows: JobSignal? = null,
    /** The catalogue entries a function item may be. */
    val entries: List<String> = emptyList(),
)

@Resource("/surface-job-kinds")
class SurfaceJobKindsResource

/** Every job kind as `/surface-job-kinds` serves it. */
fun jobKindDtos(): List<JobKindDto> = JobKind.entries.map { JobKindDto(it.id, it.label, it.help, it.item, it.maxItems, it.follows, it.entries) }

/** `GET /api/rest/surface-job-kinds` — every job kind, static per build. Which regions each fits is on the profile. */
internal fun Route.routeApiRestSurfaceJobKinds() {
    get<SurfaceJobKindsResource> {
        call.respond(jobKindDtos())
    }
}

/** The kinds that fit each job region of [profile], by region id. */
internal fun jobKindsFitting(profile: ControlSurfaceRegistry.DeviceTypeInfo): Map<String, List<String>> =
    profile.jobRegions.associate { region -> region.id to JobKind.entries.filter { it.fits(region) }.map { it.id } }
