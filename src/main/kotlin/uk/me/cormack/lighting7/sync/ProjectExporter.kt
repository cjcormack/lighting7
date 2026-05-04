package uk.me.cormack.lighting7.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.fx.ParameterInfo
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
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
import uk.me.cormack.lighting7.sync.dto.FixtureGroupMemberJson
import uk.me.cormack.lighting7.sync.dto.FixturePatchJson
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.sync.dto.FxDefinitionJson
import uk.me.cormack.lighting7.sync.dto.FxPresetJson
import uk.me.cormack.lighting7.sync.dto.FxPresetPropertyAssignmentJson
import uk.me.cormack.lighting7.sync.dto.InstallsJson
import uk.me.cormack.lighting7.sync.dto.ParkedChannelJson
import uk.me.cormack.lighting7.sync.dto.ProjectJson
import uk.me.cormack.lighting7.sync.dto.RiggingJson
import uk.me.cormack.lighting7.sync.dto.ScriptMetaJson
import uk.me.cormack.lighting7.sync.dto.ShowEntryJson
import uk.me.cormack.lighting7.sync.dto.StageRegionJson
import uk.me.cormack.lighting7.sync.dto.TombstoneJson
import uk.me.cormack.lighting7.sync.dto.UniverseConfigJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Exports a project's portable graph to a folder of canonical JSON files.
 *
 * Layout (Phase 1, flatter than the eventual git-repo layout in `docs/plans/cloud-sync.md` —
 * future phases may re-nest cues under their stack folder for diff scoping):
 *
 * ```
 * /formatVersion.json
 * /project.json
 * /installs.json                  -- empty in Phase 1; Phase 2 populates
 * /showEntries/{uuid}.json
 * /cueStacks/{uuid}.json
 * /cues/{uuid}.json
 * /cuePropertyAssignments/{uuid}.json
 * /cuePresetApplications/{uuid}.json
 * /cueAdHocEffects/{uuid}.json
 * /cueTriggers/{uuid}.json
 * /fixturePatches/{uuid}.json
 * /universeConfigs/{uuid}.json     -- omits machine-local `address` field
 * /riggings/{uuid}.json
 * /stageRegions/{uuid}.json
 * /fixtureGroups/{uuid}.json       -- members embedded inline
 * /fxPresets/{uuid}.json           -- propertyAssignments embedded inline
 * /fxDefinitions/{uuid}.json
 * /cueSlots/{uuid}.json
 * /parkedChannels/{uuid}.json
 * /controlSurfaceBindings/{uuid}.json
 * /scripts/{uuid}.kts              -- raw script body for git-friendly diffs
 * /scripts/{uuid}.meta.json
 * /tombstones/{tableName}/{uuid}.json -- deletion markers; written by [SnapshotEngine]
 *                                       via [writeTombstones], not by [export], because
 *                                       the exporter doesn't know which records were
 *                                       just deleted.
 * ```
 */
class ProjectExporter(private val state: State) {

    /**
     * `liveKeys` is every record the exporter wrote as a live record (i.e. not a
     * tombstone — the snapshot engine writes those separately). Returning them lets
     * callers diff against `sync_state` without re-walking the export folder.
     */
    data class Result(val path: Path, val fileCount: Int, val liveKeys: Set<RecordKey>)

