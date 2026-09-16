package uk.me.cormack.lighting7.launcher

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * *Open Screen 1* / *Open Screen 2* — the two tray items that launch the theatre desk's two
 * chromeless browser windows (multi-screen plan §3.6, D13).
 *
 * **Windows only**, and the reason is browser flags, not taste: `--app=` is a Chromium flag, so
 * Edge and Chrome have it and Safari has none. The macOS tray deliberately gains nothing rather
 * than half a feature — the Safari route to a chromeless desk screen is *Add to Dock* plus macOS's
 * own full screen, which the launcher cannot drive, and whether Safari will hold two Dock apps of
 * one origin with two `?window=` start URLs is desk check S2.7. `FU-LAUNCHER-SCREEN-POSITION`
 * covers both that and the `--window-position` this deliberately omits: the two screens open
 * wherever the browser last put them, and dragging one across is the operator's job for now.
 *
 * **The URL is always `http://localhost:8413/`**, never the `.local` LAN name, and that is
 * load-bearing rather than a convenience. Installation, Keyboard Lock and the Window Management
 * API are all secure-context features; the desk serves plain HTTP, and `localhost` is the one
 * potentially-trustworthy origin it has. A desk screen opened at the LAN URL silently loses all
 * three (see `docs/desk-screens.md`).
 */
object DeskScreens {

    /** The two screens the tray offers, by the names they announce themselves under. */
    val SCREEN_NAMES: List<String> = listOf("Screen 1", "Screen 2")

    /**
     * Edge first, then Chrome — the order §11 settled on, because the Windows desk has Edge by
     * default and a second browser is the operator's own choice. Both 64- and 32-bit Program
     * Files, plus Chrome's per-user install under `%LOCALAPPDATA%`, which is where a Chrome
     * installed without admin rights lands.
     */
    fun browserCandidates(env: (String) -> String? = System::getenv): List<Path> {
        val roots = listOfNotNull(
            env("ProgramFiles(x86)"),
            env("ProgramFiles"),
            env("LOCALAPPDATA"),
        )
        val relative = listOf(
            "Microsoft/Edge/Application/msedge.exe",
            "Google/Chrome/Application/chrome.exe",
        )
        // Browser-major rather than root-major: an installed Edge in *any* root beats a Chrome
        // in the first one.
        return relative.flatMap { exe -> roots.map { root -> Paths.get(root, *exe.split("/").toTypedArray()) } }
    }

    /** The first candidate that exists, or null — which is what makes the tray items absent rather than dead. */
    fun findBrowser(
        candidates: List<Path> = browserCandidates(),
        exists: (Path) -> Boolean = { Files.isRegularFile(it) },
    ): Path? = candidates.firstOrNull(exists)

    /**
     * `<baseUrl>?window=<name>`, with the name percent-encoded (`Screen%201`).
     *
     * `URLEncoder` is form encoding, which spells a space `+`; a `+` in a query value is a space
     * to `URLSearchParams` too, so either would in fact read back correctly — but the plan, the
     * Screens sheet's *Copy link* and the docs all write `%20`, and one spelling is worth more
     * than the byte saved. A [baseUrl] that already carries a query is refused rather than
     * guessed at: the launcher only ever passes its own `localUrl`.
     */
    fun screenUrl(baseUrl: String, screenName: String): String {
        require('?' !in baseUrl) { "screenUrl expects a bare origin, got $baseUrl" }
        val encoded = URLEncoder.encode(screenName, StandardCharsets.UTF_8).replace("+", "%20")
        val origin = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return "$origin?window=$encoded"
    }

    /** The command line for one screen: the browser, in app mode, at that screen's URL. */
    fun command(browser: Path, url: String): List<String> =
        listOf(browser.toAbsolutePath().toString(), "--app=$url")

    fun isWindows(osName: String? = System.getProperty("os.name")): Boolean =
        osName.orEmpty().lowercase().contains("win")
}
