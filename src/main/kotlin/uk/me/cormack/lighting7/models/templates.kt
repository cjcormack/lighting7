package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.json.json

/**
 * A **Template group**: an ordered cluster of templates whose busk pads are mutually exclusive.
 *
 * Two facts, and nothing else:
 *
 * - **Order.** A group holds a place in the project's top-level sequence — the one ungrouped
 *   templates also occupy ([DaoTemplates.sortOrder]) — and its members hold places within it. The
 *   busk view renders a group as one bordered cluster inside its family column; `/templates` renders
 *   it as a container the operator drags templates into and out of.
 * - **Exclusivity.** Pressing a member's busk pad on a target set takes those targets off every
 *   *sibling's* layer first, in the same stack mutation (`ProgrammerLayerStack.toggle`'s
 *   `releaseSiblings`). Per **target**, not per target set: a sibling holding exactly them comes
 *   off, one that merely overlaps is narrowed to the targets the press did not name, and a disjoint
 *   one is untouched — Amber on the front wash and Blue on the back wash are two pads on two rigs,
 *   not a conflict. That is the same reading of coverage the pads' own lit ring uses, which is what
 *   the whole-set rule this started with got wrong: it left Amber's layer under Blue's on a shared
 *   fixture, lighting Amber's pad for a fixture Blue owned and popping Amber back when Blue came
 *   off. Pressing a lit pad *off* touches no sibling.
 *
 * What a group deliberately does **not** have:
 *
 * - **No stored family.** Like a template's, it is derived — from the members — and enforced to be
 *   exactly one at the write boundary (`TEMPLATE_GROUP_FAMILY`). A colour pad and a position pad
 *   never fight over a channel, so making them exclusive would only ever surprise; and a group's
 *   column on the busk view *is* its family, so a mixed group would have no home. An empty group has
 *   no family and therefore no column.
 * - **No say in composition.** A cue-authored template layer is a cue's business; a group changes
 *   what a *press* does, never what a cue composes to. Nothing in the cook reads this table.
 * - **No effect on the click gesture.** `/apply` writes literals into the programmer, and a literal
 *   has no layer for a sibling to release.
 */
object DaoTemplateGroups : IntIdTable("template_groups") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 255)

    /** Position in the project's top-level sequence, shared with ungrouped templates. */
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        // Same identity rule as a template: (project, name).
        uniqueIndex(project, name)
    }
}

class DaoTemplateGroup(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoTemplateGroup>(DaoTemplateGroups)

    var project by DaoProject referencedOn DaoTemplateGroups.project
    var name by DaoTemplateGroups.name
    var sortOrder by DaoTemplateGroups.sortOrder
    var uuid by DaoTemplateGroups.uuid

    /** The templates in this group. Order is [DaoTemplates.sortOrder]; callers sort in memory. */
    val members by DaoTemplate optionalReferrersOn DaoTemplates.group
}

/**
 * A **Template**: a named value for one attribute family, applied to a selection.
 *
 * The other half of the split [DaoLooks] used to serve alone. A Look *composes cues* — any
 * families, its own fixtures, its own effects, added to a stack as a layer with order, mask,
 * amount, timing and stomp. A Template *composes one thing* — exactly one family, no targets of
 * its own, and either applied to a selection as literals or tracked by a layer.
 *
 * Two tables rather than a `kind` column on `looks`, and the reason is the **per-fixture** case: a
 * focus position — eight heads aimed at one spot — holds eight different pan/tilts, so its rows are
 * *bound*, which is exactly a recorded Look's shape. `hasDeferredRows` could therefore never
 * separate the two, and the design's "one backend table, two front doors" does not survive contact
 * with the most useful kind of template there is. See
 * `docs/plans/completed/desk-simplification-plan.md` §Session 3.
 *
 * What a template deliberately does **not** have, each for its own reason:
 *
 * - **No `editorFixtureType`.** That column existed only so the form editor could build a synthetic
 *   fixture to render a property grid against, and it is what made "Amber Key" offerable to a MAC
 *   Aura and refused to the LED bar beside it (D6). A template stores an *intent*
 *   ([uk.me.cormack.lighting7.fx.TemplateIntent]) resolved per head at cook, so there is no mode to
 *   resolve it against and nothing to declare.
 * - **No *values and* effects.** A template holds a value **or** an effect, never both
 *   ([DaoTemplateEffects], the fx-templates plan D1) — a colour *and* a chase is a Look, which
 *   already holds rows plus deferred effects and has its own busk pool. `Holds` is the template's
 *   identity like its family is: the write boundary refuses a flip either way, so "a template is
 *   one named thing" stays true and the cook's template arm never needs both halves.
 *
 *   This reverses the original D7 ("effects live in a Look or on a cue, never on a layer"). What
 *   D7 got right survives as D2 and D3 below — one effect, and never a *bound* one. What it got
 *   wrong is that it left "a slow amber breathe on the selection" — one named thing, of one
 *   family, with no targets of its own, which is the definition of a template — reachable only as
 *   a Look with zero rows. `POST /looks/{id}/absorb-effects` remains the way a *running* effect
 *   joins a Look.
 * - **No positional colour list.** There used to be one on every cue, stack and Look — the `P1` /
 *   `P2` grammar FX parameters indexed, cascading `look > cue > global` — and a template
 *   deliberately did not join it. That whole grammar is now gone, and the inverse arrangement is
 *   what replaced it: an effect parameter names a *template*
 *   ([uk.me.cormack.lighting7.fx.templateColourSource]), so a template is the colour rather than a
 *   scope that holds a list of them.
 * - **No stored family.** Which family a template is in is *derived* — from its rows, exactly as a
 *   Look's `families` are, or from its effect's library `category` — and validated to be
 *   **exactly one** at the write boundary. A declared column would be a second source of truth for
 *   something the contents already say.
 *
 * A template may sit in a [DaoTemplateGroups] group, which gives it two things: a place in an
 * ordered cluster, and *siblings* — the templates a busk press on this one releases. See that
 * table's docblock; the rule that makes a group coherent (one family) is derived from the members
 * the same way a template's own family is derived from its rows.
 */
