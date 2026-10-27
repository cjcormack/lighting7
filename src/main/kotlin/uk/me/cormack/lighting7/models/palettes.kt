package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.javatime.duration

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.json.json

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
 *   ([uk.me.cormack.lighting7.fx.PaletteIntent]) resolved per head at cook, so there is no mode to
 *   resolve it against and nothing to declare.
 * - **No *values and* effects.** A template holds a value **or** an effect, never both
 *   ([DaoPaletteFx], the fx-templates plan D1) — a colour *and* a chase is a Look, which
 *   already holds rows plus effects and has its own busk pool. `Holds` is the template's
 *   identity like its family is: the write boundary refuses a flip either way, so "a template is
 *   one named thing" stays true and the cook's template arm never needs both halves.
 *
 *   This reverses the original D7 ("effects live in a Look or on a cue, never on a layer"). What
 *   D7 got right survives as D2 and D3 below — one effect, and never a *bound* one. What it got
 *   wrong is that it left "a slow amber breathe on the selection" — one named thing, of one
 *   family, with no targets of its own, which is the definition of a template — reachable only as
 *   a Look with zero rows. `POST /looks/{id}/absorb-fx` remains the way a *running* effect
 *   joins a Look.
 * - **No positional colour list.** There used to be one on every cue, stack and Look — the `P1` /
 *   `P2` grammar FX parameters indexed, cascading `look > cue > global` — and a template
 *   deliberately did not join it. That whole grammar is now gone, and the inverse arrangement is
 *   what replaced it: an effect parameter names a *template*
 *   ([uk.me.cormack.lighting7.fx.paletteColourSource]), so a template is the colour rather than a
 *   scope that holds a list of them.
 * - **No stored family.** Which family a template is in is *derived* — from its rows, exactly as a
 *   Look's `families` are, or from its effect's library `category` — and validated to be
 *   **exactly one** at the write boundary. A declared column would be a second source of truth for
 *   something the contents already say.
 *
 * A template has no place in a stored order, and no siblings. Both were properties of a template
 * *group*, and both moved to the busk page when the operator took ownership of the layout: a pad's
 * place is its position in a bank, and the templates a press releases are the records on that
 * bank's sibling pads when the bank is solo. See `docs/lighting-composition-model.md`
 * §"The busk layout". `GET /palettes` therefore lists by name.
 */
object DaoPalettes : IntIdTable("palettes") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 255)
    val notes = text("notes").nullable()


    /**
     * Fade for every row this template writes; null = the caller's default.
     *
     * On the template rather than per row, unlike [DaoLookRows.fadeDuration]. A template is one
     * value in one family, so a per-row fade would only ever be N copies of one number — and the
     * per-fixture case (one row per head of the same focus position) is exactly where they must
     * agree.
     */
    val fadeDuration = duration("fade_duration").nullable()
    val uuid = javaUUID("uuid").autoGenerate()

    /**
     * When this template was last **pressed** on this desk, in epoch milliseconds; null until it
     * has been.
     *
     * What the programmer's row of recent chips is ordered by, and the whole of the storage for it:
     * "templates with a stamp, newest first" needs no second table, no per-family bookkeeping (the
     * family is derived from the rows, so a filtered list is already a filtered history) and no
     * cleanup — a deleted template takes its stamp with it.
     *
     * **A press is an application, never a release.** The four doors that stamp it are the chip's
     * ⌥click (*Apply as values*), its tap, a busk pad and a MIDI `pressPalette`, and each stamps only on the arm
     * that puts the template *on* — see `PalettePressLog`. Toggling a layer off leaves the stamp
     * where it was, because "what I reached for" is not undone by putting it down.
     *
     * A `utcInstant` column like every other instant in this schema (`users.created_at`,
     * `users.last_login_at`, …): UTC text carrying an explicit `Z`, at millisecond resolution.
     * Stamp it from `nowUtc()` and render it with `Instant.toIsoUtc()` — see `models/timeColumns.kt`
     * for why neither is optional. The DTO exposes it as an ISO-8601 instant, which is what a
     * client sorts and reads.
     *
     * **Deliberately absent from the sync export** (`PaletteJson` names every field it carries,
     * and this is not one of them): a desk's press history is not show content, and carrying it
     * would make cloning a project hand the clone someone else's habits.
     */
    val lastPressedAt = utcInstant("last_pressed_at").nullable()

    init {
        // Same identity rule as a Look: (project, name). "Amber Key" is one template per project,
        // which is the entire point of dropping the fixture type — the old per-type namespace is
        // what forced one colour to exist several times over.
        uniqueIndex(project, name)
    }
}

