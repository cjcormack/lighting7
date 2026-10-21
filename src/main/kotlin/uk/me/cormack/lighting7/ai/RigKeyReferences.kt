package uk.me.cormack.lighting7.ai

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.midi.BindingTargetJson
import uk.me.cormack.lighting7.midi.encodePayload
import uk.me.cormack.lighting7.models.DaoBuskRigRows
import uk.me.cormack.lighting7.models.DaoBuskRigTile
import uk.me.cormack.lighting7.models.DaoBuskRigTiles
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoControlSurfaceBindings
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCueAdHocEffects
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignments
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookEffect
import uk.me.cormack.lighting7.models.DaoLookEffects
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoLookRows
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.DaoScripts
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplateRow
import uk.me.cormack.lighting7.models.DaoTemplateRows
import uk.me.cormack.lighting7.models.DaoTemplates
import uk.me.cormack.lighting7.models.EffectTargetRef
import uk.me.cormack.lighting7.models.TargetRef
import java.util.UUID
import uk.me.cormack.lighting7.models.entrySlotsOf
import uk.me.cormack.lighting7.models.placeLabel

/**
 * Where a project's own stored records name a fixture by key or a group by name — cue rows, ad-hoc
 * effects and palette-layer targets, Look rows and effects, template rows and surface bindings — for MCP's
 * patch edits: who still names a key `unpatch_fixtures` removes, and the rewrite `rename_fixtures`
 * and `edit_groups` make. A cell is named `<fixture key>.<cell>`, so a fixture key also matches its
 * cells. Scripts are Kotlin source, so they are only ever reported. Inside a transaction.
 */
internal object RigKeyReferences {
    /** Who names a key: `CUE | LOOK | PALETTE | BINDING | SCRIPT`. */
    data class User(val kind: String, val id: Int, val name: String)

    /** What a rewrite changed: the records to republish and announce. */
    class Touched {
        /** The cues a rewrite touched, for `republishCueIfLive` and `cuesRecomposed`. */
        val cues = LinkedHashSet<Int>()
        val looks = LinkedHashSet<UUID>()
        val templates = LinkedHashSet<UUID>()
        var bindings = 0
        var buskTiles = 0
        val isEmpty get() = cues.isEmpty() && looks.isEmpty() && templates.isEmpty() && bindings == 0 && buskTiles == 0
    }

    /**
     * Every user of [fixtureKeys] (a key or one of its cells) and [groupNames] in [project], keyed by
     * `fixture:<key>` / `group:<name>` as the answer names them. [patchedKeys] is every key in the
     * rig, so a fixture whose own key holds a `.` is never read as another's cell.
     */
    fun users(
        project: DaoProject,
        fixtureKeys: Set<String>,
        groupNames: Set<String>,
        patchedKeys: Set<String>,
    ): Map<String, List<User>> {
        val found = LinkedHashMap<String, LinkedHashSet<User>>()
        walk(project, Touched()) { user, type, key ->
            val name = match(type, key, fixtureKeys, groupNames, patchedKeys)
            if (name != null) found.getOrPut(name) { LinkedHashSet() } += user
            null
        }
        if (fixtureKeys.isNotEmpty() || groupNames.isNotEmpty()) {
            DaoScript.find { DaoScripts.project eq project.id }.forEach { script ->
                val user = User("SCRIPT", script.id.value, script.name)
                fixtureKeys.filter { "\"$it\"" in script.script }.forEach { found.getOrPut("fixture:$it") { LinkedHashSet() } += user }
                groupNames.filter { "\"$it\"" in script.script }.forEach { found.getOrPut("group:$it") { LinkedHashSet() } += user }
            }
        }
        return found.mapValues { it.value.toList() }
    }

