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
    DaoLooks, DaoLookRows, DaoLookEffects,
    DaoTemplates, DaoTemplateRows, DaoTemplateEffects,
    DaoSpeedMasters,
    DaoCueStacks, DaoCues,
    DaoCueLayers,
    DaoCueAdHocEffects, DaoCuePropertyAssignments, DaoCueTriggers,
    DaoAiConversations, DaoCueSlots,
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
    // Machine-local one-shot tube state (stage-view plan session 9); keyed by patch uuid, no FK.
    DaoEffectTubeStates,
)