    /**
     * Export to [targetDir]. [knownInstalls] is the registry of peer installs already in
     * the cloud repo (read by [SnapshotEngine] from `installs.json` before the wipe step);
     * the local install is unioned in so the file accumulates every install that has ever
     * pushed. Manual export passes an empty map — the result is just the local install,
     * matching pre-Phase-8 behaviour.
     */
    fun export(
        projectId: Int,
        targetDir: Path,
        knownInstalls: Map<String, String> = emptyMap(),
    ): Result {
        val liveKeys = mutableSetOf<RecordKey>()
        val fileCount = transaction(state.database) {
            val project = DaoProject.findById(projectId)
                ?: throw IllegalArgumentException("Project not found: $projectId")
            val projectUuid = project.uuid

            Files.createDirectories(targetDir)

            writeJson(targetDir.resolve("formatVersion.json"), FormatVersionJson.serializer(), FormatVersionJson())
            // installs.json is the registry of installs that have written to this repo —
            // union the peer set already on disk with the local install so the file
            // accumulates rather than overwrites. Local install always wins on key clash
            // (a renamed install should propagate its new friendlyName).
            val localInstalls = DaoInstall.all()
                .associate { it.uuid.toString() to it.friendlyName }
            val installs = knownInstalls + localInstalls
            writeJson(
                targetDir.resolve("installs.json"),
                InstallsJson.serializer(),
                InstallsJson(installs = installs),
            )
            writeJson(
                targetDir.resolve("project.json"),
                ProjectJson.serializer(),
                ProjectJson(
                    uuid = projectUuid.toString(),
                    name = project.name,
                    description = project.description,
                    stageWidthM = project.stageWidthM,
                    stageDepthM = project.stageDepthM,
                    stageHeightM = project.stageHeightM,
                ),
            )
            var count = 3

            count += writeAll(targetDir, "showEntries", project.showEntries.toList(), ShowEntryJson.serializer(), { it.uuid }, liveKeys) { e ->
                ShowEntryJson(
                    uuid = e.uuid.toString(),
                    cueStackUuid = e.cueStack?.uuid?.toString(),
                    entryType = e.entryType,
                    sortOrder = e.sortOrder,
                    label = e.label,
                )
            }

            count += writeAll(targetDir, "cueStacks", project.cueStacks.toList(), CueStackJson.serializer(), { it.uuid }, liveKeys) { s ->
                CueStackJson(s.uuid.toString(), s.name, s.palette, s.loop)
            }

            count += writeAll(targetDir, "cues", project.cues.toList(), CueJson.serializer(), { it.uuid }, liveKeys) { c ->
                CueJson(
                    uuid = c.uuid.toString(),
                    cueStackUuid = c.cueStack?.uuid?.toString(),
                    name = c.name,
                    palette = c.palette,
                    updateGlobalPalette = c.updateGlobalPalette,
                    sortOrder = c.sortOrder,
                    autoAdvance = c.autoAdvance,
                    autoAdvanceDelayMs = c.autoAdvanceDelayMs,
                    fadeDurationMs = c.fadeDurationMs,
                    fadeCurve = c.fadeCurve,
                    cueNumber = c.cueNumber,
                    notes = c.notes,
                    cueType = c.cueType,
                    stomp = c.stomp,
                )
            }

            count += writeCueChildren(targetDir, project, liveKeys)

            count += writeAll(targetDir, "universeConfigs", project.universeConfigs.toList(), UniverseConfigJson.serializer(), { it.uuid }, liveKeys) { u ->
                // `address` deliberately omitted — machine-local per cloud-sync design.
                UniverseConfigJson(u.uuid.toString(), u.subnet, u.universe, u.controllerType)
            }

            count += writeAll(targetDir, "riggings", project.riggings.toList(), RiggingJson.serializer(), { it.uuid }, liveKeys) { r ->
                RiggingJson(
                    uuid = r.uuid.toString(),
                    name = r.name,
                    kind = r.kind,
                    positionX = r.positionX,
                    positionY = r.positionY,
                    positionZ = r.positionZ,
                    yawDeg = r.yawDeg,
                    pitchDeg = r.pitchDeg,
                    rollDeg = r.rollDeg,
                    lengthM = r.lengthM,
                    sortOrder = r.sortOrder,
                )
            }

            count += writeAll(targetDir, "stageRegions", project.stageRegions.toList(), StageRegionJson.serializer(), { it.uuid }, liveKeys) { s ->
                StageRegionJson(
                    uuid = s.uuid.toString(),
                    name = s.name,
                    centerX = s.centerX,
                    centerY = s.centerY,
                    centerZ = s.centerZ,
                    widthM = s.widthM,
                    depthM = s.depthM,
                    heightM = s.heightM,
                    yawDeg = s.yawDeg,
                    sortOrder = s.sortOrder,
                )
            }

            count += writeAll(targetDir, "fixturePatches", project.fixturePatches.toList(), FixturePatchJson.serializer(), { it.uuid }, liveKeys) { p ->
                FixturePatchJson(
                    uuid = p.uuid.toString(),
                    universeConfigUuid = p.universeConfig.uuid.toString(),
                    fixtureTypeKey = p.fixtureTypeKey,
                    key = p.key,
                    displayName = p.displayName,
                    startChannel = p.startChannel,
                    sortOrder = p.sortOrder,
                    stageX = p.stageX,
                    stageY = p.stageY,
                    stageZ = p.stageZ,
                    baseYawDeg = p.baseYawDeg,
                    basePitchDeg = p.basePitchDeg,
                    riggingUuid = p.rigging?.uuid?.toString(),
                    beamAngleDeg = p.beamAngleDeg,
                    gelCode = p.gelCode,
                )
            }

            count += writeAll(targetDir, "fixtureGroups", project.fixtureGroups.toList(), FixtureGroupJson.serializer(), { it.uuid }, liveKeys) { g ->
                val members = g.members
                    .sortedWith(compareBy({ it.sortOrder }, { it.uuid }))
                    .map { m ->
                        FixtureGroupMemberJson(
                            uuid = m.uuid.toString(),
                            fixturePatchUuid = m.fixturePatch.uuid.toString(),
                            sortOrder = m.sortOrder,
                            panOffset = m.panOffset,
                            tiltOffset = m.tiltOffset,
                        )
                    }
                FixtureGroupJson(g.uuid.toString(), g.name, members)
            }

            count += writeAll(targetDir, "fxPresets", project.fxPresets.toList(), FxPresetJson.serializer(), { it.uuid }, liveKeys) { p ->
                val assignments = p.propertyAssignments
                    .sortedWith(compareBy({ it.sortOrder }, { it.uuid }))
                    .map { a ->
                        FxPresetPropertyAssignmentJson(
                            uuid = a.uuid.toString(),
                            propertyName = a.propertyName,
                            value = a.value,
                            fadeDurationMs = a.fadeDurationMs,
                            sortOrder = a.sortOrder,
                            elementKey = a.elementKey,
                        )
                    }
                FxPresetJson(
                    uuid = p.uuid.toString(),
                    name = p.name,
                    fixtureType = p.fixtureType,
                    description = p.description,
                    effects = p.effects,
                    palette = p.palette,
                    propertyAssignments = assignments,
                )
            }

            count += writeAll(targetDir, "fxDefinitions", project.fxDefinitions.toList(), FxDefinitionJson.serializer(), { it.uuid }, liveKeys) { d ->
                // Encode ParameterInfo through the canonical JSON instance so nested defaults/nulls
                // follow the same omission rules as the parent document.
                val parametersJson = canonicalJson.encodeToJsonElement(
                    ListSerializer(ParameterInfo.serializer()),
                    d.parameters,
                )
                FxDefinitionJson(
                    uuid = d.uuid.toString(),
                    effectId = d.effectId,
                    name = d.name,
                    category = d.category,
                    outputType = d.outputType,
                    effectMode = d.effectMode,
                    parameters = parametersJson,
                    compatibleProperties = d.compatibleProperties,
                    script = d.script,
                    defaultStepTiming = d.defaultStepTiming,
                    timingSource = d.timingSource,
                )
            }

            count += writeAll(targetDir, "cueSlots", project.cueSlots.toList(), CueSlotJson.serializer(), { it.uuid }, liveKeys) { s ->
                CueSlotJson(
                    uuid = s.uuid.toString(),
                    page = s.page,
                    slotIndex = s.slotIndex,
                    cueUuid = s.cue?.uuid?.toString(),
                    cueStackUuid = s.cueStack?.uuid?.toString(),
                )
            }

            count += writeAll(
                targetDir, "parkedChannels", project.parkedChannels.toList(),
                ParkedChannelJson.serializer(), { it.uuid }, liveKeys,
            ) { p ->
                ParkedChannelJson(
                    uuid = p.uuid.toString(),
                    universe = p.universe,
                    channel = p.channel,
                    value = p.value,
                )
            }

            count += writeAll(
                targetDir, "controlSurfaceBindings", project.controlSurfaceBindings.toList(),
                ControlSurfaceBindingJson.serializer(), { it.uuid }, liveKeys,
            ) { b ->
                ControlSurfaceBindingJson(
                    uuid = b.uuid.toString(),
                    deviceTypeKey = b.deviceTypeKey,
                    controlId = b.controlId,
                    bank = b.bank,
                    targetType = b.targetType,
                    targetPayload = b.targetPayload,
                    takeoverPolicy = b.takeoverPolicy,
                    sortOrder = b.sortOrder,
                )
            }

            count += writeScripts(targetDir, project, liveKeys)
            count
        }
        return Result(targetDir, fileCount, liveKeys)
    }

