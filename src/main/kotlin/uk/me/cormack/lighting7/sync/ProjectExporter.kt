package uk.me.cormack.lighting7.sync

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.locate.storedLocate
import uk.me.cormack.lighting7.fixture.media.fittedMedia
import uk.me.cormack.lighting7.fixture.lantern.LanternFocus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.dao.IntEntity
import uk.me.cormack.lighting7.fx.ParameterInfo
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.CueMoveInDarkSettings
import uk.me.cormack.lighting7.models.MoveInDarkSettings
import uk.me.cormack.lighting7.models.moveInDarkSettings
import uk.me.cormack.lighting7.models.DaoCueSceneryRow
import uk.me.cormack.lighting7.models.DaoCueStackSceneryRow
import uk.me.cormack.lighting7.models.DaoLookSceneryRow
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoShowColour
import uk.me.cormack.lighting7.models.DaoShowColours
import uk.me.cormack.lighting7.models.DaoShowGroupOrder
import uk.me.cormack.lighting7.models.DaoShowGroupOrders
import uk.me.cormack.lighting7.models.ShowGroupOrders
import uk.me.cormack.lighting7.models.DaoShowHide
import uk.me.cormack.lighting7.models.DaoShowParkRelease
import uk.me.cormack.lighting7.models.DaoShowParkReleases
import uk.me.cormack.lighting7.models.DaoShowHides
import uk.me.cormack.lighting7.models.DaoShowOverride
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.decodeFacet
import uk.me.cormack.lighting7.models.facetElement
import uk.me.cormack.lighting7.models.detachedFrom
import uk.me.cormack.lighting7.models.isVenueBound
import uk.me.cormack.lighting7.sync.dto.ShowColourJson
import uk.me.cormack.lighting7.sync.dto.ShowGroupOrderJson
import uk.me.cormack.lighting7.sync.dto.ShowHideJson
import uk.me.cormack.lighting7.sync.dto.ShowParkReleaseJson
import uk.me.cormack.lighting7.sync.dto.ShowOverrideJson
import uk.me.cormack.lighting7.venue.VenuePins
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.models.cueSceneryOf
import uk.me.cormack.lighting7.models.cueEventsOf
import uk.me.cormack.lighting7.models.DaoCueEvent
import uk.me.cormack.lighting7.models.lookSceneryOf
import uk.me.cormack.lighting7.models.stackSceneryOf
import uk.me.cormack.lighting7.models.extraPlacementsOf
import uk.me.cormack.lighting7.models.storedParamsObject
import uk.me.cormack.lighting7.models.warnMalformedBuskPad
import uk.me.cormack.lighting7.models.warnMalformedBuskRigTile
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.dto.ControlSurfaceBindingJson
import uk.me.cormack.lighting7.sync.dto.CueAdHocFxJson
import uk.me.cormack.lighting7.sync.dto.CueJson
import uk.me.cormack.lighting7.sync.dto.CueStackEntryJson
import uk.me.cormack.lighting7.sync.dto.LookFxJson
import uk.me.cormack.lighting7.sync.dto.SceneryChangeJson
import uk.me.cormack.lighting7.sync.dto.CueEventJson
import uk.me.cormack.lighting7.sync.dto.CueSpeedMasterJson
import uk.me.cormack.lighting7.sync.dto.LookJson
import uk.me.cormack.lighting7.sync.dto.LookRowJson
import uk.me.cormack.lighting7.sync.dto.PaletteJson
import uk.me.cormack.lighting7.sync.dto.PaletteFxJson
import uk.me.cormack.lighting7.sync.dto.PaletteRowJson
import uk.me.cormack.lighting7.sync.dto.CueLayerJson
import uk.me.cormack.lighting7.sync.dto.CueAttributeAssignmentJson
import uk.me.cormack.lighting7.sync.dto.BuskBankJson
import uk.me.cormack.lighting7.sync.dto.BuskColumnJson
import uk.me.cormack.lighting7.sync.dto.BuskPadJson
import uk.me.cormack.lighting7.sync.dto.BuskPageJson
import uk.me.cormack.lighting7.sync.dto.BuskRigRowJson
import uk.me.cormack.lighting7.sync.dto.BuskRigTileJson
import uk.me.cormack.lighting7.sync.dto.CueSlotJson
import uk.me.cormack.lighting7.sync.dto.CueStackJson
import uk.me.cormack.lighting7.sync.dto.CueTriggerJson
import uk.me.cormack.lighting7.sync.dto.FixtureGroupJson
import uk.me.cormack.lighting7.sync.dto.FixtureGroupMemberJson
import uk.me.cormack.lighting7.sync.dto.FixturePatchJson
import uk.me.cormack.lighting7.sync.dto.PatchPlacementJson
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.sync.dto.FxDefinitionJson
import uk.me.cormack.lighting7.sync.dto.InstallsJson
import uk.me.cormack.lighting7.sync.dto.ParkedChannelJson
import uk.me.cormack.lighting7.sync.dto.ProjectJson
import uk.me.cormack.lighting7.sync.dto.PromptBookAnchorJson
import uk.me.cormack.lighting7.sync.dto.PromptBookAnnotationJson
import uk.me.cormack.lighting7.sync.dto.PromptBookJson
import uk.me.cormack.lighting7.sync.dto.SpeedMasterJson
import uk.me.cormack.lighting7.sync.dto.RiggingJson
import uk.me.cormack.lighting7.sync.dto.ScriptMetaJson
import uk.me.cormack.lighting7.sync.dto.StageElementJson
import uk.me.cormack.lighting7.sync.dto.StageRegionJson
import uk.me.cormack.lighting7.sync.dto.StageViewpointJson
import uk.me.cormack.lighting7.sync.dto.TombstoneJson
import uk.me.cormack.lighting7.sync.dto.UniverseConfigJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import uk.me.cormack.lighting7.models.CueStackEntryKind
import uk.me.cormack.lighting7.models.entriesOf
import uk.me.cormack.lighting7.routes.heldAutoNumbers

