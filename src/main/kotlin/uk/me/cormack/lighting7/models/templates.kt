package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * A **Template**: a named value for one attribute family, applied to a selection.
 *
 * The other half of the split [DaoLooks] used to serve alone. A Look *composes cues* — any
 * families, its own fixtures, its own effects, added to a stack as a layer with order, mask,
 * amount, timing and stomp. A Template *composes values* — exactly one family, no effects, no
 * targets of its own, and either applied to a selection as literals or tracked by a layer.
 *
 * Two tables rather than a `kind` column on `looks`, and the reason is the **per-fixture** case: a
 * focus position — eight heads aimed at one spot — holds eight different pan/tilts, so its rows are
 * *bound*, which is exactly a recorded Look's shape. `hasDeferredRows` could therefore never
 * separate the two, and the design's "one backend table, two front doors" does not survive contact
 * with the most useful kind of template there is. See
 * `docs/plans/desk-simplification-plan.md` §Session 3.
 *
 * What a template deliberately does **not** have, each for its own reason:
 *
 * - **No `editorFixtureType`.** That column existed only so the form editor could build a synthetic
 *   fixture to render a property grid against, and it is what made "Amber Key" offerable to a MAC
 *   Aura and refused to the LED bar beside it (D6). A template stores an *intent*
 *   ([uk.me.cormack.lighting7.fx.TemplateIntent]) resolved per head at cook, so there is no mode to
 *   resolve it against and nothing to declare.
 * - **No effects.** D7: effects live in a Look or on a cue, never on a layer — and a template is
 *   the thing a layer tracks. `POST /looks/{id}/absorb-effects` remains the way a running effect
 *   joins a library entity.
 * - **No positional colour list.** There used to be one on every cue, stack and Look — the `P1` /
 *   `P2` grammar FX parameters indexed, cascading `look > cue > global` — and a template
 *   deliberately did not join it. That whole grammar is now gone, and the inverse arrangement is
 *   what replaced it: an effect parameter names a *template*
 *   ([uk.me.cormack.lighting7.fx.templateColourSource]), so a template is the colour rather than a
 *   scope that holds a list of them.
 * - **No stored family.** Which family a template is in is *derived* from its rows, exactly as a
 *   Look's `families` are, and validated to be **exactly one** at the write boundary. A declared
 *   column would be a second source of truth for something the rows already say.
 */
object DaoTemplates : IntIdTable("templates") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 255)
    val notes = text("notes").nullable()
    val sortOrder = integer("sort_order").default(0)

    /**
     * Fade for every row this template writes, in ms; null = the caller's default.
     *
     * On the template rather than per row, unlike [DaoLookRows.fadeDurationMs]. A template is one
     * value in one family, so a per-row fade would only ever be N copies of one number — and the
     * per-fixture case (one row per head of the same focus position) is exactly where they must
     * agree.
     */
    val fadeDurationMs = long("fade_duration_ms").nullable()
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        // Same identity rule as a Look: (project, name). "Amber Key" is one template per project,
        // which is the entire point of dropping the fixture type — the old per-type namespace is
        // what forced one colour to exist several times over.
        uniqueIndex(project, name)
    }
}

class DaoTemplate(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoTemplate>(DaoTemplates)

    var project by DaoProject referencedOn DaoTemplates.project
    var name by DaoTemplates.name
    var notes by DaoTemplates.notes
    var sortOrder by DaoTemplates.sortOrder
    var fadeDurationMs by DaoTemplates.fadeDurationMs
    var uuid by DaoTemplates.uuid
    val rows by DaoTemplateRow referrersOn DaoTemplateRows.template
}

/**
 * One stored template row: "for this target, this property is this intent".
 *
 * [targetType] is either [DEFERRED_TARGET_TYPE] — a **generic** template, one value for any head,
 * taking its targets from whatever applies it — or `fixture`, a **per-fixture** template recorded
 * from where the heads actually are. Those are the two shapes the library row labels *Generic* and
 * *Per fixture*; they are not two entities, and a group row is not a third: a template names no
 * targets of its own by definition, so the only reason a row names a fixture is that its value is
 * specific to that fixture, which a group cannot be.
 *
 * [value] is an **intent**, not a literal — see [uk.me.cormack.lighting7.fx.TemplateIntent] for the
 * grammar and [uk.me.cormack.lighting7.fx.TemplateResolver] for the one implementation that turns
 * one into channels.
 *
 * There is deliberately **no `elementKey`**. Element-scoped rows compose nowhere
 * (`FU-LOOK-ELEMENT-ROWS`), and a template is a *rule*; a per-element rule is precisely the case
 * that has no composition at all. A per-element value belongs in a recorded Look, which names a
 * head and can therefore hold anything that head has.
 */
object DaoTemplateRows : IntIdTable("template_rows") {
    val template = reference("template_id", DaoTemplates)

    /** [DEFERRED_TARGET_TYPE], or `fixture` for a per-fixture template. Never `group`. */
    val targetType = varchar("target_type", 50)

    /** Empty string when [targetType] is [DEFERRED_TARGET_TYPE]. */
    val targetKey = varchar("target_key", 255)
    val propertyName = varchar("property_name", 255)

    /** A [uk.me.cormack.lighting7.fx.TemplateIntent] in its serialised form. */
    val value = text("value")
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()
}

class DaoTemplateRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoTemplateRow>(DaoTemplateRows)

    var template by DaoTemplate referencedOn DaoTemplateRows.template
    var targetType by DaoTemplateRows.targetType
    var targetKey by DaoTemplateRows.targetKey
    var propertyName by DaoTemplateRows.propertyName
    var value by DaoTemplateRows.value
    var sortOrder by DaoTemplateRows.sortOrder
    var uuid by DaoTemplateRows.uuid

    val isDeferred: Boolean get() = targetType == DEFERRED_TARGET_TYPE

    /** The row's own target, or null when deferred or the discriminator names no known arm. */
    val target: TargetRef?
        get() = if (isDeferred) null else TargetRef.ofOrNull(targetType, targetKey)
}

/** One template row on the wire. */
@Serializable
data class TemplateRowDto(
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    val value: String,
    val sortOrder: Int = 0,
    /**
     * Validation of this row against the live patch. Populated server-side on read, ignored on
     * write — same contract as [LookRowDto.health]. A *deferred* row is always `Ok`: it names no
     * target, so there is nothing about it that can go stale.
     */
    val health: AssignmentHealth = AssignmentHealth.Ok,
) {
    val isDeferred: Boolean get() = targetType == DEFERRED_TARGET_TYPE

    val target: TargetRef? get() = if (isDeferred) null else TargetRef.of(targetType, targetKey)
}
