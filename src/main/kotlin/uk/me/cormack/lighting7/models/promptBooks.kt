package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.json.json

// ─── Enums ──────────────────────────────────────────────────────────────

enum class PromptBookAnnotationKind { NOTE, STRIKETHROUGH, FREETEXT }

/**
 * Severity/intent of a NOTE annotation — drives the callout colour in the
 * reader (note=blue, warn=amber, safety=red). Only meaningful for NOTE; other
 * kinds leave it null. Nullable in storage so pre-existing notes read as NOTE.
 */
enum class PromptBookNoteTone { NOTE, WARN, SAFETY }

// ─── DTOs (used for API serialization and json columns) ─────────────────

/**
 * A rectangle on a single script page, normalized to [0,1] in both axes
 * against the page dimensions (never pixels — survives zoom/resize/re-display).
 * `page` is the 0-based page index within the PDF. y grows downward.
 */
@Serializable
data class PromptBookRectDto(
    val page: Int,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
)

/**
 * Validate a normalized region against a script of [pageCount] pages: at least one
 * rect, every rect on an existing page with finite [0,1] coordinates and a positive
 * extent that stays on the page. Returns the first error message, or null if valid.
 * Lives next to the DTO so every write path (REST routes, sync import) shares the
 * same invariant.
 */
fun checkPromptBookRegion(region: List<PromptBookRectDto>, pageCount: Int): String? {
    if (region.isEmpty()) return "region must contain at least one rect"
    for (r in region) {
        if (r.page < 0) return "rect page must be >= 0"
        if (r.page >= pageCount) return "rect page ${r.page} is beyond the script's last page (${pageCount - 1})"
        val values = listOf("x" to r.x, "y" to r.y, "w" to r.w, "h" to r.h)
        for ((name, v) in values) {
            if (!v.isFinite()) return "rect $name must be a finite number"
        }
        if (r.x < 0.0 || r.x > 1.0) return "rect x must be between 0.0 and 1.0"
        if (r.y < 0.0 || r.y > 1.0) return "rect y must be between 0.0 and 1.0"
        if (r.w <= 0.0 || r.h <= 0.0) return "rect w and h must be positive"
        if (r.x + r.w > 1.0001) return "rect must not extend past the right page edge"
        if (r.y + r.h > 1.0001) return "rect must not extend past the bottom page edge"
    }
    return null
}

// ─── Prompt Books table ─────────────────────────────────────────────────
//
// A prompt-book binds one imported PDF script (identified by content hash,
// never filename) to the project's show: cue anchors pin cues to regions on
// the script, free annotations carry operator commentary. The cue's lighting
// payload stays in the cue stack — anchors store only the binding.

object DaoPromptBooks : IntIdTable("prompt_books") {
    val project = reference("project_id", DaoProjects)

    /** SHA-256 hex of the PDF bytes — the script's identity. */
    val scriptHash = varchar("script_hash", 64)

    /** Original filename, display only — never used for identity. */
    val scriptFileName = varchar("script_file_name", 255).nullable()
    val pageCount = integer("page_count")
    val uuid = uuid("uuid").autoGenerate()

    init {
        // One prompt book per project: a project's show has a single script.
        uniqueIndex(project)
    }
}

class DaoPromptBook(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoPromptBook>(DaoPromptBooks)

    var project by DaoProject referencedOn DaoPromptBooks.project
    var scriptHash by DaoPromptBooks.scriptHash
    var scriptFileName by DaoPromptBooks.scriptFileName
    var pageCount by DaoPromptBooks.pageCount
    var uuid by DaoPromptBooks.uuid
    val anchors by DaoPromptBookAnchor referrersOn DaoPromptBookAnchors.promptBook
    val annotations by DaoPromptBookAnnotation referrersOn DaoPromptBookAnnotations.promptBook
}

// ─── Cue Anchors table ──────────────────────────────────────────────────

object DaoPromptBookAnchors : IntIdTable("prompt_book_anchors") {
    val promptBook = reference("prompt_book_id", DaoPromptBooks, onDelete = ReferenceOption.CASCADE)
    val cue = reference("cue_id", DaoCues, onDelete = ReferenceOption.CASCADE)

    /** One-or-more normalized rects — multiple rects span page breaks / wrapped lines. */
    val region = json<List<PromptBookRectDto>>("region", Json)

    /** Cached display label ("LX 12", "Q14") — rendering convenience, not identity. */
    val label = varchar("label", 64).nullable()
    val uuid = uuid("uuid").autoGenerate()

    init {
        // One anchor per cue per book: the anchor map is keyed by cue.
        uniqueIndex(promptBook, cue)
    }
}

class DaoPromptBookAnchor(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoPromptBookAnchor>(DaoPromptBookAnchors)

    var promptBook by DaoPromptBook referencedOn DaoPromptBookAnchors.promptBook
    var cue by DaoCue referencedOn DaoPromptBookAnchors.cue
    var region by DaoPromptBookAnchors.region
    var label by DaoPromptBookAnchors.label
    var uuid by DaoPromptBookAnchors.uuid

    /**
     * The FK value without loading the referenced cue entity (avoids N+1 on serialization).
     * Falls back to the delegate for freshly-created entities whose row hasn't been
     * re-read yet — there the cue is already in the transaction's entity cache anyway.
     */
    val cueId: Int get() = readValues.getOrNull(DaoPromptBookAnchors.cue)?.value ?: cue.id.value
}

// ─── Annotations table ──────────────────────────────────────────────────
//
// Free commentary on the script — NOT cue-stack entries; separate lifecycle.
// Desync logic ignores them except STRIKETHROUGH, which feeds the
// "anchor inside a cut section" warning.

object DaoPromptBookAnnotations : IntIdTable("prompt_book_annotations") {
    val promptBook = reference("prompt_book_id", DaoPromptBooks, onDelete = ReferenceOption.CASCADE)
    val kind = varchar("kind", 20)
    val region = json<List<PromptBookRectDto>>("region", Json)

    /** NOTE / FREETEXT content; empty for STRIKETHROUGH (the region is the meaning). */
    val text = text("text").nullable()

    /** Optional colour override; otherwise derives from kind. */
    val color = varchar("color", 16).nullable()

    /** NOTE severity (NOTE/WARN/SAFETY) driving the callout colour; null → NOTE. */
    val tone = varchar("tone", 16).nullable()
    val uuid = uuid("uuid").autoGenerate()
}

class DaoPromptBookAnnotation(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoPromptBookAnnotation>(DaoPromptBookAnnotations)

    var promptBook by DaoPromptBook referencedOn DaoPromptBookAnnotations.promptBook
    var kind by DaoPromptBookAnnotations.kind
    var region by DaoPromptBookAnnotations.region
    var text by DaoPromptBookAnnotations.text
    var color by DaoPromptBookAnnotations.color
    var tone by DaoPromptBookAnnotations.tone
    var uuid by DaoPromptBookAnnotations.uuid
}
