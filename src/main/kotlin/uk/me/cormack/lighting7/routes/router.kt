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
import uk.me.cormack.lighting7.auth.adminOnly
import uk.me.cormack.lighting7.auth.installAuthGate
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
        route("/api") {
            // The gates go on a transparent child, never on this `/api` node itself: Ktor's
            // `createChild` reuses a child whose selector compares equal, so `route("/api")` here
            // and `webSocket("/api")` in plugins/Sockets.kt are one and the same RoutingNode.
            // Interceptors installed directly on it would gate the socket too — 503 during boot,
            // when the socket is precisely how the client receives boot progress, and a 401 on an
            // unauthenticated upgrade instead of the 4401 close the client is written against.
            transparentChild("gated") {
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

                // After the warm-up gate, deliberately: a mid-boot 503 wins for exempt paths,
                // and an unauthenticated caller can't probe the readiness of gated routes.
                installAuthGate(state)

                route("/rest") {
                    routeApiRestAuth(state)
                    routeApiRestStatus(state)
                    routeApiRestProjects(state)
                    routeApiRestLightsFixtures(state)
                    routeApiRestFx(state)
                    routeApiRestFxDefinitions(state)
                    routeApiRestGroups(state)
                    routeApiRestLocate(state)
                    routeApiRestProgrammer(state)
                    routeApiRestAiChat(state)
                    routeApiRestControlSurfaceTypes(state)
                    routeApiRestPerf(state)
                    routeApiRestInstall(state)
                    routeApiRestUpdate(state)

                    // Admin territory: user management and password resets (Session 3), the
                    // install-level cloud-sync batch endpoints, and the GitHub OAuth flows —
                    // they carry the desk's git identity and remotes. The per-project sync and
                    // project import/export subtrees declare it at their own mount points.
                    adminOnly {
                        routeApiRestUsers(state)
                        routeApiRestCloudSync(state)
                        routeApiOAuthGitHub(state)
                    }
                }

                // Inside the gates rather than beside them: it compiles arbitrary Kotlin on a
                // LAN-reachable port, so an ungated editor would make the `/rest` gate decorative.
                routeScriptEditor(state)
            }
        }

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
 * GitHub OAuth callback (an external redirect that can't wait), install, cloud-sync, and the
 * auth routes (logging in must work while the show is still booting; the user lands on the
 * boot overlay afterwards, which is the right sequence).
 */
private fun isWarmupExempt(path: String): Boolean =
    path == "/api/rest/status" ||
        path == "/api/rest/install" ||
        path.startsWith("/api/rest/auth/") ||
        path.startsWith("/api/rest/oauth/") ||
        path.startsWith("/api/rest/cloud-sync/") ||
        // Nothing under /update touches the show — and a desk whose show *failed* to boot is
        // precisely when you most want to be able to install the fix.
        path.startsWith("/api/rest/update") ||
        // The editor's language services run on the embedded compiler and never look at the
        // show, so the gate has nothing to protect here — and one 503 costs more than a 503
        // normally does: the widget probes `/versions` once per page and, on any failure,
        // silently drops every editor on that page to read-only with highlighting off until
        // the page is reloaded. It is inside the gated subtree for its *auth* gate.
        path.startsWith("/api/script-editor")
