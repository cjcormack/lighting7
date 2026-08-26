package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fx.EffectSpawner
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.IncludedTarget
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerFxOrigin
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.maskGroupForProperty
import uk.me.cormack.lighting7.fx.CueApplyData
import uk.me.cormack.lighting7.fx.buildCueAssignmentsForCue
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.LookEffectSpec
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.CueTargetDto
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
    /**
     * Layers installed into the programmer's stack.
     *
     * Reported because it is the **only** evidence that a cue made entirely of layers included
     * anything at all: such a cue writes no INCLUDE slots and may spawn no effects, so a caller
     * gating on `entriesWritten > 0 || fxSpawned > 0` would conclude nothing happened — and
     * therefore never set the include target, leaving Update unable to write the stack back.
     */
    val layersInstalled: Int,
    val skipped: List<RecordSkip>,
    val warnings: List<String>,
)

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
    mask: Set<PropertyMaskGroup>?,
    fadeMs: Long,
): IncludeOutcome {
    val engine = state.show.fxEngine
    val fixtures = state.show.fixtures
    val skipped = ArrayList<RecordSkip>()
    val warnings = ArrayList<String>()

    // **Only the cue's own rows become INCLUDE slots.** Its layers become *programmer layers*
    // (below), which is the whole point of this rewrite: Include used to flatten a cue's
    // composition into literals, so the operator got the right output with none of the structure —
    // and Update then had nothing to write back but literals. Now the stack arrives intact,
    // reorderable, and Update can diff it.
    val cueOwnRows = buildCueAssignmentsForCue(fixtures, cueData)

    // Still needed for the local rows: `buildCueAssignmentsForCue` deliberately emits both a
    // group-expanded member row and any direct fixture row for the same member, leaving the
    // resolver to drop the former at compose time. The programmer has no such pass. The layer half
    // needs nothing here — `cook` resolves its own specificity.
    val resolved = applySpecificityForInclude(cueOwnRows)

    // The builder fans group targets out to member rows and drops the group identity; recover
    // it so the entries can be recorded back as a group row rather than N fixture rows.
    val groupHints = includeGroupHints(state, cueData)

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

    // The cue's layers, as programmer layers. This spawns their effects too — the stack owns that
    // — so `spawnIncludedFx` below is left with only the cue's *ad-hoc* children.
    val (timedLayersSkipped, layerOutcome) = state.show.programmerLayerStack
        .installFromCue(cueData.layers, fadeMs)
    val layerGroupKeys = cueData.layers
        .flatMap { it.targets }
        .mapNotNull { (it.target as? TargetRef.Group)?.key }
        .toSet()

    val fx = spawnIncludedFx(state, cueData, mask)
    fixtureKeys += fx.coveredFixtureKeys

    val nothingIncluded = writes.isEmpty() &&
        fx.spawned == 0 && fx.alreadyRunning == 0 &&
        state.show.programmerStore.layers.isEmpty()
    if (nothingIncluded) {
        warnings += "Cue '${cueData.cueName}' has nothing to include" +
            if (mask != null) " under this mask" else ""
    }

    return IncludeOutcome(
        entriesWritten = writes.size,
        fixtureKeys = fixtureKeys.toList().sorted(),
        // A layer's group targets are *real* information rather than inferred, unlike the member
        // rows' recovered hints — the layer names the group directly.
        groupKeys = (groupHints.values.toSet() + fx.groupKeys + layerGroupKeys).toList().sorted(),
        fxSpawned = fx.spawned + layerOutcome.effectsSpawned,
        fxAlreadyRunning = fx.alreadyRunning,
        fxTimedSkipped = fx.timedSkipped + timedLayersSkipped,
        layersInstalled = state.show.programmerStore.layers.size,
        skipped = skipped,
        warnings = warnings,
    )
}

/**
 * Collapse the builders' output the way the resolver would at compose time.
 *
 * `buildCueAssignmentsForCue` deliberately emits *both* a group-expanded member row and any
 * direct fixture row for the same member, leaving `CueAssignmentResolver.applySpecificity` to drop the
 * former when composing. The programmer has no such pass — writing the raw list would let list
 * order decide the winner — so Include applies the same rule up front: a direct fixture row
 * beats a group-expanded one, and among equals the last wins.
 */
