package uk.me.cormack.lighting7.launcher

import java.awt.Desktop
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.datatransfer.StringSelection
import java.net.URI
import javax.imageio.ImageIO

/**
 * Installs a system-tray icon (menu-bar on macOS, notification area on Windows) with
 * Open / Copy LAN URL / View Logs / Quit. No-op on platforms that don't support tray —
 * the launcher continues running headlessly so the user can kill the JVM externally.
 *
 * On Windows, and only where a Chromium browser is actually installed, it also carries
 * *Open Screen 1* / *Open Screen 2*: the two chromeless desk windows (see [DeskScreens] and
 * `docs/desk-screens.md`). Absent rather than disabled where the flag does not exist — the
 * macOS route to a desk screen is Safari's *Add to Dock*, which no tray item can drive.
 */
fun installTray(localUrl: String, lanUrl: String, logsDir: java.nio.file.Path, onQuit: () -> Unit) {
    if (!SystemTray.isSupported()) {
        println("System tray not supported — running headless. Kill the JVM to quit.")
        return
    }

    val iconStream = LauncherMarker::class.java.getResourceAsStream("/lighting7.png")
        ?: error("Missing /lighting7.png resource in launcher classpath")
    val image = iconStream.use { ImageIO.read(it) }
        ?: error("Could not decode /lighting7.png")

    val popup = PopupMenu()

    popup.add(MenuItem("Open").apply {
        addActionListener {
            runCatching { Desktop.getDesktop().browse(URI(localUrl)) }
                .onFailure { println("Open failed: ${it.message}") }
        }
    })

    desktopBrowser()?.let { browser ->
        for (screen in DeskScreens.SCREEN_NAMES) {
            popup.add(MenuItem("Open $screen").apply {
                addActionListener {
                    // The URL is built *inside* the guard, as the Open and View Logs items build
                    // theirs: `screenUrl` has a `require`, and an argument expression evaluated
                    // outside `runCatching` would reach the EDT as a raw stack trace in
                    // launcher.log instead of this one friendly line.
                    runCatching {
                        val url = DeskScreens.screenUrl(localUrl, screen)
                        ProcessBuilder(DeskScreens.command(browser, url)).start()
                    }.onFailure { println("Open $screen failed: ${it.message}") }
                }
            })
        }
    }

    popup.add(MenuItem("Copy LAN URL").apply {
        addActionListener {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(lanUrl), null)
        }
    })

    popup.add(MenuItem("View Logs").apply {
        addActionListener {
            runCatching { Desktop.getDesktop().open(logsDir.toFile()) }
                .onFailure { println("Open logs failed: ${it.message}") }
        }
    })

    popup.addSeparator()

    popup.add(MenuItem("Quit").apply {
        addActionListener { onQuit() }
    })

    val trayIcon = TrayIcon(image, "lighting7", popup).apply {
        isImageAutoSize = true
        toolTip = "lighting7 — $lanUrl"
    }

    SystemTray.getSystemTray().add(trayIcon)
}

/**
 * The Chromium browser to spawn desk screens with, or null — on any non-Windows host, or a
 * Windows one where neither Edge nor Chrome is installed. Resolved once when the tray is built
 * rather than per click: the items should not exist at all if there is nothing to open them
 * with, and a browser is not installed and uninstalled mid-show.
 */
private fun desktopBrowser(): java.nio.file.Path? =
    if (DeskScreens.isWindows()) DeskScreens.findBrowser() else null
