package uk.me.cormack.lighting7.plugins

import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.routes.AddFxRequest
import uk.me.cormack.lighting7.routes.AddFxResponse
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitEachOf
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import kotlin.test.assertNotEquals

/**
 * A socket that stays open across a project switch follows the incoming show: the programmer and
 * speed masters are resynced as it arrives, and its programmer, effect, park and tempo frames reach
 * the socket. Every assertion names something only the incoming show has — its own fixture, its
 * own master 1 — so no frame from the outgoing show or from the resync can satisfy it.
 */
class ShowFollowingSubscriptionsTest : RouteIntegrationTest() {

    @Test
    fun `a socket open across a project switch follows the new show's programmer, effects, parks and tempo`() = testApplication {
        mountTestApp(state)
        val other = seedMinimalProject(state, projectName = "Other")
        transaction(state.database) {
            val project = DaoProject[other]
            DaoFixturePatch.new {
                this.project = project
                universeConfig = DaoUniverseConfig.find { DaoUniverseConfigs.project eq project.id }.single()
                fixtureTypeKey = "hex"
                key = "other-hex"
                displayName = "other-hex"
                startChannel = 1
                sortOrder = 1
            }
        }
        val rest = jsonClient()
        val outgoingMaster1 = state.show.speedMasterBank.master1Uuid()

        createWsClient().webSocket("/api") {
            withTimeout(20_000) {
                awaitEachOf(ProgrammerStateOutMessage::class, ProvenanceStateOutMessage::class, SpeedMastersStateOutMessage::class)

                state.projectManager.switchProject(other)
                awaitEachOf(ProgrammerStateOutMessage::class, SpeedMastersStateOutMessage::class)
                val show = state.show

                // The resync's provenance may already hold the first write; the second can only
                // arrive through the live provenance subscription.
                for (attribute in listOf("dimmer", "uv")) {
                    sendSerialized<InMessage>(ProgrammerSetInMessage("fixture", "other-hex", attribute, "200"))
                    awaitOfType<ProvenanceStateOutMessage> { frame ->
                        frame.entries.any { it.targetKey == "other-hex" && it.attribute == attribute }
                    }
                }

                val fxId = rest.post("/api/rest/fx/add") {
                    contentType(ContentType.Application.Json)
                    setBody(AddFxRequest(fxType = "Wave", fixtureKey = "other-hex", attribute = "dimmer"))
                }.body<AddFxResponse>().fxId
                awaitOfType<FxStateOutMessage> { frame ->
                    frame.activeFx.any { it.id == fxId && it.targetKey == "other-hex" }
                }

                show.parkManager.park(0, 9, 77u)
                awaitOfType<ParkStateOutMessage> { snapshot -> snapshot.channels.any { it.channel == 9 } }

                val master1 = show.speedMasterBank.master1Uuid()
                assertNotEquals(outgoingMaster1, master1, "each project's master 1 is its own")
                show.speedMasterBank.setBpm(null, 133.0, SpeedMasterSource.MANUAL)
                awaitOfType<SpeedMasterChangedOutMessage> { it.bpm == 133.0 }
                awaitOfType<SpeedMasterBeatOutMessage>(maxFrames = 1_000) { it.masterUuid == master1.toString() }
            }
        }
    }
}
