package uk.me.cormack.lighting7.routes

import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.HueFixture
import uk.me.cormack.lighting7.state.State

internal fun Fixture.details(): FixtureDetails {
    return when (this) {
        is DmxFixture -> {
            val channels = this.channelDescriptions().map { channel ->
                DmxFixtureChannelDetails(channel.key, channel.value)
            }

            DmxFixtureDetails(
                this.fixtureName, this.key, this.typeKey, this.universe.universe, channels
            )
        }
        is HueFixture -> {
            HueFixtureDetails(this.fixtureName, this.key, this.typeKey)
        }
    }
}

internal fun Route.routeApiRestLightsFixtures(state: State) {
    route("/fixture") {
        get("/list") {
            call.respond(state.show.fixtures.fixtures.map(Fixture::details))
        }

        get<FixtureKey> {
            call.respond(state.show.fixtures.fixture<Fixture>(it.key).details())
        }
    }
}

@Resource("/{key}")
data class FixtureKey(val key: String)

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
