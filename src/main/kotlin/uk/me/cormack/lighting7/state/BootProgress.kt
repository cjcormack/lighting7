package uk.me.cormack.lighting7.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Coarse phases of the show boot sequence, surfaced to clients so the frontend can render a
 * loading bar during the "warming up" window before the rig is live. [READY] is the terminal
 * success state; [FAILED] is terminal failure.
 */
@Serializable
enum class BootPhase {
    STARTING,
    SHOW_INIT,
    FX_COMPILE,
    FIXTURES,
    CUE_PREWARM,
    READY,
    FAILED,
}

/**
 * A snapshot of boot progress. Serialised verbatim as the `GET /api/rest/status` body and as
 * the payload of the `bootProgressState` WebSocket message.
 */
@Serializable
data class BootStatus(
    val phase: BootPhase,
    val message: String,
    /** 0..100, monotonically increasing through a successful boot. */
    val percent: Int,
    /** True once the show is fully initialised and show-dependent endpoints will serve. */
    val ready: Boolean,
    /** Human-readable failure detail when [phase] is [BootPhase.FAILED]. */
    val error: String? = null,
)

/**
 * Thread-safe holder for [BootStatus], owned by [State]. The background boot coroutine in
 * `Application.module()` drives it forward; the status endpoint reads [current] and the WS
 * layer collects [flow].
 *
 * All mutators are safe to call from any thread (the FX-compile progress callback fires from
 * parallel compile workers).
 */
class BootProgress {
    private val _flow = MutableStateFlow(
        BootStatus(BootPhase.STARTING, "Starting…", percent = 0, ready = false),
    )
    val flow: StateFlow<BootStatus> = _flow.asStateFlow()

    val current: BootStatus get() = _flow.value
    val isReady: Boolean get() = _flow.value.ready

    fun update(phase: BootPhase, message: String, percent: Int) {
        _flow.value = BootStatus(phase, message, percent.coerceIn(0, 100), ready = false)
    }

    /**
     * Report built-in FX compilation progress. Maps `done/total` onto the 10..55% band, the
     * dominant cold-boot cost, so the bar visibly advances while effects compile.
     *
     * Parallel compile workers each increment `done` then call this separately, so writes can
     * arrive out of order; the update only ever advances (never rewinds percent), keeping the bar
     * monotonic. It also refuses to overwrite an already-terminal READY state as a safety net.
     */
    fun updateFxCompile(done: Int, total: Int) {
        val percent = if (total <= 0) 10 else 10 + (done.coerceIn(0, total) * 45 / total)
        _flow.update { current ->
            if (current.ready || percent <= current.percent) {
                current
            } else {
                BootStatus(BootPhase.FX_COMPILE, "Compiling effects ($done/$total)…", percent, ready = false)
            }
        }
    }

    fun markReady() {
        _flow.value = BootStatus(BootPhase.READY, "Ready", percent = 100, ready = true)
    }

    fun markFailed(error: Throwable) {
        _flow.value = BootStatus(
            BootPhase.FAILED,
            "Startup failed",
            percent = _flow.value.percent,
            ready = false,
            error = error.message ?: error.toString(),
        )
    }
}
