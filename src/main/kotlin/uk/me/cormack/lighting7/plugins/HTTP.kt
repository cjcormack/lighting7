package uk.me.cormack.lighting7.plugins

import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureHTTP() {
    install(OpenApi) {
        info {
            title = "Lighting7 API"
            version = "0.0.1"
            description = "REST API for controlling DMX fixtures, Philips Hue, scenes, effects, and show management"
        }
    }

    routing {
        route("/api.json") {
            openApi()
        }
        route("/openapi") {
            swaggerUI("/api.json")
        }
    }
}
