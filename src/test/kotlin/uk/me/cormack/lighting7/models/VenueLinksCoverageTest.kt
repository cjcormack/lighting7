package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.Column
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Holds [VenueLinks] complete: every foreign key into a venue-ownable table has an entry, so a
 * venue Update, Extract or Detach re-points it rather than leaving it on the old version's row.
 *
 * Read from Exposed's own foreign-key metadata for every table in [ALL_TABLES], not from a list
 * kept by hand — as `SyncCoverageTest` forces a sync decision for every table, this forces a
 * re-point for every link. Add a `reference` into one of [VenueLinks.VENUE_OWNABLE] and forget the
 * registry, and this fails naming the column.
 */
class VenueLinksCoverageTest {

    private fun Column<*>.qualified() = "${table.tableName}.$name"

    /** Every foreign key in the schema whose target is a venue-ownable table, as (column, target). */
    private val linksInSchema: List<Pair<Column<*>, String>> =
        ALL_TABLES.flatMap { it.foreignKeys }
            .filter { it.targetTable in VenueLinks.VENUE_OWNABLE }
            .flatMap { fk -> fk.from.map { it to fk.targetTable.tableName } }

    @Test
    fun `every foreign key into a venue-ownable table has a VenueLinks entry`() {
        val registered = VenueLinks.ALL.map { it.column }.toSet<Column<*>>()
        val missing = linksInSchema.filter { (column, _) -> column !in registered }
            .map { (column, target) -> "${column.qualified()} -> $target" }
        assertEquals(
            emptyList(), missing,
            "Foreign key(s) into a venue-ownable table with no VenueLinks entry. A venue Update would " +
                "leave them on the old version's row: add each to VenueLinks.ALL with its owner path " +
                "(models/VenueLinks.kt), and a row to VenueLinksRepointTest.",
        )
    }

    @Test
    fun `every VenueLinks entry is a real foreign key to the target it names`() {
        val inSchema = linksInSchema.associate { (column, target) -> column to target }
        val stale = VenueLinks.ALL.filter { inSchema[it.column] != it.target.tableName }
            .map { "${it.name} -> ${it.target.tableName}" }
        assertEquals(emptyList(), stale, "VenueLinks entry with no matching foreign key in the schema")
    }

    @Test
    fun `the registry is the twelve links the venue plan found, once each`() {
        assertEquals(
            listOf(
                "busk_rig_tiles.group_id", "busk_rig_tiles.patch_id",
                "cue_events.patch_id",
                "cue_scenery.element_id", "cue_stack_scenery.element_id",
                "fixture_group_members.fixture_patch_id", "fixture_group_members.group_id",
                "fixture_patch_placements.fixture_patch_id", "fixture_patch_placements.rigging_id",
                "fixture_patches.rigging_id", "fixture_patches.universe_config_id",
                "look_scenery.element_id",
            ),
            VenueLinks.ALL.map { it.name }.sorted(),
        )
    }

    @Test
    fun `every venue-ownable table is in the schema`() {
        assertEquals(emptyList(), VenueLinks.VENUE_OWNABLE.filter { it !in ALL_TABLES }.map { it.tableName })
    }
}
