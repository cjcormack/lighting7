package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Coroutine-aware test fake for [DmxController]. Mirrors [ArtNetController]'s per-channel
 * conflated-consumer + ack shape so blocking-vs-suspend benchmarks (see
 * `BenchmarkSetValues`) measure a meaningful fan-out cost — unlike [MockDmxController],
 * whose `setValuesSuspend` falls through to the synchronous body.
 *
 * Lives in the main source set (alongside `MockDmxController`) because [DmxController]
 * is sealed: direct subclasses must share its module / package.
 *
 * Skipped vs. the production controller: no UDP transport, no `transmissionNeeded`
 * coalesce, no fade ticker (`fadeMs` is ignored — workloads should pass `0L`). Those
 * are orthogonal to the fan-out path being measured.
 *
 * Ownership: a [SupervisorJob]-rooted scope (NOT `GlobalScope`) launches one consumer
 * per channel. Call [close] from `@AfterTest` to cancel and join — leaking the per-channel
 * coroutines across test runs is the same hazard `FU-TEST-COREMIDI-INIT-DEADLOCK` fixed.
 */
class AsyncTestDmxController(
    override val universe: Universe = Universe(0, 0),
    private val parkSource: ParkSource? = null,
) : DmxController {

    override val currentValues = ConcurrentHashMap<Int, UByte>(512)

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + ArtNetController.changerDispatcher)

    private val channelChangeChannels: Map<Int, Channel<ChannelUpdatePayload>> =
        (1..512).associateWith { settlingChannel() }

    init {
        for ((channelNo, ch) in channelChangeChannels) {
            scope.launch {
                try {
                    for (payload in ch) {
                        currentValues[channelNo] = payload.change.newValue
                        payload.settled.complete(Unit)
                    }
                } finally {
                    ch.close()
                    ch.cancel()
                }
            }
        }
    }

    override fun setValues(valuesToSet: List<Pair<Int, ChannelChange>>) {
        runBlocking { setValuesSuspend(valuesToSet) }
    }

    override suspend fun setValuesSuspend(valuesToSet: List<Pair<Int, ChannelChange>>) {
        if (valuesToSet.isEmpty()) return
        coroutineScope {
            for ((channelNo, change) in valuesToSet) {
                launch { doSetChannelSuspend(channelNo, change) }
            }
        }
    }

    private suspend fun doSetChannelSuspend(channelNo: Int, change: ChannelChange) {
        if (channelNo !in 1..512) return
        val ch = channelChangeChannels[channelNo] ?: return
        val payload = ChannelUpdatePayload(change)
        try {
            ch.send(payload)
        } catch (_: ClosedSendChannelException) {
            return
        }
        payload.settled.await()
    }

    override fun setValue(channelNo: Int, channelChange: ChannelChange) {
        setValues(listOf(channelNo to channelChange))
    }

    override fun setValue(channelNo: Int, channelValue: UByte, fadeMs: Long) {
        setValue(channelNo, ChannelChange(channelValue, fadeMs))
    }

    override fun getValue(channelNo: Int): UByte =
        parkSource?.getParkedValue(universe.universe, channelNo)
            ?: currentValues[channelNo]
            ?: 0u

    override fun restoreState(values: Map<Int, UByte>) {
        for ((channelNo, value) in values) {
            if (channelNo in 1..512) currentValues[channelNo] = value
        }
    }

    override fun addTransmitModifier(modifier: TransmitModifier) {}
    override fun removeTransmitModifier(modifier: TransmitModifier) {}

    /** Cancel the per-channel consumers and await termination. Idempotent. */
    fun close() {
        channelChangeChannels.values.forEach { it.close() }
        runBlocking { supervisor.cancelAndJoin() }
    }
}
