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
import uk.me.cormack.lighting7.midi.EncoderDescriptor
import uk.me.cormack.lighting7.midi.FaderDescriptor
import uk.me.cormack.lighting7.state.State

@Serializable
@Resource("/controlSurfaceTypes")
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
        val note: Int,
        val channel: Int,
        val bankId: String,
    ) : ControlDescriptorDto()
}

@Serializable
data class ControlSurfaceTypeDto(
    val typeKey: String,
    val vendor: String?,
    val product: String?,
    val portPattern: String?,
    val className: String,
    val controls: List<ControlDescriptorDto>,
    val banks: List<BankDto>,
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
        channel = channel,
        bankId = bankId,
    )
}

private fun BankDefinition.toDto(): BankDto = BankDto(id = id, name = name)

/**
 * `GET /api/rest/controlSurfaceTypes` — list all registered control-surface device
 * profiles with their controls and banks. Mirrors `/api/rest/fixture/types`.
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
            )
        })
    }
}
