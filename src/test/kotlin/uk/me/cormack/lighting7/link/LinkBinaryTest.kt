package uk.me.cormack.lighting7.link

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.createFile
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which sidecar the desk runs (cloud-p1 plan D10): `cloud.link.binary`, else `-Dlighting7.link`, else the installer's. */
class LinkBinaryTest {
    private val dir = Files.createTempDirectory("link-binary")
    private val jar = dir.resolve("lighting7.jar").createFile()
    private var saved: String? = null

    @Before
    fun clearProperty() {
        saved = System.clearProperty("lighting7.link")
    }

    @After
    fun restoreProperty() {
        saved?.let { System.setProperty("lighting7.link", it) } ?: System.clearProperty("lighting7.link")
    }

    @Test
    fun `the installer's sidecar for this machine, beside the jar`() {
        assertNull(LinkBinary.resolve(null, jar), "none installed")
        val bundled = dir.resolve(LinkBinary.fileName).createFile()
        assertEquals(bundled, LinkBinary.resolve(null, jar))
        assertTrue(Files.isExecutable(bundled), "made executable where the installer left it not")
        assertTrue(LinkBinary.fileName.startsWith("lighting7-link-${LinkBinary.target}"))
    }

    @Test
    fun `the command line's, then local conf's, win over the installer's`() {
        dir.resolve(LinkBinary.fileName).createFile()
        System.setProperty("lighting7.link", "/opt/dev/lighting7-link")
        assertEquals("/opt/dev/lighting7-link", LinkBinary.resolve(null, jar).toString())
        assertEquals("link/lighting7-link", LinkBinary.resolve("link/lighting7-link", jar).toString())
    }

    @Test
    fun `none when the desk runs from classes and nothing names one`() {
        assertNull(LinkBinary.resolve(null, null))
    }
}
