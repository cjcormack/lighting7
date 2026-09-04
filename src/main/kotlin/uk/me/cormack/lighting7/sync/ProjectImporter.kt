package uk.me.cormack.lighting7.sync

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.ParameterInfo
import uk.me.cormack.lighting7.models.DaoBuskBank
import uk.me.cormack.lighting7.models.DaoBuskColumn
import uk.me.cormack.lighting7.models.DaoBuskPad
import uk.me.cormack.lighting7.models.DaoBuskPage
import uk.me.cormack.lighting7.models.BuskFlow
import uk.me.cormack.lighting7.models.buskPadKind
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookEffect
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueSlot
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueTrigger
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFxDefinition
import uk.me.cormack.lighting7.models.DaoSpeedMaster
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import uk.me.cormack.lighting7.models.DaoPromptBook
import uk.me.cormack.lighting7.models.DaoPromptBooks
import uk.me.cormack.lighting7.models.DaoPromptBookAnchor
import uk.me.cormack.lighting7.models.DaoPromptBookAnchors
import uk.me.cormack.lighting7.models.DaoPromptBookAnnotation
import uk.me.cormack.lighting7.models.DaoPromptBookAnnotations
import uk.me.cormack.lighting7.models.checkPromptBookRegion
import uk.me.cormack.lighting7.models.layerSourceShape
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.DaoStageRegion
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.routes.deleteBuskPage
import uk.me.cormack.lighting7.routes.deleteCueChildren
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.dto.ControlSurfaceBindingJson
import uk.me.cormack.lighting7.sync.dto.CueAdHocEffectJson
import uk.me.cormack.lighting7.sync.dto.CueJson
import uk.me.cormack.lighting7.sync.dto.LookJson
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplateGroup
import uk.me.cormack.lighting7.models.DaoTemplateEffect
import uk.me.cormack.lighting7.models.DaoTemplateRow
import uk.me.cormack.lighting7.sync.dto.TemplateGroupJson
import uk.me.cormack.lighting7.sync.dto.TemplateJson
import uk.me.cormack.lighting7.sync.dto.CueLayerJson
import uk.me.cormack.lighting7.sync.dto.CuePropertyAssignmentJson
import uk.me.cormack.lighting7.sync.dto.BuskPageJson
import uk.me.cormack.lighting7.sync.dto.CueSlotJson
import uk.me.cormack.lighting7.sync.dto.CueStackJson
import uk.me.cormack.lighting7.sync.dto.CueTriggerJson
import uk.me.cormack.lighting7.sync.dto.SpeedMasterJson
import uk.me.cormack.lighting7.sync.dto.PromptBookAnchorJson
import uk.me.cormack.lighting7.sync.dto.PromptBookAnnotationJson
import uk.me.cormack.lighting7.sync.dto.PromptBookJson
import uk.me.cormack.lighting7.sync.dto.FixtureGroupJson
import uk.me.cormack.lighting7.sync.dto.FixturePatchJson
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.sync.dto.FxDefinitionJson
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

// v9 added `templateGroups/` and `TemplateJson.groupUuid`. SUPPORTED moves for v6's reason (a v8
// reader would import every template ungrouped and write the groups away on its next push); MIN
// stays at 5 because the folder reads as empty when missing and the field defaults to null.
//
// v6 gave templates their own entity: a `templates/` folder, and `CueLayerJson.lookUuid` optional
// beside a new `templateUuid`. Only SUPPORTED moves — **MIN deliberately stays at 5**, because a v5
// repo still imports unchanged (no `templates/` folder reads as empty, and every v5 cue layer names a
// `lookUuid`). Moving SUPPORTED is what makes a v5 install refuse a v6 repo, where a cue layer may
// carry `templateUuid` alone and a v5 reader would take `lookUuid`'s null straight into
// `UUID.fromString` — a Java platform type, so no compile-time stop — and fail with a bare NPE.
//
// v5 collapsed FX presets and named palettes into `looks/`, and `cuePresetApplications/` into
// `cueLayers/`. MIN jumped to 5 with it: a v4 repo's `cuePresetApplications` records name a
// `presetUuid` that no longer resolves to anything, and there is no in-place upgrade — reading one
// would import a project whose cues had lost their composition entirely, which is worse than
// refusing it. Bumping both is therefore deliberate, not an oversight.
//
// **These two constants are the real gate.** `FormatVersionJson.minReader` is *written* but never
// read by the importer — `loadAndValidateArchive` compares against these — so bumping the DTO
// alone leaves the gate wide open.
//
// v4 added `promptScripts/{hash}.pdf` binary blobs to the repo; the writer emitting 4 was what
// made a pre-v4 install refuse a v4 repo (it lacked the wipe-preserve logic and would delete the
// PDFs, reverting them onto peers).
internal const val SUPPORTED_FORMAT_VERSION = 10
internal const val MIN_SUPPORTED_FORMAT_VERSION = 5