    /**
     * Writes one canonical-JSON file per entity under `[targetDir]/[subdir]/{uuid}.json`. The
     * subdirectory is created lazily — projects with no rows in [entities] produce no folder.
     * Each written record's [RecordKey] is added to [liveKeys] so the snapshot pipeline can
     * derive deletions without re-walking the export folder.
     */
    private fun <E, J> writeAll(
        targetDir: Path,
        subdir: String,
        entities: List<E>,
        serializer: KSerializer<J>,
        uuidOf: (E) -> UUID,
        liveKeys: MutableSet<RecordKey>,
        mapper: (E) -> J,
    ): Int {
        if (entities.isEmpty()) return 0
        val sub = targetDir.resolve(subdir)
        Files.createDirectories(sub)
        entities.forEach { e ->
            val uuid = uuidOf(e)
            writeJson(sub.resolve("$uuid.json"), serializer, mapper(e))
            liveKeys.add(RecordKey(subdir, uuid))
        }
        return entities.size
    }

    private fun writeCueChildren(dir: Path, project: DaoProject, liveKeys: MutableSet<RecordKey>): Int {
        val cues = project.cues.toList()
        if (cues.isEmpty()) return 0

        val propAssignDir = dir.resolve("cuePropertyAssignments")
        val presetAppDir = dir.resolve("cuePresetApplications")
        val adHocDir = dir.resolve("cueAdHocEffects")
        val triggerDir = dir.resolve("cueTriggers")
        var count = 0

        cues.forEach { c ->
            val cueUuid = c.uuid.toString()

            c.propertyAssignments.forEach { a ->
                Files.createDirectories(propAssignDir)
                writeJson(
                    propAssignDir.resolve("${a.uuid}.json"),
                    CuePropertyAssignmentJson.serializer(),
                    CuePropertyAssignmentJson(
                        uuid = a.uuid.toString(),
                        cueUuid = cueUuid,
                        targetType = a.targetType,
                        targetKey = a.targetKey,
                        propertyName = a.propertyName,
                        value = a.value,
                        fadeDurationMs = a.fadeDurationMs,
                        sortOrder = a.sortOrder,
                        moveInDark = a.moveInDark,
                    ),
                )
                liveKeys.add(RecordKey("cuePropertyAssignments", a.uuid))
                count++
            }

            c.presetApplications.forEach { a ->
                Files.createDirectories(presetAppDir)
                writeJson(
                    presetAppDir.resolve("${a.uuid}.json"),
                    CuePresetApplicationJson.serializer(),
                    CuePresetApplicationJson(
                        uuid = a.uuid.toString(),
                        cueUuid = cueUuid,
                        presetUuid = a.preset.uuid.toString(),
                        targets = a.targets,
                        delayMs = a.delayMs,
                        intervalMs = a.intervalMs,
                        randomWindowMs = a.randomWindowMs,
                        sortOrder = a.sortOrder,
                    ),
                )
                liveKeys.add(RecordKey("cuePresetApplications", a.uuid))
                count++
            }

            c.adHocEffects.forEach { e ->
                Files.createDirectories(adHocDir)
                writeJson(
                    adHocDir.resolve("${e.uuid}.json"),
                    CueAdHocEffectJson.serializer(),
                    CueAdHocEffectJson(
                        uuid = e.uuid.toString(),
                        cueUuid = cueUuid,
                        targetType = e.targetType,
                        targetKey = e.targetKey,
                        effectType = e.effectType,
                        category = e.category,
                        propertyName = e.propertyName,
                        beatDivision = e.beatDivision,
                        blendMode = e.blendMode,
                        distribution = e.distribution,
                        phaseOffset = e.phaseOffset,
                        elementMode = e.elementMode,
                        elementFilter = e.elementFilter,
                        stepTiming = e.stepTiming,
                        parameters = e.parameters,
                        delayMs = e.delayMs,
                        intervalMs = e.intervalMs,
                        randomWindowMs = e.randomWindowMs,
                        sortOrder = e.sortOrder,
                    ),
                )
                liveKeys.add(RecordKey("cueAdHocEffects", e.uuid))
                count++
            }

            c.triggers.forEach { t ->
                Files.createDirectories(triggerDir)
                writeJson(
                    triggerDir.resolve("${t.uuid}.json"),
                    CueTriggerJson.serializer(),
                    CueTriggerJson(
                        uuid = t.uuid.toString(),
                        cueUuid = cueUuid,
                        triggerType = t.triggerType,
                        scriptUuid = t.script.uuid.toString(),
                        delayMs = t.delayMs,
                        intervalMs = t.intervalMs,
                        randomWindowMs = t.randomWindowMs,
                        sortOrder = t.sortOrder,
                    ),
                )
                liveKeys.add(RecordKey("cueTriggers", t.uuid))
                count++
            }
        }
        return count
    }

