package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixtureGroupMembers
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.RigOwner
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.reorderGroupMembers
import uk.me.cormack.lighting7.routes.deleteOwnPatch
import uk.me.cormack.lighting7.routes.deleteOwnUniverse
import uk.me.cormack.lighting7.routes.republishCueIfLive
import uk.me.cormack.lighting7.routes.republishForLookEdit
import uk.me.cormack.lighting7.routes.republishForTemplateEdit
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.venue.VenueRigGuard
import java.util.UUID

/**
 * MCP's patch edits beyond `patch_fixtures`' add-or-update: `unpatch_fixtures` (fixtures, and whole
 * universes, out of the patch), `rename_fixtures` (a fixture's key) and `edit_groups` (a group's
 * membership, order and name). A removal deletes the rows the REST routes' deletes do
 * ([deleteOwnPatch], [deleteOwnUniverse]); what still names a removed key, and the rewrite a rename
 * makes, are [RigKeyReferences]'. Each call is checked whole and written in one transaction, and a
 * dry run is that transaction rolled back, so it answers exactly what the write would do.
 */
internal class PatchEditTools(private val state: State) {

    // ─── unpatch_fixtures ───────────────────────────────────────────────

    private class Unpatched(
        val keys: List<String>,
        val universes: List<Pair<Int, Int>>,
        val memberships: Long,
        val tiles: Int,
        val events: Int,
        val namedBy: Map<String, List<RigKeyReferences.User>>,
    )

    fun unpatchFixtures(input: JsonObject, target: ToolProject): ToolExecutionResult {
        val problems = unknownFields(input, listOf("keys", "universes", "dryRun", "projectId"), "unpatch_fixtures").toMutableList()
        val dryRun = dryRun(input, problems)
        val keys = stringList(input, "keys", "unpatch_fixtures", problems).orEmpty()
        val universeRefs = universeRefs(input, problems)
        if (keys.isEmpty() && universeRefs.isEmpty()) problems += "send keys, universes or both"
        duplicates(keys).forEach { problems += "key '$it' is listed twice" }
        duplicates(universeRefs).forEach { (u, s) -> problems += "universe $u (subnet $s) is listed twice" }
        if (problems.isNotEmpty()) return rejected(problems)

        val done = transaction(state.database) {
            val project = DaoProject[target.project.id]
            val rig = RigScope.of(project)
            val inRig = rig.patches().associateBy { it.key }
            val own = rig.own.patches().associateBy { it.key }
            val patches = keys.mapNotNull { key ->
                own[key] ?: run {
                    problems += if (key in inRig) {
                        VenueRigGuard.ownedProblem(rig, "Patch '$key'", VenueRigGuard.VENUE_FIXTURE_HINT)
                    } else {
                        "no fixture is patched as '$key' (get_patch lists the keys)"
                    }
                    null
                }
            }
            val universes = universeRefs.mapNotNull { (universe, subnet) ->
                val config = rig.universeConfigs(
                    (DaoUniverseConfigs.subnet eq subnet) and (DaoUniverseConfigs.universe eq universe),
                ).firstOrNull()
                when {
                    config == null -> { problems += "this project has no universe $universe (subnet $subnet)"; null }
                    rig.ownerOf(config.project.id) == RigOwner.VENUE -> {
                        problems += VenueRigGuard.ownedProblem(rig, "Universe $universe (subnet $subnet)"); null
                    }
                    else -> config
                }
            }
            if (problems.isNotEmpty()) return@transaction null

            val listedIds = patches.map { it.id.value }.toSet()
            val onUniverses = universes.flatMap { it.fixturePatches.toList() }.filter { it.id.value !in listedIds }
            val removed = patches + onUniverses
            val removedIds = removed.map { it.id }
            val namedBy = RigKeyReferences.users(project, removed.map { it.key }.toSet(), emptySet(), inRig.keys)
            val memberships = if (removedIds.isEmpty()) 0L
            else DaoFixtureGroupMember.find { DaoFixtureGroupMembers.fixturePatch inList removedIds }.count()

            var tiles = 0
            var events = 0
            patches.forEach { patch -> deleteOwnPatch(patch).let { (t, e) -> tiles += t; events += e } }
            universes.forEach { config -> deleteOwnUniverse(project, config).let { (t, e) -> tiles += t; events += e } }
            if (dryRun) TransactionManager.current().rollback()
            Unpatched(removed.map { it.key }, universeRefs, memberships, tiles, events, namedBy)
        } ?: return rejected(problems)

        if (!dryRun) {
            if (done.tiles > 0) state.show.fixtures.buskRigChanged()
            if (done.events > 0) state.show.fixtures.cueListChanged()
            if (target.isCurrent) reloadRig(target)
            state.show.fixtures.patchListChanged()
        }
        val verb = if (dryRun) "Would remove" else "Removed"
        return success(
            "$verb ${done.keys.size} fixture(s)" + if (done.universes.isEmpty()) "" else " and ${done.universes.size} universe(s)",
            buildJsonObject {
                put("dryRun", dryRun)
                putJsonArray("removed") { done.keys.forEach { add(it) } }
                putJsonArray("universesRemoved") {
                    done.universes.forEach { (u, s) -> addJsonObject { put("universe", u); put("subnet", s) } }
                }
                put("groupMembershipsRemoved", done.memberships)
                put("buskRigTilesRemoved", done.tiles)
                put("cueEventsRemoved", done.events)
                putJsonArray("namedBy") { namedBy(done.namedBy) }
            },
        )
    }

