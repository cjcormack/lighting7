package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.fixture.dmx.DmxTrigger
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * Marks a [DmxTrigger] member as a **one-shot trigger** (stage-view plan session 9, D15): an output
 * that spends something physical when it fires — a confetti tube, a streamer — and so must never be
 * a value a Look, a template, a cue, an effect, the programmer or Record can hold.
 *
 * A trigger is its own property kind, not a `@FixtureProperty`: nothing that resolves a property by
 * name sees it, and it takes no part in composition or a crossfade. It fires as an event — a cue
 * event, a hold-to-fire button, a MIDI `FireTrigger` — through `state/EffectsService.kt`, and only
 * while the desk is armed.
 *
 * @param description Shown on the panel and in the DMX sheet ("Tube A").
 * @param label The short name an event row and a button use ("A").
 * @param armName The name of the shared arm channel. Not a property either: the arm follows the
 *   desk's arm (D16) and nothing else may write it. Named so a stored row naming it is refused, and
 *   stripped, by name.
 * @param armDescription The arm channel's description for the DMX sheet ("Master enable").
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class FixtureTrigger(
    val description: String,
    val label: String,
    val armName: String,
    val armDescription: String,
)

/** One trigger as its class declares it. */
data class TriggerSpec(
    /** The member's name, which is the trigger's name on the wire, in a cue event and in a binding. */
    val name: String,
    val label: String,
    val description: String,
    val armName: String,
    val armDescription: String,
    internal val classProperty: KProperty1<*, *>,
)

/** One trigger of one patched fixture: its spec and the channels it owns. */
data class ResolvedTrigger(
    val spec: TriggerSpec,
    val universe: Int,
    val channelNo: Int,
    val armChannelNo: Int,
) {
    val name: String get() = spec.name
}

/**
 * The `@FixtureTrigger` members of every fixture class, and the constants a fire uses.
 *
 * Cached per class like [FixturePropertyCatalogue], and for the same reason: the scan is reflective,
 * and the trigger output asks on every fixture-register change.
 */
object FixtureTriggers {
    /** What a fire raises a trigger channel to. The Twin Shot fires at ≥ 51; full is unambiguous. */
    val FIRE_LEVEL: UByte = 255u

    /** What a trigger channel and a disarmed arm channel sit at — the Twin Shot's idle is 0–50. */
    val IDLE_LEVEL: UByte = 0u

    /** What an armed arm channel sits at. */
    val ARM_LEVEL: UByte = 255u

    /** How long a fire holds its channel high before returning it to idle. */
    val PULSE: Duration = Duration.ofMillis(300)

    /**
     * The lowest level at which any trigger here fires — the threshold a park on a trigger or arm
     * channel is refused at (`ParkSocket`, the AI's `park_channel`). Below it, a park is a lock-out
     * that holds the channel idle, which is allowed.
     */
    val FIRE_THRESHOLD: UByte = 51u

    private val specs = ConcurrentHashMap<KClass<*>, List<TriggerSpec>>()

    fun specsOf(klass: KClass<*>): List<TriggerSpec> =
        specs[klass] ?: specs.computeIfAbsent(klass) { scan(it) }

    private fun scan(klass: KClass<*>): List<TriggerSpec> =
        klass.memberProperties.mapNotNull { p ->
            val ann = p.annotations.filterIsInstance<FixtureTrigger>().firstOrNull() ?: return@mapNotNull null
            TriggerSpec(p.name, ann.label, ann.description, ann.armName, ann.armDescription, p)
        }.sortedBy { it.name }

    /** [fixture]'s triggers with their channels; empty for every fixture that has none. */
    fun of(fixture: Fixture): List<ResolvedTrigger> {
        val specs = specsOf(fixture::class)
        if (specs.isEmpty()) return emptyList()
        return specs.mapNotNull { spec ->
            @Suppress("UNCHECKED_CAST")
            val trigger = (spec.classProperty as KProperty1<Any, *>).get(fixture) as? DmxTrigger ?: return@mapNotNull null
            ResolvedTrigger(spec, trigger.universe.universe, trigger.channelNo, trigger.armChannelNo)
        }
    }

    /** Every name a stored row must not hold on a fixture of [klass]: its triggers and their arms. */
    fun reservedNamesOf(klass: KClass<*>): Set<String> =
        specsOf(klass).flatMapTo(LinkedHashSet()) { listOf(it.name, it.armName) }

    /** [reservedNamesOf] by fixture type key; empty for an unknown key or a type with no trigger. */
    fun reservedNamesForTypeKey(typeKey: String): Set<String> =
        reservedByTypeKey[typeKey].orEmpty()

    /** The trigger specs of the type [typeKey], or empty. */
    fun specsForTypeKey(typeKey: String): List<TriggerSpec> =
        FixtureTypeRegistry.classForTypeKey(typeKey)?.let { specsOf(it) }.orEmpty()

    private val reservedByTypeKey: Map<String, Set<String>> by lazy {
        FixtureTypeRegistry.allTypes.mapNotNull { info ->
            val klass = FixtureTypeRegistry.classForTypeKey(info.typeKey) ?: return@mapNotNull null
            reservedNamesOf(klass).takeIf { it.isNotEmpty() }?.let { info.typeKey to it }
        }.toMap()
    }

    /**
     * Every reserved name of every type that has a trigger — what a row with **no** target of its
     * own (a generic template row, a Look's deferred effect) is checked against, since it lands on
     * whatever is selected.
     */
    val allReservedNames: Set<String> by lazy { reservedByTypeKey.values.flatten().toSet() }
}
