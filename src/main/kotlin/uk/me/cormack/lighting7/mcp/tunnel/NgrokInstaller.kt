package uk.me.cormack.lighting7.mcp.tunnel

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

class NgrokInstallException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Opens a download: the body stream and, when the server says, its length. */
fun interface NgrokFetcher {
    fun open(url: String): Pair<InputStream, Long?>
}

/**
 * Downloads the pinned ngrok agent into `<appDataDir>/ngrok/<checksum prefix>/` on first use.
 *
 * The archive is hashed as it streams and refused unless it matches the pinned SHA-256, and only
 * then is the agent extracted — so a binary on disk under that directory is one whose archive
 * matched. The directory is named after the checksum, not the version, so changing the pinned
 * table can never be satisfied by a binary an older table installed.
 */
class NgrokInstaller(
    private val installRoot: Path,
    private val fetcher: NgrokFetcher = JdkFetcher,
) {
    fun binaryPath(build: NgrokDistribution.Build): Path =
        installRoot.resolve(build.sha256.take(12)).resolve(build.binaryName)

    fun isInstalled(build: NgrokDistribution.Build): Boolean = Files.isRegularFile(binaryPath(build))

    /**
     * Returns the agent's path, downloading and verifying it first if it is not already there.
     * [onProgress] gets (bytes so far, total or null). Blocking; call it off the request thread.
     */
    fun ensureInstalled(build: NgrokDistribution.Build, onProgress: (Long, Long?) -> Unit = { _, _ -> }): Path {
        val target = binaryPath(build)
        if (Files.isRegularFile(target)) return target
        Files.createDirectories(target.parent)
        val archive = installRoot.resolve("download-${UUID.randomUUID()}.part")
        val staged = target.resolveSibling("${build.binaryName}.${UUID.randomUUID()}.part")
        try {
            download(build, archive, onProgress)
            extract(build, archive, staged)
            staged.toFile().setExecutable(true, false)
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            Files.deleteIfExists(archive)
            Files.deleteIfExists(staged)
        }
    }

    private fun download(build: NgrokDistribution.Build, archive: Path, onProgress: (Long, Long?) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val (body, total) = try {
            fetcher.open(build.url)
        } catch (e: NgrokInstallException) {
            throw e
        } catch (e: Exception) {
            throw NgrokInstallException("Couldn't download ngrok from ${URI(build.url).host}: ${e.message ?: e.javaClass.simpleName}", e)
        }
        var read = 0L
        body.use { input ->
            Files.newOutputStream(archive).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    read += n
                    if (read > MAX_ARCHIVE_BYTES) throw NgrokInstallException("The ngrok download is larger than expected; refusing it")
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    onProgress(read, total)
                }
            }
        }
        val actual = HexFormat.of().formatHex(digest.digest())
        if (!actual.equals(build.sha256, ignoreCase = true)) {
            throw NgrokInstallException(
                "The ngrok download didn't match the version this desk pins (${NgrokDistribution.VERSION}). " +
                    "ngrok has probably released a newer agent: update lighting7, or set mcp.tunnel.ngrokPath " +
                    "in local.conf to an ngrok you installed yourself.",
            )
        }
    }

    private fun extract(build: NgrokDistribution.Build, archive: Path, staged: Path) {
        val found = when (build.archive) {
            NgrokDistribution.Archive.ZIP -> ZipInputStream(Files.newInputStream(archive)).use { zip ->
                generateSequence { zip.nextEntry }
                    .firstOrNull { !it.isDirectory && it.name.substringAfterLast('/') == build.binaryName }
                    ?.let { Files.copy(zip, staged, StandardCopyOption.REPLACE_EXISTING); true } ?: false
            }
            NgrokDistribution.Archive.TGZ -> GZIPInputStream(Files.newInputStream(archive)).use { gz ->
                extractFromTar(gz, build.binaryName, staged)
            }
        }
        if (!found) throw NgrokInstallException("The ngrok download didn't contain ${build.binaryName}")
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 200L * 1024 * 1024

        /**
         * The one regular file named [name] from a ustar stream, copied to [target]. A reader of
         * our own rather than a dependency: the archive's checksum has already been verified, so
         * all this has to do is find one member, and a tar header is 512 bytes of fixed fields.
         */
        internal fun extractFromTar(input: InputStream, name: String, target: Path): Boolean {
            val header = ByteArray(512)
            while (true) {
                if (input.readNBytes(header, 0, 512) < 512) return false
                if (header.all { it == 0.toByte() }) return false
                val entryName = String(header, 0, 100, Charsets.US_ASCII).substringBefore('\u0000')
                val prefix = String(header, 345, 155, Charsets.US_ASCII).substringBefore('\u0000')
                val sizeField = String(header, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                val size = if (sizeField.isEmpty()) 0L else sizeField.toLong(8)
                val type = header[156].toInt().toChar()
                val fullName = if (prefix.isEmpty()) entryName else "$prefix/$entryName"
                val padded = (size + 511) / 512 * 512
                if ((type == '0' || type == '\u0000') && fullName.substringAfterLast('/') == name) {
                    Files.newOutputStream(target).use { out ->
                        var remaining = size
                        val buffer = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (n < 0) throw NgrokInstallException("The ngrok download was truncated")
                            out.write(buffer, 0, n)
                            remaining -= n
                        }
                    }
                    return true
                }
                input.skipNBytes(padded)
            }
        }
    }

    private object JdkFetcher : NgrokFetcher {
        private val client: HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()

        override fun open(url: String): Pair<InputStream, Long?> {
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(5)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                response.body().close()
                throw NgrokInstallException("Couldn't download ngrok: ${URI(url).host} answered ${response.statusCode()}")
            }
            val length = response.headers().firstValueAsLong("Content-Length").let { if (it.isPresent) it.asLong else null }
            return response.body() to length
        }
    }
}
