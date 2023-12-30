package uk.me.cormack.lighting7.routes

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import uk.me.cormack.lighting7.state.State

val kotlinCompilerServerClient = HttpClient(CIO) {
    install(Logging)
    install(ContentEncoding) {
        deflate(1.0F)
        gzip(0.9F)
    }
    defaultRequest {
        url("http://localhost:8321/")
    }
}

internal fun Route.routeKotlinCompilerServer(state: State) {
    route("/kotlin-compiler-server/api/{version}/compiler/{action}") {
        handleKotlinCompilerServerRequest()
    }

    route("/kotlin-compiler-server/versions") {
        handleKotlinCompilerServerRequest()
    }
}

private fun Route.handleKotlinCompilerServerRequest() {
    handle {
        val path = call.request.path().removePrefix("/kotlin-compiler-server/")

        val action = call.parameters["action"]
        if (action == "run") {
            // TODO handle locally and create a compatible response
            proxyKotlinCompileServerRequest(path)
        } else {
            proxyKotlinCompileServerRequest(path)
        }
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.proxyKotlinCompileServerRequest(
    path: String,
) {
    val requestBody = call.receive<ByteArray>()

    val resp = kotlinCompilerServerClient.request {
        method = call.request.httpMethod
        url {
            this.appendPathSegments(path)
            this.parameters.appendAll(call.request.queryParameters)
        }
        this.contentType(call.request.contentType())
        this.setBody(requestBody)
    }

    call.response.status(resp.status)
    resp.contentType()

    val body = resp.body<String>()

    call.respondText(body, resp.contentType())
}
