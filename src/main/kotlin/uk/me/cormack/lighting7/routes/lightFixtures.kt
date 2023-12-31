package uk.me.cormack.lighting7.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.HueFixture
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestLightsFixtures(state: State) {
    route("/fixture") {
        get("/list") {
            val fixtures = state.show.fixtures.fixtures.map {
                when (it) {
                    is DmxFixture -> {
                        val channels = it.channelDescriptions().map { channel ->
                            DmxFixtureChannelDetails(channel.key, channel.value)
                        }

                        DmxFixtureDetails(
                            it.fixtureName, it.key, it.typeKey, it.universe.universe, channels
                        )
                    }
                    is HueFixture -> {
                        HueFixtureDetails(it.fixtureName, it.key, it.typeKey)
                    }
                }
            }
            call.respond(fixtures)
        }
    }
}

@Serializable
sealed interface FixtureDetails {
    val name: String
    val key: String
    val typeKey: String
}

@Serializable
data class DmxFixtureChannelDetails(
    val channelNo: Int,
    val description: String,
)

@Serializable
data class DmxFixtureDetails(
    override val name: String,
    override val key: String,
    override val typeKey: String,
    val universe: Int,
    val channels: List<DmxFixtureChannelDetails>,

): FixtureDetails

@Serializable
data class HueFixtureDetails(
    override val name: String,
    override val key: String,
    override val typeKey: String,
): FixtureDetails
