package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.fx.TEMPLATE_COLOUR_REF_PREFIX
import uk.me.cormack.lighting7.fx.parseTemplateColourRefUuid
import uk.me.cormack.lighting7.models.*
import java.util.UUID

/**
 * What a copy did, or would do, and every reference it could not carry: each problem with the
 * records that have it, in the order met.
 */
internal class CopyReport {
    val looks = mutableListOf<String>()
    val looksLinked = mutableListOf<String>()
    val templates = mutableListOf<String>()
    val templatesLinked = mutableListOf<String>()
    val unresolved = LinkedHashMap<String, LinkedHashSet<String>>()

    fun unresolved(what: String, by: String) {
        unresolved.getOrPut(what) { LinkedHashSet() } += by
    }
}

/**
 * **The one cue copier** (cue model plan §3.8, D9, D18): a cue, and what it names, from [source]
 * into [target]. The cue's Duplicate, `POST /cues/copy-from` and MCP's `copy_to_project` all copy
 * through it. Within one project every reference is kept as it is. Across projects:
 *
 *  - A Look or palette a layer applies, or a `tmpl:` reference in an effect parameter names, is the
 *    target's of that name, else copied with the cue (its own references mapped the same way).
 *  - A speed master maps by index, a script by name. One the target lacks is reported: a layer's or
 *    effect's is left to master 1, a cue's own tempo pair and a script hook are left out.
 *  - A venue record (a cue event's patch, a scenery row's element) matches by **uuid** first when
 *    both projects are on the same venue, whatever version each pins; otherwise, and failing that, a
 *    patch by key and fixture type and an element by name. One that does not resolve is left out.
 *  - A fixture or group target is kept whatever the target's rig holds, and reported when the rig
 *    (`RigScope.of(target)`) does not have it; the cue then reads it as missing.
 *
 * An effect's run key (stack-timeline plan C4) is fresh: one per source key for the copier's life,
 * so a copy made of several cues keeps their runs joined to each other and to nothing in the source.
 *
 * What it could not carry is in [report]. Prompt-book anchors are an entry's and are never copied.
 * Must be used inside one transaction, which a dry run rolls back.
 */
internal class CueCopier(val source: DaoProject, val target: DaoProject) {
    val report = CopyReport()
    private val crossProject = source.id != target.id
    /** How the problems name the projects: by the name an operator reads in the review. */
    private val targetName = "'${target.name}'"
    private val sourceName = "'${source.name}'"

    private val rig by lazy { RigScope.of(target) }
    private val fixtureKeys by lazy { rig.patches().map { it.key }.toSet() }
    private val groupNames by lazy { rig.groups().map { it.name }.toSet() }
    private val patchesByUuid by lazy { rig.patches().associateBy { it.uuid } }
    private val patchesByKey by lazy { rig.patches().associateBy { it.key } }
    private val elementsByUuid by lazy { rig.stageElements().associateBy { it.uuid } }
    private val elementsByName by lazy { rig.stageElements().associateBy { it.name } }
    private val scriptsByName by lazy { DaoScript.find { DaoScripts.project eq target.id }.associateBy { it.name } }
    private val sourceMasterIndex by lazy { speedMastersOf(source).associate { it.uuid to it.masterIndex } }
    private val targetMasterByIndex by lazy { speedMastersOf(target).associate { it.masterIndex to it.uuid } }
    private val targetLooks by lazy { DaoLook.find { DaoLooks.project eq target.id }.associateBy { it.name } }
    private val targetTemplates by lazy { DaoTemplate.find { DaoTemplates.project eq target.id }.associateBy { it.name } }
    private val sourceTemplatesByUuid by lazy { DaoTemplate.find { DaoTemplates.project eq source.id }.associateBy { it.uuid } }

    /** Venue records match by uuid only between two projects on one venue (D18). */
    private val sameVenue by lazy { venueUuidOf(source)?.let { it == venueUuidOf(target) } == true }

    private val lookMap = HashMap<Int, DaoLook>()
    private val templateMap = HashMap<Int, DaoTemplate>()
    private val addedElements = HashMap<String, DaoStageElement>()
    private val runKeys = HashMap<UUID, UUID>()

    /** [element], copied into the target by its caller, is what scenery naming it by name finds. */
    fun elementAdded(element: DaoStageElement) {
        addedElements[element.name] = element
    }

