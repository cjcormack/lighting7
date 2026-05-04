package uk.me.cormack.lighting7.sync

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.fx.ParameterInfo
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCuePresetApplication
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueSlot
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueTrigger
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFxDefinition
import uk.me.cormack.lighting7.models.DaoFxPreset
import uk.me.cormack.lighting7.models.DaoFxPresetPropertyAssignment
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.DaoShowEntry
import uk.me.cormack.lighting7.models.DaoStageRegion
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.routes.deleteCueChildren
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.dto.ControlSurfaceBindingJson
import uk.me.cormack.lighting7.sync.dto.CueAdHocEffectJson
import uk.me.cormack.lighting7.sync.dto.CueJson
import uk.me.cormack.lighting7.sync.dto.CuePresetApplicationJson
import uk.me.cormack.lighting7.sync.dto.CuePropertyAssignmentJson
import uk.me.cormack.lighting7.sync.dto.CueSlotJson
import uk.me.cormack.lighting7.sync.dto.CueStackJson
import uk.me.cormack.lighting7.sync.dto.CueTriggerJson
import uk.me.cormack.lighting7.sync.dto.FixtureGroupJson
import uk.me.cormack.lighting7.sync.dto.FixturePatchJson
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.sync.dto.FxDefinitionJson
import uk.me.cormack.lighting7.sync.dto.FxPresetJson
import uk.me.cormack.lighting7.sync.dto.ParkedChannelJson
import uk.me.cormack.lighting7.sync.dto.ProjectJson
import uk.me.cormack.lighting7.sync.dto.RiggingJson
import uk.me.cormack.lighting7.sync.dto.ScriptMetaJson
import uk.me.cormack.lighting7.sync.dto.ShowEntryJson
import uk.me.cormack.lighting7.sync.dto.StageRegionJson
import uk.me.cormack.lighting7.sync.dto.UniverseConfigJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

internal const val SUPPORTED_FORMAT_VERSION = 3
internal const val MIN_SUPPORTED_FORMAT_VERSION = 3

/**
 * Import-time error with the HTTP status the route layer should report. Carrying the status
 * on the exception keeps the catch block in `routes/projectExport.kt` to a single arm.
 */
class ImportError(val status: HttpStatusCode, message: String) : RuntimeException(message) {
    companion object {
        fun conflict(message: String) = ImportError(HttpStatusCode.Conflict, message)
        fun unsupportedFormat(message: String) = ImportError(HttpStatusCode.UnprocessableEntity, message)
        fun invalidArchive(message: String) = ImportError(HttpStatusCode.BadRequest, message)
    }
}

class ProjectImporter(private val state: State) {

    data class Result(val projectId: Int, val projectUuid: String, val name: String)

    /**
     * Imports a project from [sourceDir]. Single transaction — any failure rolls back. Refuses
     * (Conflict) if a project with the same UUID already exists, or if the imported name
     * collides with an existing project (use [nameOverride] to disambiguate). Forces
     * `isCurrent = false` on the new project.
     */
    fun import(sourceDir: Path, nameOverride: String?): Result {
        val (_, projectJson) = loadAndValidateArchive(sourceDir)
        val targetName = nameOverride ?: projectJson.name
        val targetUuid = UUID.fromString(projectJson.uuid)

        return transaction(state.database) {
            // UUID-collision check first — a same-UUID project means we'd be merging, which
            // Phase 1 deliberately doesn't support.
            val uuidCollision = DaoProject.find { DaoProjects.uuid eq targetUuid }.firstOrNull()
            if (uuidCollision != null) {
                throw ImportError.conflict(
                    "A project with UUID $targetUuid already exists (\"${uuidCollision.name}\"). " +
                        "Phase 1 does not support merge; remove the existing project first."
                )
            }
            val nameCollision = DaoProject.find { DaoProjects.name eq targetName }.firstOrNull()
            if (nameCollision != null) {
                throw ImportError.conflict(
                    "A project named \"$targetName\" already exists. Provide a different name."
                )
            }

            // isCurrent forced false: importing must never silently switch which project the
            // operator is operating on. activeEntryId stays null — Phase 1 doesn't preserve
            // operator UI state.
            val project = DaoProject.new {
                name = targetName
                description = projectJson.description
                isCurrent = false
                activeEntryId = null
                stageWidthM = projectJson.stageWidthM
                stageDepthM = projectJson.stageDepthM
                stageHeightM = projectJson.stageHeightM
                uuid = targetUuid
            }

            populateProject(sourceDir, project)

            Result(
                projectId = project.id.value,
                projectUuid = targetUuid.toString(),
                name = targetName,
            )
        }
    }

