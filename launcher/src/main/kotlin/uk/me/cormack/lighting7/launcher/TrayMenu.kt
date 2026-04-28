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