class DaoPalette(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoPalette>(DaoPalettes)

    var project by DaoProject referencedOn DaoPalettes.project
    var name by DaoPalettes.name
    var notes by DaoPalettes.notes
    var fadeDuration by DaoPalettes.fadeDuration
    var uuid by DaoPalettes.uuid
    var lastPressedAt by DaoPalettes.lastPressedAt

    val rows by DaoPaletteRow referrersOn DaoPaletteRows.palette

    /**
     * The effect rows, of which there is at most one — see [effect].
     *
     * A plain `referrersOn` rather than a one-to-one back reference, deliberately: the delete paths
     * (the route's guard, the importer's wipe, the project cascade) all want to iterate, and a
     * back reference would throw rather than iterate if a hand-edited database ever held two.
     */
    val effects by DaoPaletteFxEntity referrersOn DaoPaletteFx.palette

    /**
     * This template's one effect, or null for a value template.
     *
     * D2: at most one, enforced by `uniqueIndex(palette)` and at the write boundary. `firstOrNull`
     * rather than `single` because a read is not the place to throw over data the index already
     * forbids.
     */
    val effect: DaoPaletteFxEntity? get() = effects.firstOrNull()
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
 * [value] is an **intent**, not a literal — see [uk.me.cormack.lighting7.fx.PaletteIntent] for the
 * grammar and [uk.me.cormack.lighting7.fx.PaletteResolver] for the one implementation that turns
 * one into channels.
 *
 * There is deliberately **no `elementKey`**. A template is a *rule*, and it reaches a cell the way
 * it reaches any head: a cell is a target (`{type:'fixture', key:<elementKey>}`, busk-further plan
 * D11), so a generic row fans over whichever cells the layer names. A per-element *value* belongs
 * in a recorded Look, which names a head and can therefore hold anything that head has — its
 * element rows compose since the busk-further plan's session 1 closed `FU-LOOK-ELEMENT-ROWS`.
 */
object DaoPaletteRows : IntIdTable("palette_rows") {
    val palette = reference("palette_id", DaoPalettes)

    /** [DEFERRED_TARGET_TYPE], or `fixture` for a per-fixture template. Never `group`. */
    val targetType = varchar("target_type", 50)

    /** Empty string when [targetType] is [DEFERRED_TARGET_TYPE]. */
    val targetKey = varchar("target_key", 255)
    val attribute = varchar("attribute", 255)

    /** A [uk.me.cormack.lighting7.fx.PaletteIntent] in its serialised form. */
    val value = text("value")
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()
}

class DaoPaletteRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoPaletteRow>(DaoPaletteRows)

    var palette by DaoPalette referencedOn DaoPaletteRows.palette
    var targetType by DaoPaletteRows.targetType
    var targetKey by DaoPaletteRows.targetKey
    var attribute by DaoPaletteRows.attribute
    var value by DaoPaletteRows.value
    var sortOrder by DaoPaletteRows.sortOrder
    var uuid by DaoPaletteRows.uuid

    val isDeferred: Boolean get() = targetType == DEFERRED_TARGET_TYPE

    /** The row's own target, or null when deferred or the discriminator names no known arm. */
    val target: TargetRef?
        get() = if (isDeferred) null else TargetRef.ofOrNull(targetType, targetKey)
}

