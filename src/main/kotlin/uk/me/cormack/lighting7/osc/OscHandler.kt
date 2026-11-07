package uk.me.cormack.lighting7.osc

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.history.WriteOrigin
import uk.me.cormack.lighting7.midi.ActionOutcome
import uk.me.cormack.lighting7.midi.SurfaceDispatch
import uk.me.cormack.lighting7.telemetry.TraceSampling
import uk.me.cormack.lighting7.telemetry.Tracing
import io.sentry.Sentry
import io.sentry.SpanStatus
import java.time.Duration
import java.time.Instant

/** A log row's outcome (OSC plan §3 "Outcomes and the log"). */
enum class OscOutcome { DONE, NOT_FOUND, AMBIGUOUS, REFUSED, FAILED, BAD_MESSAGE }

/** What one message did, for its log row; [resolved] when it reached a record, for the received set. */
data class OscHandled(val outcome: OscOutcome, val detail: String, val resolved: Boolean = false)

/**
 * One message, resolved and dispatched (OSC plan D2, D7, D8): its address against the current
 * project through [lookupFor], read in one transaction as a REST request reads, then its target
 * through the [SurfaceDispatch] a MIDI surface uses, under the sender's own [WriteOrigin] so History
 * names it. Runs on the service's collector, one message at a time, and never throws: any throw — a
 * project switch in flight among the causes — is a *failed* row.
 */
class OscHandler(
    private val database: Database,
    private val currentProjectId: () -> Int,
    private val dispatch: SurfaceDispatch,
    private val ensureHistory: (Int) -> Unit = {},
    private val lookupFor: (Int) -> OscLookup = ::DbOscLookup,
) {
    /** The start of the second [tracesThisSecond] counts, in nanos; touched only by the collector. */
    private var traceSecondStart = 0L
    private var tracesThisSecond = 0

    /**
     * One message as an `osc.message` transaction (`TraceSampling.OP_OSC_MESSAGE`) named by the
     * pattern it matched, tagged with its outcome — at most [TRACE_BUDGET_PER_SECOND] a second, past
     * which a message runs untraced, so a flood cannot spend the trace quota. A GO it makes is the
     * existing `cue.go`, a child here where this transaction is sampled and its own where it is not.
     */
    fun handle(inbound: OscInbound): OscHandled {
        if (!Sentry.isEnabled() || !withinTraceBudget()) return handleUntraced(inbound)
        val address = (inbound.item as? OscPacket.Item.Message)?.message?.address
        val name = address?.let(OscAddresses::patternOf) ?: if (address == null) BAD_PACKET else UNKNOWN_ADDRESS
        return Tracing.transaction(TraceSampling.OP_OSC_MESSAGE, name) { span ->
            span?.setData("osc.queued_ms", Duration.between(inbound.at, Instant.now()).toMillis())
            handleUntraced(inbound).also { handled ->
                span?.setTag(OUTCOME_TAG, handled.outcome.name.lowercase())
                span?.status = when (handled.outcome) {
                    OscOutcome.DONE -> SpanStatus.OK
                    OscOutcome.NOT_FOUND -> SpanStatus.NOT_FOUND
                    OscOutcome.AMBIGUOUS, OscOutcome.BAD_MESSAGE -> SpanStatus.INVALID_ARGUMENT
                    OscOutcome.REFUSED -> SpanStatus.FAILED_PRECONDITION
                    OscOutcome.FAILED -> SpanStatus.INTERNAL_ERROR
                }
            }
        }
    }

    private fun withinTraceBudget(): Boolean = synchronized(this) {
        val now = System.nanoTime()
        if (now - traceSecondStart >= 1_000_000_000L) {
            traceSecondStart = now
            tracesThisSecond = 0
        }
        tracesThisSecond++ < TRACE_BUDGET_PER_SECOND
    }

    private fun handleUntraced(inbound: OscInbound): OscHandled {
        val message = when (val item = inbound.item) {
            is OscPacket.Item.Bad -> return OscHandled(OscOutcome.BAD_MESSAGE, item.reason)
            is OscPacket.Item.Message -> item.message
        }
        var handled: OscHandled? = null
        val outcome = SurfaceDispatch.guarded("OSC ${message.address} from ${inbound.sender.name}") {
            val projectId = currentProjectId()
            val resolution = Tracing.span("osc.resolve") {
                transaction(database) { OscAddresses.resolve(message.address, message.args, lookupFor(projectId)) }
            }
            handled = when (resolution) {
                is OscResolution.NotFound -> OscHandled(OscOutcome.NOT_FOUND, resolution.message)
                is OscResolution.Ambiguous -> OscHandled(OscOutcome.AMBIGUOUS, resolution.message)
                is OscResolution.Bad -> OscHandled(OscOutcome.BAD_MESSAGE, resolution.message)
                is OscResolution.Press, is OscResolution.Level -> null
            }
            if (handled != null) return@guarded ActionOutcome.DONE
            val origin = WriteOrigin(HistorySource.osc(inbound.sender.id, inbound.sender.name)).apply {
                label = "${inbound.sender.name} ${message.address}"
            }
            runCatching { ensureHistory(projectId) }
            val (reached, done) = Tracing.span("osc.dispatch") { WriteOrigin.with(origin) {
                when (resolution) {
                    is OscResolution.Press ->
                        resolution.reached to dispatch.press(resolution.target, SurfaceDispatch.Origin(DEVICE_TYPE, -1, origin.source))
                    is OscResolution.Level -> resolution.reached to dispatch.level(resolution.target, resolution.value)
                    else -> error("resolved above")
                }
            } }
            handled = when (done) {
                is ActionOutcome.Done -> OscHandled(OscOutcome.DONE, listOfNotNull(reached, done.detail).joinToString(" · "), resolved = true)
                is ActionOutcome.Refused -> OscHandled(OscOutcome.REFUSED, "$reached: ${done.message}", resolved = true)
                is ActionOutcome.NotFound -> OscHandled(OscOutcome.NOT_FOUND, done.message, resolved = true)
                is ActionOutcome.Failed -> OscHandled(OscOutcome.FAILED, "$reached: ${done.message}", resolved = true)
            }
            ActionOutcome.DONE
        }
        if (outcome is ActionOutcome.Failed) return OscHandled(OscOutcome.FAILED, outcome.message, resolved = handled?.resolved == true)
        return handled ?: OscHandled(OscOutcome.FAILED, "no outcome")
    }

    companion object {
        /** The device type an OSC press names, which only an encoder bank would read. */
        const val DEVICE_TYPE = "osc"

        /** Traced messages a second; the rest run untraced. */
        const val TRACE_BUDGET_PER_SECOND = 5

        const val OUTCOME_TAG = "osc.outcome"

        /** A trace's name for a message that matched no row, or for an unreadable packet. */
        const val UNKNOWN_ADDRESS = "unknown address"
        const val BAD_PACKET = "bad packet"
    }
}
