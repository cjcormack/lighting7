package uk.me.cormack.lighting7.testsupport

import uk.me.cormack.lighting7.link.LinkIn
import uk.me.cormack.lighting7.link.LinkJson
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * A stand-in for lighting7-link, run as its own process by the broker's tests: it speaks the stdio
 * protocol (docs/cloud-engineering.md §"The sidecar") with no WebRTC behind it, and misbehaves as its
 * mode says. Every stdin line it reads, the secret first, is appended to the record file.
 *
 * `FakeSidecar <mode> <record file>`, the modes: `normal`; `hang`, which stops reading stdin once
 * started; `die`, which exits at its first `session.open`; `flood-stdout` and `flood-stderr`, which
 * write without end while still answering.
 *
 * In `normal`, an offer whose SDP is `then-close` is answered and then closed `TOKEN_EXPIRED`.
 */
object FakeSidecar {
    private val out = PrintStream(System.out, true, Charsets.UTF_8)

    private fun emit(line: String) = synchronized(out) { out.println(line) }

    /** How to launch it, for [uk.me.cormack.lighting7.cloud.CloudService.linkCommand]. */
    fun command(mode: String, record: File): List<String> =
        listOf(File(System.getProperty("java.home"), "bin/java").path, "-Xshare:auto", "-XX:TieredStopAtLevel=1", "-cp", System.getProperty("java.class.path"), FakeSidecar::class.java.name, mode, record.path)

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args[0]
        val record = File(args[1])
        val stdin = System.`in`.bufferedReader(Charsets.UTF_8)
        fun read(): String? = stdin.readLine()?.also { synchronized(record) { record.appendText(it + "\n") } }
        read() ?: exitProcess(2)
        emit("""{"type":"started","build":"fake"}""")
        when (mode) {
            "hang" -> Thread.sleep(Long.MAX_VALUE)
            "flood-stdout" -> Thread {
                val long = "x".repeat(300 * 1024)
                var i = 0L
                while (true) {
                    emit(
                        when ((i++ % 4).toInt()) {
                            0 -> "not json at all $i"
                            1 -> long
                            2 -> """{"type":"from.the.future","n":$i}"""
                            else -> """{"type":"session.ice","sid":"nobody-$i"}"""
                        },
                    )
                }
            }.apply { isDaemon = true }.start()
            "flood-stderr" -> Thread {
                val err = PrintStream(System.err, false, Charsets.UTF_8)
                var i = 0L
                while (true) err.println("noise ${i++} " + "y".repeat(200))
            }.apply { isDaemon = true }.start()
        }
        while (true) {
            val line = read() ?: break
            val message = runCatching { LinkJson.decodeFromString(LinkIn.serializer(), line) }.getOrNull() ?: continue
            when (message) {
                is LinkIn.Open -> {
                    if (mode == "die") exitProcess(3)
                    val sid = message.sid
                    emit("""{"type":"session.answer","sid":"$sid","sdp":"answer for $sid"}""")
                    emit("""{"type":"session.ice","sid":"$sid","candidate":{"candidate":"candidate:1 1 udp 2130706431 127.0.0.1 50000 typ host","sdpMid":"0","sdpMLineIndex":0}}""")
                    emit("""{"type":"session.ice","sid":"$sid"}""")
                    emit("""{"type":"session.state","sid":"$sid","path":"RELAY"}""")
                    if (message.offer == "then-close") emit("""{"type":"session.closed","sid":"$sid","reason":"TOKEN_EXPIRED"}""")
                }
                is LinkIn.Close -> emit("""{"type":"session.closed","sid":"${message.sid}","reason":"${message.reason}"}""")
                is LinkIn.Ice -> Unit
            }
        }
    }
}