/**
 * Import-time error with the HTTP status the route layer should report. Carrying the status
 * on the exception keeps the catch block in `routes/projectExport.kt` to a single arm.
 */
class ImportError(val status: HttpStatusCode, message: String) : RuntimeException(message) {
    companion object {
        fun conflict(message: String) = ImportError(HttpStatusCode.Conflict, message)
        fun unsupportedFormat(message: String) = ImportError(HttpStatusCode.UnprocessableEntity, message)
        fun invalidArchive(message: String) = ImportError(HttpStatusCode.BadRequest, message)
        fun notFound(message: String) = ImportError(HttpStatusCode.NotFound, message)
    }
}

class ProjectImporter(private val state: State) {

    private val logger = LoggerFactory.getLogger(ProjectImporter::class.java)

    data class Result(val projectId: Int, val projectUuid: String, val name: String)

    /**
     * Imports a project from [sourceDir]. Single transaction — any failure rolls back. Refuses
     * (Conflict) if a project with the same UUID already exists, or if the imported name
     * collides with an existing project (use [nameOverride] to disambiguate). Forces
     * `isCurrent = false` on the new project.
     *
     * [descriptionOverride] replaces the archive's description when non-null; null keeps the
     * archive's. Used by [ProjectCloner] so a clone can be re-described at creation time.
     */
    fun import(sourceDir: Path, nameOverride: String?, descriptionOverride: String? = null): Result {
        val (_, projectJson) = loadAndValidateArchive(sourceDir)
        val targetName = nameOverride ?: projectJson.name
        val targetUuid = UUID.fromString(projectJson.uuid)

        val result = transaction(state.database) {
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
            // operator is operating on. activeStackId stays null — importing doesn't preserve
            // operator UI state (which stack is live).
            val project = DaoProject.new {
                name = targetName
                description = descriptionOverride ?: projectJson.description
                isCurrent = false
                activeStackId = null
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

        // Copy any script PDF(s) shipped in the export folder into the local content
        // store (byte-accurate) so the prompt book renders without a manual re-import.
        // Done AFTER the transaction and best-effort: the store is only a UI cache with a
        // missing-PDF re-import fallback, and the copy can be up to 100MB — neither a copy
        // failure nor its duration should roll back or stall the committed import. The
        // sync-pull path hydrates in the engine instead, from the real git checkout.
        runCatching { PromptScriptRepoSync.hydrateStore(state, targetUuid, sourceDir) }
            .onFailure { logger.warn("Prompt-book PDF hydrate failed for imported project {}: {}", targetUuid, it.message) }

        return result
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
            project.activeStackId = null
            // Iterate every book row for the project (not just project.promptBook) so a
            // pre-collapse project with leftover extra books is fully cleaned before its
            // cues go, avoiding orphaned anchors pointing at deleted cue rows.
            DaoPromptBook.find { DaoPromptBooks.project eq project.id }.forEach { book ->
                DaoPromptBookAnchors.deleteWhere { DaoPromptBookAnchors.promptBook eq book.id }
                DaoPromptBookAnnotations.deleteWhere { DaoPromptBookAnnotations.promptBook eq book.id }
                book.delete()
            }
            // Busk pages before the records their pads point at — a pad is a plain FK with no
            // cascade (`DaoBuskPads`), so it has to go first, and by hand: pads → banks → columns → page.
            project.buskPages.forEach { deleteBuskPage(it) }
            project.cues.forEach { cue ->
                deleteCueChildren(cue)
                cue.delete()
            }
            project.cueStacks.forEach { it.delete() }
            project.cueSlots.forEach { it.delete() }
            // Layers before cues would be redundant — deleteCueChildren already drops them — but
            // looks must go after every layer that points at one.
            project.looks.forEach { look ->
                look.rows.forEach { it.delete() }
                look.effects.forEach { it.delete() }
                look.delete()
            }
            // Templates after cues (deleteCueChildren already dropped any layer pointing at one).
            // Without this, the surviving row is untouched and `populateProject` below re-imports
            // the JSON templates on top of it, tripping `uniqueIndex(project, name)`.
            project.templates.forEach { template ->
                template.rows.forEach { it.delete() }
                template.effects.forEach { it.delete() }
                template.delete()
            }
            // Groups after templates: `templates.group_id` points at one.
            project.templateGroups.forEach { it.delete() }
            project.speedMasters.forEach { it.delete() }
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
        val lookMap = importLooks(sourceDir, project)
        val templateGroupMap = importTemplateGroups(sourceDir, project)
        val templateMap = importTemplates(sourceDir, project, templateGroupMap)
        importSpeedMasters(sourceDir, project)
        val universeMap = importUniverseConfigs(sourceDir, project)
        val riggingMap = importRiggings(sourceDir, project)
        importStageRegions(sourceDir, project)
        val patchMap = importFixturePatches(sourceDir, project, universeMap, riggingMap)
        importFixtureGroups(sourceDir, project, patchMap)
        val cueStackMap = importCueStacks(sourceDir, project)
        val cueMap = importCues(sourceDir, project, cueStackMap)
        importCuePropertyAssignments(sourceDir, cueMap)
        importCueLayers(sourceDir, cueMap, lookMap, templateMap)
        importCueAdHocEffects(sourceDir, cueMap)
        importCueTriggers(sourceDir, cueMap, scriptMap)
        importLegacyShowOrder(sourceDir, project, cueStackMap)
        val promptBook = importPromptBook(sourceDir, project)
        importPromptBookAnchors(sourceDir, promptBook, cueMap)
        importPromptBookAnnotations(sourceDir, promptBook)
        importCueSlots(sourceDir, project, cueMap, cueStackMap, lookMap)
        // Pages after every record a pad can name.
        importBuskPages(sourceDir, project, templateMap, lookMap, cueMap)
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

    /**
     * Looks, with their rows and effects. Returns a uuid → DAO map because a cue *layer* references
     * its Look through a real FK — unlike a named palette, which was only ever named by a `ref:{uuid}`
     * string inside an opaque `value` column and so needed no map.
     */
    private fun importLooks(dir: Path, project: DaoProject): Map<UUID, DaoLook> =
        readDir(dir.resolve("looks")) { json ->
            val l = canonicalDecode(LookJson.serializer(), json)
            val uuid = UUID.fromString(l.uuid)
            val dao = DaoLook.new {
                this.project = project
                name = l.name
                notes = l.notes
                sortOrder = l.sortOrder
                this.uuid = uuid
            }
            l.rows.forEach { r ->
                DaoLookRow.new {
                    look = dao
                    targetType = r.targetType
                    targetKey = r.targetKey
                    propertyName = r.propertyName
                    value = r.value
                    fadeDurationMs = r.fadeDurationMs
                    elementKey = r.elementKey
                    sortOrder = r.sortOrder
                    this.uuid = UUID.fromString(r.uuid)
                }
            }
            // No strict effect-enum check here, deliberately, unlike the authoring routes: a
            // snapshot can come from a *newer* build whose blend modes this one does not know
            // yet, and refusing the whole import over one unrecognised string is worse than the
            // warn `EffectSpecCoercion.Lenient` logs when the effect eventually spawns.
            l.effects.forEach { e ->
                DaoLookEffect.new {
                    look = dao
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
                    speedMasterUuid = e.speedMasterUuid?.let { UUID.fromString(it) }
                    rateSpeedMasterUuid = e.rateSpeedMasterUuid?.let { UUID.fromString(it) }
                    sortOrder = e.sortOrder
                    this.uuid = UUID.fromString(e.uuid)
                }
            }
            uuid to dao
        }

    /**
     * Speed masters. Returns nothing: look-effect and cue-effect rows reference a master by
     * `speedMasterUuid` string, not through a FK, so no uuid → DAO map is needed downstream.
     */
    private fun importSpeedMasters(dir: Path, project: DaoProject) {
        readDir(dir.resolve("speedMasters")) { json ->
            val m = canonicalDecode(SpeedMasterJson.serializer(), json)
            val uuid = UUID.fromString(m.uuid)
            val dao = DaoSpeedMaster.new {
                this.project = project
                masterIndex = m.masterIndex
                name = m.name
                bpm = m.bpm
                source = m.source
                notes = m.notes
                // Written verbatim like every other imported row — the write-boundary rules
                // don't run on import, and the bank degrades a malformed pair (or a ratio on
                // master 1) to "manual" rather than failing the show.
                usageCategory = m.usage
                followNum = m.followNum
                followDen = m.followDen
                followTargetUuid = m.followTargetUuid?.let { UUID.fromString(it) }
                this.uuid = uuid
            }
            uuid to dao
        }
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
            kindOverride = p.kindOverride
            stageHidden = p.stageHidden
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
                loop = s.loop
                sortOrder = s.sortOrder
                type = s.type
                label = s.label
                this.uuid = uuid
            }
            uuid to dao
        }

    private fun importCues(
        dir: Path,
        project: DaoProject,
        cueStackMap: Map<UUID, DaoCueStack>,
    ): Map<UUID, DaoCue> {
        // Every cue belongs to a stack now. Legacy archives may carry standalone cues (null
        // cueStackUuid); those land in a project "Unsorted" stack, created once on demand.
        var unsortedStack: DaoCueStack? = null
        fun unsorted(): DaoCueStack = unsortedStack ?: run {
            val stack = project.cueStacks.firstOrNull {
                it.name == "Unsorted" && it.type == CueStackType.STACK.name
            } ?: DaoCueStack.new {
                name = "Unsorted"
                this.project = project
                loop = false
                type = CueStackType.STACK.name
                sortOrder = (project.cueStacks.maxOfOrNull { it.sortOrder } ?: -1) + 1
            }
            unsortedStack = stack
            stack
        }

        return readDir(dir.resolve("cues")) { json ->
        val c = canonicalDecode(CueJson.serializer(), json)
        val uuid = UUID.fromString(c.uuid)
        val stack = c.cueStackUuid?.let {
            val stackUuid = UUID.fromString(it)
            cueStackMap[stackUuid]
                ?: throw ImportError.invalidArchive("Cue ${c.uuid} references unknown cue stack $stackUuid")
        } ?: unsorted()
        val dao = DaoCue.new {
            name = c.name
            this.project = project
            cueStack = stack
            sortOrder = c.sortOrder
            autoAdvance = c.autoAdvance
            autoAdvanceDelayMs = c.autoAdvanceDelayMs
            fadeDurationMs = c.fadeDurationMs
            fadeCurve = c.fadeCurve
            cueNumber = c.cueNumber
            cueNumberAuto = c.cueNumberAuto
            notes = c.notes
            cueType = c.cueType
            stomp = c.stomp
            pinnedToBusk = c.pinnedToBusk
            this.uuid = uuid
        }
        uuid to dao
        }
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

    /** Template groups (v9). Before templates, which reference one by uuid. */
    private fun importTemplateGroups(dir: Path, project: DaoProject): Map<UUID, DaoTemplateGroup> =
        readDir(dir.resolve("templateGroups")) { json ->
            val g = canonicalDecode(TemplateGroupJson.serializer(), json)
            val uuid = UUID.fromString(g.uuid)
            val dao = DaoTemplateGroup.new {
                this.project = project
                name = g.name
                sortOrder = g.sortOrder
                this.uuid = uuid
            }
            uuid to dao
        }

    private fun importTemplates(
        dir: Path,
        project: DaoProject,
        groupMap: Map<UUID, DaoTemplateGroup>,
    ): Map<UUID, DaoTemplate> =
        readDir(dir.resolve("templates")) { json ->
            val t = canonicalDecode(TemplateJson.serializer(), json)
            val uuid = UUID.fromString(t.uuid)
            // A dangling `groupUuid` ungroups rather than aborting the pull: a group is an
            // enrichment of the template (its place and its siblings), not its content, so a
            // template that has lost its group is still a whole template — the same lenience the
            // effect below gets, and the opposite of a cue layer, which is nothing without its
            // source. Warned, because it is still a repo inconsistency worth a line.
            val group = t.groupUuid?.let { g ->
                groupMap[UUID.fromString(g)].also { found ->
                    if (found == null) {
                        logger.warn("Template {} names group {} which the archive does not carry; importing ungrouped", t.name, g)
                    }
                }
            }
            val dao = DaoTemplate.new {
                this.project = project
                name = t.name
                notes = t.notes
                sortOrder = t.sortOrder
                fadeDurationMs = t.fadeDurationMs
                this.group = group
                this.uuid = uuid
            }
            t.rows.forEach { r ->
                DaoTemplateRow.new {
                    template = dao
                    targetType = r.targetType
                    targetKey = r.targetKey
                    propertyName = r.propertyName
                    value = r.value
                    sortOrder = r.sortOrder
                    this.uuid = UUID.fromString(r.uuid)
                }
            }
            // Written verbatim, like an imported Look effect and for the same reason: the write
            // boundary's rules don't run on import, so a snapshot from a *newer* build whose
            // category or blend mode this one does not recognise still lands rather than aborting
            // the pull. `EffectSpecCoercion.Lenient` warns if it eventually fails to spawn.
            t.effect?.let { e ->
                DaoTemplateEffect.new {
                    template = dao
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
                    speedMasterUuid = e.speedMasterUuid?.let { m -> UUID.fromString(m) }
                    rateSpeedMasterUuid = e.rateSpeedMasterUuid?.let { m -> UUID.fromString(m) }
                    this.uuid = UUID.fromString(e.uuid)
                }
            }
            uuid to dao
        }

    private fun importCueLayers(
        dir: Path,
        cueMap: Map<UUID, DaoCue>,
        lookMap: Map<UUID, DaoLook>,
        templateMap: Map<UUID, DaoTemplate>,
    ) {
        readDir(dir.resolve("cueLayers")) { json ->
            val l = canonicalDecode(CueLayerJson.serializer(), json)
            val cueUuid = UUID.fromString(l.cueUuid)
            val cue = cueMap[cueUuid]
                ?: throw ImportError.invalidArchive("Cue layer ${l.uuid} references unknown cue $cueUuid")
            // Exactly one referent, checked explicitly. `UUID.fromString` takes a Java platform
            // type, so a null slips straight through it and fails later as an NPE with no archive
            // context — the reason the two nullable fields are inspected here rather than a
            // `fromString` running on whichever one happens to be set.
            //
            // The *verdict* is [layerSourceShape], shared with the read and REST-write paths so
            // there is one rule; the *severity* deliberately isn't. Everywhere else a malformed
            // layer warns and is dropped, because a desk mid-show has nowhere to report it. Archive
            // JSON is untrusted input arriving through a call that can fail, so here it fails —
            // silently losing a layer of someone's show on a sync pull is the worse outcome.
            val lookUuidRaw = l.lookUuid
            val templateUuidRaw = l.templateUuid
            layerSourceShape(lookUuidRaw, templateUuidRaw).problem?.let { problem ->
                throw ImportError.invalidArchive(
                    "Cue layer ${l.uuid} names $problem; exactly one of lookUuid / templateUuid is required",
                )
            }
            var look: DaoLook? = null
            var template: DaoTemplate? = null
            if (lookUuidRaw != null) {
                val lookUuid = UUID.fromString(lookUuidRaw)
                look = lookMap[lookUuid]
                    ?: throw ImportError.invalidArchive("Cue layer ${l.uuid} references unknown look $lookUuid")
            } else {
                val templateUuid = UUID.fromString(templateUuidRaw)
                template = templateMap[templateUuid]
                    ?: throw ImportError.invalidArchive(
                        "Cue layer ${l.uuid} references unknown template $templateUuid",
                    )
            }
            val uuid = UUID.fromString(l.uuid)
            DaoCueLayer.new {
                this.cue = cue
                this.look = look
                this.template = template
                sortOrder = l.sortOrder
                enabled = l.enabled
                targets = l.targets
                propertyMask = l.propertyMask
                blendMode = l.blendMode
                amount = l.amount
                stomp = l.stomp
                speedMasterUuid = l.speedMasterUuid?.let { UUID.fromString(it) }
                rateSpeedMasterUuid = l.rateSpeedMasterUuid?.let { UUID.fromString(it) }
                delayMs = l.delayMs
                intervalMs = l.intervalMs
                randomWindowMs = l.randomWindowMs
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
                speedMasterUuid = e.speedMasterUuid?.let { UUID.fromString(it) }
                rateSpeedMasterUuid = e.rateSpeedMasterUuid?.let { UUID.fromString(it) }
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

    /**
     * Legacy back-compat: older archives stored show order and separators in a `showEntries/`
     * directory. The collapsed model folds those into the cue-stacks collection, so we apply each
     * STACK entry's order onto its referenced stack's `sortOrder`, and materialise each MARKER
     * entry as a SEPARATOR stack. New archives have no `showEntries/` dir, so this is a no-op.
     */
    private fun importLegacyShowOrder(
        dir: Path,
        project: DaoProject,
        cueStackMap: Map<UUID, DaoCueStack>,
    ) {
        readDir(dir.resolve("showEntries")) { json ->
            val e = canonicalDecode(ShowEntryJson.serializer(), json)
            val uuid = UUID.fromString(e.uuid)
            if (e.entryType == "MARKER") {
                DaoCueStack.new {
                    this.project = project
                    name = e.label ?: "Separator"
                    label = e.label
                    loop = false
                    type = CueStackType.SEPARATOR.name
                    sortOrder = e.sortOrder
                    this.uuid = uuid
                }
            } else {
                val stack = e.cueStackUuid?.let { cueStackMap[UUID.fromString(it)] }
                stack?.sortOrder = e.sortOrder
            }
            uuid to Unit
        }
    }

    private fun importCueSlots(
        dir: Path,
        project: DaoProject,
        cueMap: Map<UUID, DaoCue>,
        cueStackMap: Map<UUID, DaoCueStack>,
        lookMap: Map<UUID, DaoLook>,
    ) {
        readDir(dir.resolve("cueSlots")) { json ->
            val s = canonicalDecode(CueSlotJson.serializer(), json)
            val uuid = UUID.fromString(s.uuid)
            // Exactly one arm. Archive JSON is untrusted input with a diagnostic channel of its
            // own, so a slot naming none or two aborts like any other malformed record here — the
            // busk *pad* below is the lenient one, and the docblocks on both say why they differ.
            if (listOfNotNull(s.cueUuid, s.cueStackUuid, s.lookUuid).size != 1) {
                throw ImportError.invalidArchive("Cue slot ${s.uuid} must name exactly one of cueUuid, cueStackUuid or lookUuid")
            }
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
            val look = s.lookUuid?.let {
                val lookUuid = UUID.fromString(it)
                lookMap[lookUuid]
                    ?: throw ImportError.invalidArchive("Cue slot ${s.uuid} references unknown look $lookUuid")
            }
            DaoCueSlot.new {
                this.project = project
                page = s.page
                slotIndex = s.slotIndex
                this.cue = cue
                this.cueStack = stack
                this.look = look
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    /**
     * `buskPages/` (v10): one document per page, columns, banks and pads nested.
     *
     * A pad whose record the archive does not carry — or which names none or two — is **dropped
     * with a warning** rather than aborting the pull, the template-group posture: a pad is an
     * enrichment of its record (a place on a page), not content, so a page that has lost a pad is
     * still a whole page. Stored sort orders are kept as written; a gap left by a dropped pad is
     * harmless (readers sort, they do not index) and the next layout write renumbers densely.
     * Structural fields are validated only as far as the enum goes: a width or flow the desk does
     * not know is not a reason to lose the page, and the layout route refuses it on the next write.
     */
    private fun importBuskPages(
        dir: Path,
        project: DaoProject,
        templateMap: Map<UUID, DaoTemplate>,
        lookMap: Map<UUID, DaoLook>,
        cueMap: Map<UUID, DaoCue>,
    ) {
        readDir(dir.resolve("buskPages")) { json ->
            val p = canonicalDecode(BuskPageJson.serializer(), json)
            val uuid = UUID.fromString(p.uuid)
            val page = DaoBuskPage.new {
                this.project = project
                name = p.name
                sortOrder = p.sortOrder
                this.uuid = uuid
            }
            p.columns.forEach { c ->
                val column = DaoBuskColumn.new {
                    this.page = page
                    row = c.row
                    sortOrder = c.sortOrder
                    width = c.width
                    this.uuid = UUID.fromString(c.uuid)
                }
                c.banks.forEach { b ->
                    val bank = DaoBuskBank.new {
                        this.column = column
                        sortOrder = b.sortOrder
                        name = b.name
                        solo = b.solo
                        flow = BuskFlow.entries.firstOrNull { it.name == b.flow }?.name ?: BuskFlow.WRAP.name
                        this.uuid = UUID.fromString(b.uuid)
                    }
                    b.pads.forEach { d ->
                        if (buskPadKind(d.templateUuid, d.lookUuid, d.cueUuid) == null) {
                            logger.warn("Busk pad {} on page {} names no record or more than one; dropping the pad", d.uuid, p.name)
                            return@forEach
                        }
                        val template = d.templateUuid?.let { templateMap[UUID.fromString(it)] }
                        val look = d.lookUuid?.let { lookMap[UUID.fromString(it)] }
                        val cue = d.cueUuid?.let { cueMap[UUID.fromString(it)] }
                        if (template == null && look == null && cue == null) {
                            logger.warn(
                                "Busk pad {} on page {} names record {} which the archive does not carry; dropping the pad",
                                d.uuid, p.name, d.templateUuid ?: d.lookUuid ?: d.cueUuid,
                            )
                            return@forEach
                        }
                        DaoBuskPad.new {
                            this.bank = bank
                            sortOrder = d.sortOrder
                            this.template = template
                            this.look = look
                            this.cue = cue
                            this.uuid = UUID.fromString(d.uuid)
                        }
                    }
                }
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

    private fun importPromptBook(dir: Path, project: DaoProject): DaoPromptBook? {
        // Parse only inside readDir (no DB writes), so a legacy multi-book archive can't
        // insert several rows and trip the one-book-per-project unique index. A project
        // has at most one book; more than one file is an invalid archive.
        val parsed = readDir(dir.resolve("promptBooks")) { json ->
            val b = canonicalDecode(PromptBookJson.serializer(), json)
            UUID.fromString(b.uuid) to b
        }
        if (parsed.size > 1) {
            throw ImportError.invalidArchive("project has ${parsed.size} prompt books; expected at most one")
        }
        val b = parsed.values.firstOrNull() ?: return null
        return DaoPromptBook.new {
            this.project = project
            scriptHash = b.scriptHash
            scriptFileName = b.scriptFileName
            pageCount = b.pageCount
            coverPages = b.coverPages
            this.uuid = UUID.fromString(b.uuid)
        }
    }

    private fun importPromptBookAnchors(
        dir: Path,
        promptBook: DaoPromptBook?,
        cueMap: Map<UUID, DaoCue>,
    ) {
        readDir(dir.resolve("promptBookAnchors")) { json ->
            val a = canonicalDecode(PromptBookAnchorJson.serializer(), json)
            val book = promptBook
                ?: throw ImportError.invalidArchive("Prompt book anchor ${a.uuid} has no prompt book to attach to")
            val cueUuid = UUID.fromString(a.cueUuid)
            val cue = cueMap[cueUuid]
                ?: throw ImportError.invalidArchive("Prompt book anchor ${a.uuid} references unknown cue $cueUuid")
            checkPromptBookRegion(a.region, book.pageCount)?.let {
                throw ImportError.invalidArchive("Prompt book anchor ${a.uuid} has an invalid region: $it")
            }
            val uuid = UUID.fromString(a.uuid)
            val dao = DaoPromptBookAnchor.new {
                this.promptBook = book
                this.cue = cue
                region = a.region
                label = a.label
                this.uuid = uuid
            }
            uuid to dao
        }
    }

    private fun importPromptBookAnnotations(dir: Path, promptBook: DaoPromptBook?) {
        readDir(dir.resolve("promptBookAnnotations")) { json ->
            val n = canonicalDecode(PromptBookAnnotationJson.serializer(), json)
            val book = promptBook
                ?: throw ImportError.invalidArchive("Prompt book annotation ${n.uuid} has no prompt book to attach to")
            checkPromptBookRegion(n.region, book.pageCount)?.let {
                throw ImportError.invalidArchive("Prompt book annotation ${n.uuid} has an invalid region: $it")
            }
            val uuid = UUID.fromString(n.uuid)
            val dao = DaoPromptBookAnnotation.new {
                this.promptBook = book
                kind = n.kind
                region = n.region
                text = n.text
                color = n.color
                tone = n.tone
                this.uuid = uuid
            }
            uuid to dao
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