/**
 * Exports a project's portable graph as canonical JSON: to a folder of files ([export]), or any of
 * its record folders to a [RecordSink] ([exportKinds]) — a [MapSink] for a reader that wants the
 * records in memory. Each folder is one [RecordKind] in [KINDS].
 *
 * Layout (Phase 1, flatter than the eventual git-repo layout in `docs/plans/cloud-sync.md` —
 * future phases may re-nest cues under their stack folder for diff scoping):
 *
 * ```
 * /formatVersion.json
 * /project.json                  -- v24: `kind`, and a show's pin (`venueUuid`, `venueVersion`, `venueRepo`);
 *                                    a standalone show's origin (`detachedFromVenueUuid`, `detachedFromVersion`)
 *                                    and `inProduction`, omitted while true
 * /installs.json                  -- empty in Phase 1; Phase 2 populates
 * /showHides/{uuid}.json           -- v24: a show's hides of its venue's records
 * /showOverrides/{uuid}.json       -- v24 (session 5): a show's facet overrides of venue patches
 * /showColours/{uuid}.json         -- v36: a show's recolours of venue patches and groups
 * /showGroupOrders/{uuid}.json     -- v44: a show's orders of venue groups' members
 * /showParkReleases/{uuid}.json    -- v27: a show's releases of its venue's parks
 * /cueStacks/{uuid}.json           -- includes SEPARATOR rows and show order (sortOrder)
 * /cues/{uuid}.json               -- v32: what a cue is; no stack, position, number or follow
 * /cueStackEntries/{uuid}.json    -- v32: one place in a stack's list (a cue's, or a marker)
 * /cueAttributeAssignments/{uuid}.json  -- v38: was cuePropertyAssignments/
 * /cueLayers/{uuid}.json
 * /cueAdHocFx/{uuid}.json        -- v38: was cueAdHocEffects/
 * /cueTriggers/{uuid}.json
 * /fixturePatches/{uuid}.json
 * /universeConfigs/{uuid}.json     -- omits machine-local `address` field
 * /riggings/{uuid}.json
 * /stageRegions/{uuid}.json
 * /stageElements/{uuid}.json
 * /stageViewpoints/{uuid}.json
 * /fixtureGroups/{uuid}.json       -- members embedded inline
 * /looks/{uuid}.json               -- rows and FX (`fx`) embedded inline
 * /palettes/{uuid}.json            -- v38: was templates/; rows, or one FX (`fx`), embedded inline
 * /speedMasters/{uuid}.json
 * /fxDefinitions/{uuid}.json
 * /cueSlots/{uuid}.json           -- a cue, or (v10+) a Look
 * /buskPages/{uuid}.json           -- v10+: columns, banks and pads embedded inline
 * /buskRig/{uuid}.json             -- v12+: one rig row per document, tiles embedded inline
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
        /**
         * Copy the referenced prompt-book PDFs and scene images into the folder too. Only a reader
         * of the records alone passes false, which has no use for up to a hundred megabytes of bytes
         * it never looks at.
         */
        blobs: Boolean = true,
    ): Result {
        val sink = DirSink(targetDir)
        var reconcileProjectUuid: UUID? = null
        var referencedScriptHashes: Set<String> = emptySet()
        var referencedImageHashes: Set<String> = emptySet()
        transaction(state.database) {
            val project = DaoProject.findById(projectId)
                ?: throw IllegalArgumentException("Project not found: $projectId")
            reconcileProjectUuid = project.uuid

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

            KINDS.forEach { it.write(project, sink) }

            // Capture the referenced script-PDF hashes; the actual (up-to-100MB) copy
            // happens AFTER the transaction commits so it never holds the DB write lock.
            referencedScriptHashes = listOfNotNull(project.promptBook).map { it.scriptHash }.toSet()
            // Likewise the images the scene's cloths are painted with (v23).
            referencedImageHashes = SceneImageRepoSync.referencedHashes(projectId)
        }
        // Copy the referenced script PDF(s) into `promptScripts/{hash}.pdf` (raw bytes)
        // and remove orphaned ones — outside the transaction so the copy never blocks the
        // DB writer. PDFs are not records; they ride the repo as content-addressed blobs.
        reconcileProjectUuid?.takeIf { blobs }?.let {
            PromptScriptRepoSync.reconcileTree(state, it, referencedScriptHashes, targetDir)
            // And the referenced scene images into `sceneImages/{hash}.{png|jpg}`, the same way.
            SceneImageRepoSync.reconcileTree(state, it, referencedImageHashes, targetDir)
        }
        // formatVersion.json and installs.json, which are not records.
        return Result(targetDir, sink.fileCount + 2, sink.liveKeys)
    }

    /**
     * Write [projectId]'s records of [folders] — each a [RecordKind.folder] — to [sink], in one
     * transaction (the caller's, when there is one). [export] is every folder into a [DirSink].
     */
    fun exportKinds(projectId: Int, folders: Set<String>, sink: RecordSink) {
        val unknown = folders - FOLDERS
        require(unknown.isEmpty()) { "No record kind for folder(s) $unknown" }
        transaction(state.database) {
            val project = DaoProject.findById(projectId)
                ?: throw IllegalArgumentException("Project not found: $projectId")
            KINDS.filter { it.folder in folders }.forEach { it.write(project, sink) }
        }
    }

    /** Every record of [projectId], in memory. */
    fun exportRecords(projectId: Int): MapSink = MapSink().also { exportKinds(projectId, FOLDERS, it) }

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

    /**
     * One record folder of the export: [folder], and how [write] puts a project's records of it to a
     * sink. [tables] are the tables its records are made of, from [SyncDispositions].
     */
    class RecordKind(val folder: String, val write: (DaoProject, RecordSink) -> Unit) {
        val tables: Set<Table> get() = SyncDispositions.tablesOf(folder)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ProjectExporter::class.java)

        /** A kind writing one record per entity [entities] lists, each through [mapper]. */
        private fun <E : IntEntity, J> kind(
            folder: String,
            serializer: KSerializer<J>,
            entities: (DaoProject) -> List<E>,
            uuidOf: (E) -> UUID,
            mapper: (E) -> J,
        ) = RecordKind(folder) { project, sink ->
            entities(project).forEach { e -> sink.record(folder, uuidOf(e), e.id.value, serializer, mapper(e)) }
        }

        /** A kind writing each cue's children [children] lists, each through [mapper] with its cue's uuid. */
        private fun <E : IntEntity, J> cueChildKind(
            folder: String,
            serializer: KSerializer<J>,
            children: (DaoCue) -> Iterable<E>,
            uuidOf: (E) -> UUID,
            mapper: (E, String) -> J,
        ) = RecordKind(folder) { project, sink ->
            project.cues.forEach { c ->
                val cueUuid = c.uuid.toString()
                children(c).forEach { e -> sink.record(folder, uuidOf(e), e.id.value, serializer, mapper(e, cueUuid)) }
            }
        }

        /** Every record folder, in the order [export] writes them. */
        val KINDS: List<RecordKind> = listOf(
            RecordKind(SyncDispositions.PROJECT_FOLDER) { project, sink ->
                val origin = project.detachedFrom()
                sink.record(
                    SyncDispositions.PROJECT_FOLDER, project.uuid, project.id.value, ProjectJson.serializer(),
                    ProjectJson(
                        uuid = project.uuid.toString(),
                        name = project.name,
                        description = project.description,
                        stageWidthM = project.stageWidthM,
                        stageDepthM = project.stageDepthM,
                        stageHeightM = project.stageHeightM,
                        // v24: what the project is, and a show's pin with the hint naming where its venue
                        // syncs. The hint the show arrived with wins, so every desk that imported it writes
                        // the same bytes whatever it has attached; a show made here, with none, names the
                        // venue's own attached repo when this desk has one.
                        kind = project.kind,
                        venueUuid = project.venueUuid?.takeIf { project.isVenueBound }?.toString(),
                        venueVersion = project.venueVersion?.takeIf { project.isVenueBound },
                        venueRepo = project.venueUuid?.takeIf { project.isVenueBound }?.let { venueUuid ->
                            project.venueRepo ?: VenuePins.venueOf(venueUuid)?.let { VenuePins.repoHintOf(it.id.value) }
                        },
                        // A standalone show's origin (venue-attach plan D1), both or neither.
                        detachedFromVenueUuid = origin?.first?.toString(),
                        detachedFromVersion = origin?.second,
                        inProduction = project.inProduction,
                    ),
                )
            },

            // v24: a show's hides of its venue's records. Only the show's own rows travel in its
            // archive (every table below reads the project's own rows, never `RigScope`'s effective
            // rig); the hides are what it subtracts from the venue's.
            kind(
                "showHides", ShowHideJson.serializer(),
                { project -> DaoShowHide.find { DaoShowHides.project eq project.id }.toList() }, { it.uuid },
            ) { h ->
                ShowHideJson(uuid = h.uuid.toString(), recordKind = h.recordKind, recordUuid = h.recordUuid.toString())
            },

            // v24, session 5: a show's facet overrides of venue patches. The value and base travel as the
            // facet's fields; a row whose stored text no longer parses is left out rather than written
            // as something another desk would refuse.
            kind(
                "showOverrides", ShowOverrideJson.serializer(),
                { project ->
                    DaoShowOverride.find { DaoShowOverrides.project eq project.id }.toList()
                        .filter { o ->
                            (decodeFacet(o.facet, o.value) != null && decodeFacet(o.facet, o.base) != null).also { ok ->
                                if (!ok) logger.warn("Export: override {} ({} of {}) does not parse as its facet; leaving it out", o.uuid, o.facet, o.recordUuid)
                            }
                        }
                },
                { it.uuid },
            ) { o ->
                ShowOverrideJson(
                    uuid = o.uuid.toString(),
                    recordUuid = o.recordUuid.toString(),
                    facet = o.facet,
                    value = facetElement(decodeFacet(o.facet, o.value)!!),
                    base = facetElement(decodeFacet(o.facet, o.base)!!),
                )
            },

            // v36: a show's recolours of venue patches and groups, as stored.
            kind(
                "showColours", ShowColourJson.serializer(),
                { project -> DaoShowColour.find { DaoShowColours.project eq project.id }.toList() }, { it.uuid },
            ) { c ->
                ShowColourJson(uuid = c.uuid.toString(), recordUuid = c.recordUuid.toString(), colour = c.colour)
            },

            // v44: a show's orders of venue groups, as stored.
            kind(
                "showGroupOrders", ShowGroupOrderJson.serializer(),
                { project -> DaoShowGroupOrder.find { DaoShowGroupOrders.project eq project.id }.toList() }, { it.uuid },
            ) { o ->
                ShowGroupOrderJson(
                    uuid = o.uuid.toString(),
                    groupUuid = o.groupUuid.toString(),
                    members = ShowGroupOrders.decode(o.members).map { it.toString() },
                )
            },

            kind("cueStacks", CueStackJson.serializer(), { project -> project.cueStacks.toList() }, { it.uuid }) { s ->
                CueStackJson(
                    uuid = s.uuid.toString(),
                    name = s.name,
                    loop = s.loop,
                    sortOrder = s.sortOrder,
                    type = s.type,
                    label = s.label,
                    scenery = stackSceneryOf(s.id).map { it.toJson() },
                ).withMoveInDark(s.moveInDarkSettings)
            },

            // v32: a cue is what a state is, never where it sits (cue model plan D2, §3.9).
            kind("cues", CueJson.serializer(), { project -> project.cues.toList() }, { it.uuid }) { c ->
                CueJson(
                    uuid = c.uuid.toString(),
                    name = c.name,
                    fadeDurationMs = c.fadeDuration?.toMillis(),
                    fadeCurve = c.fadeCurve,
                    notes = c.notes,
                    stomp = c.stomp,
                    scenery = cueSceneryOf(c.id).map { it.toJson() },
                    events = cueEventsOf(c.id).map { it.toJson() },
                    speedMasters = c.speedMasters.map { CueSpeedMasterJson(it.speedMasterUuid, it.bpm) },
                ).withMoveInDark(c.moveInDarkSettings)
            },

            // v32: each place in a stack's list is its own record, written from the entry itself, so a
            // shared or loose cue travels as it is. The
            // number when explicit, and an auto one only where a reader deriving from position could
            // not give it (P5, as amended 2026-10-16: `heldAutoNumbers`).
            RecordKind("cueStackEntries") { project, sink ->
                val heldNumbers = project.cueStacks.flatMap { heldAutoNumbers(it).entries }.associate { it.key to it.value }
                project.cueStacks.flatMap { entriesOf(it) }.forEach { e ->
                    val json = CueStackEntryJson(
                        uuid = e.uuid.toString(),
                        cueStackUuid = e.stack.uuid.toString(),
                        cueUuid = e.cue?.uuid?.toString(),
                        kind = CueStackEntryKind.MARKER.name.takeIf { e.isMarker },
                        label = e.label.takeIf { e.isMarker },
                        ordinal = e.ordinal,
                        cueNumber = when {
                            e.isMarker -> null
                            !e.cueNumberAuto && !e.cueNumber.isNullOrEmpty() -> e.cueNumber
                            else -> heldNumbers[e.id.value]
                        },
                        autoAdvance = e.autoAdvance && !e.isMarker,
                        autoAdvanceDelayMs = e.autoAdvanceDelay?.toMillis()?.takeIf { !e.isMarker },
                    )
                    sink.record("cueStackEntries", e.uuid, e.id.value, CueStackEntryJson.serializer(), json)
                }
            },

            // A cue's children, each its own folder.
            cueChildKind(
                "cueAttributeAssignments", CueAttributeAssignmentJson.serializer(),
                { it.attributeAssignments }, { it.uuid },
            ) { a, cueUuid ->
                CueAttributeAssignmentJson(
                    uuid = a.uuid.toString(),
                    cueUuid = cueUuid,
                    targetType = a.targetType,
                    targetKey = a.targetKey,
                    attribute = a.attribute,
                    value = a.value,
                    fadeDurationMs = a.fadeDuration?.toMillis(),
                    sortOrder = a.sortOrder,
                )
            },

            cueChildKind("cueLayers", CueLayerJson.serializer(), { it.layers }, { it.uuid }) { l, cueUuid ->
                CueLayerJson(
                    uuid = l.uuid.toString(),
                    cueUuid = cueUuid,
                    lookUuid = l.look?.uuid?.toString(),
                    paletteUuid = l.palette?.uuid?.toString(),
                    sortOrder = l.sortOrder,
                    enabled = l.enabled,
                    targets = l.targets,
                    attributeMask = l.attributeMask,
                    blendMode = l.blendMode,
                    amount = l.amount,
                    stomp = l.stomp,
                    speedMasterUuid = l.speedMasterUuid?.toString(),
                    rateSpeedMasterUuid = l.rateSpeedMasterUuid?.toString(),
                    delayMs = l.delay?.toMillis(),
                    intervalMs = l.interval?.toMillis(),
                    randomWindowMs = l.randomWindow?.toMillis(),
                )
            },

            cueChildKind("cueAdHocFx", CueAdHocFxJson.serializer(), { it.adHocFx }, { it.uuid }) { e, cueUuid ->
                CueAdHocFxJson(
                    uuid = e.uuid.toString(),
                    cueUuid = cueUuid,
                    targetType = e.targetType,
                    targetKey = e.targetKey,
                    fxType = e.fxType,
                    family = e.family,
                    attribute = e.attribute,
                    cycle = e.cycle,
                    blendMode = e.blendMode,
                    order = e.order,
                    phaseOffset = e.phaseOffset,
                    elementMode = e.elementMode,
                    elementFilter = e.elementFilter,
                    timing = e.timing,
                    parameters = e.parameters,
                    delayMs = e.delay?.toMillis(),
                    intervalMs = e.interval?.toMillis(),
                    randomWindowMs = e.randomWindow?.toMillis(),
                    sortOrder = e.sortOrder,
                    speedMasterUuid = e.speedMasterUuid?.toString(),
                    rateSpeedMasterUuid = e.rateSpeedMasterUuid?.toString(),
                    seconds = e.seconds,
                    amount = e.amount,
                    direction = e.direction,
                    runs = e.runs,
                    spread = e.spread,
                    wings = e.wings,
                    segments = e.segments,
                    parts = e.parts,
                    spreadUnit = e.spreadUnit,
                    runUuid = e.runUuid?.toString(),
                )
            },

            cueChildKind("cueTriggers", CueTriggerJson.serializer(), { it.triggers }, { it.uuid }) { t, cueUuid ->
                CueTriggerJson(
                    uuid = t.uuid.toString(),
                    cueUuid = cueUuid,
                    triggerType = t.triggerType,
                    scriptUuid = t.script.uuid.toString(),
                    delayMs = t.delay?.toMillis(),
                    intervalMs = t.interval?.toMillis(),
                    randomWindowMs = t.randomWindow?.toMillis(),
                    sortOrder = t.sortOrder,
                )
            },

            // A project has at most one prompt book.
            kind("promptBooks", PromptBookJson.serializer(), { project -> listOfNotNull(project.promptBook) }, { it.uuid }) { b ->
                PromptBookJson(
                    uuid = b.uuid.toString(),
                    scriptHash = b.scriptHash,
                    scriptFileName = b.scriptFileName,
                    pageCount = b.pageCount,
                    coverPages = b.coverPages,
                )
            },
            kind(
                "promptBookAnchors", PromptBookAnchorJson.serializer(),
                { project -> project.promptBook?.anchors?.toList().orEmpty() }, { it.uuid },
            ) { a ->
                PromptBookAnchorJson(
                    uuid = a.uuid.toString(),
                    // v32: the entry it marks (cue model plan D4).
                    entryUuid = a.entry.uuid.toString(),
                    region = a.region,
                    label = a.label,
                )
            },
            kind(
                "promptBookAnnotations", PromptBookAnnotationJson.serializer(),
                { project -> project.promptBook?.annotations?.toList().orEmpty() }, { it.uuid },
            ) { n ->
                PromptBookAnnotationJson(
                    uuid = n.uuid.toString(),
                    kind = n.kind,
                    region = n.region,
                    text = n.text,
                    color = n.color,
                    tone = n.tone,
                )
            },

            kind("universeConfigs", UniverseConfigJson.serializer(), { project -> project.universeConfigs.toList() }, { it.uuid }) { u ->
                // `address` deliberately omitted — machine-local per cloud-sync design.
                UniverseConfigJson(u.uuid.toString(), u.subnet, u.universe, u.controllerType)
            },

            kind("riggings", RiggingJson.serializer(), { project -> project.riggings.toList() }, { it.uuid }) { r ->
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
            },

            kind("stageRegions", StageRegionJson.serializer(), { project -> project.stageRegions.toList() }, { it.uuid }) { s ->
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
            },

            // v18: the scene document. Params travel as a nested object, so the canonical encoder
            // sorts them and the clone remapper reaches a platform's `regionUuid`.
            kind("stageElements", StageElementJson.serializer(), { project -> project.stageElements.toList() }, { it.uuid }) { e ->
                StageElementJson(
                    uuid = e.uuid.toString(),
                    name = e.name,
                    kind = e.kind,
                    layer = e.layer,
                    positionX = e.positionX,
                    positionY = e.positionY,
                    positionZ = e.positionZ,
                    yawDeg = e.yawDeg,
                    widthM = e.widthM,
                    depthM = e.depthM,
                    heightM = e.heightM,
                    finishColour = e.finishColour,
                    finishPattern = e.finishPattern,
                    emissive = e.emissive,
                    params = storedParamsObject(e.params).takeIf { it.isNotEmpty() },
                    hidden = e.hidden,
                    sortOrder = e.sortOrder,
                )
            },

            kind("stageViewpoints", StageViewpointJson.serializer(), { project -> project.stageViewpoints.toList() }, { it.uuid }) { v ->
                StageViewpointJson(
                    uuid = v.uuid.toString(),
                    name = v.name,
                    kind = v.kind,
                    eyeX = v.eyeX,
                    eyeY = v.eyeY,
                    eyeZ = v.eyeZ,
                    targetX = v.targetX,
                    targetY = v.targetY,
                    targetZ = v.targetZ,
                    fovDeg = v.fovDeg,
                    seatElementUuid = v.seatElementUuid?.toString(),
                    seatId = v.seatId,
                    sortOrder = v.sortOrder,
                )
            },

            kind("fixturePatches", FixturePatchJson.serializer(), { project -> project.fixturePatches.toList() }, { it.uuid }) { p ->
                FixturePatchJson(
                    uuid = p.uuid.toString(),
                    universeConfigUuid = p.universeConfig.uuid.toString(),
                    fixtureTypeKey = p.fixtureTypeKey,
                    key = p.key,
                    displayName = p.displayName,
                    headNumber = p.headNumber,
                    startChannel = p.startChannel,
                    sortOrder = p.sortOrder,
                    stageX = p.stageX,
                    stageY = p.stageY,
                    stageZ = p.stageZ,
                    baseYawDeg = p.baseYawDeg,
                    basePitchDeg = p.basePitchDeg,
                    baseRollDeg = p.baseRollDeg,
                    riggingUuid = p.rigging?.uuid?.toString(),
                    beamAngleDeg = p.beamAngleDeg,
                    gelCode = p.gelCode,
                    kindOverride = p.kindOverride,
                    lengthM = p.lengthM,
                    stageHidden = p.stageHidden,
                    infrastructure = p.infrastructure,
                    lanternType = p.lanternType,
                    zoomDeg = p.zoomDeg,
                    lampRotationDeg = p.lampRotationDeg,
                    shutters = LanternFocus.shuttersFromText(p.shutters),
                    gateRotationDeg = p.gateRotationDeg,
                    iris = p.iris,
                    focusSoftness = p.focusSoftness,
                    lampWatts = p.lampWatts,
                    lampKelvin = p.lampKelvin,
                    locate = p.storedLocate,
                    media = p.fittedMedia,
                    colour = p.colour,
                    extraPlacements = extraPlacementsOf(p).map { pl ->
                        PatchPlacementJson(
                            uuid = pl.uuid.toString(),
                            label = pl.label,
                            riggingUuid = pl.rigging?.uuid?.toString(),
                            stageX = pl.stageX,
                            stageY = pl.stageY,
                            stageZ = pl.stageZ,
                            baseYawDeg = pl.baseYawDeg,
                            basePitchDeg = pl.basePitchDeg,
                            baseRollDeg = pl.baseRollDeg,
                            lengthM = pl.lengthM,
                            lanternType = pl.lanternType,
                            zoomDeg = pl.zoomDeg,
                            lampRotationDeg = pl.lampRotationDeg,
                            shutters = LanternFocus.shuttersFromText(pl.shutters),
                            gateRotationDeg = pl.gateRotationDeg,
                            iris = pl.iris,
                            focusSoftness = pl.focusSoftness,
                            media = pl.fittedMedia,
                        )
                    },
                )
            },

            kind("fixtureGroups", FixtureGroupJson.serializer(), { project -> project.fixtureGroups.toList() }, { it.uuid }) { g ->
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
                FixtureGroupJson(g.uuid.toString(), g.name, members, g.colour)
            },

            kind("buskRig", BuskRigRowJson.serializer(), { project -> project.buskRigRows.toList() }, { it.uuid }) { row ->
                BuskRigRowJson(
                    uuid = row.uuid.toString(),
                    name = row.name,
                    sortOrder = row.sortOrder,
                    flow = row.flow,
                    width = row.width,
                    tiles = row.tiles
                        .sortedWith(compareBy({ it.sortOrder }, { it.uuid }))
                        // A malformed tile is absent everywhere else it is read; exporting it would
                        // only hand the next importer the same row to drop.
                        .filter { tile -> tile.kind != null || run { warnMalformedBuskRigTile { tile.uuid.toString() }; false } }
                        .map { tile ->
                            BuskRigTileJson(
                                uuid = tile.uuid.toString(),
                                sortOrder = tile.sortOrder,
                                groupUuid = tile.group?.uuid?.toString(),
                                patchUuid = tile.patch?.uuid?.toString(),
                                elementKey = tile.elementKey,
                                cellMode = tile.cellMode,
                                cellSplit = tile.cellSplit,
                                label = tile.label,
                            )
                        },
                )
            },

            // FX presets and named palettes are no longer exported: v5 collapsed both into looks,
            // and the startup migration converts any local rows. Writing either alongside `looks/`
            // would put two representations of one entity in the repo, and an import would
            // materialise both.
            kind("looks", LookJson.serializer(), { project -> project.looks.toList() }, { it.uuid }) { l ->
                val rows = l.rows
                    .sortedWith(compareBy({ it.sortOrder }, { it.uuid }))
                    .map { r ->
                        LookRowJson(
                            uuid = r.uuid.toString(),
                            targetType = r.targetType,
                            targetKey = r.targetKey,
                            attribute = r.attribute,
                            value = r.value,
                            fadeDurationMs = r.fadeDuration?.toMillis(),
                            elementKey = r.elementKey,
                            sortOrder = r.sortOrder,
                        )
                    }
                val effects = l.effects
                    .sortedWith(compareBy({ it.sortOrder }, { it.uuid }))
                    .map { e ->
                        LookFxJson(
                            uuid = e.uuid.toString(),
                            targetType = e.targetType,
                            targetKey = e.targetKey,
                            fxType = e.fxType,
                            family = e.family,
                            attribute = e.attribute,
                            cycle = e.cycle,
                            blendMode = e.blendMode,
                            order = e.order,
                            phaseOffset = e.phaseOffset,
                            elementMode = e.elementMode,
                            elementFilter = e.elementFilter,
                            timing = e.timing,
                            parameters = e.parameters,
                            speedMasterUuid = e.speedMasterUuid?.toString(),
                            rateSpeedMasterUuid = e.rateSpeedMasterUuid?.toString(),
                            seconds = e.seconds,
                            amount = e.amount,
                            direction = e.direction,
                            runs = e.runs,
                            spread = e.spread,
                            wings = e.wings,
                            segments = e.segments,
                            parts = e.parts,
                            spreadUnit = e.spreadUnit,
                            sortOrder = e.sortOrder,
                        )
                    }
                LookJson(
                    uuid = l.uuid.toString(),
                    name = l.name,
                    notes = l.notes,
                    rows = rows,
                    fx = effects,
                    scenery = lookSceneryOf(l.id).map { it.toJson() },
                )
            },

            kind("palettes", PaletteJson.serializer(), { project -> project.palettes.toList() }, { it.uuid }) { t ->
                PaletteJson(
                    uuid = t.uuid.toString(),
                    name = t.name,
                    notes = t.notes,
                    fadeDurationMs = t.fadeDuration?.toMillis(),
                    rows = t.rows
                        .sortedWith(compareBy({ it.sortOrder }, { it.uuid }))
                        .map { r ->
                            PaletteRowJson(
                                uuid = r.uuid.toString(),
                                targetType = r.targetType,
                                targetKey = r.targetKey,
                                attribute = r.attribute,
                                value = r.value,
                                sortOrder = r.sortOrder,
                            )
                        },
                    // At most one (D2), so no `sortedWith` — there is no order to stabilise.
                    fx = t.effect?.let { e ->
                        PaletteFxJson(
                            uuid = e.uuid.toString(),
                            fxType = e.fxType,
                            family = e.family,
                            attribute = e.attribute,
                            cycle = e.cycle,
                            blendMode = e.blendMode,
                            order = e.order,
                            phaseOffset = e.phaseOffset,
                            elementMode = e.elementMode,
                            elementFilter = e.elementFilter,
                            timing = e.timing,
                            parameters = e.parameters,
                            speedMasterUuid = e.speedMasterUuid?.toString(),
                            rateSpeedMasterUuid = e.rateSpeedMasterUuid?.toString(),
                            seconds = e.seconds,
                            amount = e.amount,
                            direction = e.direction,
                            runs = e.runs,
                            spread = e.spread,
                            wings = e.wings,
                            segments = e.segments,
                            parts = e.parts,
                            spreadUnit = e.spreadUnit,
                        )
                    },
                )
            },

            kind("speedMasters", SpeedMasterJson.serializer(), { project -> project.speedMasters.toList() }, { it.uuid }) { m ->
                SpeedMasterJson(
                    uuid = m.uuid.toString(),
                    masterIndex = m.masterIndex,
                    name = m.name,
                    bpm = m.bpm,
                    source = m.source,
                    notes = m.notes,
                    family = m.family,
                    // Through the sanitising getter, not the raw columns: a half-written or
                    // non-positive pair (or a ratio on master 1) reads as "manual" on every
                    // other surface, and exporting the junk verbatim would clone/sync it.
                    followNum = m.followRatio?.first,
                    followDen = m.followRatio?.second,
                    followTargetUuid = m.followTarget?.toString(),
                )
            },

            kind("fxDefinitions", FxDefinitionJson.serializer(), { project -> project.fxDefinitions.toList() }, { it.uuid }) { d ->
                // Encode ParameterInfo through the canonical JSON instance so nested defaults/nulls
                // follow the same omission rules as the parent document.
                val parametersJson = canonicalJson.encodeToJsonElement(
                    ListSerializer(ParameterInfo.serializer()),
                    d.parameters,
                )
                FxDefinitionJson(
                    uuid = d.uuid.toString(),
                    fxId = d.fxId,
                    name = d.name,
                    family = d.family,
                    outputType = d.outputType,
                    fxMode = d.fxMode.name,
                    parameters = parametersJson,
                    compatibleAttributes = d.compatibleAttributes,
                    script = d.script,
                    defaultTiming = d.defaultTiming,
                    timingSource = d.timingSource,
                )
            },

            kind("cueSlots", CueSlotJson.serializer(), { project -> project.cueSlots.toList() }, { it.uuid }) { s ->
                CueSlotJson(
                    uuid = s.uuid.toString(),
                    page = s.page,
                    slotIndex = s.slotIndex,
                    cueUuid = s.cue?.uuid?.toString(),
                    lookUuid = s.look?.uuid?.toString(),
                )
            },

            kind("buskPages", BuskPageJson.serializer(), { project -> project.buskPages.toList() }, { it.uuid }) { page ->
                BuskPageJson(
                    uuid = page.uuid.toString(),
                    name = page.name,
                    sortOrder = page.sortOrder,
                    columns = page.columns
                        .sortedWith(compareBy({ it.row }, { it.sortOrder }, { it.uuid }))
                        .map { column ->
                            BuskColumnJson(
                                uuid = column.uuid.toString(),
                                row = column.row,
                                sortOrder = column.sortOrder,
                                width = column.width,
                                banks = column.banks
                                    .sortedWith(compareBy({ it.sortOrder }, { it.uuid }))
                                    .map { bank ->
                                        BuskBankJson(
                                            uuid = bank.uuid.toString(),
                                            sortOrder = bank.sortOrder,
                                            name = bank.name,
                                            solo = bank.solo,
                                            flow = bank.flow,
                                            pads = bank.pads
                                                .sortedWith(compareBy({ it.sortOrder }, { it.uuid }))
                                                // A malformed pad is absent everywhere else it is read;
                                                // exporting it would only hand the next importer the
                                                // same row to drop.
                                                .filter { pad ->
                                                    pad.kind != null || run { warnMalformedBuskPad { pad.uuid.toString() }; false }
                                                }
                                                .map { pad ->
                                                    BuskPadJson(
                                                        uuid = pad.uuid.toString(),
                                                        sortOrder = pad.sortOrder,
                                                        paletteUuid = pad.palette?.uuid?.toString(),
                                                        lookUuid = pad.look?.uuid?.toString(),
                                                        cueUuid = pad.cue?.uuid?.toString(),
                                                    )
                                                },
                                        )
                                    },
                            )
                        },
                )
            },

            kind("parkedChannels", ParkedChannelJson.serializer(), { project -> project.parkedChannels.toList() }, { it.uuid }) { p ->
                ParkedChannelJson(
                    uuid = p.uuid.toString(),
                    universe = p.universe,
                    channel = p.channel,
                    value = p.value,
                )
            },

            // v27: a show's releases of its venue's parks, by channel.
            kind(
                "showParkReleases", ShowParkReleaseJson.serializer(),
                { project -> DaoShowParkRelease.find { DaoShowParkReleases.project eq project.id }.toList() }, { it.uuid },
            ) { r ->
                ShowParkReleaseJson(uuid = r.uuid.toString(), universe = r.universe, channel = r.channel)
            },

            kind(
                "controlSurfaceBindings", ControlSurfaceBindingJson.serializer(),
                { project -> project.controlSurfaceBindings.toList() }, { it.uuid },
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
            },


            RecordKind("scripts") { project, sink ->
                project.scripts.forEach { s ->
                    sink.script(s.uuid, s.id.value, ScriptMetaJson(s.uuid.toString(), s.name, s.scriptType.name), s.script)
                }
            },
        )

        val FOLDERS: Set<String> = KINDS.mapTo(linkedSetOf()) { it.folder }
    }
}

