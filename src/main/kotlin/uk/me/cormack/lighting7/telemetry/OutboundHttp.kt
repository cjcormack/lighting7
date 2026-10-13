package uk.me.cormack.lighting7.telemetry

import io.ktor.client.HttpClientConfig
import io.sentry.ktorClient.SentryKtorClientPlugin

/**
 * An `http.client` span and a breadcrumb per request (Sentry plan D6). No trace headers go out —
 * `SentryRuntime` lists no propagation target — and a failed response is the caller's to judge,
 * not an issue.
 */
fun HttpClientConfig<*>.installSentry() {
    install(SentryKtorClientPlugin) {
        captureFailedRequests = false
    }
}