/** One template row on the wire. */
@Serializable
data class PaletteRowDto(
    val targetType: String,
    val targetKey: String,
    val attribute: String,
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

// ─── Palette FX table ─────────────────────────────────────────────────

/**
 * The one effect an *effect template* holds — the fx-templates plan's D1–D3.
 *
 * Mirrors [DaoLookFx] column for column, minus three:
 *
 * - **No [targetType] / [targetKey]** (D3). An effect template is always **generic**: an effect
 *   fans over whatever the applying layer names, so there is no per-fixture effect to describe:
 *   the composer runs it on each of the layer's targets. This is load-bearing beyond
 *   tidiness — [uk.me.cormack.lighting7.fx.PaletteRegistry] caches snapshots on the argument that
 *   "nothing patch-shaped is in the cache, so a repatch cannot make an entry stale", and a bound
 *   effect naming a group would be precisely that.
 * - **No `sort_order`** (D2). One effect per template, so there is no order to keep. Enforced by
 *   the unique index below *and* at the write boundary, because the index alone would surface as a
 *   constraint violation rather than a named 400.
 *
 * [parameters] may hold a `pal:{uuid}` colour reference (D12) — the useful direction, an effect
 * template's colour parameter naming a *value* colour template. Naming its own uuid is refused at
 * the write boundary, and `paletteFxReferenceCount` scans this table so the referenced template
 * cannot be deleted out from under it.
 */
object DaoPaletteFx : IntIdTable("palette_fx") {
    val palette = reference("palette_id", DaoPalettes)
    val fxType = varchar("fx_type", 255)

    /** The effect library's own `category` — D4 derives the template's family from it. */
    val family = varchar("family", 50)
    val attribute = varchar("attribute", 255).nullable()
    val cycle = double("cycle")
    val blendMode = varchar("blend_mode", 50)
    val order = varchar("spread_order", 50)
    val phaseOffset = double("phase_offset").default(0.0)
    val elementMode = varchar("element_mode", 50).nullable()
    val elementFilter = varchar("element_filter", 50).nullable()
    val timing = varchar("timing", 8).nullable()
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

    /** D7's *Seconds* for a beat effect; see [PaletteFxDto.seconds]. */
    val seconds = bool("seconds").default(false)

    /** Amount (effects plan D2), 0.0–1.0; see [LookFxSpec.amount]. */
    val amount = double("amount").default(1.0)

    /** Direction (D5) by name; see [LookFxSpec.direction]. */
    val direction = varchar("direction", 16).default("FORWARD")

    /** Runs (D9), null for forever; see [LookFxSpec.runs]. */
    val runs = integer("runs").nullable()

    /**
     * The spread's shape (effects plan D4); see [LookFxSpec.spread]. Groups 0 is off. The three
     * counts are `spread_`-prefixed columns: `GROUPS` is an SQLite keyword (window frames).
     */
    val spread = double("spread").default(1.0)
    val wings = integer("spread_wings").default(1)
    val segments = integer("spread_segments").default(1)
    val parts = integer("spread_parts").default(0)
    /** [LookFxSpec.spreadUnit]: `STEP`, or null for `CYCLE` — every row written before it existed. */
    val spreadUnit = varchar("spread_unit", 8).nullable()
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        // D2, at the storage layer. One row per template, so a template can never grow a second
        // effect behind the write boundary's back — an import or a hand edit included.
        uniqueIndex(palette)
    }
}

class DaoPaletteFxEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoPaletteFxEntity>(DaoPaletteFx)

    var palette by DaoPalette referencedOn DaoPaletteFx.palette
    var fxType by DaoPaletteFx.fxType
    var family by DaoPaletteFx.family
    var attribute by DaoPaletteFx.attribute
    var cycle by DaoPaletteFx.cycle
    var blendMode by DaoPaletteFx.blendMode
    var order by DaoPaletteFx.order
    var phaseOffset by DaoPaletteFx.phaseOffset
    var elementMode by DaoPaletteFx.elementMode
    var elementFilter by DaoPaletteFx.elementFilter
    var timing by DaoPaletteFx.timing
    var parameters by DaoPaletteFx.parameters
    var speedMasterUuid by DaoPaletteFx.speedMasterUuid
    var rateSpeedMasterUuid by DaoPaletteFx.rateSpeedMasterUuid
    var seconds by DaoPaletteFx.seconds
    var amount by DaoPaletteFx.amount
    var direction by DaoPaletteFx.direction
    var runs by DaoPaletteFx.runs
    var spread by DaoPaletteFx.spread
    var wings by DaoPaletteFx.wings
    var segments by DaoPaletteFx.segments
    var parts by DaoPaletteFx.parts
    var spreadUnit by DaoPaletteFx.spreadUnit
    var uuid by DaoPaletteFx.uuid
}

