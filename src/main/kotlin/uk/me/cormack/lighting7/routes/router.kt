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
            routeApiRestScript(state)
            routeApiRestScene(state)
            routeApiRestFixtures(state)
        }

        // Static plugin. Try to access `/static/index.html
        staticFiles("/", File("/Users/chris/Development/Personal/lighting-react/build/"))
    }
}
