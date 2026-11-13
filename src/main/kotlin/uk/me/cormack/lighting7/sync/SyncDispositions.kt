package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.v1.core.Table
import uk.me.cormack.lighting7.models.DaoBuskBanks
import uk.me.cormack.lighting7.models.DaoBuskColumns
import uk.me.cormack.lighting7.models.DaoBuskPads
import uk.me.cormack.lighting7.models.DaoBuskPages
import uk.me.cormack.lighting7.models.DaoBuskRigRows
import uk.me.cormack.lighting7.models.DaoBuskRigTiles
import uk.me.cormack.lighting7.models.DaoCloudConnections
import uk.me.cormack.lighting7.models.DaoCloudIdentityLinks
import uk.me.cormack.lighting7.models.DaoCloudLinkEvents
import uk.me.cormack.lighting7.models.DaoCloudLinkInvites
import uk.me.cormack.lighting7.models.DaoCloudRemoteSessions
import uk.me.cormack.lighting7.models.DaoControlSurfaceBindings
import uk.me.cormack.lighting7.models.DaoSurfaceJobs
import uk.me.cormack.lighting7.models.DaoCueAdHocFx
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignments
import uk.me.cormack.lighting7.models.DaoCueEvents
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoCueScenery
import uk.me.cormack.lighting7.models.DaoCueSlots
import uk.me.cormack.lighting7.models.DaoCueStackEntries
import uk.me.cormack.lighting7.models.DaoCueStackScenery
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoCueTriggers
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoEffectTubeStates
import uk.me.cormack.lighting7.models.DaoFixtureGroupMembers
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacements
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoFxDefinitions
import uk.me.cormack.lighting7.models.DaoInstalls
import uk.me.cormack.lighting7.models.DaoLookFx
import uk.me.cormack.lighting7.models.DaoLookRows
import uk.me.cormack.lighting7.models.DaoLookScenery
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoMachineOverrides
import uk.me.cormack.lighting7.models.DaoPaletteFx
import uk.me.cormack.lighting7.models.DaoPaletteRows
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.models.DaoParkedChannels
import uk.me.cormack.lighting7.models.DaoPasswordResetTokens
import uk.me.cormack.lighting7.models.DaoProjectScalerStates
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoPromptBookAnchors
import uk.me.cormack.lighting7.models.DaoPromptBookAnnotations
import uk.me.cormack.lighting7.models.DaoPromptBooks
import uk.me.cormack.lighting7.models.DaoRemoteAccessSettingsTable
import uk.me.cormack.lighting7.models.DaoOscSettingsTable
import uk.me.cormack.lighting7.models.DaoOscSendersTable
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoScripts
import uk.me.cormack.lighting7.models.DaoShowColours
import uk.me.cormack.lighting7.models.DaoShowGroupOrders
import uk.me.cormack.lighting7.models.DaoShowHides
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.DaoShowParkReleases
import uk.me.cormack.lighting7.models.DaoSpeedMasters
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.DaoStageRegions
import uk.me.cormack.lighting7.models.DaoStageViewpoints
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.DaoSyncLinkedRepos
import uk.me.cormack.lighting7.models.DaoSyncLogEntries
import uk.me.cormack.lighting7.models.DaoSyncSessionConflicts
import uk.me.cormack.lighting7.models.DaoSyncSessions
import uk.me.cormack.lighting7.models.DaoSyncStates
import uk.me.cormack.lighting7.models.DaoTelemetrySettingsTable
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.DaoUserSessions
import uk.me.cormack.lighting7.models.DaoUsers
import uk.me.cormack.lighting7.models.DaoVenueVersions

/** What a table's rows are, as far as cloud sync, project cloning and the undo journal are concerned. */
sealed interface SyncDisposition {
    /**
     * Portable show content: exported to [folder] as canonical JSON, imported back, and carried by
     * [ProjectCloner]. [embeddedField] names the JSON array this table's rows are nested into when it
     * has no folder of its own (group members, a Look's rows).
     */
    data class Portable(val folder: String, val embeddedField: String? = null) : SyncDisposition

    /** The project row itself — exported as `project.json`, the record [SyncDispositions.PROJECT_FOLDER]. */
    data object ProjectRoot : SyncDisposition

    /** Per-rig or per-install data, deliberately absent from the export. */
    data class MachineLocal(val why: String) : SyncDisposition

    /** Transient or non-show data that no export, import or clone should carry. */
    data class Excluded(val why: String) : SyncDisposition
}

/**
 * The recorded decision for every table in the schema, and so the map from a table to the record
 * folder its rows are written into. Mirrors the decision tree in `CLAUDE.md` §"Database changes and
 * cloud sync"; keep the two in step. `SyncCoverageTest` holds every table in `ALL_TABLES` to an entry
 * here and every portable one to the exporter.
 */