object DaoTemplates : IntIdTable("templates") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 255)
    val notes = text("notes").nullable()

    /**
     * Position in the library: within [group] when grouped, otherwise in the project's top-level
     * sequence, which ungrouped templates **share with the groups** ([DaoTemplateGroups.sortOrder]).
     * The reorder route renumbers both sides in one pass; nothing else assigns a position except a
     * create, which appends.
     */
    val sortOrder = integer("sort_order").default(0)

    /**
     * The group this template belongs to, or null for a top-level template.
     *
     * No `ReferenceOption`: SQLite enforces no FK cascade without a per-connection pragma, and the
     * group DELETE route ungroups its members itself before deleting the row. On a database that
     * predates this column the FK clause never lands at all — `createMissingTablesAndColumns` can
     * `ADD COLUMN` but SQLite cannot `ADD CONSTRAINT` (the `cue_layers` CHECK has the same story) —
     * so every route validates group-in-project by hand rather than trusting the schema.
     */
    val group = reference("group_id", DaoTemplateGroups).nullable()

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

    /** The group this template sits in, or null at top level. See [DaoTemplateGroups]. */
    var group by DaoTemplateGroup optionalReferencedOn DaoTemplates.group
    val rows by DaoTemplateRow referrersOn DaoTemplateRows.template

    /**
     * The effect rows, of which there is at most one — see [effect].
     *
     * A plain `referrersOn` rather than a one-to-one back reference, deliberately: the delete paths
     * (the route's guard, the importer's wipe, the project cascade) all want to iterate, and a
     * back reference would throw rather than iterate if a hand-edited database ever held two.
     */
    val effects by DaoTemplateEffect referrersOn DaoTemplateEffects.template

    /**
     * This template's one effect, or null for a value template.
     *
     * D2: at most one, enforced by `uniqueIndex(template)` and at the write boundary. `firstOrNull`
     * rather than `single` because a read is not the place to throw over data the index already
     * forbids.
     */
    val effect: DaoTemplateEffect? get() = effects.firstOrNull()
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

// ─── Template Effects table ────────────────────────────────────────────

/**
 * The one effect an *effect template* holds — the fx-templates plan's D1–D3.
 *
 * Mirrors [DaoLookEffects] column for column, minus three:
 *
 * - **No [targetType] / [targetKey]** (D3). An effect template is always **generic**: an effect
 *   fans over whatever the applying layer names, so there is no per-fixture effect to describe.
 *   The composer treats it exactly as a *deferred* Look effect. This is load-bearing beyond
 *   tidiness — [uk.me.cormack.lighting7.fx.TemplateRegistry] caches snapshots on the argument that
 *   "nothing patch-shaped is in the cache, so a repatch cannot make an entry stale", and a bound
 *   effect naming a group would be precisely that.
 * - **No `sort_order`** (D2). One effect per template, so there is no order to keep. Enforced by
 *   the unique index below *and* at the write boundary, because the index alone would surface as a
 *   constraint violation rather than a named 400.
 *
 * [parameters] may hold a `tmpl:{uuid}` colour reference (D12) — the useful direction, an effect
 * template's colour parameter naming a *value* colour template. Naming its own uuid is refused at
 * the write boundary, and `templateFxReferenceCount` scans this table so the referenced template
 * cannot be deleted out from under it.
 */
