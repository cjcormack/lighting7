package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.IncludedTarget
import uk.me.cormack.lighting7.fx.Layer3Resolver
import uk.me.cormack.lighting7.fx.PaletteCascade
import uk.me.cormack.lighting7.fx.ProgrammerFxOrigin
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.toPaletteColours
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.maskGroupForProperty
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.CuePresetApplicationDto
import uk.me.cormack.lighting7.models.DaoFxPreset
import uk.me.cormack.lighting7.models.FxPresetEffectDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State

private val logger = LoggerFactory.getLogger("uk.me.cormack.lighting7.routes.programmerInclude")

/** What an Include actually did, for the response and for "Select Heads on Include". */
data class IncludeOutcome(
    val entriesWritten: Int,
    val fixtureKeys: List<String>,
    val groupKeys: List<String>,
    val fxSpawned: Int,
    val fxAlreadyRunning: Int,
    val fxTimedSkipped: Int,
    val skipped: List<RecordSkip>,
    val warnings: List<String>,
)

/** A preset application resolved against the DB, so the spawn pass doesn't need a transaction. */
internal data class IncludedPreset(
    val presetId: Int,
    val application: CuePresetApplicationDto,
    val effects: List<FxPresetEffectDto>,
    val palette: List<ExtendedColour>,
)

/** Load the immediate preset applications of [cueData] with their effects and palettes. */
internal fun loadImmediatePresets(state: State, cueData: CueApplyData): List<IncludedPreset> =
    transaction(state.database) {
        cueData.presetApplications
            .filter { it.delayMs == null && it.intervalMs == null }
            .mapNotNull { app ->
                val preset = DaoFxPreset.findById(app.presetId) ?: return@mapNotNull null
                IncludedPreset(app.presetId, app, preset.effects, preset.palette.toPaletteColours())
            }
    }

/**
 * Pull a cue's contents into the programmer as `INCLUDE`-owned entries and programmer-band FX,
 * so the operator can edit the cue on stage and write it back with Update.
 *
 * The INCLUDE slot is load-bearing beyond just holding a value: because the store keeps a
 * per-owner slot stack, it survives underneath any later `WEB` write. Update reads it back to
 * tell "the operator changed this" from "this is just what I included" — see
 * `changedSinceInclude` in `programmerUpdate.kt`.
 */
internal fun includeCueIntoProgrammer(
    state: State,
    cueData: CueApplyData,
    immediatePresets: List<IncludedPreset>,
    mask: Set<PropertyMaskGroup>?,
    fadeMs: Long,
): IncludeOutcome {
    val engine = state.show.fxEngine
    val fixtures = state.show.fixtures
    val skipped = ArrayList<RecordSkip>()
    val warnings = ArrayList<String>()

    val cascade = PaletteCascade(
        cue = cueData.palette.toPaletteColours(),
        global = engine.getPalette(),
    )
    val priority = cueDerivedPriority(cueData)

    val cueOwnRows = buildLayer3AssignmentsForCue(fixtures, cueData, cascade)
    val presetRows = immediatePresets.flatMap { preset ->
        buildLayer3AssignmentsForPreset(
            fixtures, cueData.cueId, priority,
            preset.presetId, presetPropertyAssignments(state, preset.presetId),
            preset.application.targets,
            cascade = cascade.copy(preset = preset.palette),
        )
    }

    // Preset rows are concatenated after the cue's own so that, where both assert the same
    // (fixture, property) at the same specificity, the preset wins — the order `applyCue` uses.
    val resolved = applySpecificityForInclude(cueOwnRows + presetRows)

    // The builders fan group targets out to member rows and drop the group identity; recover
    // it so the entries can be recorded back as a group row rather than N fixture rows.
    val groupHints = includeGroupHints(state, cueData, immediatePresets)

    val writes = ArrayList<FxEngine.ProgrammerPropertyWrite>(resolved.size)
    val fixtureKeys = LinkedHashSet<String>()
    for (row in resolved) {
        val fixture: GroupableFixture = try {
            fixtures.untypedGroupableFixture(row.targetKey)
        } catch (_: Exception) {
            skipped += RecordSkip(row.targetKey, row.propertyName, reason = RecordSkipReason.MISSING_FIXTURE)
            continue
        }
        val group = maskGroupForProperty(fixture, row.propertyName)
        if (!maskAllows(mask, group)) continue
        writes += FxEngine.ProgrammerPropertyWrite(
            fixture, row.propertyName, row.value,
            sourceGroup = groupHints[row.targetKey to canonicalPropertyName(row.propertyName)],
        )
        fixtureKeys += row.targetKey
    }

    // One batched write: one lock acquisition, one controller transaction, one provenance
    // emit. A large cue is hundreds of properties, and per-property publishing would make
    // Include visibly stutter the rig.
    if (writes.isNotEmpty()) {
        engine.writeProgrammerProperties(
            ProgrammerOwner.INCLUDE,
            writes,
            // Included content is recordable: Record TOUCHED and the Update checklist must
            // both see it. Whether the operator subsequently *changed* it is a separate
            // question, answered by the surviving INCLUDE slot rather than by this flag.
            touched = true,
            // A sticky editing owner, like WEB — absorb the sideband so a stale raw channel
            // value can't resurface when the include is later released.
            absorbSideband = true,
            fadeMs = fadeMs,
        )
    }

    val fx = spawnIncludedFx(state, cueData, immediatePresets, mask, cascade)
    fixtureKeys += fx.coveredFixtureKeys

    if (writes.isEmpty() && fx.spawned == 0 && fx.alreadyRunning == 0) {
        warnings += "Cue '${cueData.cueName}' has nothing to include" +
            if (mask != null) " under this mask" else ""
    }

    return IncludeOutcome(
        entriesWritten = writes.size,
        fixtureKeys = fixtureKeys.toList().sorted(),
        groupKeys = (groupHints.values.toSet() + fx.groupKeys).toList().sorted(),
        fxSpawned = fx.spawned,
        fxAlreadyRunning = fx.alreadyRunning,
        fxTimedSkipped = fx.timedSkipped,
        skipped = skipped,
        warnings = warnings,
    )
}

