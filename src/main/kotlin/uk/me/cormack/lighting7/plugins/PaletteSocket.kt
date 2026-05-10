package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.parseExtendedColour

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class PaletteInMessage : InMessage()

@Serializable
@SerialName("setPalette")
data class SetPaletteInMessage(val colours: List<String>) : PaletteInMessage()

@Serializable
@SerialName("setPaletteColour")
data class SetPaletteColourInMessage(val index: Int, val colour: String) : PaletteInMessage()

@Serializable
@SerialName("addPaletteColour")
data class AddPaletteColourInMessage(val colour: String) : PaletteInMessage()

@Serializable
@SerialName("removePaletteColour")
data class RemovePaletteColourInMessage(val index: Int) : PaletteInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class PaletteOutMessage : OutMessage()

@Serializable
@SerialName("paletteChanged")
data class PaletteChangedOutMessage(
    val palette: List<String>,
) : PaletteOutMessage()

@Serializable
@SerialName("stackPalettesChanged")
data class StackPalettesChangedOutMessage(
    val stackPalettes: Map<Int, List<String>>,
) : PaletteOutMessage()

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handlePalette(scope: SocketScope, message: PaletteInMessage) {
    val engine = scope.state.show.fxEngine
    when (message) {
        is SetPaletteInMessage ->
            engine.setPalette(message.colours.map { parseExtendedColour(it) })
        is SetPaletteColourInMessage ->
            engine.setPaletteColour(message.index, parseExtendedColour(message.colour))
        is AddPaletteColourInMessage ->
            engine.addPaletteColour(parseExtendedColour(message.colour))
        is RemovePaletteColourInMessage ->
            engine.removePaletteColour(message.index)
    }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

fun setupPaletteSubscriptions(scope: SocketScope) {
    val engine = scope.state.show.fxEngine

    scope.subscribe(engine.paletteFlow) { palette ->
        scope.send(PaletteChangedOutMessage(palette.map { it.toSerializedString() }))
    }

    scope.subscribe(engine.stackPaletteFlow) { stackPalettes ->
        scope.send(StackPalettesChangedOutMessage(
            stackPalettes.mapValues { (_, colours) -> colours.map { it.toSerializedString() } },
        ))
    }
}