object DaoTemplateEffects : IntIdTable("template_effects") {
    val template = reference("template_id", DaoTemplates)
    val effectType = varchar("effect_type", 255)

    /** The effect library's own `category` — D4 derives the template's family from it. */
    val category = varchar("category", 50)
    val propertyName = varchar("property_name", 255).nullable()
    val beatDivision = double("beat_division")
    val blendMode = varchar("blend_mode", 50)
    val distribution = varchar("distribution", 50)
    val phaseOffset = double("phase_offset").default(0.0)
    val elementMode = varchar("element_mode", 50).nullable()
    val elementFilter = varchar("element_filter", 50).nullable()
    val stepTiming = bool("step_timing").nullable()
    val parameters = json<Map<String, String>>("parameters", Json)

    /**
     * Speed master this effect subscribes to (null → master 1).
     *
     * Stamped at *authoring* time from the project master whose `usage` matches the family (D8),
     * not resolved at apply time: "by usage" is how the default is labelled in the sheet, not a
     * stored mode, so the `null → slot 0` invariant the bank and the wire protocol are built on
     * does not move. The accepted cost is that retagging a master's usage later does not move
     * templates already stamped — `FU-TMPL-USAGE-RETAG`.
     */
    val speedMasterUuid = javaUUID("speed_master_uuid").nullable()
    val rateSpeedMasterUuid = javaUUID("rate_speed_master_uuid").nullable()
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        // D2, at the storage layer. One row per template, so a template can never grow a second
        // effect behind the write boundary's back — an import or a hand edit included.
        uniqueIndex(template)
    }
}

class DaoTemplateEffect(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoTemplateEffect>(DaoTemplateEffects)

    var template by DaoTemplate referencedOn DaoTemplateEffects.template
    var effectType by DaoTemplateEffects.effectType
    var category by DaoTemplateEffects.category
    var propertyName by DaoTemplateEffects.propertyName
    var beatDivision by DaoTemplateEffects.beatDivision
    var blendMode by DaoTemplateEffects.blendMode
    var distribution by DaoTemplateEffects.distribution
    var phaseOffset by DaoTemplateEffects.phaseOffset
    var elementMode by DaoTemplateEffects.elementMode
    var elementFilter by DaoTemplateEffects.elementFilter
    var stepTiming by DaoTemplateEffects.stepTiming
    var parameters by DaoTemplateEffects.parameters
    var speedMasterUuid by DaoTemplateEffects.speedMasterUuid
    var rateSpeedMasterUuid by DaoTemplateEffects.rateSpeedMasterUuid
    var uuid by DaoTemplateEffects.uuid
}

/**
 * A template's effect on the wire — [LookEffectDto] minus the target and the sort order (D2/D3).
 *
 * Deliberately **not** [LookEffectSpec], despite holding the same fields: that is the shared
 * *spawn* shape, with no `uuid` and no place for one, and this is a client-supplied *authoring*
 * shape that a route validates. The bridge between them is
 * [uk.me.cormack.lighting7.fx.EffectEntry.toEffectSpec], the same one a Look effect crosses.
 */
@Serializable
data class TemplateEffectDto(
    val effectType: String,
    val category: String,
    val propertyName: String? = null,
    val beatDivision: Double,
    val blendMode: String,
    val distribution: String,
    val phaseOffset: Double = 0.0,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val stepTiming: Boolean? = null,
    val parameters: Map<String, String> = emptyMap(),
    /** Speed master uuid (null → master 1). Uuid, not int id — see [DaoTemplateEffects.speedMasterUuid]. */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). */
    val rateSpeedMasterUuid: String? = null,
    /**
     * `BEAT` or `WALL_CLOCK`, **resolved server-side on read and ignored on write** — the same
     * contract [TemplateRowDto.health] has, and for the same reason: it is the registry's answer
     * about [effectType], not a fact the author gets to assert.
     *
     * It is here because without it [beatDivision] is unreadable. The field is beats for a `BEAT`
     * effect and *seconds* for a `WALL_CLOCK` one, so `2.0` is either two beats or two seconds —
     * two readings a tempo apart — and every surface that renders a template's speed would
     * otherwise have to fetch the whole FX library to find out which. Three already would: the
     * library row, the editor's *Runs on* panel and the busk pad.
     *
     * Null where the stored `effectType` does not resolve in the registry, and a reader should then
     * say nothing about the speed rather than guess a unit. Two ways that happens, and the second is
     * the reachable one: an import from a desk carrying script-registered effects this one lacks
     * (the write boundary refuses authoring such a template, but not importing one), and — because
     * the registry is the **current show's** — reading *another* project's library, where an effect
     * only that project's scripts register is unknown here. The template list route serves any
     * project, so that is an ordinary page view rather than a corrupt state.
     */
    val timingSource: String? = null,
)
