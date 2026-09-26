package uk.me.cormack.lighting7.mcp.tunnel

/**
 * The ngrok agent this desk downloads, pinned by SHA-256.
 *
 * ngrok's licence does not let us ship the agent inside the installer (every desk uses the
 * operator's own ngrok account), so the desk fetches it from ngrok's CDN the first time remote
 * access is turned on — what Homebrew, `pyngrok` and `java-ngrok` do. See the project's
 * `decisions/mcp-tunnel-ngrok.md`.
 *
 * **The URLs are ngrok's `stable` channel**, which serves whatever ngrok released last. The
 * checksums were taken from the 3.39.11 downloads (Chris, 2026-09-26), so the day ngrok publishes
 * a newer agent every fresh desk's download stops matching and is refused — loudly, naming the
 * pinned version — rather than running an unverified binary. The fix then is a new table here;
 * meanwhile `mcp.tunnel.ngrokPath` points a desk at an agent the operator installed.
 */
object NgrokDistribution {
    const val VERSION = "3.39.11"

    private const val BASE = "https://bin.equinox.io/c/bNyj1mQVY4c"

    enum class Archive { ZIP, TGZ }

    data class Build(val platform: String, val url: String, val sha256: String, val archive: Archive) {
        /** The agent's file name inside the archive and on disk. */
        val binaryName: String get() = if (platform.startsWith("windows")) "ngrok.exe" else "ngrok"
    }

    val builds: Map<String, Build> = listOf(
        Build("darwin-arm64", "$BASE/ngrok-v3-stable-darwin-arm64.zip",
            "9324a6552d74e25d5bdfdbedc4b32422c96f044fda37877498ad8ef10bddf7f7", Archive.ZIP),
        Build("darwin-amd64", "$BASE/ngrok-v3-stable-darwin-amd64.zip",
            "c6b9b3d9184fc08c33fb8b181d9f241d8f5d61162a0be0521b6dfc1f11813a96", Archive.ZIP),
        Build("windows-amd64", "$BASE/ngrok-v3-stable-windows-amd64.zip",
            "699bbf1932ec43a573b764bd03e6568efa2c4e45955eb3cc2089c19bb4be4464", Archive.ZIP),
        Build("windows-arm64", "$BASE/ngrok-v3-stable-windows-arm64.zip",
            "493b7ea95a04e92488e9316f4728790bdeda8e00a3c2c688f8ac78a307627718", Archive.ZIP),
        Build("linux-amd64", "$BASE/ngrok-v3-stable-linux-amd64.tgz",
            "cec0b4997fcc5f529dfc74bac89050354d11a915f968720600039738fdf330cf", Archive.TGZ),
    ).associateBy { it.platform }

    /** `darwin-arm64`, `windows-amd64`, … for this JVM, or null for an OS ngrok isn't pinned for. */
    fun platformOf(osName: String, osArch: String): String? {
        val os = osName.lowercase()
        val osPart = when {
            os.startsWith("mac") || os.contains("darwin") -> "darwin"
            os.startsWith("windows") -> "windows"
            os.startsWith("linux") -> "linux"
            else -> return null
        }
        val archPart = when (osArch.lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "amd64", "x86_64", "x64" -> "amd64"
            else -> return null
        }
        return "$osPart-$archPart"
    }

    /** The pinned build for this JVM's platform, or null when there is none (e.g. Linux arm64). */
    fun forThisPlatform(): Build? =
        platformOf(System.getProperty("os.name"), System.getProperty("os.arch"))?.let { builds[it] }
}
