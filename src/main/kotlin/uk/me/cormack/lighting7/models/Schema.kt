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
    DaoProjects, DaoScripts, DaoFxPresets, DaoFxPresetPropertyAssignments,
    DaoPalettes, DaoPaletteEntries,
    DaoCueStacks, DaoCues,
    DaoCuePresetApplications, DaoCueAdHocEffects, DaoCuePropertyAssignments, DaoCueTriggers,
    DaoAiConversations, DaoCueSlots,
    DaoUniverseConfigs, DaoRiggings, DaoStageRegions,
    DaoFixturePatches, DaoFixtureGroups, DaoFixtureGroupMembers,
    DaoParkedChannels, DaoFxDefinitions,
    DaoPromptBooks, DaoPromptBookAnchors, DaoPromptBookAnnotations,
    DaoControlSurfaceBindings,
    DaoProjectScalerStates,
    DaoInstalls, DaoMachineOverrides,
    DaoSyncConfigs, DaoSyncLinkedRepos,
    DaoSyncStates, DaoSyncSessions, DaoSyncSessionConflicts,
    DaoSyncLogEntries,
    DaoOAuthIdentities,
)
