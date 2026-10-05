package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.fixture.dmx.DmxChannelHold
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxCommand
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * Marks a [DmxCommand] member as a **fixture command** (fixture optics plan session 7, D13): a reset,
 * a lamp strike, a lamp off — something the fixture does once when a level is held on a channel, and
 * that must never be a value a Look, a template, a cue, an effect, the programmer or Record can hold.
 * A reset recorded into a Look re-homes every head on every recall; a lamp off in a cue is a dark
 * stage for eight minutes.
 *
 * A command is its own kind, not a `@FixtureProperty`: nothing that resolves a property by name sees
 * it, and it takes no part in composition or a crossfade. It runs from the fixture panel's
 * *Commands* menu behind a confirm (`POST …/patches/{pid}/commands/{command}`), or from MCP's
 * `run_fixture_command`, and the desk holds its level for [holdMs] before giving the channel back.
 *
 * @param label The menu item ("Reset scroller").
 * @param description What it does and what it costs, shown in the confirm ("Re-homes the gel scroller
 *   and the lenses. The beam moves while it runs.").
 * @param holdMs How long the desk holds the level. The manual's time where it states one, marked
 *   `// Estimate:` where it does not (D15).
 * @param confirm Whether the panel asks first. Every command in the library does; a harmless one may
 *   opt out.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class FixtureCommand(
    val label: String,
    val description: String,
    val holdMs: Long,
    val confirm: Boolean = true,
)

/** One command as its class declares it. */
data class CommandSpec(
    /** The member's name, which is the command's name on the wire and in the route. */
    val name: String,
    val label: String,
    val description: String,
    val holdMs: Long,
    val confirm: Boolean,
    internal val classProperty: KProperty1<*, *>,
)

/** One command of one patched fixture: its spec and the channels it holds. */
data class ResolvedCommand(
    val spec: CommandSpec,
    val universe: Int,
    val channelNo: Int,
    val level: UByte,
    val bandMin: UByte,
    val bandMax: UByte,
    val idleLevel: UByte,
    val alongside: List<DmxChannelHold>,
    /** No property of the fixture covers [channelNo]: the desk owns it between commands too. */
    val dedicated: Boolean,
) {
    val name: String get() = spec.name

    /** Whether [value] on [channelNo] is this command. */
    fun inBand(value: UByte): Boolean = value in bandMin..bandMax
}

/**
 * The `@FixtureCommand` members of every fixture class.
 *
 * The specs are cached per class like [FixtureTriggers], for the same reason: the scan is reflective,
 * and the command output asks on every fixture-register change. [of] resolves an instance's channels
 * afresh each time, as [FixtureTriggers.of] does — a fixture with no commands returns before any walk.
 */
object FixtureCommands {
    /** The longest hold a declaration may ask for; `FixtureCommandsTest` enforces it. */
    const val MAX_HOLD_MS: Long = 15_000

    private val specs = ConcurrentHashMap<KClass<*>, List<CommandSpec>>()

    fun specsOf(klass: KClass<*>): List<CommandSpec> =
        specs[klass] ?: specs.computeIfAbsent(klass) { scan(it) }

    private fun scan(klass: KClass<*>): List<CommandSpec> =
        klass.memberProperties.mapNotNull { p ->
            val ann = p.annotations.filterIsInstance<FixtureCommand>().firstOrNull() ?: return@mapNotNull null
            CommandSpec(p.name, ann.label, ann.description, ann.holdMs, ann.confirm, p)
        }.sortedBy { it.name }

    /** [fixture]'s commands with their channels, in name order; empty for every fixture that has none. */
    fun of(fixture: Fixture): List<ResolvedCommand> {
        if (fixture !is DmxFixture) return emptyList()
        val specs = specsOf(fixture::class)
        if (specs.isEmpty()) return emptyList()
        val covered = propertyChannelsOf(fixture)
        return specs.mapNotNull { spec ->
            @Suppress("UNCHECKED_CAST")
            val command = (spec.classProperty as KProperty1<Any, *>).get(fixture) as? DmxCommand ?: return@mapNotNull null
            ResolvedCommand(
                spec = spec,
                universe = command.universe.universe,
                channelNo = command.channelNo,
                level = command.level,
                bandMin = command.bandMin,
                bandMax = command.bandMax,
                idleLevel = command.idleLevel,
                alongside = command.alongside,
                dedicated = command.channelNo !in covered,
            )
        }
    }

    /**
     * Every channel a `@FixtureProperty` of [fixture] — or of one of its heads — drives. A command on
     * any other channel is dedicated.
     */
    fun propertyChannelsOf(fixture: DmxFixture): Set<Int> {
        val out = HashSet<Int>()
        fun add(value: Any?) {
            when (value) {
                is DmxSlider -> out += value.channelNo
                is DmxColour -> {
                    out += value.redSlider.channelNo
                    out += value.greenSlider.channelNo
                    out += value.blueSlider.channelNo
                }
                is DmxFixtureSetting<*> -> out += value.channelNo
            }
        }
        for (prop in fixture.fixtureProperties) add(runCatching { prop.classProperty.call(fixture) }.getOrNull())
        if (fixture is MultiElementFixture<*>) {
            for (element in fixture.elements) {
                for (prop in FixturePropertyCatalogue.of(element::class).all) {
                    @Suppress("UNCHECKED_CAST")
                    add(runCatching { (prop.classProperty as KProperty1<Any, *>).call(element) }.getOrNull())
                }
            }
        }
        return out
    }

    /** Every name a stored row must not hold on a fixture of [klass]: its commands'. */
    fun reservedNamesOf(klass: KClass<*>): Set<String> = specsOf(klass).mapTo(LinkedHashSet()) { it.name }

    /** [reservedNamesOf] by fixture type key; empty for an unknown key or a type with no command. */
    fun reservedNamesForTypeKey(typeKey: String): Set<String> = reservedByTypeKey[typeKey].orEmpty()

    /** The command specs of the type [typeKey], or empty. */
    fun specsForTypeKey(typeKey: String): List<CommandSpec> =
        FixtureTypeRegistry.classForTypeKey(typeKey)?.let { specsOf(it) }.orEmpty()

    private val reservedByTypeKey: Map<String, Set<String>> by lazy {
        FixtureTypeRegistry.allTypes.mapNotNull { info ->
            val klass = FixtureTypeRegistry.classForTypeKey(info.typeKey) ?: return@mapNotNull null
            reservedNamesOf(klass).takeIf { it.isNotEmpty() }?.let { info.typeKey to it }
        }.toMap()
    }

    /**
     * Every command name of every type — what a row with **no** target of its own (a generic template
     * row, a Look's deferred effect) is checked against, since it lands on whatever is selected.
     */
    val allReservedNames: Set<String> by lazy { reservedByTypeKey.values.flatten().toSet() }

    /** One band a property must not hold, because a command shares its channel. */
    data class SharedBand(val command: String, val range: IntRange)

    /**
     * For the type [typeKey]: each fixture-level property that shares a channel with a command, and
     * the bands it must not hold — what the stored-row strip judges a level against. Empty for a
     * type whose commands are all on dedicated channels, or that has none.
     */
    fun sharedBandsForTypeKey(typeKey: String): Map<String, List<SharedBand>> =
        sharedBandsByTypeKey.computeIfAbsent(typeKey) { key ->
            if (specsForTypeKey(key).isEmpty()) return@computeIfAbsent emptyMap()
            val fixture = FixtureTypeRegistry.introspectionInstanceForTypeKey(key) ?: return@computeIfAbsent emptyMap()
            val shared = of(fixture).filterNot { it.dedicated }
            if (shared.isEmpty()) return@computeIfAbsent emptyMap()
            fixture.fixtureProperties.mapNotNull { prop ->
                val channel = when (val v = runCatching { prop.classProperty.call(fixture) }.getOrNull()) {
                    is DmxSlider -> v.channelNo
                    is DmxFixtureSetting<*> -> v.channelNo
                    else -> return@mapNotNull null
                }
                shared.filter { it.channelNo == channel }
                    .map { SharedBand(it.name, it.bandMin.toInt()..it.bandMax.toInt()) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { prop.name to it }
            }.toMap()
        }

    private val sharedBandsByTypeKey = ConcurrentHashMap<String, Map<String, List<SharedBand>>>()

    /** Every property name that shares a command's channel on some type — the write boundaries' gate. */
    val allSharedBandProperties: Set<String> by lazy {
        reservedByTypeKey.keys.flatMapTo(HashSet()) { sharedBandsForTypeKey(it).keys }
    }
}
