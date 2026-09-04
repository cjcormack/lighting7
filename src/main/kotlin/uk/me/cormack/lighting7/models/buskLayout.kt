package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.slf4j.LoggerFactory

/**
 * The **busk layout**: pages of rows of columns of banks of pads, built by the operator.
 *
 * The busk view used to lay itself out from the library — a column per family, a template group
 * drawn as a cluster, every Look with a deferred effect in a pool. `docs/plans/busk-layout-plan.md`
 * moves that decision to the operator (D1): a page is something they build, referencing library
 * records, and the page owns the two facts a template group used to own — where a pad sits and what
 * else goes off when it is pressed. The library goes back to being a flat list.
 *
 * Four tables, one nesting, no coordinates (D2):
 *
 * - A [DaoBuskPages] page is a name and a position in the project. A project has any number.
 * - A [DaoBuskColumns] column sits in a **row** of its page — `row` is a dense integer the layout
 *   route renumbers, not a table — and has a width share in twelfths ([BUSK_WIDTHS]: ¼ … full).
 *   Widths in a row need not sum to twelve; the client draws them as `fr` shares.
 * - A [DaoBuskBanks] bank stacks in its column top to bottom, has a name, is `solo` or stacking, and
 *   has a `flow`: pads wrap to the bank's width or run one per line ([BuskFlow]). Solo never
 *   decides a bank's shape.
 * - A [DaoBuskPads] pad is an **ordered reference** to exactly one template, Look or cue (D3). One
 *   record may sit on several pads, on several pages.
 *
 * Nothing is positioned by hand, so nothing overlaps and columns stack on a narrow screen — this is
 * MagicQ's fixed execute grid, not QLC+'s free canvas.
 *
 * What the layout deliberately does **not** do:
 *
 * - **It never guards a delete.** A pad is an enrichment of its record, not content. Deleting a
 *   template, Look or cue deletes its pads inside the same transaction, and the template and Look
 *   delete guards do not learn about pads. Sync reads a pad whose record the archive lacks as
 *   absent, with a warning, for the same reason.
 * - **It never touches the cook.** A bank changes what a *press* does — which siblings a solo bank
 *   releases, resolved by the press route and handed to `ProgrammerLayerStack.toggle` as
 *   `releaseSiblings` (D4) — never what a cue composes to. Nothing in `CueComposer` reads these
 *   tables.
 * - **No stored family, no one-family rule.** A bank may hold a position template beside a movement
 *   Look beside a cue; that mixed exclusivity is the brief the template group could not express.
 *
 * **No `ReferenceOption` on any FK here**, for the reason `templates.group_id` has none: SQLite
 * enforces no cascade without a per-connection pragma, so the routes cascade by hand (pads → banks
 * → columns → page, and a record's pads inside its own delete). The exactly-one rule on a pad is a
 * CHECK constraint below, with the `cue_layers` caveat — it reaches only a database created after
 * it was added, so every reader treats a malformed pad as absent ([buskPadKind]) rather than
 * trusting the schema.
 */
object DaoBuskPages : IntIdTable("busk_pages") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 255)

    /** Position among the project's pages; renumbered densely by the reorder route. */
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        // Same identity rule as a template or a Look: (project, name).
        uniqueIndex(project, name)
    }
}

class DaoBuskPage(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoBuskPage>(DaoBuskPages)

    var project by DaoProject referencedOn DaoBuskPages.project
    var name by DaoBuskPages.name
    var sortOrder by DaoBuskPages.sortOrder
    var uuid by DaoBuskPages.uuid

    /** The page's columns, every row's together. Order is (`row`, `sortOrder`); callers sort in memory. */
    val columns by DaoBuskColumn referrersOn DaoBuskColumns.page
}

/** The width shares a column may take, in twelfths: ¼, ⅓, ½, ⅔, ¾ and full. */
val BUSK_WIDTHS: Set<Int> = setOf(3, 4, 6, 8, 9, 12)

object DaoBuskColumns : IntIdTable("busk_columns") {
    val page = reference("page_id", DaoBuskPages)

    /** Which row of the page this column is in — a dense integer from zero, not a table. */
    val row = integer("row")

    /** Position within the row, dense from zero. */
    val sortOrder = integer("sort_order")

    /** Width share in twelfths — one of [BUSK_WIDTHS], validated by the layout route. */
    val width = integer("width")
    val uuid = javaUUID("uuid").autoGenerate()
}

class DaoBuskColumn(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoBuskColumn>(DaoBuskColumns)

    var page by DaoBuskPage referencedOn DaoBuskColumns.page
    var row by DaoBuskColumns.row
    var sortOrder by DaoBuskColumns.sortOrder
    var width by DaoBuskColumns.width
    var uuid by DaoBuskColumns.uuid

    /** The banks stacked in this column. Order is `sortOrder`; callers sort in memory. */
    val banks by DaoBuskBank referrersOn DaoBuskBanks.column
}