    // ─── rename_fixtures ────────────────────────────────────────────────

    private class Renamed(
        val renames: Map<String, String>,
        val touched: RigKeyReferences.Touched,
        val scripts: Map<String, List<RigKeyReferences.User>>,
    )

    fun renameFixtures(input: JsonObject, target: ToolProject): ToolExecutionResult {
        val problems = unknownFields(input, listOf("fixtures", "dryRun", "projectId"), "rename_fixtures").toMutableList()
        val dryRun = dryRun(input, problems)
        val list = input["fixtures"] as? JsonArray
        if (list == null || list.isEmpty()) problems += "fixtures must be a non-empty array"
        val rows = list.orEmpty().mapIndexedNotNull { i, element ->
            val where = "fixtures[$i]"
            val row = element as? JsonObject ?: run { problems += "$where: not an object"; return@mapIndexedNotNull null }
            problems += unknownFields(row, listOf("key", "newKey"), where)
            val key = string(row, "key")?.trim().orEmpty()
            val newKey = string(row, "newKey")?.trim().orEmpty()
            val rowProblems = mutableListOf<String>()
            if (key.isEmpty()) rowProblems += "key must be a non-blank string"
            if (newKey.isEmpty()) rowProblems += "newKey must be a non-blank string"
            if (newKey.length > 100) rowProblems += "newKey must be at most 100 characters"
            if ('.' in newKey) rowProblems += "newKey may not contain '.': a fixture's cells are named <key>.<cell>"
            if (key.isNotEmpty() && key == newKey) rowProblems += "newKey is the same as key"
            problems += rowProblems.map { "$where ('$key'): $it" }
            if (rowProblems.isEmpty()) key to newKey else null
        }
        val keys = list.orEmpty().mapNotNull { (it as? JsonObject)?.let { row -> string(row, "key")?.trim() }?.takeIf(String::isNotEmpty) }
        duplicates(keys).forEach { problems += "key '$it' is listed twice" }
        duplicates(rows.map { it.second }).forEach { problems += "newKey '$it' is given to more than one fixture" }
        if (problems.isNotEmpty()) return rejected(problems)
        val renames = rows.toMap()

        val done = transaction(state.database) {
            val project = DaoProject[target.project.id]
            val rig = RigScope.of(project)
            val inRig = rig.patches().associateBy { it.key }
            val own = rig.own.patches().associateBy { it.key }
            val patches = renames.keys.mapNotNull { key ->
                own[key] ?: run {
                    problems += if (key in inRig) {
                        VenueRigGuard.ownedProblem(rig, "Patch '$key'", VenueRigGuard.VENUE_FIXTURE_HINT)
                    } else {
                        "no fixture is patched as '$key' (get_patch lists the keys)"
                    }
                    null
                }
            }
            renames.values.forEach { newKey ->
                val holder = inRig[newKey] ?: return@forEach
                if (holder.key in renames) return@forEach
                problems += if (rig.ownerOf(holder.project.id) == RigOwner.VENUE) {
                    "${VenueRigGuard.CODE_KEY_TAKEN_BY_VENUE}: '$newKey' is a venue fixture's key — keys are one namespace " +
                        "across a show and its venue; to reuse it, hide that fixture in this show (override_venue_fixtures)"
                } else {
                    "'$newKey' is already patched (rename it too, or pick another key)"
                }
            }
            if (problems.isNotEmpty()) return@transaction null

            val scripts = scriptsNaming(project, renames.keys, emptySet(), inRig.keys)
            renameRows(patches.map { it.id.value to renames.getValue(it.key) }) { id, value ->
                DaoFixturePatches.update({ DaoFixturePatches.id eq id }) { it[DaoFixturePatches.key] = value }
            }
            val touched = RigKeyReferences.rewrite(project, renames, emptyMap(), patches.map { it.id.value }, inRig.keys)
            if (dryRun) TransactionManager.current().rollback()
            Renamed(renames, touched, scripts)
        } ?: return rejected(problems)

        if (!dryRun) announceRewrite(target, done.touched)
        return success(
            "${if (dryRun) "Would rename" else "Renamed"} ${done.renames.size} fixture(s)",
            buildJsonObject {
                put("dryRun", dryRun)
                putJsonArray("renamed") {
                    done.renames.forEach { (from, to) -> addJsonObject { put("key", from); put("newKey", to) } }
                }
                rewrittenCounts(done.touched)
                putJsonArray("scriptsNotRewritten") { scriptsNotRewritten(done.scripts, "key") }
            },
        )
    }

