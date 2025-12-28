package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.ticker
import kotlin.coroutines.CoroutineContext

@OptIn(ObsoleteCoroutinesApi::class)
internal class TickerState(private val controller: ArtNetController, coroutineContext: CoroutineContext, private val channelNo: Int, numberOfSteps: Int, channelUpdatePayload: ArtNetController.ChannelUpdatePayload) {
    val ticker: ReceiveChannel<Unit>

    private val targetValue: UByte
    private val startValue: UByte
    private val curve: EasingCurve

    private val startMs: Long
    private val fadeMs: Long

    private var lastSetValue: UByte

    init {
        val currentValue = controller.currentValues[channelNo] ?: 0u
        val stepMs = channelUpdatePayload.change.fadeMs / numberOfSteps

        startValue = currentValue
        lastSetValue = currentValue

        targetValue = channelUpdatePayload.change.newValue
        curve = channelUpdatePayload.change.curve
        ticker = ticker(stepMs, context = coroutineContext)
        startMs = System.currentTimeMillis()
        fadeMs = channelUpdatePayload.change.fadeMs
    }

    suspend fun setValue(currentTickTimeMs: Long = System.currentTimeMillis() - startMs): Boolean {
        val hasFinished: Boolean
        val newValue: UByte

        if (currentTickTimeMs >= fadeMs) {
            ticker.cancel()
            hasFinished = true
            newValue = targetValue
        } else {
            hasFinished = false
            // Calculate progress as a ratio of elapsed time to total fade time
            val progress = currentTickTimeMs.toDouble() / fadeMs.toDouble()
            // Apply easing curve to interpolate between start and target values
            newValue = curve.interpolate(startValue, targetValue, progress)
        }

        if (newValue != lastSetValue) {
            controller.currentValues[channelNo] = newValue
            lastSetValue = newValue
            controller.transmissionNeeded.send(Unit)
        }
        lastSetValue = newValue

        return hasFinished
    }
}
