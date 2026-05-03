package uk.me.cormack.lighting7.routes

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.state.optionalString
import java.io.File

fun Application.configureRouting(state: State) {
    install(Resources)
    install(ContentNegotiation) {
        json()
    }
    routing {
        route("/api/rest") {
            routeApiRestProjects(state)
            routeApiRestLightsFixtures(state)
            routeApiRestFx(state)
            routeApiRestFxDefinitions(state)
            routeApiRestGroups(state)
            routeApiRestAiChat(state)
            routeApiRestControlSurfaceTypes(state)
            routeApiRestPerf(state)
            routeApiRestInstall(state)
            routeApiRestCloudSync(state)
            routeApiOAuthGitHub(state)
        }

        routeKotlinCompilerServer(state)

        // Frontend bundle: prefer a configured on-disk dist/ for dev, otherwise serve the
        // copy baked into the JAR by Gradle's copyFrontend task.
        val configuredStaticPath = state.config.optionalString("frontend.staticPath")
        if (configuredStaticPath != null) {
            staticFiles("/", File(configuredStaticPath)) {
                default("index.html")
            }
        } else {
            staticResources("/", "static") {
                default("index.html")
            }
        }
    }
}