private fun presetPropertyAssignments(state: State, presetId: Int) =
    transaction(state.database) {
        DaoFxPreset.findById(presetId)?.toPropertyAssignmentDtos() ?: emptyList()
    }

/**
 * Collapse the builders' output the way the resolver would at compose time.
 *
 * `buildLayer3AssignmentsForCue` deliberately emits *both* a group-expanded member row and any
 * direct fixture row for the same member, leaving `Layer3Resolver.applySpecificity` to drop the
 * former when composing. The programmer has no such pass — writing the raw list would let list
 * order decide the winner — so Include applies the same rule up front: a direct fixture row
 * beats a group-expanded one, and among equals the last wins.
 */
private fun applySpecificityForInclude(
    rows: List<Layer3Resolver.Assignment>,
): List<Layer3Resolver.Assignment> {
    val winners = LinkedHashMap<Pair<String, String>, Layer3Resolver.Assignment>()
    for (row in rows) {
        val key = row.targetKey to canonicalPropertyName(row.propertyName)
        val current = winners[key]
        if (current == null || current.targetIsGroup || !row.targetIsGroup) {
            winners[key] = row
        }
    }
    return winners.values.toList()
}

/** `(memberKey, canonicalProperty) → groupKey` for the cue's group-shaped content. */
private fun includeGroupHints(
    state: State,
    cueData: CueApplyData,
    immediatePresets: List<IncludedPreset>,
): Map<Pair<String, String>, String> {
    val fixtures = state.show.fixtures
    val out = HashMap<Pair<String, String>, String>()

    for (row in cueData.propertyAssignments) {
        val target = row.target
        if (target !is TargetRef.Group) continue
        val members = try {
            fixtures.untypedGroup(target.key).fixtures
        } catch (_: Exception) {
            continue
        }
        val property = canonicalPropertyName(row.propertyName)
        for (member in members) out.putIfAbsent(member.targetKey to property, target.key)
    }

    // A preset applied to a group is group-shaped too; its property assignments carry the
    // property names, the application carries the targets.
    for (preset in immediatePresets) {
        val groupTargets = preset.application.targets
            .map { it.target }
            .filterIsInstance<TargetRef.Group>()
        if (groupTargets.isEmpty()) continue
        val properties = presetPropertyAssignments(state, preset.presetId)
            .mapNotNull { it.propertyName }
            .map { canonicalPropertyName(it) }
        for (groupTarget in groupTargets) {
            val members = try {
                fixtures.untypedGroup(groupTarget.key).fixtures
            } catch (_: Exception) {
                continue
            }
            for (member in members) {
                for (property in properties) {
                    out.putIfAbsent(member.targetKey to property, groupTarget.key)
                }
            }
        }
    }
    return out
}

private data class IncludedFxOutcome(
    val spawned: Int,
    val alreadyRunning: Int,
    val timedSkipped: Int,
    val coveredFixtureKeys: Set<String>,
    val groupKeys: Set<String>,
)

/**
 * Re-spawn the cue's immediate FX children as programmer-band instances.
 *
 * Two rules earn their keep here:
 *
 * 1. **Already-running children are skipped.** If the cue is live, its own instance is already
 *    producing exactly that output. There is no FX-vs-FX suppression in the engine
 *    (suppression keys off programmer *values*), so a band duplicate would compose on top and
 *    visibly double the effect under any non-`OVERRIDE` blend mode.
 * 2. **`cueId`/`cueStackId` stay null** on spawned instances. Tagging them would let
 *    `removeEffectsForCue` sweep the operator's programmer contents out from under them when
 *    the cue stops. `programmerOrigin` carries the provenance Update needs instead, and it is
 *    not something any cue-teardown path looks at.
 */
