package uk.me.cormack.lighting7.dmx

import ch.bildspur.artnet.ArtNetClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.selects.select
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
class ArtNetController(override val subnet: Int, override val universe: Int, val address: String? = null, val needsRefresh: Boolean = false): DmxController {
    internal val fadeTickMs = 10

    private val artnet = ArtNetClient()

    private val channelChangeChannels: Map<Int, Channel<ChannelUpdatePayload>>

    internal val transmissionNeeded = Channel<Unit>(Channel.Factory.CONFLATED)

    override val currentValues = ConcurrentHashMap<Int, UByte>(512)

    private var previousSentDmxData = ByteArray(512)

    private val listeners = ArrayList<ChannelChangeListener>()

    init {
        artnet.start()

        channelChangeChannels = HashMap()

        for (channelNo in 1..512) {
            currentValues[channelNo] = 0u
        }

        GlobalScope.launch {
            runTransmissionChannel()

            (1..512).forEach { channelNo ->
                val channel = Channel<ChannelUpdatePayload>(Channel.Factory.CONFLATED)
                channelChangeChannels[channelNo] = channel
                runChannelChangerChannel(channelNo, channel)
            }
        }
    }

    class ChannelUpdatePayload(val change: ChannelChange, val updateNotificationChannel: Channel<Unit>)

    override fun setValues(valuesToSet: List<Pair<Int, ChannelChange>>) {
        var valuesChanged = false

        runBlocking {
            valuesToSet.forEach { (channelNo, channelChange) ->
                if (doSetChannel(channelNo, channelChange)) {
                    valuesChanged = true
                }
            }
        }

        if (valuesChanged) {
            runBlocking {
                transmissionNeeded.send(Unit)
            }
        }
    }

    override fun setValue(channelNo: Int, channelChange: ChannelChange) {
        val hasUpdated = runBlocking {
            doSetChannel(channelNo, channelChange)
        }

        if (hasUpdated) {
            runBlocking {
                transmissionNeeded.send(Unit)
            }
        }
    }

    override fun setValue(channelNo: Int, channelValue: UByte, fadeMs: Long) {
        setValue(channelNo, ChannelChange(channelValue, fadeMs))
    }

    private fun CoroutineScope.doSetChannel(channelNo: Int, channelChange: ChannelChange): Boolean {
        if (channelNo < 1 || channelNo > 512) {
            return false
        }
        if (channelChange.newValue < 0u || channelChange.newValue > 255u) {
            return false
        }

        val changeChannel = channelChangeChannels[channelNo]
        checkNotNull(changeChannel)

        val updateDoneChannel = Channel<Unit>()

        launch {
            changeChannel.send(ChannelUpdatePayload(channelChange, updateDoneChannel))
            updateDoneChannel.receive()
        }

        return true
    }

    override fun getValue(channelNo: Int): UByte {
        return currentValues[channelNo] ?: 0u
    }

    fun registerListener(listener: ChannelChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun unregisterListener(listener: ChannelChangeListener) {
        listeners.remove(listener)
    }

    fun close() {
        transmissionNeeded.close()
    }

    private fun CoroutineScope.runTransmissionChannel() {
        var isClosed = false

        sendCurrentValues()

        val ticker = ticker(25)
        val sendEverythingTicker = if (needsRefresh) {
            ticker(1000)
        } else {
            null
        }

        var consecutiveErrors = 0

        launch(newSingleThreadContext("ArtNetThread-$subnet-$universe")) {
            while(coroutineContext.isActive && !isClosed) {
                try {
                    select<Unit> {
                        ticker.onReceiveCatching {
                            if (it.isClosed) {
                                return@onReceiveCatching
                            }

                            select<Unit> {
                                transmissionNeeded.onReceiveCatching { it ->
                                    if (it.isClosed) {
                                        isClosed = true
                                        ticker.cancel()
                                        return@onReceiveCatching
                                    }
                                }
                                if (sendEverythingTicker != null) {
                                    sendEverythingTicker.onReceiveCatching {
                                    }
                                }
                            }

                            sendCurrentValues()
                        }
                    }
                    consecutiveErrors = 0
                } catch (e: Exception) {
                    if (consecutiveErrors == 0) {
                        e.printStackTrace()
                    }
                    consecutiveErrors++

                    if (consecutiveErrors > 20) {
                        // if too many errors, we'll bail out and let this thing stop. A restart of NK is needed.
                        println("Too many consecutive errors")
                        throw e
                    }
                    delay(25)
                }
            }

            artnet.stop()
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

        currentValues.forEach { (channelNo, channelValue) ->
            val byteValue = channelValue.toByte()
            dmxData[channelNo - 1] = byteValue

            if (previousSentDmxData[channelNo - 1] != byteValue) {
                changes[channelNo] = channelValue
            }
        }
        previousSentDmxData = dmxData

        if (address == null) {
            artnet.broadcastDmx(subnet, universe, dmxData)
        } else {
            artnet.unicastDmx(address, subnet, universe, dmxData)
        }

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
        if (subnet != other.subnet) return false
        if (address != other.address) return false

        return true
    }

    override fun hashCode(): Int {
        var result = universe
        result = 31 * result + subnet
        return result
    }
}
