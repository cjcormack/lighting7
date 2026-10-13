package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.EffectSpawner
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.IncludedLookEffect
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.lookEffectSettings
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.maskGroupForProperty
import uk.me.cormack.lighting7.fx.toEffectEntry
import uk.me.cormack.lighting7.fx.toEffectSpec
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.LayerSourceKind
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State

private val logger = LoggerFactory.getLogger("programmerLookInclude")

/*
 * **Include a Look into the programmer**, plus the target-expansion helper the Record paths share.
 *
 * This was `programmerPaletteRecord.kt`, whose own doc comment said the filename was "wider than the
 * contents, deliberately for one session" — waiting for the deletion it belonged to rather than
 * being renamed twice. Session 4 is that deletion. What went with the palette tables:
 *
 * - `writeRecordingIntoPalette` and `PaletteWriteOutcome` — recording the programmer into a palette.
 *   `routes/lookRecord.kt` replaces it, and the destination table no longer exists.
 * - `updateIncludedPalette` — the palette arm of Mode A Update, reachable only by a caller naming
 *   `paletteId` explicitly. The desk never did, and the Looks migration left no palette rows behind,
 *   so it was already dead in practice.
 * - `includePaletteIntoProgrammer` and `handleIncludePalette` — the palette *wrappers* around the
 *   shared include body. The body survives as [includeExpandedIntoProgrammer]; only the wrappers
 *   were palette-shaped, which is why Include needed no rewrite to lose them.
 */

/**
 * Expand a selection of fixture and group targets into the fixture keys Record filters on.
 * Unknown targets contribute nothing — the caller reports an empty result as a 400.
 */
internal fun expandTargetsToFixtureKeys(state: State, targets: List<CueTargetDto>): Set<String> {
    val out = LinkedHashSet<String>()
    for (target in targets) {
        when (val ref = TargetRef.ofOrNull(target.type, target.key)) {
            is TargetRef.Fixture -> out += ref.key
            is TargetRef.Group -> {
                val members = runCatching {
                    state.show.fixtures.untypedGroup(ref.key).fixtures
                        .filterIsInstance<uk.me.cormack.lighting7.fixture.Fixture>()
                }.getOrNull() ?: continue
                members.forEach { out += it.key }
            }
            null -> continue
        }
    }
    return out
}


/**
 * Load a Look's rows into the programmer as the edit buffer, and — with [includeFx] — its effects
 * as programmer-band effects, so a Look's effects can be seen and changed on stage and written back
 * by Update (`updateIncludedLook`) as its values are.
 *
 * Every row arrives with a target of its own: a Look row is always bound (sweep item B6), and a
 * row an older database left deferred never survives `loadLookSnapshot`. An effect may be deferred
 * — it runs on whatever applies the Look — so a deferred one runs on [deferredTargets], the
 * selection the operator is including onto, one instance per target as a layer would run it; with
 * no selection it is not spawned and the warning says why.
 */
internal fun includeLookIntoProgrammer(
    state: State,
    lookId: Int,
    lookUuid: java.util.UUID,
    mask: Set<PropertyMaskGroup>?,
    fadeMs: Long,
    includeFx: Boolean = true,
    deferredTargets: List<CueTargetDto> = emptyList(),
): LookIncludeOutcome {
    val values = includeExpandedIntoProgrammer(
        state, lookUuid, mask, fadeMs,
        uk.me.cormack.lighting7.fx.IncludedTarget.look(lookId, lookUuid),
    )
    if (!includeFx) return values
    val fx = spawnIncludedLookEffects(state, lookId, mask, deferredTargets)
    return values.copy(
        fixtureKeys = (values.fixtureKeys + fx.coveredFixtureKeys).distinct(),
        fxSpawned = fx.spawned,
        fxAlreadyRunning = fx.alreadyRunning,
        fxNeedTargets = fx.needTargets,
    )
}

private data class IncludedLookFx(
    val spawned: Int,
    val alreadyRunning: Int,
    val needTargets: Int,
    val coveredFixtureKeys: Set<String>,
)

/**
 * Run a Look's effects in the programmer band, each stamped with the stored effect it realises
 * ([uk.me.cormack.lighting7.fx.IncludedLookEffect]) — `spawnIncludedFx`'s rules for a cue's
 * children, applied to a Look's:
 *
 * - **Already running is skipped.** Where the Look is live — a programmer layer, a cue's layer —
 *   its own instance is producing that output, and a band copy on top would double it under any
 *   blend but Override. A second Include of the same Look finds its first one's instances and
 *   skips them for the same reason.
 * - **Masked like a value**: by the family of the property the effect drives on its target.
 */
