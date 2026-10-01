package uk.me.cormack.lighting7.fixture

import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.show.Fixtures

/**
 * A write that names a one-shot trigger, or its arm, refused by name (stage-view plan session 9,
 * D15). An [IllegalArgumentException], so every route and tool that already answers one with a 400
 * keeps doing so; `plugins/ErrorHandling.kt` adds the code for one that escapes a handler.
 */
class TriggerNotStorableException(message: String) : IllegalArgumentException(message) {
    companion object {
        const val CODE = "TRIGGER_NOT_STORABLE"
    }
}

/**
 * Which fixtures and groups carry a one-shot trigger, and the names they reserve — what every write
 * boundary checks a `(target, property)` against before it stores or applies it.
 *
 * A trigger is not a `@FixtureProperty`, so nothing resolves one by name; a row naming one would be
 * stored as an unhealthy row today and fire nothing. That is not good enough for a value that spends
 * a cartridge: the row would come back to life the day the type grew a property of that name, and it
 * says nothing to the operator who wrote it. So each boundary refuses it, by name, here:
 *
 * - a **fixture** target (or a cell of one): its own triggers and their arm;
 * - a **group** target: any member's;
 * - a row with **no target of its own** (a generic template row, a deferred effect), which lands on
 *   whatever is selected: every trigger name any fixture type declares.
 *
 * Built from the live register ([of] `Fixtures`) for the programmer and the live effect routes, or
 * from a project's stored patches ([of] `DaoProject`, inside a transaction) for a stored row, which
 * may belong to a project that is not loaded.
 */
class TriggerIndex private constructor(
    /** Patch key → (display name, reserved names). Only fixtures that have a trigger. */
    private val fixtures: Map<String, Pair<String, Set<String>>>,
    /** Group name → reserved names across its trigger-carrying members. Only groups that have one. */
    private val groups: Map<String, Set<String>>,
) {
    /** True when nothing in the project carries a trigger, so a row naming a target can never be refused. */
    val isEmpty: Boolean get() = fixtures.isEmpty()

    /**
     * Why [propertyName] on this target may not be stored or set, or null when it may. [targetType]
     * is the stored discriminator (`fixture`, `group`, or the deferred marker / null for a row with no
     * target of its own). [where] prefixes the message (`rows[2]`, `propertyAssignments[0]`).
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
            else -> FixtureTriggers.allReservedNames.matching(name)?.let {
                "$where: '$it' is a one-shot trigger's name, and a row with no target of its own lands on " +
                    "whatever is selected — so it cannot name one. A trigger fires as an event (a cue's " +
                    "Events, the cannon's panel, a MIDI FireTrigger), never as a stored value"
            }
        }
    }

    /** Throws [TriggerNotStorableException] naming every refused row at once, or returns. */
    fun check(rows: Iterable<RowRef>) {
        if (FixtureTriggers.allReservedNames.isEmpty()) return
        val problems = rows.mapNotNull { refusal(it.targetType, it.targetKey, it.propertyName, it.where) }
        if (problems.isNotEmpty()) throw TriggerNotStorableException(problems.joinToString("; "))
    }

    /** One `(target, property)` a write is about to store, and where it sits in the request. */
    data class RowRef(
        val targetType: String?,
        val targetKey: String?,
        val propertyName: String?,
        val where: String,
    )

    companion object {
        /** Nothing carries a trigger — the index a project with no cannon gets. */
        val EMPTY = TriggerIndex(emptyMap(), emptyMap())

        /**
         * Whether [propertyName] is a name any trigger-carrying type reserves — the cheap gate a hot
         * path (a fader's programmer write) takes before it builds an index.
         */
        fun mayRefuse(propertyName: String?): Boolean =
            propertyName != null && FixtureTriggers.allReservedNames.any { it.equals(propertyName.trim(), ignoreCase = true) }

        /** [refusal] against the live register, or null at once for a name no trigger reserves. */
        fun refusalLive(register: Fixtures, targetType: String?, targetKey: String?, propertyName: String?, where: String): String? =
            if (!mayRefuse(propertyName)) null else of(register).refusal(targetType, targetKey, propertyName, where)

        /** From the live register: the current project as it is loaded. */
        fun of(register: Fixtures): TriggerIndex {
            val fixtures = register.fixtures.mapNotNull { f ->
                FixtureTriggers.reservedNamesOf(f::class).takeIf { it.isNotEmpty() }?.let { f.key to (f.fixtureName to it) }
            }.toMap()
            if (fixtures.isEmpty()) return EMPTY
            val groups = register.groups.mapNotNull { g ->
                g.fixtures.filterIsInstance<Fixture>()
                    .flatMapTo(LinkedHashSet()) { fixtures[it.key]?.second.orEmpty() }
                    .takeIf { it.isNotEmpty() }?.let { g.name to it }
            }.toMap()
            return TriggerIndex(fixtures, groups)
        }

        /** From [project]'s stored patches and groups. Must run inside a transaction. */
        fun of(project: DaoProject): TriggerIndex {
            val patches = DaoFixturePatch.find { DaoFixturePatches.project eq project.id }.toList()
            val reservedById = patches.mapNotNull { p ->
                FixtureTriggers.reservedNamesForTypeKey(p.fixtureTypeKey).takeIf { it.isNotEmpty() }?.let { p.id.value to (p.key to (p.displayName to it)) }
            }.toMap()
            if (reservedById.isEmpty()) return EMPTY
            val groups = DaoFixtureGroup.find { DaoFixtureGroups.project eq project.id }.mapNotNull { g ->
                g.members.flatMapTo(LinkedHashSet()) { m -> reservedById[m.fixturePatch.id.value]?.second?.second.orEmpty() }
                    .takeIf { it.isNotEmpty() }?.let { g.name to it }
            }.toMap()
            return TriggerIndex(reservedById.values.toMap(), groups)
        }

        private fun Set<String>.matching(name: String): String? = firstOrNull { it.equals(name, ignoreCase = true) }

        private fun message(where: String, name: String, on: String) =
            "$where: '$name' on $on is a one-shot trigger or its arm — it fires as an event (a cue's Events, " +
                "the cannon's panel, a MIDI FireTrigger) while the desk is armed, and no Look, template, cue, " +
                "effect or programmer value can hold it"
    }
}
