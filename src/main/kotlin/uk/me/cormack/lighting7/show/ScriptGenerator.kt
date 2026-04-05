package uk.me.cormack.lighting7.show

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.models.*

/**
 * Generates a Kotlin DSL load-fixtures script from DB patch records.
 * Used when switching a project from DB_BASED to SCRIPT_BASED mode.
 */
object ScriptGenerator {

    /**
     * Generate a load-fixtures script from the project's DB patch configuration.
     */
    fun generateLoadFixturesScript(projectId: Int, database: Database): String {
        val (universeConfigs, patches, groups) = transaction(database) {
            val project = DaoProject.findById(projectId)
                ?: throw IllegalArgumentException("Project $projectId not found")

            val configs = DaoUniverseConfig.find { DaoUniverseConfigs.project eq project.id }
                .orderBy(DaoUniverseConfigs.universe to SortOrder.ASC)
                .map { ConfigData(it.id.value, it.subnet, it.universe, it.controllerType, it.address) }

            val patches = DaoFixturePatch.find { DaoFixturePatches.project eq project.id }
                .orderBy(DaoFixturePatches.sortOrder to SortOrder.ASC)
                .map { PatchData(it.key, it.displayName, it.fixtureTypeKey, it.startChannel, it.universeConfig.id.value) }

            val groups = DaoFixtureGroup.find { DaoFixtureGroups.project eq project.id }
                .map { group ->
                    val members = group.members
                        .sortedBy { it.sortOrder }
                        .map { GroupMemberRef(it.fixturePatch.key, it.panOffset, it.tiltOffset) }
                    GroupRef(group.name, members)
                }

            Triple(configs, patches, groups)
        }

        return buildScript(universeConfigs, patches, groups)
    }

    private fun buildScript(
        universeConfigs: List<ConfigData>,
        patches: List<PatchData>,
        groups: List<GroupRef>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("fixtures.register {")

        // Universe variables
        val universeVarNames = mutableMapOf<Int, String>() // configId → varName
        for (config in universeConfigs) {
            val varName = "u${config.universe}"
            universeVarNames[config.id] = varName
            sb.appendLine("    val $varName = Universe(${config.subnet}, ${config.universe})")

            val controllerExpr = when (config.controllerType.uppercase()) {
                "ARTNET" -> if (config.address != null) {
                    "ArtNetController($varName, \"${config.address}\")"
                } else {
                    "ArtNetController($varName)"
                }
                "MOCK" -> "MockDmxController($varName)"
                else -> "ArtNetController($varName)"
            }
            sb.appendLine("    addController($controllerExpr)")
            sb.appendLine()
        }

        // Fixture variables
        val fixtureVarNames = mutableMapOf<String, String>() // fixtureKey → varName
        val usedVarNames = mutableSetOf<String>()

        for (patch in patches) {
            val className = resolveClassName(patch.fixtureTypeKey)
            val varName = toVarName(patch.key, usedVarNames)
            fixtureVarNames[patch.key] = varName
            usedVarNames.add(varName)

            val universeVar = universeVarNames[patch.universeConfigId] ?: "u0"
            sb.appendLine("    val $varName = addFixture($className($universeVar, \"${escapeString(patch.key)}\", \"${escapeString(patch.displayName)}\", ${patch.startChannel}))")
        }

        // Groups
        if (groups.isNotEmpty() && patches.isNotEmpty()) {
            sb.appendLine()
        }
        for (group in groups) {
            if (group.members.isEmpty()) continue

            sb.appendLine("    createGroup<GroupableFixture>(\"${escapeString(group.name)}\") {")
            for (member in group.members) {
                val fixtureVar = fixtureVarNames[member.fixtureKey] ?: continue
                val offsets = buildList {
                    if (member.panOffset != 0.0) add("panOffset = ${member.panOffset}")
                    if (member.tiltOffset != 0.0) add("tiltOffset = ${member.tiltOffset}")
                }
                if (offsets.isEmpty()) {
                    sb.appendLine("        add($fixtureVar)")
                } else {
                    sb.appendLine("        add($fixtureVar, ${offsets.joinToString(", ")})")
                }
            }
            sb.appendLine("    }")
        }

        sb.appendLine("}")
        return sb.toString()
    }

    /**
     * Resolve a typeKey to a Kotlin class simple name.
     * Falls back to the typeKey itself if not found.
     */
    private fun resolveClassName(typeKey: String): String {
        return FixtureTypeRegistry.classNameForTypeKey(typeKey)
            ?: (typeKey.replaceFirstChar { it.uppercase() } + "Fixture")
    }

    /**
     * Convert a fixture key like "par-1" to a valid Kotlin variable name like "par1".
     */
    private fun toVarName(key: String, usedNames: Set<String>): String {
        var name = key
            .replace(Regex("[^a-zA-Z0-9]"), "")
            .replaceFirstChar { it.lowercase() }

        if (name.isEmpty() || name[0].isDigit()) {
            name = "f$name"
        }

        // Ensure uniqueness
        var candidate = name
        var suffix = 2
        while (candidate in usedNames) {
            candidate = "$name$suffix"
            suffix++
        }
        return candidate
    }

    private fun escapeString(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    // Data classes
    private data class ConfigData(val id: Int, val subnet: Int, val universe: Int, val controllerType: String, val address: String?)
    private data class PatchData(val key: String, val displayName: String, val fixtureTypeKey: String, val startChannel: Int, val universeConfigId: Int)
    private data class GroupMemberRef(val fixtureKey: String, val panOffset: Double, val tiltOffset: Double)
    private data class GroupRef(val name: String, val members: List<GroupMemberRef>)
}