    private fun writeScripts(dir: Path, project: DaoProject, liveKeys: MutableSet<RecordKey>): Int {
        val scripts = project.scripts.toList()
        if (scripts.isEmpty()) return 0
        val sub = dir.resolve("scripts")
        Files.createDirectories(sub)
        scripts.forEach { s ->
            // Body as raw .kts so git diffs read like normal Kotlin.
            Files.writeString(sub.resolve("${s.uuid}.kts"), s.script)
            writeJson(
                sub.resolve("${s.uuid}.meta.json"),
                ScriptMetaJson.serializer(),
                ScriptMetaJson(s.uuid.toString(), s.name, s.scriptType),
            )
            liveKeys.add(RecordKey("scripts", s.uuid))
        }
        return scripts.size * 2
    }

    private fun <T> writeJson(path: Path, serializer: KSerializer<T>, value: T) {
        Files.writeString(path, canonicalEncode(serializer, value))
    }

    /**
     * Write a tombstone marker for each [keys] entry under
     * `[targetDir]/tombstones/{tableName}/{uuid}.json`. The body is intentionally minimal
     * and timestamp-free so the file's hash stays stable across re-snapshots — see
     * [TombstoneJson].
     */
    fun writeTombstones(targetDir: Path, keys: Collection<RecordKey>): Int {
        if (keys.isEmpty()) return 0
        val body = canonicalEncode(TombstoneJson.serializer(), TombstoneJson())
        val byTable = keys.groupBy { it.tableName }
        var count = 0
        for ((tableName, recordKeys) in byTable) {
            val sub = targetDir.resolve(RecordHasher.TOMBSTONES_DIR).resolve(tableName)
            Files.createDirectories(sub)
            for (key in recordKeys) {
                Files.writeString(sub.resolve("${key.uuid}.json"), body)
                count++
            }
        }
        return count
    }
}
