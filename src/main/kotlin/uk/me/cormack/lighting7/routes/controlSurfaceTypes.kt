package uk.me.cormack.lighting7.routes

import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.midi.BankButtonDescriptor
import uk.me.cormack.lighting7.midi.BankDefinition
import uk.me.cormack.lighting7.midi.ButtonDescriptor
import uk.me.cormack.lighting7.midi.ControlDescriptor
import uk.me.cormack.lighting7.midi.ControlSurfaceRegistry
import uk.me.cormack.lighting7.midi.SurfaceCatalogue
import uk.me.cormack.lighting7.midi.EncoderDescriptor
import uk.me.cormack.lighting7.midi.FaderDescriptor
import uk.me.cormack.lighting7.midi.JobRegion
import uk.me.cormack.lighting7.midi.SlotRole
import uk.me.cormack.lighting7.midi.SurfaceLayout
import uk.me.cormack.lighting7.state.State

@Serializable
@Resource("/control-surface-types")
class ControlSurfaceTypesResource

@Serializable
data class BankDto(val id: String, val name: String)

@Serializable
sealed class ControlDescriptorDto {
    abstract val controlId: String
    abstract val label: String

    @Serializable
    @SerialName("fader")
    data class Fader(
        override val controlId: String,
        override val label: String,
        val cc: Int,
        val channel: Int,
        val hasMotor: Boolean,
        val motorCc: Int?,
        val touchNote: Int?,
        val touchCc: Int?,
        val resolution: String,
    ) : ControlDescriptorDto()

    @Serializable
    @SerialName("encoder")
    data class Encoder(
        override val controlId: String,
        override val label: String,
        val cc: Int,
        val channel: Int,
        val ringCc: Int?,
        val ringStyle: String,
        val pushNote: Int?,
        val pushLed: String,
    ) : ControlDescriptorDto()

    @Serializable
    @SerialName("button")
    data class Button(
        override val controlId: String,
        override val label: String,
        val note: Int,
        val channel: Int,
        val ledFeedback: String,
    ) : ControlDescriptorDto()

    @Serializable
    @SerialName("bankButton")
    data class BankButton(
        override val controlId: String,
        override val label: String,
        val note: Int?,
        val programChange: Int?,
        val channel: Int,
        val bankId: String,
    ) : ControlDescriptorDto()
}

/** One slot of a job region: the control each of its roles is. */
@Serializable
data class JobSlotDto(val roles: Map<SlotRole, String>)

/** A region a job fills (surface-setup plan D11), and the job kinds that fit it. */
@Serializable
data class JobRegionDto(val id: String, val name: String, val slots: List<JobSlotDto>, val kinds: List<String>)

/** One control's place in the panel picture. */
@Serializable
data class LayoutCellDto(val controlId: String, val col: Int, val row: Int)

@Serializable
data class LayoutRegionDto(val name: String, val columns: Int, val cells: List<LayoutCellDto>)

/** The panel picture. Absent when the profile declares none — the client falls back to a table. */
@Serializable
data class SurfaceLayoutDto(val regions: List<LayoutRegionDto>)

@Serializable
data class ControlSurfaceTypeDto(
    val typeKey: String,
    val vendor: String?,
    val product: String?,
    val portPattern: String?,
    val className: String,
    val controls: List<ControlDescriptorDto>,
    val banks: List<BankDto>,
    val jobRegions: List<JobRegionDto> = emptyList(),
    val layout: SurfaceLayoutDto? = null,
)

private fun ControlDescriptor.toDto(): ControlDescriptorDto = when (this) {
    is FaderDescriptor -> ControlDescriptorDto.Fader(
        controlId = controlId,
        label = label,
        cc = cc,
        channel = channel,
        hasMotor = hasMotor,
        motorCc = motorCc,
        touchNote = touchNote,
        touchCc = touchCc,
        resolution = resolution.name,
    )
    is EncoderDescriptor -> ControlDescriptorDto.Encoder(
        controlId = controlId,
        label = label,
        cc = cc,
        channel = channel,
        ringCc = ringCc,
        ringStyle = ringStyle.name,
        pushNote = pushNote,
        pushLed = pushLed.name,
    )
    is ButtonDescriptor -> ControlDescriptorDto.Button(
        controlId = controlId,
        label = label,
        note = note,
        channel = channel,
        ledFeedback = ledFeedback.name,
    )
    is BankButtonDescriptor -> ControlDescriptorDto.BankButton(
        controlId = controlId,
        label = label,
        note = note,
        programChange = programChange,
        channel = channel,
        bankId = bankId,
    )
}

private fun BankDefinition.toDto(): BankDto = BankDto(id = id, name = name)

private fun JobRegion.toDto(kinds: List<String>): JobRegionDto =
    JobRegionDto(id = id, name = name, slots = slots.map { JobSlotDto(it.controls) }, kinds = kinds)

private fun SurfaceLayout.toDto(): SurfaceLayoutDto = SurfaceLayoutDto(
    regions = regions.map { region ->
        LayoutRegionDto(
            name = region.name,
            columns = region.columns,
            cells = region.cells.map { LayoutCellDto(it.controlId, it.col, it.row) },
        )
    },
)

/**
 * `GET /api/rest/control-surface-types` — list all registered control-surface device
 * profiles with their controls and banks. Mirrors `/api/rest/fixture-types`.
 */
@Suppress("UNUSED_PARAMETER")
internal fun Route.routeApiRestControlSurfaceTypes(state: State) {
    get<ControlSurfaceTypesResource> {
        call.respond(ControlSurfaceRegistry.allTypes.map { info ->
            ControlSurfaceTypeDto(
                typeKey = info.typeKey,
                vendor = info.vendor.ifEmpty { null },
                product = info.product.ifEmpty { null },
                portPattern = info.portPattern.ifEmpty { null },
                className = info.className,
                controls = info.controls.map { it.toDto() },
                banks = info.banks.map { it.toDto() },
                jobRegions = jobKindsFitting(info).let { fits -> info.jobRegions.map { it.toDto(fits.getValue(it.id)) } },
                layout = info.layout?.toDto(),
            )
        })
    }
}

@Resource("/surface-functions")
class SurfaceFunctionsResource

/**
 * `GET /api/rest/surface-functions` — the function catalogue (surface-setup plan D15): every function
 * an editor offers, from the specs, with its words and parameters. Static per build.
 */
internal fun Route.routeApiRestSurfaceFunctions() {
    get<SurfaceFunctionsResource> {
        call.respond(SurfaceCatalogue.entries)
    }
}
