package uk.me.cormack.lighting7.testsupport

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import uk.me.cormack.lighting7.plugins.OutMessage

/**
 * Build an HTTP client wired for both JSON content-negotiation and WebSockets, using the
 * shared [TestJson]. Mirrors the production server's encoding so round-trip tests don't
 * silently transit through a different codec.
 */
fun ApplicationTestBuilder.createWsClient(): HttpClient = createClient {
    install(ContentNegotiation) { json(TestJson) }
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(TestJson)
    }
}

/**
 * Read inbound frames until one deserializes as [T]. The server fans out an initial-state
 * burst on connect (channelMapping / fxState / palette / beatSync — see plugins/Sockets.kt);
 * tests use this to skip past those and wait for the message they actually care about.
 */
suspend inline fun <reified T : OutMessage> DefaultClientWebSocketSession.awaitOfType(
    maxFrames: Int = 100,
): T {
    repeat(maxFrames) {
        val msg = receiveDeserialized<OutMessage>()
        if (msg is T) return msg
    }
    error("Never saw ${T::class.simpleName} after $maxFrames frames")
}
