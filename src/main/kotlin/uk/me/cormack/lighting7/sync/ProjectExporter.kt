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
import uk.me.cormack.lighting7.sync.dto.ScriptMetaJson
import uk.me.cormack.lighting7.sync.dto.ShowEntryJson
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
 * /fixtureGroups/{uuid}.json       -- members embedded inline
 * /fxPresets/{uuid}.json           -- propertyAssignments embedded inline
 * /fxDefinitions/{uuid}.json
 * /cueSlots/{uuid}.json
 * /parkedChannels/{uuid}.json
 * /controlSurfaceBindings/{uuid}.json
 * /scripts/{uuid}.kts              -- raw script body for git-friendly diffs
 * /scripts/{uuid}.meta.json
 * ```
 */
class ProjectExporter(private val state: State) {

    data class Result(val path: Path, val fileCount: Int)

    fun export(projectId: Int, targetDir: Path): Result {
        val fileCount = transaction(state.database) {
            val project = DaoProject.findById(projectId)
                ?: throw IllegalArgumentException("Project not found: $projectId")
            val projectUuid = project.uuid

            Files.createDirectories(targetDir)

            writeJson(targetDir.resolve("formatVersion.json"), FormatVersionJson.serializer(), FormatVersionJson())
            // installs.json is the registry of installs that have written to this repo. Today we
            // record only the local install; once cloud sync lands, git history merges entries
            // from peers.
            val installs = DaoInstall.all()
                .associate { it.uuid.toString() to it.friendlyName }
            writeJson(
                targetDir.resolve("installs.json"),
                InstallsJson.serializer(),
                InstallsJson(installs = installs),
            )
            writeJson(
                targetDir.resolve("project.json"),
                ProjectJson.serializer(),
                ProjectJson(projectUuid.toString(), project.name, project.description),
            )
            var count = 3

            count += writeAll(targetDir, "showEntries", project.showEntries.toList(), ShowEntryJson.serializer(), { it.uuid }) { e ->
                ShowEntryJson(
                    uuid = e.uuid.toString(),
                    cueStackUuid = e.cueStack?.uuid?.toString(),
                    entryType = e.entryType,
                    sortOrder = e.sortOrder,
                    label = e.label,
                )
            }

            count += writeAll(targetDir, "cueStacks", project.cueStacks.toList(), CueStackJson.serializer(), { it.uuid }) { s ->
                CueStackJson(s.uuid.toString(), s.name, s.palette, s.loop)
            }

            count += writeAll(targetDir, "cues", project.cues.toList(), CueJson.serializer(), { it.uuid }) { c ->
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

            count += writeCueChildren(targetDir, project)

            count += writeAll(targetDir, "universeConfigs", project.universeConfigs.toList(), UniverseConfigJson.serializer(), { it.uuid }) { u ->
                // `address` deliberately omitted — machine-local per cloud-sync design.
                UniverseConfigJson(u.uuid.toString(), u.subnet, u.universe, u.controllerType)
            }

            count += writeAll(targetDir, "fixturePatches", project.fixturePatches.toList(), FixturePatchJson.serializer(), { it.uuid }) { p ->
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
                    riggingPosition = p.riggingPosition,
                    beamAngleDeg = p.beamAngleDeg,
                    gelCode = p.gelCode,
                )
            }

            count += writeAll(targetDir, "fixtureGroups", project.fixtureGroups.toList(), FixtureGroupJson.serializer(), { it.uuid }) { g ->
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

            count += writeAll(targetDir, "fxPresets", project.fxPresets.toList(), FxPresetJson.serializer(), { it.uuid }) { p ->
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

            count += writeAll(targetDir, "fxDefinitions", project.fxDefinitions.toList(), FxDefinitionJson.serializer(), { it.uuid }) { d ->
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

            count += writeAll(targetDir, "cueSlots", project.cueSlots.toList(), CueSlotJson.serializer(), { it.uuid }) { s ->
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
                ParkedChannelJson.serializer(), { it.uuid },
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
                ControlSurfaceBindingJson.serializer(), { it.uuid },
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

            count += writeScripts(targetDir, project)
            count
        }
        return Result(targetDir, fileCount)
    }

    /**
     * Writes one canonical-JSON file per entity under `[targetDir]/[subdir]/{uuid}.json`. The
     * subdirectory is created lazily — projects with no rows in [entities] produce no folder.
     */
    private fun <E, J> writeAll(
        targetDir: Path,
        subdir: String,
        entities: List<E>,
        serializer: KSerializer<J>,
        uuidOf: (E) -> UUID,
        mapper: (E) -> J,
    ): Int {
        if (entities.isEmpty()) return 0
        val sub = targetDir.resolve(subdir)
        Files.createDirectories(sub)
        entities.forEach { e ->
            writeJson(sub.resolve("${uuidOf(e)}.json"), serializer, mapper(e))
        }
        return entities.size
    }

    private fun writeCueChildren(dir: Path, project: DaoProject): Int {
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
                count++
            }
        }
        return count
    }

    private fun writeScripts(dir: Path, project: DaoProject): Int {
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
        }
        return scripts.size * 2
    }

    private fun <T> writeJson(path: Path, serializer: KSerializer<T>, value: T) {
        Files.writeString(path, canonicalEncode(serializer, value))
    }
}