    /** A new cue holding everything [cue] holds, named [name], in no stack. */
    fun copyCue(cue: DaoCue, name: String, by: String = "cue '${cue.name}'"): DaoCue {
        val copy = DaoCue.new {
            this.name = name
            project = target
            fadeDuration = cue.fadeDuration
            fadeCurve = cue.fadeCurve
            notes = cue.notes
            stomp = cue.stomp
            speedMasters = cueSpeedMasters(cue.speedMasters, by)
        }
        // Both referent columns are written: a layer names exactly one of a Look and a palette.
        for (layer in cue.layers.sortedBy { it.sortOrder }) {
            // A Look layer names no targets (absolute-looks plan D1); a palette's are checked here.
            val targets = if (layer.look != null) emptyList() else layer.targets
            targets.forEach { checkTarget(it.type, it.key, by) }
            DaoCueLayer.new {
                this.cue = copy
                look = layer.look?.let(::look)
                template = layer.template?.let(::template)
                sortOrder = layer.sortOrder
                enabled = layer.enabled
                this.targets = targets
                propertyMask = layer.propertyMask
                blendMode = layer.blendMode
                amount = layer.amount
                stomp = layer.stomp
                speedMasterUuid = master(layer.speedMasterUuid, by)
                rateSpeedMasterUuid = master(layer.rateSpeedMasterUuid, by)
                delay = layer.delay
                interval = layer.interval
                randomWindow = layer.randomWindow
            }
        }
        for (assignment in cue.propertyAssignments) {
            checkTarget(assignment.targetType, assignment.targetKey, by)
            DaoCuePropertyAssignment.new {
                this.cue = copy
                targetType = assignment.targetType
                targetKey = assignment.targetKey
                propertyName = assignment.propertyName
                value = assignment.value
                fadeDuration = assignment.fadeDuration
                sortOrder = assignment.sortOrder
                moveInDark = assignment.moveInDark
            }
        }
        for (effect in cue.adHocEffects) {
            checkTarget(effect.targetType, effect.targetKey, by)
            DaoCueAdHocEffect.new {
                this.cue = copy
                targetType = effect.targetType
                targetKey = effect.targetKey
                effectType = effect.effectType
                category = effect.category
                propertyName = effect.propertyName
                beatDivision = effect.beatDivision
                blendMode = effect.blendMode
                distribution = effect.distribution
                phaseOffset = effect.phaseOffset
                elementMode = effect.elementMode
                elementFilter = effect.elementFilter
                stepTiming = effect.stepTiming
                parameters = parameters(effect.parameters, by)
                delay = effect.delay
                interval = effect.interval
                randomWindow = effect.randomWindow
                sortOrder = effect.sortOrder
                speedMasterUuid = master(effect.speedMasterUuid, by)
                rateSpeedMasterUuid = master(effect.rateSpeedMasterUuid, by)
                seconds = effect.seconds
                size = effect.size
                direction = effect.direction
                nShot = effect.nShot
                spread = effect.spread
                wings = effect.wings
                blocks = effect.blocks
                groups = effect.groups
                runUuid = effect.runUuid?.let { runKeys.getOrPut(it) { UUID.randomUUID() } }
            }
        }
        for (trigger in cue.triggers) {
            val script = script(trigger.script, by) ?: continue
            DaoCueTrigger.new {
                this.cue = copy
                triggerType = trigger.triggerType
                delay = trigger.delay
                interval = trigger.interval
                randomWindow = trigger.randomWindow
                this.script = script
                sortOrder = trigger.sortOrder
            }
        }
        for (row in cueSceneryOf(cue.id)) {
            val element = element(row.element, by) ?: continue
            DaoCueSceneryRow.new {
                this.cue = copy
                this.element = element
                stateJson = row.stateJson
                transition = row.transition
                sortOrder = row.sortOrder
            }
        }
        for (event in cueEventsOf(cue.id)) {
            val patch = patch(event.patch, event.trigger, by) ?: continue
            DaoCueEvent.new {
                this.cue = copy
                this.patch = patch
                trigger = event.trigger
                offset = event.offset
                sortOrder = event.sortOrder
            }
        }
        return copy
    }

    /** The target's Look for [look]: itself within one project, else the target's of its name, else a copy. */
    fun look(look: DaoLook): DaoLook {
        if (!crossProject) return look
        lookMap[look.id.value]?.let { return it }
        targetLooks[look.name]?.let {
            lookMap[look.id.value] = it
            report.looksLinked += look.name
            return it
        }
        return copyLook(look)
    }

