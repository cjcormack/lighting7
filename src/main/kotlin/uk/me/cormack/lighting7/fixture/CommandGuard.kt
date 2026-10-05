package uk.me.cormack.lighting7.fixture

import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.show.Fixtures

/**
 * A write that names a fixture command, refused by name (fixture optics plan session 7, D13). An
 * [IllegalArgumentException], so every route and tool that already answers one with a 400 keeps
 * doing so; `plugins/ErrorHandling.kt` adds the code for one that escapes a handler.
 */
class CommandNotStorableException(message: String) : IllegalArgumentException(message) {
    companion object {
        const val CODE = "COMMAND_NOT_STORABLE"
    }
}

/**
 * Which fixtures and groups carry a fixture command, and the names they reserve — what every write
 * boundary checks a `(target, property)` against before it stores or applies it, beside
 * [TriggerIndex] and on the same three rules:
 *
 * - a **fixture** target (or a cell of one): its own commands;
 * - a **group** target: any member's;
 * - a row with **no target of its own** (a generic template row, a deferred effect), which lands on
 *   whatever is selected: every command name any fixture type declares.
 *
 * A command is not a `@FixtureProperty`, so nothing resolves one by name and a row naming one would
 * write nothing — but the Varytec's and the Shehds' `reset` *were* properties until this session, and
 * a row naming a name would come back to life the day a type grew a property of it. So it is refused,
 * by name, and says why.
 *
 * The other half of the guard is by value: a property that shares its channel with a command (the
 * MAC 250's shutter, the Orbit's program channel) can still be handed a level in the command's band.
 * The output refuses that ([uk.me.cormack.lighting7.state.CommandOutput]), whatever wrote it — and a
 * **stored** Look or cue row holding one is refused here too ([levelRefusal], through [check] for a
 * [TriggerIndex.RowRef] that carries its value), by the rule the stored-row strip deletes one by
 * (`models/commandRows.kt`): a fixture row by its own type's bands, a group row by every member's.
 */
