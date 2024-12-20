package uk.me.cormack.lighting7.routes

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import uk.me.cormack.lighting7.state.State
import java.io.File

fun Application.configureRouting(state: State) {
    install(Resources)
    install(ContentNegotiation) {
        json()
    }
    routing {
        route("/api/rest") {
            routeApiRestLightsScript(state)
            routeApiRestLightsScene(state)
            routeApiRestLightsFixtures(state)
        }

        routeKotlinCompilerServer(state)

        staticFiles("/", File("/Users/chris/Development/Personal/lighting-react/dist/")) {
            default("index.html")
        }
    }
}