    /**
     * Replace an existing project's data with whatever is on disk at [sourceDir]. Used by
     * the cloud-sync pull path: the working tree has just been fast-forwarded to a new
     * remote SHA, and the DB needs to match.
     *
     *  * Validates the JSON's project UUID against the existing row — refuses to clobber a
     *    different project by accident.
     *  * Cascade-deletes child rows (cues, stacks, presets, etc.) and re-imports them from
     *    JSON, preserving the existing project row's `id` so non-synced FK references
     *    (`machine_overrides`, `sync_configs`) survive.
     *  * Updates the project's name + description from JSON.
     *
     * Runs in a single transaction — partial pulls can't leave the DB inconsistent.
     */
    fun replaceFromWorkingTree(projectId: Int, sourceDir: Path): Result {
        val (_, projectJson) = loadAndValidateArchive(sourceDir)
        val incomingUuid = UUID.fromString(projectJson.uuid)

        return transaction(state.database) {
            val project = DaoProject.findById(projectId)
                ?: throw ImportError.invalidArchive("Project $projectId not found")
            if (project.uuid != incomingUuid) {
                throw ImportError.conflict(
                    "Working tree's project UUID ($incomingUuid) does not match local project ${project.id.value} (${project.uuid}). " +
                        "Refusing to clobber a different project."
                )
            }

            // Mirrors the project-delete cascade in `routes/projects.kt` (same FK-safe
            // order) but leaves the DaoProject row plus non-synced child tables (machine
            // overrides, sync configs) alone.
            project.activeEntryId = null
            project.showEntries.forEach { it.delete() }
            project.cues.forEach { cue ->
                deleteCueChildren(cue)
                cue.delete()
            }
            project.cueStacks.forEach { it.delete() }
            project.cueSlots.forEach { it.delete() }
            project.fxPresets.forEach { preset ->
                preset.propertyAssignments.forEach { it.delete() }
                preset.delete()
            }
            project.fixtureGroups.forEach { group ->
                group.members.forEach { it.delete() }
                group.delete()
            }
            project.fixturePatches.forEach { it.delete() }
            project.riggings.forEach { it.delete() }
            project.stageRegions.forEach { it.delete() }
            project.universeConfigs.forEach { it.delete() }
            project.parkedChannels.forEach { it.delete() }
            project.fxDefinitions.forEach { it.delete() }
            project.scripts.forEach { it.delete() }
            project.controlSurfaceBindings.forEach { it.delete() }

            project.name = projectJson.name
            project.description = projectJson.description
            project.stageWidthM = projectJson.stageWidthM
            project.stageDepthM = projectJson.stageDepthM
            project.stageHeightM = projectJson.stageHeightM

            populateProject(sourceDir, project)

            Result(
                projectId = project.id.value,
                projectUuid = project.uuid.toString(),
                name = project.name,
            )
        }
    }