    // ─── edit_groups ────────────────────────────────────────────────────

    private class GroupRow(
        val index: Int,
        val name: String,
        val newName: String?,
        val members: List<String>?,
        val add: List<String>,
        val remove: List<String>,
    ) {
        val where get() = "groups[$index] ('$name')"
    }

    private class GroupDone(val name: String, val newName: String?, val created: Boolean, val members: List<String>)

    private class Edited(
        val groups: List<GroupDone>,
        val touched: RigKeyReferences.Touched,
        val scripts: Map<String, List<RigKeyReferences.User>>,
    )

    fun editGroups(input: JsonObject, target: ToolProject): ToolExecutionResult {
        val problems = unknownFields(input, listOf("groups", "dryRun", "projectId"), "edit_groups").toMutableList()
        val dryRun = dryRun(input, problems)
        val list = input["groups"] as? JsonArray
        if (list == null || list.isEmpty()) problems += "groups must be a non-empty array"
        val rows = list.orEmpty().mapIndexedNotNull { i, element ->
            val row = element as? JsonObject ?: run { problems += "groups[$i]: not an object"; return@mapIndexedNotNull null }
            val name = string(row, "name")?.trim().orEmpty()
            val where = "groups[$i] ('$name')"
            val rowProblems = unknownFields(row, listOf("name", "newName", "members", "add", "remove"), where).toMutableList()
            val newName = string(row, "newName")?.trim()
            val members = stringList(row, "members", where, rowProblems)
            val add = stringList(row, "add", where, rowProblems).orEmpty().distinct()
            val remove = stringList(row, "remove", where, rowProblems).orEmpty().distinct()
            if (name.isEmpty()) rowProblems += "$where: name must be a non-blank string"
            if (row["newName"] != null && row["newName"] !is JsonNull && newName.isNullOrEmpty()) {
                rowProblems += "$where: newName must be a non-blank string"
            }
            if (newName != null && newName.length > 100) rowProblems += "$where: newName must be at most 100 characters"
            if (newName != null && newName == name) rowProblems += "$where: newName is the same as name"
            if (members != null && (add.isNotEmpty() || remove.isNotEmpty())) {
                rowProblems += "$where: send members (the whole list) or add/remove, not both"
            }
            members?.let { duplicates(it).forEach { k -> rowProblems += "$where: '$k' is listed twice in members" } }
            add.filter { it in remove }.forEach { rowProblems += "$where: '$it' is in both add and remove" }
            if (newName == null && members == null && add.isEmpty() && remove.isEmpty()) {
                rowProblems += "$where: nothing to change — send newName, members, add or remove"
            }
            problems += rowProblems
            if (rowProblems.isEmpty()) GroupRow(i, name, newName?.takeIf { it.isNotEmpty() }, members, add, remove) else null
        }
        val names = list.orEmpty().mapNotNull { (it as? JsonObject)?.let { row -> string(row, "name")?.trim() }?.takeIf(String::isNotEmpty) }
        duplicates(names).forEach { problems += "group '$it' is listed twice" }
        if (problems.isNotEmpty()) return rejected(problems)

        val done = transaction(state.database) {
            val project = DaoProject[target.project.id]
            val rig = RigScope.of(project)
            val inRig = rig.groups().associateBy { it.name }
            val own = rig.own.groups().associateBy { it.name }
            val patchesByKey = rig.patches().associateBy { it.key }
            val keyById = patchesByKey.values.associate { it.id.value to it.key }

            val existing = HashMap<Int, List<DaoFixtureGroupMember>>()
            val creates = mutableListOf<GroupRow>()
            rows.forEach { row ->
                val group = own[row.name]
                when {
                    group != null -> existing[row.index] = rig.members(group).sortedBy { it.sortOrder }
                        .filter { it.readValues[DaoFixtureGroupMembers.fixturePatch].value in keyById }
                    row.name in inRig -> problems += VenueRigGuard.ownedProblem(rig, "Group '${row.name}'")
                    row.newName == null && row.remove.isEmpty() && (row.members != null || row.add.isNotEmpty()) -> creates += row
                    else -> problems += "${row.where}: this project has no group by that name " +
                        "(to make one, send members or add without newName or remove)"
                }
                ((row.members ?: emptyList()) + row.add + row.remove).filter { it !in patchesByKey }.distinct().forEach {
                    problems += "${row.where}: no fixture is patched as '$it' (get_patch lists the keys)"
                }
                existing[row.index]?.let { members ->
                    val held = members.map { keyById.getValue(it.readValues[DaoFixtureGroupMembers.fixturePatch].value) }.toSet()
                    row.remove.filter { it in patchesByKey && it !in held }.forEach {
                        problems += "${row.where}: '$it' is not in this group"
                    }
                }
            }
            val renames = rows.filter { it.newName != null && it.index in existing }.associate { it.name to it.newName!! }
            val claimed = renames.values + creates.map { it.name }
            duplicates(claimed).forEach { problems += "group name '$it' is given to more than one group" }
            claimed.distinct().forEach { name ->
                val holder = inRig[name] ?: return@forEach
                if (holder.name in renames) return@forEach
                problems += if (rig.ownerOf(holder.project.id) == RigOwner.VENUE) {
                    "${VenueRigGuard.CODE_KEY_TAKEN_BY_VENUE}: group name '$name' is the venue's — names are one namespace " +
                        "across a show and its venue"
                } else {
                    "a group named '$name' already exists (rename it too, or pick another name)"
                }
            }
            if (problems.isNotEmpty()) return@transaction null

            val scripts = scriptsNaming(project, emptySet(), renames.keys, patchesByKey.keys)
            renameRows(renames.map { (from, to) -> own.getValue(from).id.value to to }) { id, value ->
                DaoFixtureGroups.update({ DaoFixtureGroups.id eq id }) { it[DaoFixtureGroups.name] = value }
            }
            val groups = rows.map { row ->
                val group = own[row.name] ?: DaoFixtureGroup.new {
                    this.project = project
                    name = row.name
                }
                val before = existing[row.index].orEmpty()
                val members = editMembers(group, before, row, patchesByKey, keyById)
                GroupDone(row.name, row.newName.takeIf { row.index in existing }, created = row.index !in existing, members)
            }
            val touched = RigKeyReferences.rewrite(project, emptyMap(), renames, emptyList(), patchesByKey.keys)
            if (dryRun) TransactionManager.current().rollback()
            Edited(groups, touched, scripts)
        } ?: return rejected(problems)

        if (!dryRun) announceRewrite(target, done.touched)
        return success(
            "${if (dryRun) "Would edit" else "Edited"} ${done.groups.size} group(s)",
            buildJsonObject {
                put("dryRun", dryRun)
                putJsonArray("groups") {
                    done.groups.forEach { g ->
                        addJsonObject {
                            put("name", g.newName ?: g.name)
                            g.newName?.let { put("renamedFrom", g.name) }
                            if (g.created) put("created", true)
                            putJsonArray("members") { g.members.forEach { add(it) } }
                        }
                    }
                }
                rewrittenCounts(done.touched)
                putJsonArray("scriptsNotRewritten") { scriptsNotRewritten(done.scripts, "group") }
            },
        )
    }