object SyncDispositions {

    /** The folder `project.json` is read as: one record, keyed by the project's uuid. */
    const val PROJECT_FOLDER = "project"

    val byTable: Map<Table, SyncDisposition> = mapOf(
        DaoProjects to SyncDisposition.ProjectRoot,

        DaoScripts to SyncDisposition.Portable("scripts"),
        DaoFxDefinitions to SyncDisposition.Portable("fxDefinitions"),
        DaoLooks to SyncDisposition.Portable("looks"),
        DaoLookRows to SyncDisposition.Portable("looks", "rows"),
        DaoPalettes to SyncDisposition.Portable("palettes"),
        DaoPaletteRows to SyncDisposition.Portable("palettes", "rows"),
        DaoPaletteFx to SyncDisposition.Portable("palettes", "fx"),
        DaoLookFx to SyncDisposition.Portable("looks", "fx"),
        // Portable rather than excluded-live-state: look/cue effects reference a master by
        // speedMasterUuid, and that reference only survives clone/import if the masters
        // travel with the show. The exported bpm is a starting default, not a live knob.
        DaoSpeedMasters to SyncDisposition.Portable("speedMasters"),
        DaoUniverseConfigs to SyncDisposition.Portable("universeConfigs"),
        DaoRiggings to SyncDisposition.Portable("riggings"),
        DaoStageRegions to SyncDisposition.Portable("stageRegions"),
        // v18: the scene document (stage-view plan session 2). Portable, both: "Row F" is the
        // venue's, not this Mac's (D6).
        DaoStageElements to SyncDisposition.Portable("stageElements"),
        DaoStageViewpoints to SyncDisposition.Portable("stageViewpoints"),
        // v20: scenery (stage-view plan session 8), embedded in the cue, stack or Look that owns it
        // as a Look's rows are — an owner says one thing about each element.
        DaoCueScenery to SyncDisposition.Portable("cues", "scenery"),
        DaoCueStackScenery to SyncDisposition.Portable("cueStacks", "scenery"),
        DaoLookScenery to SyncDisposition.Portable("looks", "scenery"),
        // v21: a cue's events (stage-view plan session 9), embedded in the cue as its scenery is.
        DaoCueEvents to SyncDisposition.Portable("cues", "events"),
        DaoFixturePatches to SyncDisposition.Portable("fixturePatches"),
        // A paired dimmer's other lanterns, embedded in list order in their patch's document.
        DaoFixturePatchPlacements to SyncDisposition.Portable("fixturePatches", "extraPlacements"),
        DaoFixtureGroups to SyncDisposition.Portable("fixtureGroups"),
        DaoFixtureGroupMembers to SyncDisposition.Portable("fixtureGroups", "members"),
        DaoCueStacks to SyncDisposition.Portable("cueStacks"),
        DaoCues to SyncDisposition.Portable("cues"),
        // Cue model plan session 3 (formatVersion 32): a stack's list is its own records, one file per
        // entry — markers included — so a drag rewrites one file and two desks adding to one stack add
        // two. Until then each cue's one entry rode inside its cue file.
        DaoCueStackEntries to SyncDisposition.Portable("cueStackEntries"),
        DaoCueAttributeAssignments to SyncDisposition.Portable("cueAttributeAssignments"),
        DaoCueLayers to SyncDisposition.Portable("cueLayers"),
        DaoCueAdHocFx to SyncDisposition.Portable("cueAdHocFx"),
        DaoCueTriggers to SyncDisposition.Portable("cueTriggers"),
        DaoCueSlots to SyncDisposition.Portable("cueSlots"),
        DaoBuskPages to SyncDisposition.Portable("buskPages"),
        DaoBuskColumns to SyncDisposition.Portable("buskPages", "columns"),
        DaoBuskBanks to SyncDisposition.Portable("buskPages", "banks"),
        DaoBuskPads to SyncDisposition.Portable("buskPages", "pads"),
        // v12: one document per rig row, tiles inline — `buskRig/{rowUuid}.json`, a record folder
        // like every other, because a top-level file is outside the record scan (busk-further plan
        // §3.7, session 2 amendment).
        DaoBuskRigRows to SyncDisposition.Portable("buskRig"),
        DaoBuskRigTiles to SyncDisposition.Portable("buskRig", "tiles"),
        DaoParkedChannels to SyncDisposition.Portable("parkedChannels"),
        DaoPromptBooks to SyncDisposition.Portable("promptBooks"),
        DaoPromptBookAnchors to SyncDisposition.Portable("promptBookAnchors"),
        DaoPromptBookAnnotations to SyncDisposition.Portable("promptBookAnnotations"),
        DaoControlSurfaceBindings to SyncDisposition.Portable("controlSurfaceBindings"),
        DaoSurfaceJobs to SyncDisposition.Portable("surfaceJobs"),

        DaoMachineOverrides to SyncDisposition.MachineLocal(
            "per-rig field overrides (controller IPs); cloned same-machine, never exported"
        ),
        DaoInstalls to SyncDisposition.MachineLocal("this install's identity"),
        DaoCloudConnections to SyncDisposition.MachineLocal("this install's adoption by Lighting7 Cloud"),
        DaoCloudIdentityLinks to SyncDisposition.MachineLocal("which cloud account each desk account is"),
        DaoCloudRemoteSessions to SyncDisposition.MachineLocal("the audit of remote sessions through the cloud"),
        DaoCloudLinkInvites to SyncDisposition.MachineLocal("invites to link a cloud account to a desk account"),
        DaoCloudLinkEvents to SyncDisposition.MachineLocal("the audit of cloud account links"),
        DaoSyncConfigs to SyncDisposition.MachineLocal("per-project sync settings for this install"),
        DaoSyncLinkedRepos to SyncDisposition.MachineLocal("which remote this install is attached to"),
        DaoSyncStates to SyncDisposition.MachineLocal("last-synced record hashes for this install"),
        DaoSyncSessions to SyncDisposition.MachineLocal("in-flight conflict sessions"),
        DaoSyncSessionConflicts to SyncDisposition.MachineLocal("in-flight conflict rows"),
        DaoSyncLogEntries to SyncDisposition.MachineLocal("local sync activity log"),
        DaoUsers to SyncDisposition.MachineLocal("desk-local user accounts; never leave this machine"),
        DaoUserSessions to SyncDisposition.MachineLocal("live login sessions for this desk"),
        DaoRemoteAccessSettingsTable to SyncDisposition.MachineLocal("what this desk lets a remote caller do: scripts, effects, fixture commands"),
        DaoOscSettingsTable to SyncDisposition.MachineLocal("this desk's OSC listener: whether it is on, its port and its interface"),
        DaoOscSendersTable to SyncDisposition.MachineLocal("the senders this desk hears OSC from, by their LAN addresses"),
        DaoTelemetrySettingsTable to SyncDisposition.MachineLocal("this install's consent to send errors and logs to Sentry"),
        DaoPasswordResetTokens to SyncDisposition.MachineLocal("short-lived local password reset tokens"),
        DaoEffectTubeStates to SyncDisposition.MachineLocal("which confetti tubes on this rig are spent: the physical cannon, not the show"),
        // Venue plan §3.2: this desk's index of its venue snapshots, rebuilt from the venue repo's
        // tags (session 4, `VenueRepoSync`); the version itself travels as a tagged commit, never as this row.
        DaoVenueVersions to SyncDisposition.MachineLocal("this desk's index of venue versions; rebuilt from the venue repo's tags"),

        DaoProjectScalerStates to SyncDisposition.Excluded("machine-local blackout / grand-master level"),
        // Venue plan §3.2, exported from formatVersion 24 (session 4). Only a show on a venue holds
        // any, so `SyncCoverageTest` reads them from `seedShowOnVenue`'s export.
        DaoShowHides to SyncDisposition.Portable("showHides"),
        // Venue plan §3.2, exported from session 5 at formatVersion 24 (session 4 reserved the folder).
        // A show on a venue's alone, read from `seedShowOnVenue`'s export like the hides.
        DaoShowOverrides to SyncDisposition.Portable("showOverrides"),
        // Venue parks, formatVersion 27: a show's releases of its venue's parks, a show on a venue's
        // alone, read from `seedShowOnVenue`'s export like the hides.
        DaoShowParkReleases to SyncDisposition.Portable("showParkReleases"),
        // Fixture colours, formatVersion 36: a show's recolours of its venue's patches and groups, a
        // show on a venue's alone, read from `seedShowOnVenue`'s export like the hides.
        DaoShowColours to SyncDisposition.Portable("showColours"),
        // Group order, formatVersion 44: a show's orders of its venue's groups, a show on a venue's alone,
        // read from `seedShowOnVenue`'s export like the hides.
        DaoShowGroupOrders to SyncDisposition.Portable("showGroupOrders"),
    )

    /** The record folder [table]'s rows are written into, or null for a table no export carries. */
    fun folderOf(table: Table): String? = when (val d = byTable[table]) {
        is SyncDisposition.Portable -> d.folder
        SyncDisposition.ProjectRoot -> PROJECT_FOLDER
        else -> null
    }

    /** Every record folder: each portable table's, and [PROJECT_FOLDER]. */
    val folders: Set<String> = byTable.keys.mapNotNullTo(sortedSetOf()) { folderOf(it) }

    /** The tables whose rows make up [folder]'s records. */
    fun tablesOf(folder: String): Set<Table> = byTable.keys.filterTo(mutableSetOf()) { folderOf(it) == folder }
}