    /**
     * Common up-front validation for both [import] and [replaceFromWorkingTree]. Reads
     * `formatVersion.json` and `project.json`, version-checks, and returns the parsed
     * pair. Done before opening a DB transaction so a malformed archive never
     * partially-mutates the DB.
     */
    private fun loadAndValidateArchive(sourceDir: Path): Pair<FormatVersionJson, ProjectJson> {
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw ImportError.invalidArchive("Folder not found: $sourceDir")
        }
        val formatPath = sourceDir.resolve("formatVersion.json")
        if (!formatPath.exists()) {
            throw ImportError.invalidArchive("Missing formatVersion.json")
        }
        val format = canonicalDecode(FormatVersionJson.serializer(), Files.readString(formatPath))
        if (format.formatVersion > SUPPORTED_FORMAT_VERSION) {
            throw ImportError.unsupportedFormat(
                "Repo format v${format.formatVersion} is newer than this install supports (v$SUPPORTED_FORMAT_VERSION). Upgrade lighting7."
            )
        }
        if (format.formatVersion < MIN_SUPPORTED_FORMAT_VERSION) {
            throw ImportError.unsupportedFormat(
                "Repo format v${format.formatVersion} is older than this install supports (v$MIN_SUPPORTED_FORMAT_VERSION). Re-export from a newer install."
            )
        }
        val projectPath = sourceDir.resolve("project.json")
        if (!projectPath.exists()) {
            throw ImportError.invalidArchive("Missing project.json")
        }
        val projectJson = canonicalDecode(ProjectJson.serializer(), Files.readString(projectPath))
        return format to projectJson
    }

    /**
     * Read every per-table directory under [sourceDir] and create the corresponding rows
     * under [project]. Shared by both the fresh-import path and the working-tree-replace
     * path; the topological order matters because parent rows must exist before children
     * dereference them through the maps returned by each step.
     */
    private fun populateProject(sourceDir: Path, project: DaoProject) {
        val scriptMap = importScripts(sourceDir, project)
        importFxDefinitions(sourceDir, project)
        val fxPresetMap = importFxPresets(sourceDir, project)
        val universeMap = importUniverseConfigs(sourceDir, project)
        val riggingMap = importRiggings(sourceDir, project)
        importStageRegions(sourceDir, project)
        val patchMap = importFixturePatches(sourceDir, project, universeMap, riggingMap)
        importFixtureGroups(sourceDir, project, patchMap)
        val cueStackMap = importCueStacks(sourceDir, project)
        val cueMap = importCues(sourceDir, project, cueStackMap)
        importCuePropertyAssignments(sourceDir, cueMap)
        importCuePresetApplications(sourceDir, cueMap, fxPresetMap)
        importCueAdHocEffects(sourceDir, cueMap)
        importCueTriggers(sourceDir, cueMap, scriptMap)
        importShowEntries(sourceDir, project, cueStackMap)
        importCueSlots(sourceDir, project, cueMap, cueStackMap)
        importParkedChannels(sourceDir, project)
        importControlSurfaceBindings(sourceDir, project)
    }

    private fun importScripts(dir: Path, project: DaoProject): Map<UUID, DaoScript> {
        val sub = dir.resolve("scripts")
        if (!sub.exists()) return emptyMap()
        val map = mutableMapOf<UUID, DaoScript>()
        Files.list(sub).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".meta.json") }.forEach { metaFile ->
                val meta = canonicalDecode(ScriptMetaJson.serializer(), Files.readString(metaFile))
                val bodyFile = sub.resolve("${meta.uuid}.kts")
                val body = if (bodyFile.exists()) Files.readString(bodyFile) else ""
                val uuid = UUID.fromString(meta.uuid)
                val dao = DaoScript.new {
                    name = meta.name
                    script = body
                    this.project = project
                    scriptType = meta.scriptType
                    this.uuid = uuid
                }
                map[uuid] = dao
            }
        }
        return map
    }

    private fun importFxDefinitions(dir: Path, project: DaoProject): Map<UUID, DaoFxDefinition> =
        readDir(dir.resolve("fxDefinitions")) { json ->
            val d = canonicalDecode(FxDefinitionJson.serializer(), json)
            val uuid = UUID.fromString(d.uuid)
            val dao = DaoFxDefinition.new {
                effectId = d.effectId
                name = d.name
                category = d.category
                outputType = d.outputType
                effectMode = d.effectMode
                parameters = d.parameters?.let {
                    canonicalJson.decodeFromJsonElement(ListSerializer(ParameterInfo.serializer()), it)
                } ?: emptyList()
                compatibleProperties = d.compatibleProperties
                script = d.script
                this.project = project
                defaultStepTiming = d.defaultStepTiming
                timingSource = d.timingSource
                this.uuid = uuid
            }
            uuid to dao
        }

    private fun importFxPresets(dir: Path, project: DaoProject): Map<UUID, DaoFxPreset> =
        readDir(dir.resolve("fxPresets")) { json ->
            val p = canonicalDecode(FxPresetJson.serializer(), json)
            val uuid = UUID.fromString(p.uuid)
            val dao = DaoFxPreset.new {
                name = p.name
                description = p.description
                this.project = project
                fixtureType = p.fixtureType
                effects = p.effects
                palette = p.palette
                this.uuid = uuid
            }
            p.propertyAssignments.forEach { a ->
                DaoFxPresetPropertyAssignment.new {
                    preset = dao
                    propertyName = a.propertyName
                    value = a.value
                    fadeDurationMs = a.fadeDurationMs
                    sortOrder = a.sortOrder
                    elementKey = a.elementKey
                    this.uuid = UUID.fromString(a.uuid)
                }
            }
            uuid to dao
        }

    private fun importUniverseConfigs(dir: Path, project: DaoProject): Map<UUID, DaoUniverseConfig> =
        readDir(dir.resolve("universeConfigs")) { json ->
            val u = canonicalDecode(UniverseConfigJson.serializer(), json)
            val uuid = UUID.fromString(u.uuid)
            val dao = DaoUniverseConfig.new {
                this.project = project
                subnet = u.subnet
                universe = u.universe
                controllerType = u.controllerType
                // address is machine-local — left null on import, set later via Phase 2 overrides.
                address = null
                this.uuid = uuid
            }
            uuid to dao
        }

    private fun importRiggings(dir: Path, project: DaoProject): Map<UUID, DaoRigging> =
        readDir(dir.resolve("riggings")) { json ->
            val r = canonicalDecode(RiggingJson.serializer(), json)
            val uuid = UUID.fromString(r.uuid)
            val dao = DaoRigging.new {
                this.project = project
                name = r.name
                kind = r.kind
                positionX = r.positionX
                positionY = r.positionY
                positionZ = r.positionZ
                yawDeg = r.yawDeg
                pitchDeg = r.pitchDeg
                rollDeg = r.rollDeg
                lengthM = r.lengthM
                sortOrder = r.sortOrder
                this.uuid = uuid
            }
            uuid to dao
        }

    private fun importStageRegions(dir: Path, project: DaoProject) {
        readDir(dir.resolve("stageRegions")) { json ->
            val s = canonicalDecode(StageRegionJson.serializer(), json)
            val uuid = UUID.fromString(s.uuid)
            DaoStageRegion.new {
                this.project = project
                name = s.name
                centerX = s.centerX
                centerY = s.centerY
                centerZ = s.centerZ
                widthM = s.widthM
                depthM = s.depthM
                heightM = s.heightM
                yawDeg = s.yawDeg
                sortOrder = s.sortOrder
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    private fun importFixturePatches(
        dir: Path,
        project: DaoProject,
        universeMap: Map<UUID, DaoUniverseConfig>,
        riggingMap: Map<UUID, DaoRigging>,
    ): Map<UUID, DaoFixturePatch> = readDir(dir.resolve("fixturePatches")) { json ->
        val p = canonicalDecode(FixturePatchJson.serializer(), json)
        val uuid = UUID.fromString(p.uuid)
        val universeUuid = UUID.fromString(p.universeConfigUuid)
        val universe = universeMap[universeUuid]
            ?: throw ImportError.invalidArchive("Fixture patch ${p.uuid} references unknown universe $universeUuid")
        val rigging = p.riggingUuid?.let {
            val riggingUuid = UUID.fromString(it)
            riggingMap[riggingUuid]
                ?: throw ImportError.invalidArchive("Fixture patch ${p.uuid} references unknown rigging $riggingUuid")
        }
        val dao = DaoFixturePatch.new {
            this.project = project
            universeConfig = universe
            this.rigging = rigging
            fixtureTypeKey = p.fixtureTypeKey
            key = p.key
            displayName = p.displayName
            startChannel = p.startChannel
            sortOrder = p.sortOrder
            stageX = p.stageX
            stageY = p.stageY
            stageZ = p.stageZ
            baseYawDeg = p.baseYawDeg
            basePitchDeg = p.basePitchDeg
            beamAngleDeg = p.beamAngleDeg
            gelCode = p.gelCode
            this.uuid = uuid
        }
        uuid to dao
    }

    private fun importFixtureGroups(
        dir: Path,
        project: DaoProject,
        patchMap: Map<UUID, DaoFixturePatch>,
    ): Map<UUID, DaoFixtureGroup> = readDir(dir.resolve("fixtureGroups")) { json ->
        val g = canonicalDecode(FixtureGroupJson.serializer(), json)
        val uuid = UUID.fromString(g.uuid)
        val dao = DaoFixtureGroup.new {
            this.project = project
            name = g.name
            this.uuid = uuid
        }
        g.members.forEach { m ->
            val patchUuid = UUID.fromString(m.fixturePatchUuid)
            val patch = patchMap[patchUuid]
                ?: throw ImportError.invalidArchive("Group member ${m.uuid} references unknown patch $patchUuid")
            DaoFixtureGroupMember.new {
                group = dao
                fixturePatch = patch
                sortOrder = m.sortOrder
                panOffset = m.panOffset
                tiltOffset = m.tiltOffset
                this.uuid = UUID.fromString(m.uuid)
            }
        }
        uuid to dao
    }

    private fun importCueStacks(dir: Path, project: DaoProject): Map<UUID, DaoCueStack> =
        readDir(dir.resolve("cueStacks")) { json ->
            val s = canonicalDecode(CueStackJson.serializer(), json)
            val uuid = UUID.fromString(s.uuid)
            val dao = DaoCueStack.new {
                name = s.name
                this.project = project
                palette = s.palette
                loop = s.loop
                this.uuid = uuid
            }
            uuid to dao
        }

    private fun importCues(
        dir: Path,
        project: DaoProject,
        cueStackMap: Map<UUID, DaoCueStack>,
    ): Map<UUID, DaoCue> = readDir(dir.resolve("cues")) { json ->
        val c = canonicalDecode(CueJson.serializer(), json)
        val uuid = UUID.fromString(c.uuid)
        val stack = c.cueStackUuid?.let {
            val stackUuid = UUID.fromString(it)
            cueStackMap[stackUuid]
                ?: throw ImportError.invalidArchive("Cue ${c.uuid} references unknown cue stack $stackUuid")
        }
        val dao = DaoCue.new {
            name = c.name
            this.project = project
            palette = c.palette
            updateGlobalPalette = c.updateGlobalPalette
            cueStack = stack
            sortOrder = c.sortOrder
            autoAdvance = c.autoAdvance
            autoAdvanceDelayMs = c.autoAdvanceDelayMs
            fadeDurationMs = c.fadeDurationMs
            fadeCurve = c.fadeCurve
            cueNumber = c.cueNumber
            notes = c.notes
            cueType = c.cueType
            stomp = c.stomp
            this.uuid = uuid
        }
        uuid to dao
    }

    private fun importCuePropertyAssignments(dir: Path, cueMap: Map<UUID, DaoCue>) {
        readDir(dir.resolve("cuePropertyAssignments")) { json ->
            val a = canonicalDecode(CuePropertyAssignmentJson.serializer(), json)
            val cueUuid = UUID.fromString(a.cueUuid)
            val cue = cueMap[cueUuid]
                ?: throw ImportError.invalidArchive("Property assignment ${a.uuid} references unknown cue $cueUuid")
            val uuid = UUID.fromString(a.uuid)
            DaoCuePropertyAssignment.new {
                this.cue = cue
                targetType = a.targetType
                targetKey = a.targetKey
                propertyName = a.propertyName
                value = a.value
                fadeDurationMs = a.fadeDurationMs
                sortOrder = a.sortOrder
                moveInDark = a.moveInDark
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    private fun importCuePresetApplications(
        dir: Path,
        cueMap: Map<UUID, DaoCue>,
        presetMap: Map<UUID, DaoFxPreset>,
    ) {
        readDir(dir.resolve("cuePresetApplications")) { json ->
            val a = canonicalDecode(CuePresetApplicationJson.serializer(), json)
            val cueUuid = UUID.fromString(a.cueUuid)
            val presetUuid = UUID.fromString(a.presetUuid)
            val cue = cueMap[cueUuid]
                ?: throw ImportError.invalidArchive("Preset application ${a.uuid} references unknown cue $cueUuid")
            val preset = presetMap[presetUuid]
                ?: throw ImportError.invalidArchive("Preset application ${a.uuid} references unknown preset $presetUuid")
            val uuid = UUID.fromString(a.uuid)
            DaoCuePresetApplication.new {
                this.cue = cue
                this.preset = preset
                targets = a.targets
                delayMs = a.delayMs
                intervalMs = a.intervalMs
                randomWindowMs = a.randomWindowMs
                sortOrder = a.sortOrder
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    private fun importCueAdHocEffects(dir: Path, cueMap: Map<UUID, DaoCue>) {
        readDir(dir.resolve("cueAdHocEffects")) { json ->
            val e = canonicalDecode(CueAdHocEffectJson.serializer(), json)
            val cueUuid = UUID.fromString(e.cueUuid)
            val cue = cueMap[cueUuid]
                ?: throw ImportError.invalidArchive("Ad-hoc effect ${e.uuid} references unknown cue $cueUuid")
            val uuid = UUID.fromString(e.uuid)
            DaoCueAdHocEffect.new {
                this.cue = cue
                targetType = e.targetType
                targetKey = e.targetKey
                effectType = e.effectType
                category = e.category
                propertyName = e.propertyName
                beatDivision = e.beatDivision
                blendMode = e.blendMode
                distribution = e.distribution
                phaseOffset = e.phaseOffset
                elementMode = e.elementMode
                elementFilter = e.elementFilter
                stepTiming = e.stepTiming
                parameters = e.parameters
                delayMs = e.delayMs
                intervalMs = e.intervalMs
                randomWindowMs = e.randomWindowMs
                sortOrder = e.sortOrder
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    private fun importCueTriggers(
        dir: Path,
        cueMap: Map<UUID, DaoCue>,
        scriptMap: Map<UUID, DaoScript>,
    ) {
        readDir(dir.resolve("cueTriggers")) { json ->
            val t = canonicalDecode(CueTriggerJson.serializer(), json)
            val cueUuid = UUID.fromString(t.cueUuid)
            val scriptUuid = UUID.fromString(t.scriptUuid)
            val cue = cueMap[cueUuid]
                ?: throw ImportError.invalidArchive("Trigger ${t.uuid} references unknown cue $cueUuid")
            val script = scriptMap[scriptUuid]
                ?: throw ImportError.invalidArchive("Trigger ${t.uuid} references unknown script $scriptUuid")
            val uuid = UUID.fromString(t.uuid)
            DaoCueTrigger.new {
                this.cue = cue
                triggerType = t.triggerType
                this.script = script
                delayMs = t.delayMs
                intervalMs = t.intervalMs
                randomWindowMs = t.randomWindowMs
                sortOrder = t.sortOrder
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    private fun importShowEntries(
        dir: Path,
        project: DaoProject,
        cueStackMap: Map<UUID, DaoCueStack>,
    ): Map<UUID, DaoShowEntry> = readDir(dir.resolve("showEntries")) { json ->
        val e = canonicalDecode(ShowEntryJson.serializer(), json)
        val uuid = UUID.fromString(e.uuid)
        val stack = e.cueStackUuid?.let {
            val stackUuid = UUID.fromString(it)
            cueStackMap[stackUuid]
                ?: throw ImportError.invalidArchive("Show entry ${e.uuid} references unknown cue stack $stackUuid")
        }
        val dao = DaoShowEntry.new {
            this.project = project
            cueStack = stack
            entryType = e.entryType
            sortOrder = e.sortOrder
            label = e.label
            this.uuid = uuid
        }
        uuid to dao
    }

    private fun importCueSlots(
        dir: Path,
        project: DaoProject,
        cueMap: Map<UUID, DaoCue>,
        cueStackMap: Map<UUID, DaoCueStack>,
    ) {
        readDir(dir.resolve("cueSlots")) { json ->
            val s = canonicalDecode(CueSlotJson.serializer(), json)
            val uuid = UUID.fromString(s.uuid)
            val cue = s.cueUuid?.let {
                val cueUuid = UUID.fromString(it)
                cueMap[cueUuid]
                    ?: throw ImportError.invalidArchive("Cue slot ${s.uuid} references unknown cue $cueUuid")
            }
            val stack = s.cueStackUuid?.let {
                val stackUuid = UUID.fromString(it)
                cueStackMap[stackUuid]
                    ?: throw ImportError.invalidArchive("Cue slot ${s.uuid} references unknown cue stack $stackUuid")
            }
            DaoCueSlot.new {
                this.project = project
                page = s.page
                slotIndex = s.slotIndex
                this.cue = cue
                this.cueStack = stack
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    private fun importParkedChannels(dir: Path, project: DaoProject) {
        readDir(dir.resolve("parkedChannels")) { json ->
            val p = canonicalDecode(ParkedChannelJson.serializer(), json)
            val uuid = UUID.fromString(p.uuid)
            DaoParkedChannel.new {
                this.project = project
                universe = p.universe
                channel = p.channel
                value = p.value
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    private fun importControlSurfaceBindings(dir: Path, project: DaoProject) {
        readDir(dir.resolve("controlSurfaceBindings")) { json ->
            val b = canonicalDecode(ControlSurfaceBindingJson.serializer(), json)
            val uuid = UUID.fromString(b.uuid)
            DaoControlSurfaceBinding.new {
                this.project = project
                deviceTypeKey = b.deviceTypeKey
                controlId = b.controlId
                bank = b.bank
                targetType = b.targetType
                targetPayload = b.targetPayload
                takeoverPolicy = b.takeoverPolicy
                sortOrder = b.sortOrder
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    /**
     * Reads every `*.json` file under [dir], applying [block] inside the existing transaction.
     * Returns a UUID-keyed map of whatever [block] produces. No-op (empty map) if [dir] doesn't
     * exist — every section is optional, so an export with zero scripts produces no `scripts/`.
     */
    private fun <T> readDir(dir: Path, block: (String) -> Pair<UUID, T>): Map<UUID, T> {
        if (!dir.exists() || !dir.isDirectory()) return emptyMap()
        val out = mutableMapOf<UUID, T>()
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") && !it.fileName.toString().endsWith(".meta.json") }
                .forEach { file ->
                    val (uuid, value) = block(Files.readString(file))
                    out[uuid] = value
                }
        }
        return out
    }
}