// v21: a cue's events travel embedded in the cue, the patch by uuid.
private fun DaoCueEvent.toJson() = CueEventJson(
    uuid = uuid.toString(),
    patchUuid = patch.uuid.toString(),
    trigger = trigger,
    offsetMs = offset.toMillis(),
    sortOrder = sortOrder,
)

// v20: scenery travels embedded in its owner, its element by uuid and its states as an object.
private fun DaoCueSceneryRow.toJson() = SceneryChangeJson(
    uuid = uuid.toString(),
    elementUuid = element.uuid.toString(),
    state = storedParamsObject(stateJson),
    transitionMs = transition?.toMillis(),
    sortOrder = sortOrder,
)

private fun DaoCueStackSceneryRow.toJson() = SceneryChangeJson(
    uuid = uuid.toString(),
    elementUuid = element.uuid.toString(),
    state = storedParamsObject(stateJson),
    sortOrder = sortOrder,
)

private fun DaoLookSceneryRow.toJson() = SceneryChangeJson(
    uuid = uuid.toString(),
    elementUuid = element.uuid.toString(),
    state = storedParamsObject(stateJson),
    sortOrder = sortOrder,
)

/** [this] stack's record carrying [settings] (v45). */
private fun CueStackJson.withMoveInDark(settings: MoveInDarkSettings) = copy(
    moveInDark = settings.on,
    moveInDarkWhen = settings.whenMode.name,
    moveInDarkDelayMs = settings.delay.toMillis(),
    moveInDarkTimeMs = settings.time.toMillis(),
    moveInDarkFamilies = settings.families.map { it.name }.sorted(),
)

/** [this] cue carrying its *Move in dark* setting (v46). */
private fun CueJson.withMoveInDark(settings: CueMoveInDarkSettings): CueJson = copy(
    moveInDarkMode = settings.mode.name,
    moveInDarkDelayMs = settings.delay?.toMillis(),
    moveInDarkTimeMs = settings.time?.toMillis(),
    moveInDarkLive = settings.live,
)