/** How a bank lays its pads out: wrapping to the bank's width, or one pad per line. */
enum class BuskFlow {
    WRAP,
    COLUMN,
}

object DaoBuskBanks : IntIdTable("busk_banks") {
    val column = reference("column_id", DaoBuskColumns)

    /** Position within the column, top to bottom, dense from zero. */
    val sortOrder = integer("sort_order")

    /** Shown in the bank header. May repeat across banks — a bank's identity is its row, not its name. */
    val name = varchar("name", 255)

    /**
     * Whether pressing one pad turns its siblings off (D6). Off means the bank *stacks* — the
     * behaviour every ungrouped pad had before the layout existed, now a choice per bank.
     */
    val solo = bool("solo").default(false)

    /** A [BuskFlow] name. */
    val flow = varchar("flow", 16).default(BuskFlow.WRAP.name)
    val uuid = javaUUID("uuid").autoGenerate()
}

class DaoBuskBank(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoBuskBank>(DaoBuskBanks)

    var column by DaoBuskColumn referencedOn DaoBuskBanks.column
    var sortOrder by DaoBuskBanks.sortOrder
    var name by DaoBuskBanks.name
    var solo by DaoBuskBanks.solo
    var flow by DaoBuskBanks.flow
    var uuid by DaoBuskBanks.uuid

    /** The pads in this bank. Order is `sortOrder`; callers sort in memory. */
    val pads by DaoBuskPad referrersOn DaoBuskPads.bank
}

object DaoBuskPads : IntIdTable("busk_pads") {
    val bank = reference("bank_id", DaoBuskBanks)

    /** Position within the bank, dense from zero. */
    val sortOrder = integer("sort_order")

    /**
     * What this pad presses: **exactly one** of [template] / [look] / [cue] is set — the
     * [DaoCueLayers] pattern, three-armed. Nullable FKs rather than a `(kind, id)` pair so a pad
     * pointing at a deleted record cannot be expressed once the record's delete has swept its pads.
     */
    val template = reference("template_id", DaoTemplates).nullable()
    val look = reference("look_id", DaoLooks).nullable()
    val cue = reference("cue_id", DaoCues).nullable()
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        // Exactly one referent, stated to the database as well as to the code. Reaches only a DB
        // created after it was added — see the `cue_layers` note; `buskPadKind` holds the line on
        // one that predates it.
        check("busk_pad_exactly_one_ref") {
            (template.isNotNull() and look.isNull() and cue.isNull()) or
                (template.isNull() and look.isNotNull() and cue.isNull()) or
                (template.isNull() and look.isNull() and cue.isNotNull())
        }
    }
}

class DaoBuskPad(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoBuskPad>(DaoBuskPads)

    var bank by DaoBuskBank referencedOn DaoBuskPads.bank
    var sortOrder by DaoBuskPads.sortOrder
    var template by DaoTemplate optionalReferencedOn DaoBuskPads.template
    var look by DaoLook optionalReferencedOn DaoBuskPads.look
    var cue by DaoCue optionalReferencedOn DaoBuskPads.cue
    var uuid by DaoBuskPads.uuid

    /** Which arm this pad's row sets, or null for a malformed row every reader treats as absent. */
    val kind: BuskPadKind?
        get() = buskPadKind(
            readValues[DaoBuskPads.template],
            readValues[DaoBuskPads.look],
            readValues[DaoBuskPads.cue],
        )
}

/** The three things a pad can press. Serialised by name on the wire and in sync. */
enum class BuskPadKind {
    TEMPLATE,
    LOOK,
    CUE,
}

private val buskPadLogger = LoggerFactory.getLogger("buskPad")

/**
 * The [DaoBuskPads] exactly-one rule, decided in one place — the [layerSourceShape] idea.
 *
 * Takes `Any?` because the rule is purely about presence: the read path holds entity ids, the
 * layout route int ids and the importer uuid strings. Null means malformed (none set, or more than
 * one); the caller drops the pad and, if it is a reader, says so through [warnMalformedBuskPad].
 */
fun buskPadKind(template: Any?, look: Any?, cue: Any?): BuskPadKind? {
    val set = listOfNotNull(
        template?.let { BuskPadKind.TEMPLATE },
        look?.let { BuskPadKind.LOOK },
        cue?.let { BuskPadKind.CUE },
    )
    return set.singleOrNull()
}

/**
 * The shared behaviour for a malformed pad row: warn, naming [pad], and the caller treats it as
 * absent. A pad that names no record or two cannot be pressed, and there is no reading of it that
 * produces light, so every reader drops it rather than inventing a meaning or failing a page.
 */
fun warnMalformedBuskPad(pad: () -> String) {
    buskPadLogger.warn("busk pad {} names no record or more than one — dropping the pad", pad())
}
