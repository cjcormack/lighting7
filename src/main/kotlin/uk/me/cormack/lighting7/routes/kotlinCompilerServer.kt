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
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.state.optionalString

private const val DEFAULT_COMPILER_SERVER_URL = "http://localhost:8321/"

internal fun Route.routeKotlinCompilerServer(state: State) {
    val baseUrl = state.config.optionalString("compilerServer.url") ?: DEFAULT_COMPILER_SERVER_URL

    val client = HttpClient(CIO) {
        install(Logging)
        install(ContentEncoding) {
            deflate(1.0F)
            gzip(0.9F)
        }
        defaultRequest {
            url(baseUrl)
        }
    }

    route("/kotlin-compiler-server/api/{version}/compiler/{action}") {
        handleKotlinCompilerServerRequest(client)
    }

    route("/kotlin-compiler-server/versions") {
        handleKotlinCompilerServerRequest(client)
    }
}

private fun Route.handleKotlinCompilerServerRequest(client: HttpClient) {
    handle {
        val path = call.request.path().removePrefix("/kotlin-compiler-server/")
        proxyKotlinCompileServerRequest(client, call, path)
    }
}

private suspend fun proxyKotlinCompileServerRequest(
    client: HttpClient,
    call: RoutingCall,
    path: String,
) {
    val requestBody = call.receive<ByteArray>()

    val resp = client.request {
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