    /**
     * Rename [fixtures] (old key → new) and [groups] (old name → new) everywhere [project] names
     * them, the renamed fixtures' busk rig cell tiles included. Every stored value is mapped once
     * from its old value, so a swap (a → b, b → a) lands as one. [patchedKeys] is every key in the
     * rig before the rename, as for [users].
     */
    fun rewrite(
        project: DaoProject,
        fixtures: Map<String, String>,
        groups: Map<String, String>,
        renamedPatchIds: Collection<Int>,
        patchedKeys: Set<String>,
    ): Touched {
        val touched = Touched()
        if (fixtures.isEmpty() && groups.isEmpty()) return touched
        walk(project, touched) { _, type, key -> renamed(type, key, fixtures, groups, patchedKeys) }
        if (renamedPatchIds.isNotEmpty()) {
            // A venue patch's tiles are every show's on that version: only this project's rows are rewritten.
            val rows = DaoBuskRigRows.select(DaoBuskRigRows.id).where { DaoBuskRigRows.project eq project.id }.map { it[DaoBuskRigRows.id] }
            DaoBuskRigTile.find {
                (DaoBuskRigTiles.patch inList renamedPatchIds.toList()) and DaoBuskRigTiles.elementKey.isNotNull() and (DaoBuskRigTiles.row inList rows)
            }
                .forEach { tile ->
                    val next = tile.elementKey?.let { renamed(TargetRef.Fixture.TYPE, it, fixtures, emptyMap(), patchedKeys) } ?: return@forEach
                    tile.elementKey = next
                    touched.buskTiles++
                }
        }
        return touched
    }

    /** The name [key] of [type] answers to among the given sets, or null. */
    private fun match(type: String, key: String, fixtureKeys: Set<String>, groupNames: Set<String>, patchedKeys: Set<String>): String? =
        when (type) {
            TargetRef.Fixture.TYPE -> fixtureOwner(key, fixtureKeys, patchedKeys)?.let { "fixture:$it" }
            TargetRef.Group.TYPE -> key.takeIf { it in groupNames }?.let { "group:$it" }
            else -> null
        }

    private fun renamed(
        type: String,
        key: String,
        fixtures: Map<String, String>,
        groups: Map<String, String>,
        patchedKeys: Set<String>,
    ): String? =
        when (type) {
            TargetRef.Fixture.TYPE -> fixtureOwner(key, fixtures.keys, patchedKeys)?.let { old -> fixtures.getValue(old) + key.removePrefix(old) }
            TargetRef.Group.TYPE -> groups[key]
            else -> null
        }

    /**
     * The fixture key among [keys] that [key] is, or is a cell of — the longest, should two nest. A
     * [key] that is itself one of [patchedKeys] is that fixture, never a cell.
     */
    private fun fixtureOwner(key: String, keys: Set<String>, patchedKeys: Set<String>): String? = when (key) {
        in keys -> key
        in patchedKeys -> null
        else -> keys.filter { key.startsWith("$it.") }.maxByOrNull { it.length }
    }