    /** A new Look in the target holding everything [look] holds, under its name. */
    fun copyLook(look: DaoLook): DaoLook {
        val by = "Look '${look.name}'"
        val copy = DaoLook.new {
            project = target
            name = look.name
            notes = look.notes
        }
        lookMap[look.id.value] = copy
        report.looks += look.name
        for (row in look.rows.sortedBy { it.sortOrder }) {
            checkTarget(row.targetType, row.targetKey, by)
            DaoLookRow.new {
                this.look = copy
                targetType = row.targetType
                targetKey = row.targetKey
                propertyName = row.propertyName
                value = row.value
                fadeDuration = row.fadeDuration
                elementKey = row.elementKey
                sortOrder = row.sortOrder
            }
        }
        for (effect in look.effects.sortedBy { it.sortOrder }) {
            checkTarget(effect.targetType, effect.targetKey, by)
            DaoLookEffect.new {
                this.look = copy
                targetType = effect.targetType
                targetKey = effect.targetKey
                effectType = effect.effectType
                category = effect.category
                propertyName = effect.propertyName
                beatDivision = effect.beatDivision
                blendMode = effect.blendMode
                distribution = effect.distribution
                phaseOffset = effect.phaseOffset
                elementMode = effect.elementMode
                elementFilter = effect.elementFilter
                stepTiming = effect.stepTiming
                parameters = parameters(effect.parameters, by)
                speedMasterUuid = master(effect.speedMasterUuid, by)
                rateSpeedMasterUuid = master(effect.rateSpeedMasterUuid, by)
                seconds = effect.seconds
                size = effect.size
                direction = effect.direction
                nShot = effect.nShot
                spread = effect.spread
                wings = effect.wings
                blocks = effect.blocks
                groups = effect.groups
                sortOrder = effect.sortOrder
            }
        }
        for (row in lookSceneryOf(look.id)) {
            val element = element(row.element, by) ?: continue
            DaoLookSceneryRow.new {
                this.look = copy
                this.element = element
                stateJson = row.stateJson
                sortOrder = row.sortOrder
            }
        }
        return copy
    }

    /** The target's palette for [template]: itself within one project, else the target's of its name, else a copy. */
    fun template(template: DaoTemplate): DaoTemplate {
        if (!crossProject) return template
        templateMap[template.id.value]?.let { return it }
        targetTemplates[template.name]?.let {
            templateMap[template.id.value] = it
            report.templatesLinked += template.name
            return it
        }
        val by = "palette '${template.name}'"
        val copy = DaoTemplate.new {
            project = target
            name = template.name
            notes = template.notes
            fadeDuration = template.fadeDuration
        }
        // Mapped before its effect is copied, so a Palette Steps list naming itself ends here.
        templateMap[template.id.value] = copy
        report.templates += template.name
        for (row in template.rows.sortedBy { it.sortOrder }) {
            checkTarget(row.targetType, row.targetKey, by)
            DaoTemplateRow.new {
                this.template = copy
                targetType = row.targetType
                targetKey = row.targetKey
                propertyName = row.propertyName
                value = row.value
                sortOrder = row.sortOrder
            }
        }
        for (effect in template.effects) {
            DaoTemplateEffect.new {
                this.template = copy
                effectType = effect.effectType
                category = effect.category
                propertyName = effect.propertyName
                beatDivision = effect.beatDivision
                blendMode = effect.blendMode
                distribution = effect.distribution
                phaseOffset = effect.phaseOffset
                elementMode = effect.elementMode
                elementFilter = effect.elementFilter
                stepTiming = effect.stepTiming
                parameters = parameters(effect.parameters, by)
                speedMasterUuid = master(effect.speedMasterUuid, by)
                rateSpeedMasterUuid = master(effect.rateSpeedMasterUuid, by)
                seconds = effect.seconds
                size = effect.size
                direction = effect.direction
                nShot = effect.nShot
                spread = effect.spread
                wings = effect.wings
                blocks = effect.blocks
                groups = effect.groups
            }
        }
        return copy
    }

