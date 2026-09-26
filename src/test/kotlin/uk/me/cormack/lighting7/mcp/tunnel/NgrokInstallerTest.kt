package uk.me.cormack.lighting7.mcp.tunnel

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NgrokInstallerTest {
    private val agent = "#!/bin/sh\necho fake agent\n".toByteArray()

    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun zip(name: String, body: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("README.txt")); z.write("hi".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry(name)); z.write(body); z.closeEntry()
        }
    }.toByteArray()

    /** A minimal ustar archive: a decoy member, then [name]. */
    private fun tgz(name: String, body: ByteArray): ByteArray {
        fun header(entry: String, size: Int): ByteArray {
            val h = ByteArray(512)
            entry.toByteArray().copyInto(h, 0)
            "0000644\u0000".toByteArray().copyInto(h, 100)
            String.format("%011o\u0000", size).toByteArray().copyInto(h, 124)
            h[156] = '0'.code.toByte()
            "ustar\u000000".toByteArray().copyInto(h, 257)
            return h
        }
        fun padded(b: ByteArray) = b.copyOf((b.size + 511) / 512 * 512)
        val tar = ByteArrayOutputStream().apply {
            val decoy = "not the agent".toByteArray()
            write(header("LICENSE", decoy.size)); write(padded(decoy))
            write(header(name, body.size)); write(padded(body))
            write(ByteArray(1024))
        }.toByteArray()
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tar) } }.toByteArray()
    }

    private fun installer(archive: ByteArray, fetched: MutableList<String> = mutableListOf()) =
        NgrokInstaller(createTempDirectory("ngrok-install")) { url ->
            fetched += url
            ByteArrayInputStream(archive) to archive.size.toLong()
        }

    @Test
    fun `a zip whose checksum matches is installed, executable, and not fetched twice`() {
        val archive = zip("ngrok", agent)
        val build = NgrokDistribution.Build("darwin-arm64", "https://example/ngrok.zip", sha(archive), NgrokDistribution.Archive.ZIP)
        val fetched = mutableListOf<String>()
        val installer = installer(archive, fetched)
        val progress = mutableListOf<Long>()
        val path = installer.ensureInstalled(build) { read, _ -> progress += read }
        assertContentEquals(agent, path.readBytes())
        assertTrue(Files.isExecutable(path))
        assertEquals(archive.size.toLong(), progress.last())
        assertTrue(installer.isInstalled(build))
        installer.ensureInstalled(build)
        assertEquals(1, fetched.size)
        // Nothing but the agent is left in the install directory.
        assertEquals(listOf("ngrok"), Files.list(path.parent).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `a tgz is installed from the member with the agent's name`() {
        val archive = tgz("ngrok", agent)
        val build = NgrokDistribution.Build("linux-amd64", "https://example/ngrok.tgz", sha(archive), NgrokDistribution.Archive.TGZ)
        assertContentEquals(agent, installer(archive).ensureInstalled(build).readBytes())
    }

    @Test
    fun `a download that doesn't match the pinned checksum is refused and nothing is kept`() {
        val archive = zip("ngrok", agent)
        val build = NgrokDistribution.Build("darwin-arm64", "https://example/ngrok.zip", sha("other".toByteArray()), NgrokDistribution.Archive.ZIP)
        val installer = installer(archive)
        val e = assertFailsWith<NgrokInstallException> { installer.ensureInstalled(build) }
        assertTrue(e.message!!.contains(NgrokDistribution.VERSION), e.message)
        assertTrue(e.message!!.contains("mcp.tunnel.ngrokPath"), e.message)
        assertFalse(installer.isInstalled(build))
    }

    @Test
    fun `an archive without the agent is refused`() {
        val archive = zip("something-else", agent)
        val build = NgrokDistribution.Build("windows-amd64", "https://example/ngrok.zip", sha(archive), NgrokDistribution.Archive.ZIP)
        assertFailsWith<NgrokInstallException> { installer(archive).ensureInstalled(build) }
    }

    @Test
    fun `platforms map onto the pinned table`() {
        assertEquals("darwin-arm64", NgrokDistribution.platformOf("Mac OS X", "aarch64"))
        assertEquals("windows-arm64", NgrokDistribution.platformOf("Windows 11", "aarch64"))
        assertEquals("windows-amd64", NgrokDistribution.platformOf("Windows 11", "amd64"))
        assertEquals("linux-amd64", NgrokDistribution.platformOf("Linux", "amd64"))
        assertNull(NgrokDistribution.platformOf("FreeBSD", "amd64"))
        assertNull(NgrokDistribution.platformOf("Linux", "riscv64"))
        assertEquals(null, NgrokDistribution.builds["linux-arm64"], "not pinned: that desk sets mcp.tunnel.ngrokPath")
        for (build in NgrokDistribution.builds.values) {
            assertEquals(64, build.sha256.length)
            assertTrue(build.url.startsWith("https://bin.equinox.io/"))
        }
        assertEquals("ngrok.exe", NgrokDistribution.builds.getValue("windows-arm64").binaryName)
    }
}