    /**
     * Visit every key-naming value in [project]'s own records; a non-null answer from [visit] is the
     * value's replacement, written back and recorded in [touched].
     */
    private fun walk(project: DaoProject, touched: Touched, visit: (User, String, String) -> String?) {
        // An effect's target may be a selection (effects plan D3): each fixture and group it names is
        // visited, and the key rebuilt when any of them is renamed.
        fun visitEffect(user: User, type: String, key: String): String? {
            if (type != EffectTargetRef.Selection.TYPE) return visit(user, type, key)
            val items = EffectTargetRef.Selection.parseKey(key) ?: return null
            var changed = false
            val next = items.map { item ->
                val renamed = visit(user, item.discriminator, item.key) ?: return@map item
                changed = true
                when (item) {
                    is TargetRef.Fixture -> TargetRef.Fixture(renamed)
                    is TargetRef.Group -> TargetRef.Group(renamed)
                }
            }
            return if (changed) EffectTargetRef.Selection(next).key else null
        }

        val cues = DaoCue.find { DaoCues.project eq project.id }.associateBy { it.id.value }
        if (cues.isNotEmpty()) {
            val ids = cues.keys.toList()
            // A cue is named with every place it has — `Dawn (Act 1 Q14, Act 2 Q3)` — read for every cue at once.
            val places = entrySlotsOf(ids)
            fun cueUser(id: Int) = cues.getValue(id).let { cue ->
                val where = places[id].orEmpty().joinToString { it.placeLabel() }
                User("CUE", id, if (where.isEmpty()) cue.name else "${cue.name} ($where)")
            }
            fun touch(id: Int) { touched.cues += id }
            DaoCuePropertyAssignment.find { DaoCuePropertyAssignments.cue inList ids }.forEach { row ->
                val cueId = row.readValues[DaoCuePropertyAssignments.cue].value
                visit(cueUser(cueId), row.targetType, row.targetKey)?.let { row.targetKey = it; touch(cueId) }
            }
            DaoCueAdHocEffect.find { DaoCueAdHocEffects.cue inList ids }.forEach { row ->
                val cueId = row.readValues[DaoCueAdHocEffects.cue].value
                visitEffect(cueUser(cueId), row.targetType, row.targetKey)?.let { row.targetKey = it; touch(cueId) }
            }
            // A palette layer's targets; a Look's layer names none (absolute-looks plan D1).
            DaoCueLayer.find { (DaoCueLayers.cue inList ids) and DaoCueLayers.look.isNull() }.forEach { layer ->
                val cueId = layer.readValues[DaoCueLayers.cue].value
                var changed = false
                val targets = layer.targets.map { t ->
                    visit(cueUser(cueId), t.type, t.key)?.let { changed = true; t.copy(key = it) } ?: t
                }
                if (changed) { layer.targets = targets; touch(cueId) }
            }
        }

        val looks = DaoLook.find { DaoLooks.project eq project.id }.associateBy { it.id.value }
        if (looks.isNotEmpty()) {
            val ids = looks.keys.toList()
            fun lookUser(id: Int) = User("LOOK", id, looks.getValue(id).name)
            DaoLookRow.find { DaoLookRows.look inList ids }.forEach { row ->
                val lookId = row.readValues[DaoLookRows.look].value
                visit(lookUser(lookId), row.targetType, row.targetKey)?.let { row.targetKey = it; touched.looks += looks.getValue(lookId).uuid }
                row.elementKey?.let { element ->
                    visit(lookUser(lookId), TargetRef.Fixture.TYPE, element)?.let { row.elementKey = it; touched.looks += looks.getValue(lookId).uuid }
                }
            }
            DaoLookEffect.find { DaoLookEffects.look inList ids }.forEach { row ->
                val lookId = row.readValues[DaoLookEffects.look].value
                visitEffect(lookUser(lookId), row.targetType, row.targetKey)?.let { row.targetKey = it; touched.looks += looks.getValue(lookId).uuid }
            }
        }

        val templates = DaoTemplate.find { DaoTemplates.project eq project.id }.associateBy { it.id.value }
        if (templates.isNotEmpty()) {
            DaoTemplateRow.find { DaoTemplateRows.template inList templates.keys.toList() }.forEach { row ->
                val templateId = row.readValues[DaoTemplateRows.template].value
                val template = templates.getValue(templateId)
                visit(User("PALETTE", templateId, template.name), row.targetType, row.targetKey)
                    ?.let { row.targetKey = it; touched.templates += template.uuid }
            }
        }

        DaoControlSurfaceBinding.find { DaoControlSurfaceBindings.project eq project.id }.forEach { binding ->
            val target = runCatching { BindingTargetJson.decodeFromString<BindingTarget>(binding.targetPayload) }.getOrNull()
                ?: return@forEach
            val user = User(
                "BINDING", binding.id.value,
                listOfNotNull(binding.deviceTypeKey, binding.controlId, binding.bank?.let { "bank $it" }).joinToString(" "),
            )
            target.renamed { type, key -> visit(user, type, key) }?.let {
                binding.targetPayload = it.encodePayload()
                touched.bindings++
            }
        }
    }

    /** This target with every fixture or group it names passed through [rename]; null when nothing changed. */
    private fun BindingTarget.renamed(rename: (String, String) -> String?): BindingTarget? = when (this) {
        is BindingTarget.FixtureProperty -> rename(TargetRef.Fixture.TYPE, fixtureKey)?.let { copy(fixtureKey = it) }
        is BindingTarget.FireTrigger -> rename(TargetRef.Fixture.TYPE, fixtureKey)?.let { copy(fixtureKey = it) }
        is BindingTarget.GroupProperty -> rename(TargetRef.Group.TYPE, groupName)?.let { copy(groupName = it) }
        is BindingTarget.Flash -> target.renamed(rename)?.let { copy(target = it) }
        is BindingTarget.SelectTarget -> rename(target.type, target.key)?.let { copy(target = target.copy(key = it)) }
        is BindingTarget.Strip -> rename(target.type, target.key)?.let { copy(target = target.copy(key = it)) }
        else -> null
    }
}