    /**
     * [parameters] with every `tmpl:{uuid}` mapped as a layer's palette is — a colour reference, or an
     * item of a comma-separated list (a colour list, Palette Steps' `palettes`). Anything else is kept
     * byte for byte.
     */
    fun parameters(parameters: Map<String, String>, by: String): Map<String, String> {
        if (!crossProject || parameters.values.none { TEMPLATE_COLOUR_REF_PREFIX in it.lowercase() }) return parameters
        return parameters.mapValues { (_, value) ->
            value.split(",").joinToString(",") { item ->
                val uuid = parseTemplateColourRefUuid(item) ?: return@joinToString item
                val named = sourceTemplatesByUuid[uuid]
                if (named == null) {
                    report.unresolved("palette $uuid is not in $sourceName; its reference is kept as it is", by)
                    item
                } else {
                    "$TEMPLATE_COLOUR_REF_PREFIX${template(named).uuid}"
                }
            }
        }
    }

    /** A layer's or effect's master, mapped by index; one the target lacks is left to master 1 and reported. */
    fun master(uuid: UUID?, by: String): UUID? {
        if (uuid == null || !crossProject) return uuid
        val index = sourceMasterIndex[uuid]
        val mapped = index?.let { targetMasterByIndex[it] }
        if (mapped == null) report.unresolved("speed master ${index ?: uuid} is not in $targetName; master 1 drives it", by)
        return mapped
    }

    /** A cue's own tempo pairs, each master mapped by index; one the target lacks is left out and reported. */
    fun cueSpeedMasters(list: List<CueSpeedMasterDto>, by: String): List<CueSpeedMasterDto> {
        if (!crossProject) return list
        return list.mapNotNull { pair ->
            val index = runCatching { UUID.fromString(pair.speedMasterUuid) }.getOrNull()?.let { sourceMasterIndex[it] }
            val mapped = index?.let { targetMasterByIndex[it] }
            if (mapped == null) report.unresolved("speed master ${index ?: "(deleted)"} is not in $targetName; its tempo is left out", by)
            mapped?.let { pair.copy(speedMasterUuid = it.toString()) }
        }
    }

    private fun script(script: DaoScript, by: String): DaoScript? {
        if (!crossProject) return script
        return scriptsByName[script.name]
            ?: run { report.unresolved("script '${script.name}' is not in $targetName; its trigger is left out", by); null }
    }

    /** The target's element for [element], a scenery row's: by uuid on one venue, else one this copy added, else by name. */
    fun element(element: DaoStageElement, by: String): DaoStageElement? {
        if (!crossProject) return element
        return (if (sameVenue) elementsByUuid[element.uuid] else null)
            ?: addedElements[element.name]
            ?: elementsByName[element.name]
            ?: run {
                report.unresolved("element '${element.name}' is not in the scene of $targetName; its scenery change is left out", by)
                null
            }
    }

    private fun patch(patch: DaoFixturePatch, trigger: String, by: String): DaoFixturePatch? {
        if (!crossProject) return patch
        // A venue may re-type a unit and keep its uuid, so the type is checked on both paths.
        return (if (sameVenue) patchesByUuid[patch.uuid]?.takeIf { it.fixtureTypeKey == patch.fixtureTypeKey } else null)
            ?: patchesByKey[patch.key]?.takeIf { it.fixtureTypeKey == patch.fixtureTypeKey }
            ?: run {
                report.unresolved("$targetName has no '${patch.key}' of type ${patch.fixtureTypeKey}; its $trigger event is left out", by)
                null
            }
    }

    private fun checkTarget(type: String, key: String, by: String) {
        if (!crossProject) return
        if (type == EffectTargetRef.Selection.TYPE) {
            EffectTargetRef.Selection.parseKey(key)?.forEach { checkTarget(it.discriminator, it.key, by) }
            return
        }
        val ok = when (type) {
            TargetRef.Fixture.TYPE -> fixtureKeys.any { key == it || key.startsWith("$it.") }
            TargetRef.Group.TYPE -> key in groupNames
            else -> true
        }
        if (!ok) report.unresolved("$targetName has no ${if (type == TargetRef.Group.TYPE) "group" else "fixture"} '$key'", by)
    }

    private companion object {
        /** The venue a project's rig is on: a venue's own uuid, a show's pin, else none. */
        fun venueUuidOf(project: DaoProject): UUID? = when (project.kind) {
            ProjectKind.VENUE -> project.uuid
            ProjectKind.SHOW -> project.venueUuid
            else -> null
        }
    }
}
