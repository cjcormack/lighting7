package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.selects.select
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.time.Duration.Companion.nanoseconds

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class ArtNetController(
    override val universe: Universe,
    val address: String? = null,
    refreshIntervalMs: Int = DEFAULT_REFRESH_INTERVAL_MS,
    /**
     * Channel values to seed the buffer with *before* the first frame goes out — the
     * snapshot a fixture reload carries across a controller rebuild.
     *
     * Prefer this to calling [restoreState] after construction: the transmission loop
     * starts transmitting immediately, so a post-construction restore races it and can let
     * one all-zero frame reach the rig before the real values land.
     */
    initialValues: Map<Int, UByte> = emptyMap(),
    private val parkSource: ParkSource? = null,
    private val transport: ArtNetTransport = DefaultArtNetTransport(),
): DmxController {
    companion object {
        /** Default Art-Net frame interval: 40 Hz. */
        const val DEFAULT_REFRESH_INTERVAL_MS = 25

        /**
         * A full 513-slot DMX512 frame at 250 kbit/s occupies ~22.6 ms on the wire, so a
         * node physically cannot emit more than ~44 frames/sec however fast we feed it —
         * which is also where the Art-Net spec caps a controller. Below this, every extra
         * packet is one the node discards, at a real network and CPU cost now that output
         * is a continuous stream rather than change-driven.
         */
        const val MIN_REFRESH_INTERVAL_MS = 23

        /** Nodes commonly fail-safe after ~4 s of silence; 1 Hz keeps 4x headroom. */
        const val MAX_REFRESH_INTERVAL_MS = 1000

        private const val MAX_CONSECUTIVE_ERRORS = 20
    }

    internal val fadeTickMs = 10

    private val channelChangeChannels: Map<Int, Channel<ChannelUpdatePayload>>

    @Volatile
    private var refreshInterval: Int =
        refreshIntervalMs.coerceIn(MIN_REFRESH_INTERVAL_MS, MAX_REFRESH_INTERVAL_MS)

    /**
     * Machine-local transmit period, re-read by the transmission loop once per frame, so a
     * change takes effect on the next tick without rebuilding the controller.
     *
     * Clamped on write as well as on construction: the backing value is a machine-local
     * override row a user can hand-edit, and a `1` there would pin a core at 1000 packets
     * per second on this universe.
     */
    var refreshIntervalMs: Int
        get() = refreshInterval
        set(value) {
            refreshInterval = value.coerceIn(MIN_REFRESH_INTERVAL_MS, MAX_REFRESH_INTERVAL_MS)
        }

    override val currentValues = ConcurrentHashMap<Int, UByte>(512)

    private val transmitModifiers = java.util.concurrent.CopyOnWriteArrayList<TransmitModifier>()

    private var previousSentDmxData = ByteArray(512)

    private val listeners = ArrayList<ChannelChangeListener>()

    private val packetCounter = PacketRateCounter()

    /**
     * The dedicated transmit thread, held as a field so [close] can actually release it.
     * It used to be created inline at the `launch` site, which left no handle to close and
     * leaked a thread — plus a socket and 512 coroutines — on every controller rebuild.
     */
    private val artNetDispatcher =
        newSingleThreadContext("ArtNetThread-${universe.subnet}-${universe.universe}")

    /**
     * Assigned synchronously below. If it were assigned from inside the launched coroutine,
     * a construct-then-immediately-close would observe `null` and leak the loop.
     */
    private val transmissionJob: Job

    val packetsPerSecond: Double get() = packetCounter.packetsPerSecond()
    val totalPacketsSent: Long get() = packetCounter.total

    init {
        transport.start()

        // Built synchronously: a write arriving during the construction window must not race
        // an async populate and trip `checkNotNull` in doSetChannelSuspend.
        channelChangeChannels = (1..512).associateWith {
            Channel<ChannelUpdatePayload>(Channel.Factory.CONFLATED)
        }

        for (channelNo in 1..512) {
            currentValues[channelNo] = 0u
        }
        // Seeded before the loop is launched, so the bootstrap frame carries the restored
        // rig state rather than 512 zeroes.
        for ((channelNo, value) in initialValues) {
            if (channelNo in 1..512) currentValues[channelNo] = value
        }

        transmissionJob = GlobalScope.launch(artNetDispatcher) { runTransmissionLoop() }

        // Runs exactly once however the job ends — normal exit, error bail-out, or a cancel
        // that landed before the body ever started. That last case is why this is a
        // completion handler and not a `finally` inside the loop.
        transmissionJob.invokeOnCompletion {
            // One final frame, so whatever was last written actually reaches the wire.
            // `ProjectManager.shutdownShow` blacks out every channel and *then* tears down
            // the controllers: without this flush the cancel can beat the next tick and the
            // blackout never transmits, leaving any universe the incoming project doesn't
            // cover holding its last look. Best-effort — the transport may already be
            // broken if the loop bailed out on repeated errors.
            runCatching { sendCurrentValues() }
            runCatching { transport.stop() }
            artNetDispatcher.close()
        }

        GlobalScope.launch {
            channelChangeChannels.forEach { (channelNo, channel) ->
                runChannelChangerChannel(channelNo, channel)
            }
        }
    }

    class ChannelUpdatePayload(val change: ChannelChange, val updateNotificationChannel: Channel<Unit>)

    override fun setValues(valuesToSet: List<Pair<Int, ChannelChange>>) {
        runBlocking { setValuesSuspend(valuesToSet) }
    }

    // Writers only update `currentValues`; they never signal the transmission loop. Under
    // continuous streaming the next frame is at most one `refreshIntervalMs` away and goes
    // out whether or not anything changed, so there is nothing to wake.

    override suspend fun setValuesSuspend(valuesToSet: List<Pair<Int, ChannelChange>>) {
        if (valuesToSet.isEmpty()) return

        coroutineScope {
            for ((channelNo, channelChange) in valuesToSet) {
                launch {
                    doSetChannelSuspend(channelNo, channelChange)
                }
            }
        }
    }

    override fun setValue(channelNo: Int, channelChange: ChannelChange) {
        runBlocking {
            doSetChannelSuspend(channelNo, channelChange)
        }
    }

    override fun setValue(channelNo: Int, channelValue: UByte, fadeMs: Long) {
        setValue(channelNo, ChannelChange(channelValue, fadeMs))
    }

    private suspend fun doSetChannelSuspend(channelNo: Int, channelChange: ChannelChange): Boolean {
        if (channelNo < 1 || channelNo > 512) {
            return false
        }
        if (channelChange.newValue < 0u || channelChange.newValue > 255u) {
            return false
        }

        val changeChannel = channelChangeChannels[channelNo]
        checkNotNull(changeChannel)

        val updateDoneChannel = Channel<Unit>()
        try {
            changeChannel.send(ChannelUpdatePayload(channelChange, updateDoneChannel))
        } catch (_: ClosedSendChannelException) {
            // The controller was closed underneath us. Reachable in normal operation: a
            // patch edit rebuilds controllers while the FX engine is still ticking, and
            // this write targets the controller being discarded. Report "not applied"
            // rather than letting it propagate into the FX loop.
            return false
        }
        updateDoneChannel.receive()

        return true
    }

    override fun getValue(channelNo: Int): UByte {
        return parkSource?.getParkedValue(universe.universe, channelNo)
            ?: currentValues[channelNo]
            ?: 0u
    }

    override fun restoreState(values: Map<Int, UByte>) {
        for ((channelNo, value) in values) {
            if (channelNo in 1..512) currentValues[channelNo] = value
        }
        // No transmission trigger needed: output is a continuous stream, so the next frame
        // picks this up within one refreshIntervalMs.
        //
        // This does *race* the bootstrap frame, though — the loop starts transmitting as
        // soon as the controller is constructed. To carry state across a controller rebuild
        // without a zero frame escaping first, pass `initialValues` to the constructor
        // instead of restoring afterwards.
    }

    fun registerListener(listener: ChannelChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun unregisterListener(listener: ChannelChangeListener) {
        listeners.remove(listener)
    }

    override fun addTransmitModifier(modifier: TransmitModifier) {
        if (!transmitModifiers.contains(modifier)) {
            transmitModifiers.add(modifier)
        }
    }

    override fun removeTransmitModifier(modifier: TransmitModifier) {
        transmitModifiers.remove(modifier)
    }

    /**
     * No-op for Art-Net. Every universe transmits unconditionally on every tick, so a
     * modifier or park change reaches the wire within one [refreshIntervalMs] with no
     * explicit nudge — and an out-of-band packet would push the universe above its
     * configured frame rate, which DMX512 bounds at ~44/sec in the first place.
     */
    override fun requestTransmit() {}

    /**
     * Stop transmitting and release the transmit thread and socket. Idempotent: the
     * project-switch path closes controllers through [Fixtures.register]'s `removeUnused`
     * block and shutdown closes them again through `Show.close()`.
     */
    fun close() {
        transmissionJob.cancel()
        // The channel-changer coroutines are NOT children of transmissionJob — they were
        // launched from a separate GlobalScope job — so cancelling the transmit loop leaves
        // all 512 of them suspended forever on a channel nobody will write to again. Their
        // only exit is their channel closing, so close it.
        //
        // Closed rather than cancelled on purpose: a changer cancelled between
        // `changeChannel.send()` and its ack would strand the caller forever on
        // `updateDoneChannel.receive()`. Closing still drains the buffered payload and acks
        // it, and a racing sender gets a ClosedSendChannelException that
        // `doSetChannelSuspend` turns into `false`.
        channelChangeChannels.values.forEach { it.close() }
    }

    /**
     * Continuous streaming: one Art-Net frame for this universe every [refreshIntervalMs],
     * unconditionally, whether or not any channel changed.
     *
     * This used to be change-driven — a 25 ms throttle followed by a block until something
     * wrote — which meant an idle universe transmitted nothing at all. Art-Net is UDP with
     * no retransmission, so a single dropped datagram then left the node holding a stale
     * value until the next time that channel happened to change: a blackout that silently
     * doesn't take on one universe. Streaming repairs any lost frame on the next tick, and
     * is what the node's own continuous DMX512 output expects to be fed.
     */
    private suspend fun runTransmissionLoop() {
        var consecutiveErrors = 0

        // Absolute deadlines rather than `delay(interval)` after each send: the per-frame
        // work and scheduler jitter would otherwise accumulate into drift. This is the
        // fixed-period semantic the old `ticker(25)` gave us, reimplemented because the
        // period is now mutable and a ticker cannot be retuned in place.
        var nextFrameNanos = System.nanoTime()

        while (currentCoroutineContext().isActive) {
            try {
                // The first pass is the bootstrap frame, on the dedicated ArtNet thread so
                // it has the same affinity as every subsequent transmit. `sendCurrentValues()`
                // is the only path to `transport.broadcast/unicastDmx`, and it overlays the
                // [parkSource] view — the park bootstrap safety property.
                sendCurrentValues()
                consecutiveErrors = 0
            } catch (e: CancellationException) {
                // close() cancels the job; the generic handler below would otherwise
                // swallow it and retry, leaving the loop running after close().
                throw e
            } catch (e: Exception) {
                if (consecutiveErrors == 0) {
                    e.printStackTrace()
                }
                consecutiveErrors++

                if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                    // if too many errors, we'll bail out and let this thing stop. A restart of NK is needed.
                    println("ArtNet ${universe.subnet}:${universe.universe} — too many consecutive errors")
                    throw e
                }
            }

            val periodNanos = refreshInterval.toLong() * 1_000_000L
            nextFrameNanos += periodNanos
            val now = System.nanoTime()
            if (nextFrameNanos - now < -periodNanos) {
                // More than a whole period behind: a GC pause, a suspended laptop, or the
                // interval was just shortened. Re-base instead of firing a catch-up burst —
                // after a long stall a naive `next += period` would dump thousands of
                // frames onto the wire as fast as the socket accepted them.
                nextFrameNanos = now + periodNanos
            }
            val sleepNanos = nextFrameNanos - System.nanoTime()
            if (sleepNanos > 0) delay(sleepNanos.nanoseconds)
        }
    }

    private fun CoroutineScope.runChannelChangerChannel(channelNo: Int, channel: Channel<ChannelUpdatePayload>) {
        var isClosed = false

        launch(Dispatchers.Default) {
            var tickerState: TickerState? = null

            while (isActive && !isClosed) {
                select<Unit> {
                    channel.onReceiveCatching {
                        if (it.isClosed) {
                            isClosed = true
                            return@onReceiveCatching
                        }

                        tickerState?.ticker?.cancel()
                        tickerState = null

                        val result = it.getOrThrow()

                        val numberOfSteps = if (result.change.fadeMs == 0L) {
                            1
                        } else {
                            max(1, (result.change.fadeMs / fadeTickMs).toInt())
                        }

                        if (numberOfSteps > 1) {
                            tickerState = TickerState(this@ArtNetController, coroutineContext, channelNo, numberOfSteps, result)
                            if (tickerState!!.setValue(0)) {
                                tickerState = null
                            }
                        } else {
                            currentValues[channelNo] = result.change.newValue
                        }

                        result.updateNotificationChannel.send(Unit)
                    }

                    if (tickerState != null) {
                        tickerState!!.ticker.onReceive {
                            if (tickerState!!.setValue()) {
                                tickerState = null
                            }
                        }

                    }
                }
            }
        }
    }

    private fun sendCurrentValues() {
        val changes = HashMap<Int, UByte>()
        val dmxData = ByteArray(512)

        // Grab the universe park snapshot once per frame: at 40 Hz x 512 channels x N
        // controllers, dropping the per-channel outer-map lookup is meaningful.
        val parkedView = parkSource?.universeView(universe.universe)

        currentValues.forEach { (channelNo, channelValue) ->
            val parked = parkedView?.get(channelNo)
            val outputValue = if (parked != null) {
                parked
            } else {
                var v = channelValue
                for (mod in transmitModifiers) v = mod.modify(universe, channelNo, v)
                v
            }
            val byteValue = outputValue.toByte()
            dmxData[channelNo - 1] = byteValue

            if (previousSentDmxData[channelNo - 1] != byteValue) {
                changes[channelNo] = outputValue
            }
        }
        previousSentDmxData = dmxData

        if (address == null) {
            transport.broadcastDmx(universe.subnet, universe.universe, dmxData)
        } else {
            transport.unicastDmx(address, universe.subnet, universe.universe, dmxData)
        }
        packetCounter.record()

        // Load-bearing, not an optimisation. Under continuous streaming this method runs on
        // every tick, so without the guard an idle universe would broadcast a WebSocket
        // channel update to every connected client 40 times a second. `previousSentDmxData`
        // is reassigned above unconditionally, so a repeat frame yields an empty `changes`
        // and no listener call at all.
        if (changes.isNotEmpty()) {
            listeners.forEach {
                it.channelsChanged(changes)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ArtNetController

        if (universe != other.universe) return false
        if (address != other.address) return false

        return true
    }

    override fun hashCode(): Int {
        return universe.hashCode()
    }
}
