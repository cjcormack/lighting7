package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.Table

/**
 * Every table in the schema, in FK-safe creation order.
 *
 * This is the single source of truth for "what tables exist": `State` passes it to
 * `SchemaUtils.createMissingTablesAndColumns`, and `SyncCoverageTest` asserts every entry
 * has a recorded sync disposition. Adding a table here without deciding whether its rows
 * are portable show content, machine-local, or transient runtime state fails that test —
 * which is the point. See the "Database changes and cloud sync" decision tree in
 * `CLAUDE.md` and `docs/sync-engineering.md`.
 */
val ALL_TABLES: List<Table> = listOf(
    DaoProjects, DaoScripts,
    DaoLooks, DaoLookRows, DaoLookFx,
    DaoPalettes, DaoPaletteRows, DaoPaletteFx,
    DaoSpeedMasters,
    DaoCueStacks, DaoCues,
    // After the stacks and cues it places (cue model plan session 1).
    DaoCueStackEntries,
    DaoCueLayers,
    DaoCueAdHocFx, DaoCueAttributeAssignments, DaoCueTriggers,
    DaoCueSlots,
    DaoBuskPages, DaoBuskColumns, DaoBuskBanks, DaoBuskPads,
    DaoUniverseConfigs, DaoRiggings, DaoStageRegions,
    DaoStageElements, DaoStageViewpoints,
    // After the cues, stacks, Looks and elements they reference (stage-view plan session 8).
    DaoCueScenery, DaoCueStackScenery, DaoLookScenery,
    DaoFixturePatches, DaoFixturePatchPlacements, DaoFixtureGroups, DaoFixtureGroupMembers,
    // After the cues and patches they reference (stage-view plan session 9).
    DaoCueEvents,
    // After the groups and patches their tiles reference.
    DaoBuskRigRows, DaoBuskRigTiles,
    DaoParkedChannels, DaoFxDefinitions,
    DaoPromptBooks, DaoPromptBookAnchors, DaoPromptBookAnnotations,
    DaoControlSurfaceBindings,
    DaoProjectScalerStates,
    DaoInstalls, DaoMachineOverrides,
    DaoSyncConfigs, DaoSyncLinkedRepos,
    DaoSyncStates, DaoSyncSessions, DaoSyncSessionConflicts,
    DaoSyncLogEntries,
    DaoOAuthIdentities,
    DaoUsers, DaoUserSessions, DaoPasswordResetTokens,
    // After DaoUsers, which a grant references.
    DaoMcpOAuthClients, DaoMcpOAuthGrants,
    DaoRemoteAccessSettingsTable,
    DaoTelemetrySettingsTable,
    // Machine-local one-shot tube state (stage-view plan session 9); keyed by patch uuid, no FK.
    DaoEffectTubeStates,
    // Machine-local: this desk's index of venue snapshots (venue plan session 2), after the projects.
    DaoVenueVersions,
    // A show's hides of venue records (venue plan session 3); keyed by record uuid, no FK into the rig.
    DaoShowHides,
    // A show's facet overrides of venue patches (venue plan session 5); keyed by record uuid likewise.
    DaoShowOverrides,
    // A show's releases of venue parks (venue parks, PR 2); keyed by universe and channel, no FK into the rig.
    DaoShowParkReleases,
    // A show's recolours of venue patches and groups (tables-colours plan D10); keyed by record uuid.
    DaoShowColours,
)