private fun spawnIncludedFx(
    state: State,
    cueData: CueApplyData,
    immediatePresets: List<IncludedPreset>,
    mask: Set<PropertyMaskGroup>?,
    cascade: PaletteCascade,
): IncludedFxOutcome {
    val engine = state.show.fxEngine
    var spawned = 0
    var alreadyRunning = 0
    val covered = LinkedHashSet<String>()
    val groupKeys = LinkedHashSet<String>()

    val timedSkipped = cueData.presetApplications.count { it.delayMs != null || it.intervalMs != null } +
        cueData.adHocEffects.count { it.delayMs != null || it.intervalMs != null }

    val running = engine.getActiveEffects().filter { it.cueId == cueData.cueId }

    fun spawn(
        presetEffect: FxPresetEffectDto,
        target: TargetRef,
        presetId: Int?,
        origin: ProgrammerFxOrigin,
        palette: List<ExtendedColour>,
    ) {
        if (mask != null) {
            val reference = try {
                when (target) {
                    is TargetRef.Group -> state.show.fixtures.untypedGroup(target.key).fixtures.firstOrNull()
                    is TargetRef.Fixture -> state.show.fixtures.untypedGroupableFixture(target.key)
                }
            } catch (_: Exception) {
                null
            }
            val propertyName = presetEffect.propertyName
            val group = if (reference != null && propertyName != null) {
                maskGroupForProperty(reference, propertyName)
            } else null
            if (!maskAllows(mask, group)) return
        }

        // Matched on target + preset id, deliberately *not* on effect type.
        //
        // The obvious type test — `it.effect.name.replace(" ", "") == presetEffect.effectType`
        // — only works for built-in effects, where the registration's id happens to be its
        // display name with spaces stripped. A user-defined FX definition sets `effectId`
        // independently of `name`, so the comparison never matches and Include spawns a second
        // copy on top of the live one, visibly doubling it under any non-OVERRIDE blend.
        //
        // Erring toward "already covered" is the safe direction: the cost of a false match is
        // one un-spawned band instance on a property the cue is already driving, while a false
        // miss is a visible double-apply.
        val duplicate = running.any {
            it.target.targetKey == target.key &&
                it.target.propertyName == presetEffect.propertyName &&
                it.presetId == presetId
        }
        if (duplicate) {
            alreadyRunning++
            return
        }

        val fxTarget = try {
            resolveTargetForCue(state, TogglePresetTarget(target), presetEffect)
        } catch (_: Exception) {
            null
        } ?: return

        // Snapshot suppliers: the included cue's palette must resolve even when the cue isn't
        // live, which the cue-scoped supplier can't promise.
        val snapshot = if (palette.isNotEmpty()) palette else cascade.effective
        val instance = createInstanceFromPreset(
            presetEffect, fxTarget, presetId, state,
            paletteSupplier = { snapshot },
            paletteVersionSupplier = { 0L },
        )
        uk.me.cormack.lighting7.routes.markProgrammerOwned(instance, true)
        instance.programmerOrigin = origin
        engine.addEffect(instance)
        spawned++

        if (target is TargetRef.Group) groupKeys += target.key
        covered += engine.fixtureKeysCoveredBy(instance)
    }

    for (preset in immediatePresets) {
        for (target in preset.application.targets) {
            for (presetEffect in preset.effects) {
                spawn(
                    presetEffect, target.target, preset.presetId,
                    ProgrammerFxOrigin(
                        cueData.cueId,
                        ProgrammerFxOrigin.Kind.PRESET_APPLICATION,
                        preset.presetId,
                        preset.application.sortOrder,
                    ),
                    preset.palette,
                )
            }
        }
    }

    for (adHoc in cueData.adHocEffects.filter { it.delayMs == null && it.intervalMs == null }) {
        spawn(
            adHoc.toPresetEffectDto(), adHoc.target, null,
            ProgrammerFxOrigin(cueData.cueId, ProgrammerFxOrigin.Kind.AD_HOC, null, adHoc.sortOrder),
            cascade.effective,
        )
    }

    if (spawned > 0) {
        logger.debug("include: spawned {} programmer-band effect(s) from cue {}", spawned, cueData.cueId)
    }
    return IncludedFxOutcome(spawned, alreadyRunning, timedSkipped, covered, groupKeys)
}

/** Ad-hoc children and preset effects share a shape; this is the adapter `applyCue` also uses. */
internal fun CueAdHocEffectDto.toPresetEffectDto() = FxPresetEffectDto(
    effectType = effectType,
    category = category,
    propertyName = propertyName,
    beatDivision = beatDivision,
    blendMode = blendMode,
    distribution = distribution,
    phaseOffset = phaseOffset,
    elementMode = elementMode,
    elementFilter = elementFilter,
    stepTiming = stepTiming,
    parameters = parameters,
)

/** The include target for [cueData], for [uk.me.cormack.lighting7.fx.ProgrammerStore]. */
internal fun includedTargetFor(cueData: CueApplyData) =
    IncludedTarget.cue(cueData.cueId, cueData.cueStackId)