private fun applySpecificityForInclude(
    rows: List<CueAssignmentResolver.Assignment>,
): List<CueAssignmentResolver.Assignment> {
    val winners = LinkedHashMap<Pair<String, String>, CueAssignmentResolver.Assignment>()
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

    // The preset half of this function is gone. A preset applied to a group was group-shaped too,
    // and this had to *infer* which (member, property) pairs it covered by cross-producting the
    // preset's property names with the application's targets — an approximation that over-claimed
    // whenever a preset row didn't apply to every member. A layer carries its own target list, so
    // the group shape travels with it and `ProgrammerLayerStack` needs no hint at all.
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
    mask: Set<PropertyMaskGroup>?,
): IncludedFxOutcome {
    val engine = state.show.fxEngine
    // Instances are collected here and added in one `addEffects` below: adding them one at a
    // time rebuilt the engine's sorted snapshots and re-broadcast the whole active-effect list
    // per effect (sweep item C7). The duplicate guard reads the pre-loop `running` snapshot and
    // never the instances this loop makes, so deferring the adds changes nothing it decides.
    val spawning = mutableListOf<FxInstance>()
    var alreadyRunning = 0
    val covered = LinkedHashSet<String>()
    val groupKeys = LinkedHashSet<String>()

    // Layers are counted by the caller, which knows which of them were timed.
    val timedSkipped = cueData.adHocEffects.count { it.delayMs != null || it.intervalMs != null }

    val running = engine.getActiveEffects().filter { it.cueId == cueData.cueId }

    fun spawn(
        presetEffect: LookEffectSpec,
        target: TargetRef,
        origin: ProgrammerFxOrigin,
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

        // Matched on target + property + **effect registration**, and only against the cue's own
        // ad-hoc children — the only thing this function spawns.
        //
        // Never on `it.effect.name.replace(" ", "")`: that is a registration id only for
        // built-ins, whose display name is their id with spaces in it. A user-defined FX
        // definition sets `id` independently of `name`, so the comparison never matches and
        // Include spawns a second copy on top of the live one. [FxInstance.registrationId] is the
        // id itself, so the test holds for both.
        //
        // The two null clauses are what stop a *layer's* effect from masking a child it merely
        // resembles. The child's own live instance still matches it exactly — same target,
        // property and registration, and no layer provenance either — so a live cue's children
        // are still skipped rather than doubled, which is the rule this guard exists for.
        val registrationId = state.show.fxRegistry.getRegistration(presetEffect.effectType)?.id
        val duplicate = running.any {
            it.target.targetKey == target.key &&
                it.target.propertyName == presetEffect.propertyName &&
                it.registrationId == registrationId &&
                it.lookId == null && it.cueLayerId == null
        }
        if (duplicate) {
            alreadyRunning++
            return
        }

        val fxTarget = try {
            EffectSpawner.resolveTargetForCue(state, CueTargetDto(target), presetEffect)
        } catch (e: Exception) {
            logger.warn(
                "cue {}: included fx on '{}' — target unresolvable — skipping: {}",
                cueData.cueId, target.key, e.message,
            )
            null
        } ?: return

        val instance = EffectSpawner.createInstanceFromPreset(presetEffect, fxTarget, state)
        uk.me.cormack.lighting7.routes.markProgrammerOwned(instance, true)
        instance.programmerOrigin = origin
        spawning += instance

        if (target is TargetRef.Group) groupKeys += target.key
        covered += engine.fixtureKeysCoveredBy(instance)
    }

    // A cue's *layer* effects are spawned by `ProgrammerLayerStack`, which owns their lifecycle —
    // retraction on remove, re-ranking on reorder. Only the cue's own ad-hoc children are left here.
    for (adHoc in cueData.adHocEffects.filter { it.delayMs == null && it.intervalMs == null }) {
        spawn(
            adHoc.toPresetEffectDto(), adHoc.target,
            ProgrammerFxOrigin(cueData.cueId, ProgrammerFxOrigin.Kind.AD_HOC, adHoc.sortOrder),
        )
    }

    engine.addEffects(spawning)
    val spawned = spawning.size

    if (spawned > 0) {
        logger.debug("include: spawned {} programmer-band effect(s) from cue {}", spawned, cueData.cueId)
    }
    return IncludedFxOutcome(spawned, alreadyRunning, timedSkipped, covered, groupKeys)
}

/** Ad-hoc children and preset effects share a shape; this is the adapter `applyCue` also uses. */
internal fun CueAdHocEffectDto.toPresetEffectDto() = LookEffectSpec(
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
