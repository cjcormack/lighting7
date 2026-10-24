package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.eq
import java.util.UUID

/**
 * Projects table - the base columns are defined here.
 * FK references to scripts/scenes are added after those tables are defined
 * to avoid forward reference issues at compile time.
 */
object DaoProjects: IntIdTable("projects") {
    val name = varchar("name", 50).uniqueIndex()
    val description = varchar("description", 255).nullable()
    val isCurrent = bool("is_current").default(false)
    // The project's currently-active cue stack (the show playhead), or null when the show is not
    // running. Plain integer, no enforced FK — a deleted stack is reconciled by nulling this in the
    // stack-delete path. (It replaced a legacy `active_entry_id` column that referenced the
    // dropped show_entries table; the migration that copied that value across was removed with
    // the rest of them on 2026-08-24 — see state/InstallBootstrap.kt.)
    val activeStackId = integer("active_stack_id").nullable()
    // Per-project stage bounds (metres) for the FOH 3D view; synced with the project
    // because shows are designed for a specific venue. See docs/fixtures-engineering.md.
    val stageWidthM = double("stage_width_m").nullable()
    val stageDepthM = double("stage_depth_m").nullable()
    val stageHeightM = double("stage_height_m").nullable()
    val uuid = javaUUID("uuid").autoGenerate()
    /**
     * What this project is (venue plan D1, P2): a show — every project before venues — a venue, whose
     * own rows are its **draft**, or a frozen snapshot of one venue version. A `VENUE_VERSION` is
     * never listed, never current and refuses every write (`routes/projects.kt`'s guard); its index
     * is `venue_versions`. Exported in `project.json` from `formatVersion` 24 (venue plan session 4),
     * omitted for a show, which is its default.
     */
    val kind = enumerationByName<ProjectKind>("kind", 16).default(ProjectKind.SHOW)
    /**
     * A show's **pin** (venue plan §3.1, D2): the venue it sits on, by the venue project's uuid, and
     * the version it is pinned to. Both null for a standalone show, and always both null for a venue
     * or a version. The rig scope reads the pinned snapshot's rows into the show's rig
     * (`RigScope`). Exported in `project.json` from `formatVersion` 24 (venue plan session 4), and an
     * import of a show whose pinned version is not on the desk is refused (`VENUE_MISSING`).
     */
    val venueUuid = javaUUID("venue_uuid").nullable()
    val venueVersion = integer("venue_version").nullable()
    /**
     * Where a show's venue syncs, as a hint for a desk that lacks it (venue plan §3.4): `owner/repo`
     * for a GitHub remote, else the remote's URL. Read from the archive on import and written back
     * on export as it is, so every desk that imported the show writes the same bytes; a show with
     * none (made on this desk) exports the venue's own attached repo, when there is one
     * (`venue/VenuePins.kt`'s `repoHintOf`). Null for a standalone show, a venue and a version.
     */
    val venueRepo = varchar("venue_repo", 300).nullable()
    /**
     * Where a standalone show came from (venue-attach plan D1, §4.3): the venue it was detached from,
     * by the venue project's uuid, and the version it was pinned to then. Written by Detach in the
     * transaction that clears the pin, kept by a clone, cleared by Extract and by Attach. An attach
     * reads it as the origin of its three-way match. Both or neither; null for a
     * show never on a venue, one detached before this was recorded, a venue and a version. Portable:
     * `project.json`'s `detachedFromVenueUuid` and `detachedFromVersion`.
     */
    val detachedFromVenueUuid = javaUUID("detached_from_venue_uuid").nullable()
    val detachedFromVersion = integer("detached_from_version").nullable()
}

/** Whether this is a show **on a venue** (venue plan §3.1): one with a pin. */
val DaoProject.isVenueBound: Boolean get() = kind == ProjectKind.SHOW && venueUuid != null

/**
 * A standalone show's origin (venue-attach plan D1): the venue uuid and version it was detached
 * from, or null when it records none — and for a show on a venue, a venue and a version.
 */
fun DaoProject.detachedFrom(): Pair<UUID, Int>? {
    if (kind != ProjectKind.SHOW || isVenueBound) return null
    val uuid = detachedFromVenueUuid ?: return null
    val version = detachedFromVersion ?: return null
    return uuid to version
}

/** A project's kind (venue plan §3.1). See [DaoProjects.kind]. */
enum class ProjectKind { SHOW, VENUE, VENUE_VERSION }

class DaoProject(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoProject>(DaoProjects)

    var name by DaoProjects.name
    var description by DaoProjects.description
    var isCurrent by DaoProjects.isCurrent
    var activeStackId by DaoProjects.activeStackId
    var stageWidthM by DaoProjects.stageWidthM
    var stageDepthM by DaoProjects.stageDepthM
    var stageHeightM by DaoProjects.stageHeightM
    var uuid by DaoProjects.uuid
    var kind by DaoProjects.kind
    var venueUuid by DaoProjects.venueUuid
    var venueVersion by DaoProjects.venueVersion
    var venueRepo by DaoProjects.venueRepo
    var detachedFromVenueUuid by DaoProjects.detachedFromVenueUuid
    var detachedFromVersion by DaoProjects.detachedFromVersion

    val scripts by DaoScript referrersOn DaoScripts.project
    val looks by DaoLook referrersOn DaoLooks.project
    val palettes by DaoPalette referrersOn DaoPalettes.project
    val speedMasters by DaoSpeedMaster referrersOn DaoSpeedMasters.project
    val cues by DaoCue referrersOn DaoCues.project
    val cueStacks by DaoCueStack referrersOn DaoCueStacks.project
    val cueSlots by DaoCueSlot referrersOn DaoCueSlots.project
    /** The busk layout's pages. Order is `sortOrder`; callers sort in memory. See [DaoBuskPages]. */
    val buskPages by DaoBuskPage referrersOn DaoBuskPages.project
    val buskRigRows by DaoBuskRigRow referrersOn DaoBuskRigRows.project
    val universeConfigs by DaoUniverseConfig referrersOn DaoUniverseConfigs.project
    val riggings by DaoRigging referrersOn DaoRiggings.project
    val stageRegions by DaoStageRegion referrersOn DaoStageRegions.project
    val stageElements by DaoStageElement referrersOn DaoStageElements.project
    val stageViewpoints by DaoStageViewpoint referrersOn DaoStageViewpoints.project
    val fixturePatches by DaoFixturePatch referrersOn DaoFixturePatches.project
    val fixtureGroups by DaoFixtureGroup referrersOn DaoFixtureGroups.project
    val parkedChannels by DaoParkedChannel referrersOn DaoParkedChannels.project
    val fxDefinitions by DaoFxDefinition referrersOn DaoFxDefinitions.project
    val controlSurfaceBindings by DaoControlSurfaceBinding referrersOn DaoControlSurfaceBindings.project
    /** The project's single prompt book, or null if none has been imported yet. */
    val promptBook: DaoPromptBook?
        get() = DaoPromptBook.find { DaoPromptBooks.project eq id }.firstOrNull()
}
