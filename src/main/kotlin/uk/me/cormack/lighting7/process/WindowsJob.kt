package uk.me.cormack.lighting7.process

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import org.slf4j.LoggerFactory

/**
 * A Windows Job Object with KILL_ON_JOB_CLOSE, held open for the life of this JVM: when the desk's
 * process dies, cleanly or not, Windows closes the handle and kills every process in the job, so a
 * child the desk supervises dies with it. No-op elsewhere, and on any failure (the shutdown hook and
 * the PID-file sweep still apply).
 */
object WindowsJob {
    private val log = LoggerFactory.getLogger(WindowsJob::class.java)
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun CreateJobObjectW(attributes: Pointer?, name: WString?): Pointer?
        fun SetInformationJobObject(job: Pointer, infoClass: Int, info: Pointer, length: Int): Boolean
        fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
        fun AssignProcessToJobObject(job: Pointer, process: Pointer): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    private val kernel32: Kernel32? by lazy {
        if (!isWindows) null else runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
    }

    private val job: Pointer? by lazy {
        val k = kernel32 ?: return@lazy null
        runCatching {
            val handle = k.CreateJobObjectW(null, null) ?: return@runCatching null
            // JOBOBJECT_EXTENDED_LIMIT_INFORMATION is 144 bytes on 64-bit Windows (the only Windows
            // the desk ships for); LimitFlags sits at offset 16 of its basic half.
            val info = Memory(144).apply { clear() }
            info.setInt(16, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
            if (k.SetInformationJobObject(handle, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, 144)) handle else {
                k.CloseHandle(handle)
                null
            }
        }.getOrNull()
    }

    /** Put [process] in the desk's job; [name] is for the log. */
    fun assign(process: Process, name: String) {
        if (!isWindows) return
        val k = kernel32 ?: return
        val j = job ?: return
        runCatching {
            val h = k.OpenProcess(PROCESS_SET_QUOTA or PROCESS_TERMINATE, false, process.pid().toInt()) ?: return
            try {
                if (!k.AssignProcessToJobObject(j, h)) log.warn("Couldn't put {} in the desk's job object", name)
            } finally {
                k.CloseHandle(h)
            }
        }
    }

    private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
    private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
    private const val PROCESS_SET_QUOTA = 0x0100
    private const val PROCESS_TERMINATE = 0x0001
}
