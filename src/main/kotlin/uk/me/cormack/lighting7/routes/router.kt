package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.state.optionalString
import java.io.File

// `Route.intercept` is deprecated in favour of route-scoped plugins, but responding + `finish()`
// remains the cleanest way to short-circuit a subtree; a plugin `onCall` can't cleanly halt the
// pipeline before the matched handler runs.
@Suppress("DEPRECATION")
fun Application.configureRouting(state: State) {
    install(Resources)
    install(ContentNegotiation) {
        json()
    }
    routing {
        route("/api/rest") {
            // Readiness gate for server-first boot: until the show has finished starting
            // (fixtures loaded, FX engine running), show-dependent routes would either throw
            // "Show not initialized" or serve a half-initialised show (empty fixtures). Short-
            // circuit them with 503 + the current boot status. Gating on `isShowReady` — not mere
            // Show-object existence — closes the window where routes served an empty rig mid-init,
            // and still keeps route tests (which call `initializeShow()` + `start()` directly)
            // working, since `start()` sets the readiness flag. Show-independent, time-sensitive
            // routes are exempt so e.g. an in-flight GitHub OAuth callback isn't rejected mid-boot.
            intercept(ApplicationCallPipeline.Plugins) {
                if (!state.isShowReady && !isWarmupExempt(call.request.path())) {
                    call.respond(HttpStatusCode.ServiceUnavailable, state.bootProgress.current)
                    finish()
                }
            }

            routeApiRestStatus(state)
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

/**
 * Routes exempt from the server-first readiness gate: the boot-status endpoint (clients poll it
 * to drive the loading bar) and routes that don't touch the show but are time-sensitive — the
 * GitHub OAuth callback (an external redirect that can't wait), install, and cloud-sync.
 */
private fun isWarmupExempt(path: String): Boolean =
    path == "/api/rest/status" ||
        path == "/api/rest/install" ||
        path.startsWith("/api/rest/oauth/") ||
        path.startsWith("/api/rest/cloud-sync/")
