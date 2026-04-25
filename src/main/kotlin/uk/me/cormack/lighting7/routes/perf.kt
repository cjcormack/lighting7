package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.dmx.ArtNetController
import uk.me.cormack.lighting7.midi.MidiDeviceRegistry
import uk.me.cormack.lighting7.perf.MidiLatencySnapshot
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestPerf(state: State) {
    route("/perf") {
        get<ArtNetRates> {
            val universes = state.show.fixtures.controllers
                .filterIsInstance<ArtNetController>()
                .map {
                    UniversePacketStats(
                        subnet = it.universe.subnet,
                        universe = it.universe.universe,
                        packetsPerSec = it.packetsPerSecond,
                        totalPackets = it.totalPacketsSent,
                    )
                }
            call.respond(ArtNetRatesResponse(windowSeconds = 30, universes = universes))
        }
        get<CueEditHistogram> {
            call.respond(state.cueEditLatencyTracker.snapshot())
        }
        get<MidiLatency> {
            call.respond(
                MidiLatencyResponse(
                    windowSeconds = 30,
                    histograms = state.midiLatencyTracker.snapshot(),
                    ports = state.midiRegistry.portCcRates(),
                ),
            )
        }
        post<MidiLatencyReset> {
            state.midiLatencyTracker.reset()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Resource("/artnet-rates")
data object ArtNetRates

@Resource("/cueedit-histogram")
data object CueEditHistogram

@Resource("/midi-latency")
data object MidiLatency

@Resource("/midi-latency/reset")
data object MidiLatencyReset

@Serializable
data class UniversePacketStats(
    val subnet: Int,
    val universe: Int,
    val packetsPerSec: Double,
    val totalPackets: Long,
)

@Serializable
data class ArtNetRatesResponse(
    val windowSeconds: Int,
    val universes: List<UniversePacketStats>,
)

@Serializable
data class MidiLatencyResponse(
    val windowSeconds: Int,
    val histograms: MidiLatencySnapshot,
    val ports: List<MidiDeviceRegistry.PortCcRates>,
)
