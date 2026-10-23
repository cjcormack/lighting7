package uk.me.cormack.lighting7.telemetry

import io.sentry.SamplingContext

/**
 * Which transactions are kept (Sentry plan §7, D4). The org is on the free plan, so the default is
 * conservative and the per-message table below is where WebSocket volume is cut.
 */
object TraceSampling {
    const val OP_HTTP_SERVER = "http.server"
    const val OP_WS_MESSAGE = "websocket.message"
    const val OP_BOOT = "app.boot"
    const val OP_CUE_GO = "cue.go"
    const val OP_SYNC_RUN = "sync.run"

    /** Kept whatever the sample rate — bar an idle auto-sync run, see [keepSyncRun]. */
    val ALWAYS_KEEP = setOf(OP_BOOT, OP_CUE_GO, OP_SYNC_RUN)

    /**
     * Whether a finished `sync.run` is sent. Its outcome is only known at the end, so every run is
     * sampled at the start and this decides on send: a manual run, a failed one, or one that moved
     * anything is always kept; an auto run that changed nothing is kept at [rate] ([draw] in 0..1).
     */
    fun keepSyncRun(trigger: Any?, outcome: Any?, rate: Double, draw: Double): Boolean =
        !(trigger == "AUTO" && outcome == "NO_OP") || draw < rate

    enum class WsKind {
        /** A stream an operator drags, holds or polls: never traced. */
        CONTINUOUS,

        /** A request for the current state, sent on every connect: never traced. */
        SNAPSHOT,

        /** One deliberate gesture: traced at the sample rate. */
        GESTURE,
    }

    /**
     * Every inbound WebSocket frame type, by its serial name. `TraceSamplingTest` fails until a new
     * frame has a row, so a new continuous stream cannot start spending the quota by default.
     */
    val WS_MESSAGES: Map<String, WsKind> = buildMap {
        for (type in listOf(
            "updateChannel",
            "programmer.set",
            "programmer.setColour",
            "programmer.setPosition",
            "programmer.patchLayer",
            "programmer.keyStack",
            "updateFx",
            "surfaceScaler.setGrandMaster",
            "grandMaster.set",
            "stackEffectSize.set",
            "speedMasters.setBpm",
            "speedMasters.tap",
            "speedMasters.requestBeat",
            "windows.announce",
        )) put(type, WsKind.CONTINUOUS)
        for (type in listOf(
            "channelMappingState",
            "channelState",
            "fxState",
            "parkState",
            "programmer.state",
            "projectState",
            "speedMasters.state",
            "surfaceBank.state",
            "surfaceDevices.state",
            "surfaceEncoderBank.state",
            "surfaceScaler.state",
            "universesState",
        )) put(type, WsKind.SNAPSHOT)
        for (type in listOf(
            "busk.setPage",
            "clearFx",
            "hand.drop",
            "hand.pickUp",
            "parkChannel",
            "pauseFx",
            "programmer.addLayer",
            "programmer.clearAll",
            "programmer.clearEntry",
            "programmer.clearScenery",
            "programmer.clearTarget",
            "programmer.detachLayer",
            "programmer.moveFx",
            "programmer.moveLayer",
            "programmer.removeLayer",
            "programmer.setBlind",
            "programmer.setScenery",
            "removeFx",
            "restoreChannelPark",
            "resumeFx",
            "selection.clear",
            "selection.set",
            "selection.setFamilies",
            "selection.subselect",
            "selection.toggle",
            "surfaceBank.set",
            "surfaceEncoderBank.set",
            "surfaceLearn.begin",
            "surfaceLearn.cancel",
            "surfaceLearn.commit",
            "surfaceScaler.setBlackout",
            "unparkChannel",
            "windows.follow",
            "windows.fullscreen",
            "windows.rename",
            "windows.show",
            "windows.viewOptions",
        )) put(type, WsKind.GESTURE)
    }

    /** Whether a frame of [type] may become a transaction at all; an unlisted type may not. */
    fun isTracedWsMessage(type: String): Boolean = WS_MESSAGES[type] == WsKind.GESTURE

    /** The sample rate for one transaction, with [rate] the install's `tracesSampleRate`. */
    fun sample(context: SamplingContext, rate: Double): Double {
        val transaction = context.transactionContext
        return when (transaction.operation) {
            in ALWAYS_KEEP -> 1.0
            OP_WS_MESSAGE -> if (isTracedWsMessage(transaction.name)) rate else 0.0
            else -> rate
        }
    }
}
