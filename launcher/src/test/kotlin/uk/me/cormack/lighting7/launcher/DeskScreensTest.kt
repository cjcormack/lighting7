package uk.me.cormack.lighting7.launcher

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure half of the *Open Screen N* tray items — the only half a Mac can test, since the
 * items themselves exist on Windows alone and need a real `msedge.exe` to point at.
 *
 * Two of these are load-bearing rather than tidy. The URL must be `localhost`, because
 * installation, Keyboard Lock and Window Management are secure-context features and the desk
 * serves plain HTTP (plan D13) — a desk screen opened at the `.local` name loses all three
 * silently. And Edge must come before Chrome in *any* Program Files root, because that is the
 * order §11 settled on and a root-major search would hand a per-user Chrome the first screen.
 */
class DeskScreensTest {

    private val windowsEnv = mapOf(
        "ProgramFiles(x86)" to """C:\Program Files (x86)""",
        "ProgramFiles" to """C:\Program Files""",
        "LOCALAPPDATA" to """C:\Users\chris\AppData\Local""",
    )

    private fun candidates(env: Map<String, String> = windowsEnv) = DeskScreens.browserCandidates(env::get)

    @Test
    fun `the screen URL is localhost with the name percent-encoded`() {
        assertEquals(
            "http://localhost:8413/?window=Screen%201",
            DeskScreens.screenUrl("http://localhost:8413/", "Screen 1"),
        )
        assertEquals(
            "http://localhost:8413/?window=Screen%202",
            DeskScreens.screenUrl("http://localhost:8413", "Screen 2"),
            "a base with no trailing slash still produces one path",
        )
        assertTrue(
            DeskScreens.SCREEN_NAMES.all { "%20" in DeskScreens.screenUrl("http://localhost:8413/", it) },
            "the space is %20, the spelling the plan and the sheet's Copy link both use",
        )
    }

    @Test
    fun `a base URL that already carries a query is refused rather than guessed at`() {
        assertFailsWith<IllegalArgumentException> {
            DeskScreens.screenUrl("http://localhost:8413/?window=Screen%201", "Screen 2")
        }
    }

    @Test
    fun `Edge is preferred over Chrome in any root, and a per-user Chrome is still found`() {
        val edge = Paths.get("""C:\Program Files""", "Microsoft", "Edge", "Application", "msedge.exe")
        val perUserChrome = Paths.get(
            """C:\Users\chris\AppData\Local""", "Google", "Chrome", "Application", "chrome.exe",
        )

        assertEquals(
            edge,
            DeskScreens.findBrowser(candidates()) { it == edge || it == perUserChrome },
            "Edge first, even though the Chrome here sits under an earlier root",
        )
        assertEquals(
            perUserChrome,
            DeskScreens.findBrowser(candidates()) { it == perUserChrome },
            "a Chrome installed without admin rights is still a desk browser",
        )
        assertNull(
            DeskScreens.findBrowser(candidates()) { false },
            "no browser means no tray items, rather than two that fail on click",
        )
    }

    @Test
    fun `a host with no Program Files at all yields no candidates`() {
        assertTrue(DeskScreens.browserCandidates { null }.isEmpty())
    }

    @Test
    fun `the command is the browser in app mode at that screen's URL`() {
        val browser: Path = Paths.get("""C:\Program Files\Microsoft\Edge\Application\msedge.exe""")
        assertEquals(
            listOf(
                browser.toAbsolutePath().toString(),
                "--app=http://localhost:8413/?window=Screen%201",
            ),
            DeskScreens.command(browser, DeskScreens.screenUrl("http://localhost:8413/", "Screen 1")),
        )
    }

    @Test
    fun `the items are Windows-only`() {
        assertTrue(DeskScreens.isWindows("Windows 11"))
        assertTrue(!DeskScreens.isWindows("Mac OS X"))
        assertTrue(!DeskScreens.isWindows(null))
    }
}
