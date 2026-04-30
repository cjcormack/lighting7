package uk.me.cormack.lighting7.sync.auth.oauth

import kotlinx.serialization.json.Json

/**
 * Shared kotlinx.serialization config for the OAuth subsystem — relaxed about unknown
 * keys so GitHub adding response fields doesn't break us, and dropping nulls so the
 * stored identity blob and form bodies stay tidy.
 *
 * Used by [OAuthGitHubClient] (HTTP requests + manual decode for two-shape responses)
 * and [OAuthTokenStore] (the persisted identity blob).
 */
internal val OAUTH_JSON: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