class CommandIndex private constructor(
    /** Patch key → (display name, reserved names). Only fixtures that have a command. */
    private val fixtures: Map<String, Pair<String, Set<String>>>,
    /** Group name → reserved names across its command-carrying members. Only groups that have one. */
    private val groups: Map<String, Set<String>>,
    /** Patch key → (display name, property → shared bands). Only fixtures with a shared command channel. */
    private val fixtureBands: Map<String, Pair<String, Map<String, List<FixtureCommands.SharedBand>>>> = emptyMap(),
    /** Group name → property → shared bands across its members. Only groups with such a member. */
    private val groupBands: Map<String, Map<String, List<FixtureCommands.SharedBand>>> = emptyMap(),
) {
    /**
     * Why [propertyName] on this target may not be stored or set, or null when it may. Arguments as
     * [TriggerIndex.refusal]'s.
     */
    fun refusal(targetType: String?, targetKey: String?, propertyName: String?, where: String): String? {
        val name = propertyName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (targetType?.lowercase()) {
            "fixture" -> {
                val key = targetKey ?: return null
                val (label, reserved) = fixtures[key] ?: fixtures[key.substringBefore('.')] ?: return null
                reserved.matching(name)?.let { message(where, it, "'$label'") }
            }
            "group" -> {
                val reserved = groups[targetKey] ?: return null
                reserved.matching(name)?.let { message(where, it, "a member of group '$targetKey'") }
            }
            else -> FixtureCommands.allReservedNames.matching(name)?.let {
                "$where: '$it' is a fixture command's name, and a row with no target of its own lands on " +
                    "whatever is selected — so it cannot name one. A command runs from the fixture panel's " +
                    "Commands menu, never as a stored value"
            }
        }
    }

    /**
     * Why a stored level [value] for [propertyName] on this target may not be kept, or null when it
     * may: the property shares its channel with a command and the level sits inside that command's
     * band, so it would *be* the command each time it played (the output's band guard would send idle
     * instead). Exact patch keys and group names only — a head's own property never covers a command
     * channel (`FixtureCommandsTest`) — and only a whole DMX level, as the strip reads one.
     */
    fun levelRefusal(targetType: String?, targetKey: String?, propertyName: String?, value: String?, where: String): String? {
        val property = propertyName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val level = value?.trim()?.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        val (bands, on) = when (targetType?.lowercase()) {
            "fixture" -> fixtureBands[targetKey]?.let { (label, b) -> b to "'$label'" } ?: return null
            "group" -> groupBands[targetKey]?.let { it to "a member of group '$targetKey'" } ?: return null
            else -> return null
        }
        val hit = bands[property]?.firstOrNull { level in it.range } ?: return null
        return "$where: $property = $level on $on is inside the '${hit.command}' command's band " +
            "(${hit.range.first}–${hit.range.last}), so playing it would run the command — it runs from " +
            "the fixture panel's Commands menu, never as a stored value"
    }

    /** Throws [CommandNotStorableException] naming every refused row at once, or returns. */
    fun check(rows: Iterable<TriggerIndex.RowRef>) {
        if (FixtureCommands.allReservedNames.isEmpty()) return
        val problems = rows.mapNotNull {
            refusal(it.targetType, it.targetKey, it.propertyName, it.where)
                ?: levelRefusal(it.targetType, it.targetKey, it.propertyName, it.value, it.where)
        }
        if (problems.isNotEmpty()) throw CommandNotStorableException(problems.joinToString("; "))
    }

    companion object {
        /** No fixture carries a command — and the index a row with no target is judged by alone. */
        val EMPTY = CommandIndex(emptyMap(), emptyMap())

        /** Whether [propertyName] is a name any command-carrying type reserves — the hot path's cheap gate. */
        fun mayRefuse(propertyName: String?): Boolean =
            propertyName != null && FixtureCommands.allReservedNames.any { it.equals(propertyName.trim(), ignoreCase = true) }

        /**
         * Whether a stored row for [propertyName] could be refused at all — by name, or by level on a
         * property that shares a command's channel somewhere in the library ([levelRefusal]). The
         * write boundaries' gate before they build an index, which is queries.
         */
        fun mayRefuseRow(propertyName: String?): Boolean =
            mayRefuse(propertyName) || (propertyName != null && propertyName.trim() in FixtureCommands.allSharedBandProperties)

        /** [refusal] against the live register, or null at once for a name no command reserves. */
        fun refusalLive(register: Fixtures, targetType: String?, targetKey: String?, propertyName: String?, where: String): String? =
            if (!mayRefuse(propertyName)) null else of(register).refusal(targetType, targetKey, propertyName, where)

        /** From the live register: the current project as it is loaded. */
        fun of(register: Fixtures): CommandIndex {
            val fixtures = register.fixtures.mapNotNull { f ->
                FixtureCommands.reservedNamesOf(f::class).takeIf { it.isNotEmpty() }?.let { f.key to (f.fixtureName to it) }
            }.toMap()
            if (fixtures.isEmpty()) return EMPTY
            val groups = register.groups.mapNotNull { g ->
                g.fixtures.filterIsInstance<Fixture>()
                    .flatMapTo(LinkedHashSet()) { fixtures[it.key]?.second.orEmpty() }
                    .takeIf { it.isNotEmpty() }?.let { g.name to it }
            }.toMap()
            val fixtureBands = register.fixtures.mapNotNull { f ->
                FixtureCommands.sharedBandsForTypeKey(f.typeKey).takeIf { it.isNotEmpty() }?.let { f.key to (f.fixtureName to it) }
            }.toMap()
            val groupBands = register.groups.mapNotNull { g ->
                mergeBands(g.fixtures.filterIsInstance<Fixture>().mapNotNull { fixtureBands[it.key]?.second })?.let { g.name to it }
            }.toMap()
            return CommandIndex(fixtures, groups, fixtureBands, groupBands)
        }

        /** From [project]'s stored patches and groups. Must run inside a transaction. */
        fun of(project: DaoProject): CommandIndex {
            val patches = DaoFixturePatch.find { DaoFixturePatches.project eq project.id }.toList()
            val reservedById = patches.mapNotNull { p ->
                FixtureCommands.reservedNamesForTypeKey(p.fixtureTypeKey).takeIf { it.isNotEmpty() }?.let { p.id.value to (p.key to (p.displayName to it)) }
            }.toMap()
            if (reservedById.isEmpty()) return EMPTY
            val bandsById = patches.mapNotNull { p ->
                FixtureCommands.sharedBandsForTypeKey(p.fixtureTypeKey).takeIf { it.isNotEmpty() }?.let { p.id.value to (p.key to (p.displayName to it)) }
            }.toMap()
            val groups = mutableMapOf<String, Set<String>>()
            val groupBands = mutableMapOf<String, Map<String, List<FixtureCommands.SharedBand>>>()
            for (g in DaoFixtureGroup.find { DaoFixtureGroups.project eq project.id }) {
                val members = g.members.map { it.fixturePatch.id.value }
                members.flatMapTo(LinkedHashSet()) { reservedById[it]?.second?.second.orEmpty() }
                    .takeIf { it.isNotEmpty() }?.let { groups[g.name] = it }
                mergeBands(members.mapNotNull { bandsById[it]?.second?.second })?.let { groupBands[g.name] = it }
            }
            return CommandIndex(reservedById.values.toMap(), groups, bandsById.values.toMap(), groupBands)
        }

        /** Every member's bands, per property, or null when no member has one. */
        private fun mergeBands(perMember: List<Map<String, List<FixtureCommands.SharedBand>>>): Map<String, List<FixtureCommands.SharedBand>>? {
            if (perMember.isEmpty()) return null
            val merged = LinkedHashMap<String, LinkedHashSet<FixtureCommands.SharedBand>>()
            for (bands in perMember) for ((property, list) in bands) merged.getOrPut(property) { LinkedHashSet() }.addAll(list)
            return merged.mapValues { it.value.toList() }
        }

        private fun Set<String>.matching(name: String): String? = firstOrNull { it.equals(name, ignoreCase = true) }

        private fun message(where: String, name: String, on: String) =
            "$where: '$name' on $on is a fixture command — it runs from the fixture panel's Commands menu, " +
                "behind a confirm, and no Look, template, cue, effect or programmer value can hold it"
    }
}
