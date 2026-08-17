package uk.me.cormack.lighting7.auth

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import uk.me.cormack.lighting7.state.MdnsService

/**
 * Where a QR reset token can be redeemed: the address to encode in the QR, plus every
 * other address the same page answers on (shown as selectable text under the code, for
 * when the phone can't reach the first one).
 */
data class ResetUrls(val primary: String, val alternates: List<String>)

/**
 * Build the reset URLs for [rawToken] (multi-user-auth plan, Decision 12).
 *
 * Priority order:
 * 1. the request's own scheme + `Host` header, verbatim — an address the admin's browser
 *    just proved reachable, so a phone on the same LAN almost certainly can too;
 * 2. if that host is loopback (the admin is browsing `localhost`, or the Vite dev proxy),
 *    the mDNS name, then any site-local IPv4 — a QR pointing at `127.0.0.1` would resolve
 *    to the *phone*, which is the one failure mode this ordering exists to prevent;
 * 3. [ResetUrls.alternates] always carries the mDNS name and every site-local IPv4 that
 *    isn't already the primary.
 *
 * Ports differ by branch, deliberately. The `Host` URL keeps the header's own port,
 * because that is the port the admin's browser just loaded this SPA from — behind the Vite
 * dev proxy that's the proxy, which serves the reset page and forwards the API. The
 * fallbacks have no such evidence, so they use the port the request actually arrived on.
 */
fun ApplicationCall.buildResetUrls(rawToken: String): ResetUrls {
    val scheme = request.origin.scheme
    val path = "/reset/$rawToken"
    val port = request.origin.localPort

    val hostHeader = request.header(HttpHeaders.Host)?.trim()?.takeIf { it.isNotEmpty() }
    val hostUrl = hostHeader?.let { "$scheme://$it$path" }

    val mdnsUrl = "$scheme://${MdnsService.deriveServiceName()}.local:$port$path"
    val lanUrls = runCatching { MdnsService.pickLanAddresses() }
        .getOrDefault(emptyList())
        .map { "$scheme://${it.hostAddress}:$port$path" }

    val primary = when {
        hostUrl != null && !isLoopbackHost(hostHeader) -> hostUrl
        lanUrls.isNotEmpty() -> mdnsUrl
        // No LAN interface at all (a laptop with Wi-Fi off): the mDNS name would not
        // resolve either, so keep the loopback URL rather than inventing a worse one —
        // the admin can at least redeem it in another tab on the desk itself.
        else -> hostUrl ?: mdnsUrl
    }

    val alternates = (listOf(mdnsUrl) + lanUrls + listOfNotNull(hostUrl))
        .distinct()
        .filter { it != primary }

    return ResetUrls(primary, alternates)
}

/**
 * Whether a `Host` header names this machine to itself. Port is stripped first, and IPv6
 * literals lose their brackets — `[::1]:8413` is loopback just as much as `localhost` is.
 *
 * A missing header counts as loopback: with no evidence of a reachable address, the mDNS
 * and LAN fallbacks are strictly better guesses than a URL with no host in it.
 */
private fun isLoopbackHost(hostHeader: String?): Boolean {
    if (hostHeader == null) return true
    val bare = when {
        hostHeader.startsWith("[") -> hostHeader.substringAfter('[').substringBefore(']')
        // An unbracketed IPv6 literal has several colons and no port to strip; a hostname
        // or IPv4 has at most one, and it separates the port. Without this, `::1` would
        // strip down to the empty string and read as "not loopback" — the one spelling
        // that most needs to be caught.
        hostHeader.count { it == ':' } > 1 -> hostHeader
        else -> hostHeader.substringBefore(':')
    }
    return bare.equals("localhost", ignoreCase = true) ||
        bare.equals("::1", ignoreCase = true) ||
        bare == "0.0.0.0" ||
        bare.startsWith("127.")
}