private fun spawnIncludedLookEffects(
    state: State,
    lookId: Int,
    mask: Set<PropertyMaskGroup>?,
    deferredTargets: List<CueTargetDto>,
): IncludedLookFx {
    val stored = transaction(state.database) {
        DaoLook.findById(lookId)?.effects
            ?.sortedBy { it.sortOrder }
            ?.mapNotNull { effect -> effect.toEffectEntry()?.let { effect.id.value to it } }
    }.orEmpty()
    if (stored.isEmpty()) return IncludedLookFx(0, 0, 0, emptySet())

    val engine = state.show.fxEngine
    val running = engine.getActiveEffects()
    val spawning = ArrayList<FxInstance>()
    val covered = LinkedHashSet<String>()
    var alreadyRunning = 0
    var needTargets = 0

    for ((lookEffectId, entry) in stored) {
        val targets = entry.target?.let { listOf(CueTargetDto(it)) } ?: deferredTargets
        if (targets.isEmpty()) {
            needTargets++
            continue
        }
        val spec = entry.toEffectSpec()
        val registrationId = state.show.fxRegistry.getRegistration(spec.effectType)?.id
        for (target in targets) {
            val fxTarget = try {
                EffectSpawner.resolveTargetForCue(state, target, spec)
            } catch (e: Exception) {
                logger.warn("look {}: included fx on '{}' — target unresolvable — skipping: {}", lookId, target.key, e.message)
                null
            } ?: continue

            if (mask != null) {
                val reference = when (val ref = target.target) {
                    is TargetRef.Group -> runCatching { state.show.fixtures.untypedGroup(ref.key).fixtures.firstOrNull() }.getOrNull()
                    is TargetRef.Fixture -> runCatching { state.show.fixtures.untypedGroupableFixture(ref.key) }.getOrNull()
                }
                val group = reference?.let { maskGroupForProperty(it, fxTarget.propertyName) }
                if (!maskAllows(mask, group)) continue
            }

            val duplicate = running.any {
                it.registrationId == registrationId &&
                    it.target.targetKey == fxTarget.targetKey &&
                    it.target.propertyName == fxTarget.propertyName &&
                    (it.source?.let { s -> s.kind == LayerSourceKind.LOOK && s.id == lookId } == true ||
                        it.includedLookEffect?.lookEffectId == lookEffectId)
            }
            if (duplicate) {
                alreadyRunning++
                continue
            }

            val instance = EffectSpawner.createEffectInstanceOrNull(
                spec, fxTarget, state, "look $lookId: included fx on '${target.key}'",
            ) ?: continue
            markProgrammerOwned(instance, true)
            instance.includedLookEffect = IncludedLookEffect(
                lookId, lookEffectId, entry.isDeferred, baseline = instance.lookEffectSettings(),
            )
            spawning += instance
        }
    }

    engine.addEffects(spawning)
    for (instance in spawning) covered += engine.fixtureKeysCoveredBy(instance)
    if (spawning.isNotEmpty()) {
        logger.debug("include: spawned {} programmer-band effect(s) from look {}", spawning.size, lookId)
    }
    return IncludedLookFx(spawning.size, alreadyRunning, needTargets, covered)
}

/**
 * The shared body: expand [sourceUuid] through [uk.me.cormack.lighting7.fx.LookRegistry], write the
 * literals into the programmer, and record [includedTarget] so Update knows what it is looking at.
 *
 * Writes **[ProgrammerValue.Hard]** slots, not refs: you are editing the source's own contents, and
 * a slot referencing the thing it is about to rewrite is meaningless. Contrast Include-a-cue,
 * which does write refs — there the reference is part of what the cue means.
 *
 * Group rows expand to members, and a member the source covers directly wins over the group row,
 * matching how it resolves at compose time.
 */