    /**
     * Apply [row]'s membership change to [group], whose rig-visible members are [before] in order (a
     * venue fixture the show hides is left where it is), and answer the members as they now stand.
     */
    private fun editMembers(
        group: DaoFixtureGroup,
        before: List<DaoFixtureGroupMember>,
        row: GroupRow,
        patchesByKey: Map<String, DaoFixturePatch>,
        keyById: Map<Int, String>,
    ): List<String> {
        val byKey = before.associateBy { keyById.getValue(it.readValues[DaoFixtureGroupMembers.fixturePatch].value) }
        fun join(key: String, order: Int) {
            DaoFixtureGroupMember.new {
                this.group = group
                fixturePatch = patchesByKey.getValue(key)
                sortOrder = order
            }
        }
        val members = row.members
        if (members != null) {
            byKey.filterKeys { it !in members }.values.forEach { it.delete() }
            // New members join after every place taken — a hidden venue fixture's included — and
            // [reorderGroupMembers] then puts the named ones in order in the places they hold. A
            // place written here first could tie a hidden fixture's and move it.
            var next = (group.members.maxOfOrNull { it.sortOrder } ?: -1) + 1
            members.filter { it !in byKey }.forEach { join(it, next++) }
            reorderGroupMembers(group, members.map { patchesByKey.getValue(it).id.value })
            return members
        }
        row.remove.forEach { byKey[it]?.delete() }
        var next = (before.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val added = row.add.filter { it !in byKey }
        added.forEach { join(it, next++) }
        return byKey.keys.filter { it !in row.remove } + added
    }

    // ─── Shared ─────────────────────────────────────────────────────────

    /**
     * Give each row id its new value through a temporary one first, so a swap never meets the
     * table's unique index halfway.
     */
    private fun renameRows(renames: List<Pair<Int, String>>, write: (Int, String) -> Unit) {
        renames.forEach { (id, _) -> write(id, "rename-${UUID.randomUUID()}") }
        renames.forEach { (id, value) -> write(id, value) }
    }

    /** The live desk's side of a rename: the rig, then every record [touched] names, then the lists. */
    private fun announceRewrite(target: ToolProject, touched: RigKeyReferences.Touched) {
        val fixtures = state.show.fixtures
        if (target.isCurrent) reloadRig(target)
        if (touched.looks.isNotEmpty()) {
            fixtures.lookListChanged()
            if (target.isCurrent) touched.looks.forEach { republishForLookEdit(state, it) }
        }
        if (touched.templates.isNotEmpty()) {
            fixtures.templateListChanged()
            if (target.isCurrent) touched.templates.forEach { republishForTemplateEdit(state, it) }
        }
        if (touched.cues.isNotEmpty()) {
            if (target.isCurrent) touched.cues.forEach { republishCueIfLive(state, it) }
            fixtures.cuesRecomposed(touched.cues.toList())
            fixtures.cueListChanged()
        }
        if (touched.buskTiles > 0) fixtures.buskRigChanged()
        if (touched.bindings > 0) state.controlSurfaceBindingService.invalidate(target.project.id.value)
        fixtures.patchListChanged()
    }

    private fun reloadRig(target: ToolProject) {
        DbFixtureLoader.loadFixtures(
            target.project.id.value, state.show.fixtures, state.database,
            parkSource = state.show.outputSource, hold = state.show.rigHold,
        )
    }

    private fun JsonObjectBuilder.rewrittenCounts(touched: RigKeyReferences.Touched) {
        putJsonObject("rewritten") {
            put("cues", touched.cues.size)
            put("looks", touched.looks.size)
            put("palettes", touched.templates.size)
            put("bindings", touched.bindings)
            put("buskRigCells", touched.buskTiles)
        }
    }

    /** The scripts among [RigKeyReferences.users] that name a key or group: Kotlin source a rename leaves alone. */
    private fun scriptsNaming(project: DaoProject, keys: Set<String>, groups: Set<String>, patchedKeys: Set<String>) =
        RigKeyReferences.users(project, keys, groups, patchedKeys)
            .mapValues { (_, users) -> users.filter { it.kind == "SCRIPT" } }
            .filterValues { it.isNotEmpty() }

    private fun JsonArrayBuilder.scriptsNotRewritten(found: Map<String, List<RigKeyReferences.User>>, field: String) {
        found.forEach { (ref, users) ->
            addJsonObject {
                put(field, ref.substringAfter(':'))
                putJsonArray("scripts") { users.forEach { add(it.name) } }
            }
        }
    }

    private fun JsonArrayBuilder.namedBy(found: Map<String, List<RigKeyReferences.User>>) {
        found.forEach { (ref, users) ->
            addJsonObject {
                put("kind", ref.substringBefore(':').uppercase())
                put("key", ref.substringAfter(':'))
                putJsonArray("usedBy") {
                    users.forEach { u -> addJsonObject { put("kind", u.kind); put("id", u.id); put("name", u.name) } }
                }
            }
        }
    }

    private fun universeRefs(input: JsonObject, problems: MutableList<String>): List<Pair<Int, Int>> {
        val element = input["universes"] ?: return emptyList()
        if (element is JsonNull) return emptyList()
        val list = element as? JsonArray ?: run { problems += "universes must be an array"; return emptyList() }
        return list.mapIndexedNotNull { i, e ->
            val where = "universes[$i]"
            val row = e as? JsonObject ?: run { problems += "$where: not an object"; return@mapIndexedNotNull null }
            problems += unknownFields(row, listOf("universe", "subnet"), where)
            val universe = int(row, "universe")
            val subnet = if (row["subnet"] == null || row["subnet"] is JsonNull) 0 else int(row, "subnet")
            if (universe == null) problems += "$where: universe must be an integer"
            if (subnet == null) problems += "$where: subnet must be an integer"
            if (universe != null && subnet != null) universe to subnet else null
        }
    }

    private fun dryRun(input: JsonObject, problems: MutableList<String>): Boolean = when (val e = input["dryRun"]) {
        null, is JsonNull -> false
        else -> (e as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: run { problems += "dryRun must be a boolean"; false }
    }

    /** [name] as a list of non-blank strings, trimmed; null when absent, every malformed entry a problem. */
    private fun stringList(row: JsonObject, name: String, where: String, problems: MutableList<String>): List<String>? {
        val element = row[name] ?: return null
        if (element is JsonNull) return null
        val list = element as? JsonArray ?: run { problems += "$where: $name must be an array of strings"; return null }
        return list.mapIndexedNotNull { i, e ->
            val value = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            if (value.isNullOrEmpty()) { problems += "$where: $name[$i] must be a non-blank string"; null } else value
        }
    }

    private fun string(row: JsonObject, name: String): String? = (row[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun int(row: JsonObject, name: String): Int? = (row[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun <T> duplicates(values: List<T>): Set<T> = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    private fun success(description: String, result: JsonObject) =
        ToolExecutionResult(success = true, description = description, result = result.toString())

    private fun rejected(problems: List<String>): ToolExecutionResult = ToolExecutionResult(
        success = false,
        description = "Nothing was written: ${problems.size} problem(s)",
        result = buildJsonObject {
            put("error", "Nothing was written: fix these ${problems.size} problem(s) and send the request again")
            VenueRigGuard.codeOf(problems)?.let { put("code", it) }
            putJsonArray("problems") { problems.distinct().forEach { add(it) } }
        }.toString(),
    )

    private fun unknownFields(row: JsonObject, known: List<String>, where: String): List<String> =
        row.keys.filter { it !in known }.map { "$where: unknown field '$it' (known: ${known.joinToString()})" }
}