/**
 * A template's effect on the wire — [LookFxDto] minus the target and the sort order (D2/D3).
 *
 * Deliberately **not** [LookFxSpec], despite holding the same fields: that is the shared
 * *spawn* shape, with no `uuid` and no place for one, and this is a client-supplied *authoring*
 * shape that a route validates. The bridge between them is
 * [uk.me.cormack.lighting7.fx.StoredFx.toFxSpec], the same one a Look effect crosses.
 */
@Serializable
data class PaletteFxDto(
    val fxType: String,
    val family: String,
    val attribute: String? = null,
    val cycle: Double,
    val blendMode: String,
    val order: String,
    val phaseOffset: Double = 0.0,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val timing: Timing? = null,
    val parameters: Map<String, String> = emptyMap(),
    /** Speed master uuid (null → master 1). Uuid, not int id — see [DaoPaletteFx.speedMasterUuid]. */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). */
    val rateSpeedMasterUuid: String? = null,
    /**
     * On *Seconds* (venue-visit plan D7): the effect follows no master. A beat effect's
     * `cycle` is then its own cycle in seconds; a wall-clock effect's is seconds already, and
     * it is on Seconds whenever it has no rate master, flag or not. The masters are null beside it.
     * See `fx/FxTimingChoice.kt`.
     */
    val seconds: Boolean = false,
    /**
     * Amount (effects plan D2), 0.0–1.0: how much of the effect reaches the stage over what is
     * underneath, weighted after the blend (P3). Refused outside that range at the write boundary.
     */
    val amount: Double = 1.0,
    /**
     * Direction (D5): `FORWARD`, `BACKWARD` or `BOUNCE`, each member's cycle read that way. A stored
     * FX's field, absent Forward as its archive reads it; the doors that make a new FX (the picker,
     * the palette editor, MCP) write its type's own Direction (`FxRegistration.defaultDirection`).
     */
    val direction: String = "FORWARD",
    /** Runs (D9): stop after this many cycles, at least 1; null runs forever. */
    val runs: Int? = null,
    /**
     * The spread's shape (effects plan D4): Spread 0.0–2.0 (1.0 the Order's own offsets), Wings and
     * Segments at least 1, Parts 0 (off) or more — see `fx/SpreadShape.kt`. Refused out of range, or
     * wings under a mirrored Order, at the write boundary.
     */
    val spread: Double = 1.0,
    val wings: Int = 1,
    val segments: Int = 1,
    val parts: Int = 0,
    /** What [spread] counts (fx-settings plan D6): `STEP`, the FX's own steps; null is `CYCLE`. */
    val spreadUnit: String? = null,
    /**
     * `BEAT` or `WALL_CLOCK`, **resolved server-side on read and ignored on write** — the same
     * contract [PaletteRowDto.health] has, and for the same reason: it is the registry's answer
     * about [fxType], not a fact the author gets to assert.
     *
     * It is here because without it [cycle] is unreadable. The field is beats for a `BEAT`
     * effect and *seconds* for a `WALL_CLOCK` one, so `2.0` is either two beats or two seconds —
     * two readings a tempo apart — and every surface that renders a template's speed would
     * otherwise have to fetch the whole FX library to find out which. Three already would: the
     * library row, the editor's *Runs on* panel and the busk pad.
     *
     * Null where the stored `fxType` does not resolve in the registry, and a reader should then
     * say nothing about the speed rather than guess a unit. Two ways that happens, and the second is
     * the reachable one: an import from a desk carrying script-registered effects this one lacks
     * (the write boundary refuses authoring such a template, but not importing one), and — because
     * the registry is the **current show's** — reading *another* project's library, where an effect
     * only that project's scripts register is unknown here. The template list route serves any
     * project, so that is an ordinary page view rather than a corrupt state.
     */
    val timingSource: String? = null,
)