internal fun includeExpandedIntoProgrammer(
    state: State,
    sourceUuid: java.util.UUID,
    mask: Set<PropertyMaskGroup>?,
    fadeMs: Long,
    includedTarget: uk.me.cormack.lighting7.fx.IncludedTarget,
): LookIncludeOutcome {
    val expanded = state.show.lookRegistry.expanded(sourceUuid)
        ?: return LookIncludeOutcome(0, emptyList(), emptyList())

    val writes = ArrayList<uk.me.cormack.lighting7.fx.ProgrammerWriter.PropertyWrite>()
    val skips = ArrayList<RecordSkip>()
    val fixtureKeys = LinkedHashSet<String>()

    // Which group each member was covered by, so the slot keeps the operator's group shape and a
    // later Record can collapse back to a group row.
    val groupHints = HashMap<Pair<String, String>, String>()
    for (entry in expanded.snapshot.rows) {
        val group = entry.target as? TargetRef.Group ?: continue
        val members = runCatching {
            state.show.fixtures.untypedGroup(group.key).fixtures
                .filterIsInstance<uk.me.cormack.lighting7.fixture.Fixture>()
        }.getOrNull() ?: continue
        for (member in members) {
            groupHints[member.key to canonicalPropertyName(entry.propertyName)] = group.key
        }
    }

    for ((fixtureKey, byProperty) in expanded.byFixture) {
        val fixture = runCatching { state.show.fixtures.untypedGroupableFixture(fixtureKey) }.getOrNull()
        if (fixture == null) {
            skips += RecordSkip(fixtureKey, reason = RecordSkipReason.MISSING_FIXTURE)
            continue
        }
        for ((propertyName, literal) in byProperty) {
            val group = uk.me.cormack.lighting7.fx.maskGroupForProperty(fixture, propertyName)
            if (group == null) {
                skips += RecordSkip(fixtureKey, propertyName, reason = RecordSkipReason.MISSING_PROPERTY)
                continue
            }
            if (!uk.me.cormack.lighting7.fx.maskAllows(mask, group)) {
                skips += RecordSkip(fixtureKey, propertyName, reason = RecordSkipReason.MASKED_OUT)
                continue
            }
            val resolvedProperty = uk.me.cormack.lighting7.fx.PropertyChannelWriter
                .resolveProperty(fixture, propertyName)
            val category = resolvedProperty?.category
                ?: uk.me.cormack.lighting7.fixture.PropertyCategory.OTHER
            val value = uk.me.cormack.lighting7.fx.CueAssignmentResolver.parseAssignmentValue(
                category, propertyName, literal,
                settingBacked = resolvedProperty?.value is uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting<*>,
            )
            if (value == null) {
                skips += RecordSkip(fixtureKey, propertyName, reason = RecordSkipReason.MISSING_PROPERTY)
                continue
            }
            writes += uk.me.cormack.lighting7.fx.ProgrammerWriter.PropertyWrite(
                fixture, propertyName, value,
                sourceGroup = groupHints[fixtureKey to propertyName],
            )
            fixtureKeys += fixtureKey
        }
    }

    if (writes.isNotEmpty()) {
        // One batched write, as Include-a-cue does: a large palette is hundreds of properties and
        // per-property publishing would visibly stutter the rig.
        state.show.fxEngine.programmer.writeProperties(
            uk.me.cormack.lighting7.fx.ProgrammerOwner.INCLUDE, writes, fadeMs = fadeMs,
        )
    }
    state.show.programmerStore.lastIncludedTarget = includedTarget

    return LookIncludeOutcome(writes.size, fixtureKeys.toList(), skips)
}

internal data class LookIncludeOutcome(
    val entriesWritten: Int,
    val fixtureKeys: List<String>,
    val skipped: List<RecordSkip>,
    /** The Look's effects now running in the programmer band. */
    val fxSpawned: Int = 0,
    /** Effects not spawned because the Look is already running them where it is live. */
    val fxAlreadyRunning: Int = 0,
    /** Deferred effects not spawned because the Include named no targets to run them on. */
    val fxNeedTargets: Int = 0,
)

/**
 * Why a zero-write Look include always says something: silently writing nothing reads as a broken
 * button. The reasons point at different places — the Look editor, the patch, the selection, where
 * the Look is live — so each is its own message rather than one hedged one. An effect left out of
 * an Include that staged something else is said too: Update writes back only what was included.
 */
internal fun lookIncludeWarnings(lookName: String, outcome: LookIncludeOutcome, sceneryIncluded: Int = 0): List<String> {
    val needTargets = outcome.fxNeedTargets.takeIf { it > 0 }?.let { n ->
        "${effectsPhrase(n)} of '$lookName' run on whatever applies it — select the fixtures to run " +
            "${if (n == 1) "it" else "them"} on and include again to edit ${if (n == 1) "it" else "them"}."
    }
    val alreadyRunning = outcome.fxAlreadyRunning.takeIf { it > 0 }?.let { n ->
        "${effectsPhrase(n)} of '$lookName' already running where it is live — release it there to edit " +
            "${if (n == 1) "that one" else "those"} here."
    }
    val staged = outcome.entriesWritten > 0 || sceneryIncluded > 0 || outcome.fxSpawned > 0
    return when {
        staged || needTargets != null || alreadyRunning != null -> listOfNotNull(needTargets, alreadyRunning)
        outcome.skipped.isNotEmpty() -> listOf(
            "None of '$lookName''s rows could be staged: the fixtures or properties they " +
                "name are not in the current patch, or the mask excluded them.",
        )
        else -> listOf("'$lookName' holds nothing to stage under this mask, or nothing at all.")
    }
}

private fun effectsPhrase(n: Int) = if (n == 1) "1 effect" else "$n effects"
