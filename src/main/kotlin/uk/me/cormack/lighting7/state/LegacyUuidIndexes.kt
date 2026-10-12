package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/**
 * Global `UNIQUE (uuid)` indexes the dev desk's database carries from before the model stopped
 * declaring them. A record uuid is not unique across projects — an import keeps them and a venue's
 * versions share them (`docs/fixtures-engineering.md` §"The rig scope") — so with these in place
 * the first venue extract or publish fails on `UNIQUE constraint failed: universe_configs.uuid`.
 * `createMissingTablesAndColumns` never drops an index, and a fresh database never had them.
 *
 * `uq_projects_uuid` stays: a project's uuid is unique, and the importer refuses a collision.
 */
internal val LEGACY_UUID_INDEXES = listOf(
    "uq_scripts_uuid",
    "uq_fx_definitions_uuid",
    "uq_universe_configs_uuid",
    "uq_fixture_patches_uuid",
    "uq_fixture_groups_uuid",
    "uq_fixture_group_members_uuid",
    "uq_cue_stacks_uuid",
    "uq_cue_slots_uuid",
    "uq_control_surface_bindings_uuid",
    "uq_cue_ad_hoc_effects_uuid",
    "uq_cue_property_assignments_uuid",
    "uq_cue_triggers_uuid",
    "uq_cues_uuid",
)

internal fun JdbcTransaction.dropLegacyUuidIndexes() {
    LEGACY_UUID_INDEXES.forEach { exec("DROP INDEX IF EXISTS $it") }
}
