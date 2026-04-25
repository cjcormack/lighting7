package uk.me.cormack.lighting7.routes

import io.ktor.resources.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.dmx.ArtNetController
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
    }
}

@Resource("/artnet-rates")
data object ArtNetRates

@Resource("/cueedit-histogram")
data object CueEditHistogram

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
