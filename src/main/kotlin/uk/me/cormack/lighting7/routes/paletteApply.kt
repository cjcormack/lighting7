package uk.me.cormack.lighting7.routes

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.fixturesSupportAttribute
import uk.me.cormack.lighting7.fx.StoredFx
import uk.me.cormack.lighting7.fx.FxSpawner
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.ProgrammerWriter
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.AttributeFamily
import uk.me.cormack.lighting7.fx.PaletteFanOut
import uk.me.cormack.lighting7.fx.PaletteAttribute
import uk.me.cormack.lighting7.fx.PaletteResolver
import uk.me.cormack.lighting7.fx.PaletteSnapshot
import uk.me.cormack.lighting7.fx.familyOf
import uk.me.cormack.lighting7.fx.groupHintsForTargets
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.parsePaletteIntent
import uk.me.cormack.lighting7.fx.toFxSpec
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State

private val logger = LoggerFactory.getLogger("paletteApply")

/** What an apply did, or why it did nothing — the route maps the refusal to a 400 by code. */
internal sealed interface ApplyPaletteOutcome {
    data class Applied(val response: ApplyPaletteResponse) : ApplyPaletteOutcome

    /** The selection is masked to families this template's is outside (`PALETTE_OUTSIDE_MASK`). */
    data class Refused(val message: String, val code: String) : ApplyPaletteOutcome
}

/**
 * *Apply as values* (a chip's ⌥click): resolve a template against a selection and put the result into the
 * programmer, **detached** — literals for a value template, a band copy of the effect for an
 * effect template (D1's two arms, dispatched on which one the template holds).
 *
 * Three things about it are the design rather than the implementation:
 *
 *  - **Literals, not a dependency.** Retuning the template afterwards must not move what was
 *    applied. That is the whole difference between a chip's *Apply as values* (⌥click) and its tap:
 *    the first is "this colour, now", the tap adds a layer that tracks it. `Templates.dc.html` draws both.
 *  - **Resolution happens here, server-side**, through the same [PaletteResolver] the cook uses.
 *    The client could have resolved a colour itself and written channels — it already has a batch
 *    write path — but then the white/amber policy and the wheel snap would exist in TypeScript as
 *    well as Kotlin, and the editor's promise about what the rig will do would be a coincidence
 *    rather than a guarantee.
 *  - **`ProgrammerOwner.WEB`**, not `INCLUDE`. An applied template is the operator's own busked
 *    value — Record must take it and Clear must release it, exactly as if they had dragged the
 *    cell — whereas `INCLUDE` marks the edit buffer a Look or cue was loaded into. Using INCLUDE
 *    here would also overwrite `lastIncludedTarget`, so applying a colour would silently change
 *    what Update writes back to. Value arm only: an owner governs a programmer *slot*, and an
 *    effect has none — the effect arm's equivalent is the programmer's priority band, which Record
 *    and Clear read the same way.
 *  - **The selection's mask is tested before either arm** (multi-screen plan D5). A template is
 *    one family, so under a mask it either lands whole or not at all: [families] naming a set the
 *    template's family is outside is [ApplyPaletteOutcome.Refused] by name, on the apply exactly
 *    as on the pad and the button. Null is every attribute — today's press.
 *
 * [write] false is the focused-Look-layer arm (apply-model plan D7): the same resolve and fan-out,
 * answered as [ApplyPaletteResponse.values] in the Look row grammar, with nothing written and no
 * press logged. An effect palette answers nothing that way — its effect joins a Look through
 * `absorb-fx`.
 */
internal fun applyPaletteToProgrammer(
    state: State,
    projectId: Int,
    template: PaletteSnapshot,
    targets: List<CueTargetDto>,
    fadeMs: Long,
    families: Set<AttributeFamily>? = null,
    write: Boolean = true,
): ApplyPaletteOutcome {
    val family = template.familyOf()
    if (families != null && !maskAllows(families, family)) {
        return ApplyPaletteOutcome.Refused(
            paletteOutsideMaskMessage(template.name, family, families),
            CODE_PALETTE_OUTSIDE_MASK,
        )
    }
    // D1: a template holds a value *or* an effect, so the two arms are exclusive and this is the
    // only place that has to know which. The dispatch is here rather than inside the value arm
    // because the effect arm shares none of it — not the fixture expansion (it keeps group shape,
    // see [applyFxPaletteToProgrammer]), not the group hints, not the fade.
    val outcome = when {
        template.effect == null -> applyValuePaletteToProgrammer(state, template, targets, fadeMs, write)
        write -> applyFxPaletteToProgrammer(state, template.name, template.effect, targets)
        else -> ApplyPaletteResponse(written = 0)
    }

    // The apply door of the press log. Gated on the press having *done* something, which is the
    // "never on a refusal" rule applied to the one door that cannot refuse with a status code: an
    // apply onto heads that can take none of the rows returns 200 with `written = 0` and a skip per
    // head, and putting that in an eight-slot recents row would fill it with templates this rig
    // cannot use. Both arms are covered by one test, which is the point of dispatching above. A
    // `write: false` resolve wrote nothing, so it is not a press either.
    if (write && (outcome.written > 0 || outcome.fxIds.isNotEmpty())) {
        PalettePressLog.record(state, projectId, template.paletteId)
    }
    return ApplyPaletteOutcome.Applied(outcome)
}

/** The value arm of [applyPaletteToProgrammer]: resolve every row per head and write literals. */
private fun applyValuePaletteToProgrammer(
    state: State,
    template: PaletteSnapshot,
    targets: List<CueTargetDto>,
    fadeMs: Long,
    write: Boolean,
): ApplyPaletteResponse {
    val fixtureKeys = expandTargetsToFixtureKeys(state, targets)
    if (fixtureKeys.isEmpty()) return ApplyPaletteResponse(0, emptyList())

    // The group each member arrived through, so a slot keeps the operator's group shape and a later
    // Record can collapse back to a group row — the same hint Include stamps.
    val groupHints = groupHintsForTargets(state.show.fixtures, targets.map { it.target })

    val writes = ArrayList<ProgrammerWriter.AttributeWrite>()
    val skips = ArrayList<PaletteSkipDto>()
    val fittedMediaFor: (String) -> uk.me.cormack.lighting7.fixture.media.FittedMedia? = state.show.fixtures::fittedMediaFor

    val parsedRows = template.rows.mapNotNull { row ->
        parsePaletteIntent(row.value)?.let { row to it }
            ?: run {
                logger.warn("template '{}': row '{}' is not an intent — skipping", template.name, row.value)
                null
            }
    }

    // Every row paired with the heads it lands on, before anything is resolved or written. A
    // per-fixture row applies only to the head it names, *and* only if that head is in the
    // selection: applying a focus position to two of its eight heads must move those two and leave
    // the rest alone. Each selected head then fans through [PaletteFanOut] — a multi-head fixture
    // whose property lives only on its cells (the Shehds' colour, on its three zones) lands on
    // those cells, where it used to be asked of the parent and refused as "no colour". Distinct by
    // (row, head), so a selection naming a bar *and* one of its cells writes that cell once.
    val landings = ArrayList<Landing>()
    val landed = HashSet<Pair<Int, String>>()
    parsedRows.forEachIndexed { rowIndex, (row, intent) ->
        val applyTo: List<String> = when (val target = row.target) {
            is TargetRef.Fixture -> listOf(target.key).filter { it in fixtureKeys }
            else -> fixtureKeys.toList()
        }
        for (fixtureKey in applyTo) {
            val fixture: GroupableFixture? = runCatching {
                state.show.fixtures.untypedGroupableFixture(fixtureKey)
            }.getOrNull()
            if (fixture == null) {
                skips += PaletteSkipDto(fixtureKey, row.attribute, "fixture not patched")
                continue
            }
            for (head in PaletteFanOut.headsFor(fixture, row.attribute, fittedMediaFor)) {
                if (landed.add(rowIndex to head.targetKey)) {
                    landings += Landing(row.attribute, intent, head, selectedKey = fixtureKey)
                }
            }
        }
    }

    // The whole-template colour rule, computed once per landing head before anything is written: a
    // head that cannot serve every colour row serves none of them, and reports one skip rather than
    // a skip per row. An explicit amber is in the template because the hex alone did not get where
    // the operator wanted, so writing the hex without it would put a *different* colour on that head
    // under this template's name — see `PaletteResolver.unmetColourRequirement`. Asked of the head
    // the rows *land on*, so each of a fanned parent's cells answers for itself.
    val unmetColour = HashMap<String, String>()
    for ((headKey, forHead) in landings.groupBy { it.head.targetKey }) {
        val applicable = forHead.map { it.attribute to it.intent }
        when (val requirement = PaletteResolver.unmetColourRequirement(
            forHead.first().head, applicable, PaletteFanOut.mediaOf(forHead.first().head, fittedMediaFor),
        )) {
            // Reported for both arms: an apply that lands on nothing must say why, and unlike the
            // editor's panel there is no "was never a candidate" to stay quiet about — the operator
            // selected this head deliberately.
            is PaletteResolver.ColourRequirement.NotACandidate -> {
                unmetColour[headKey] = requirement.reason
                skips += PaletteSkipDto(headKey, PaletteAttribute.COLOUR.attribute, requirement.reason)
            }
            is PaletteResolver.ColourRequirement.Unmet -> {
                unmetColour[headKey] = requirement.reason
                // The row that actually failed, not `rgbColour`: a UV-only template has no colour
                // row, so naming one would report a skip against a property the template lacks.
                skips += PaletteSkipDto(headKey, requirement.attribute, requirement.reason)
            }
            PaletteResolver.ColourRequirement.Met -> Unit
        }
    }

    for (landing in landings) {
        val headKey = landing.head.targetKey
        // Already reported once for this head, above. Silent here so one refusal is one skip.
        if (headKey in unmetColour &&
            PaletteAttribute.ofOrNull(landing.attribute)?.family == AttributeFamily.COLOUR
        ) {
            continue
        }
        val resolution = PaletteResolver.resolve(
            landing.head, landing.attribute, landing.intent, PaletteFanOut.mediaOf(landing.head, fittedMediaFor),
        )
        val value = resolution.value
        if (value == null) {
            skips += PaletteSkipDto(
                headKey,
                landing.attribute,
                (resolution.note as? PaletteResolver.Note.Unsupported)?.reason ?: "unsupported",
            )
            continue
        }
        writes += ProgrammerWriter.AttributeWrite(
            landing.head,
            // The resolved name, not the row's: on a colour wheel the value lands on
            // `colourWheel` rather than `rgbColour`, and writing the row's name would address
            // nothing.
            resolution.attribute,
            value,
            // A fanned cell takes the group its parent arrived through, as a spread's cell does.
            sourceGroup = groupHints[headKey] ?: groupHints[landing.selectedKey],
        )
    }

    if (write && writes.isNotEmpty()) {
        // One batched write, as Include does: a template across a whole rig is hundreds of
        // properties and per-property publishing would visibly stutter it.
        state.show.fxEngine.programmer.writeAttributes(ProgrammerOwner.WEB, writes, fadeMs = fadeMs)
    }
    val values = writes.map {
        FanWriteDto(CueTargetDto(TargetRef.Fixture.TYPE, it.fixture.targetKey), it.attribute, it.value.serialize())
    }
    return ApplyPaletteResponse(writes.size, skips, values = values)
}

/**
 * One template row paired with a head it lands on — the selected head, or one of its cells when
 * the row's property lives only there ([PaletteFanOut]). [selectedKey] is the head the operator
 * selected, which a cell takes its group hint from.
 */
private class Landing(
    val attribute: String,
    val intent: uk.me.cormack.lighting7.fx.PaletteIntent,
    val head: GroupableFixture,
    val selectedKey: String,
)

/**
 * *Apply as values* on an **effect** template: one detached programmer-band copy of the effect per
 * target the apply names (D9).
 *
 * Four things about it are the design rather than the implementation:
 *
 *  - **No [FxInstance.source].** The copy names no template, and that is the point: it is the
 *    effect equivalent of the value arm's literals, which likewise leave no template attribution in
 *    the programmer. Stamping the template would make both Record paths lie — `captureCurrentState`
 *    forks on `source != null` and would rebuild the copy as a *tracking* template layer — and
 *    would take the copy out of *Save as template…*, which is offered only on an effect no Look or
 *    template already owns. `FX running` shows it as a plain `programmer band` row.
 *  - **No [FxInstance.programmerLayerFxKey] either**, which is what keeps it still.
 *    `ProgrammerLayerStack.syncFx` classifies only the instances in
 *    `FxEngine.programmerLayerFx()`, so no recook can retract or retime a keyless copy — a
 *    later edit of the template leaves it exactly as it was spawned.
 *  - **Per target as named, groups not expanded**, mirroring `CueComposer.fxForLayer`, which
 *    runs a palette's effect on each of the layer's targets as authored. Identical to one-per-head for a
 *    head selection; on a group selection it keeps the distribution spread, so ⌥clicking and
 *    tapping the same selection put the same thing on stage — one detached, one tracking.
 *  - **The capability check is ours to do.** `FxTargetFactory` never fails by design, and
 *    `"rgbColour"` resolves to a `ColourTarget` whether or not the head has colour, so an effect
 *    aimed at a head that cannot take it would otherwise produce no light and no report. Two
 *    halves: the head must have the property, and the effect's output type must be the one that
 *    property applies — a spec naming `dimmer` for a colour effect resolves to a `SliderTarget`
 *    that discards every frame. The value arm gets the same honesty for free from
 *    [PaletteResolver].
 *
 * `fadeMs` is not a parameter: an effect has no arrival to fade in, which is why the authoring
 * sheet hides Fade for one. Applying twice mints two copies — the apply is not a toggle, the press
 * is.
 */
private fun applyFxPaletteToProgrammer(
    state: State,
    /** The template's name, for the log lines — the snapshot itself is not otherwise read here. */
    paletteName: String,
    /** The one effect the template holds, taken from the caller's null test rather than re-tested. */
    effect: StoredFx,
    targets: List<CueTargetDto>,
): ApplyPaletteResponse {
    val spec = effect.toFxSpec()
    // Only a label for the skip rows, for the case where no target resolved and there is no
    // resolved property name to report.
    val specAttribute = spec.attribute ?: spec.family

    val instances = ArrayList<FxInstance>()
    val skips = ArrayList<PaletteSkipDto>()

    for (target in targets) {
        val members = membersOfTarget(state, target.target)
        if (members == null) {
            skips += PaletteSkipDto(target.key, specAttribute, "not patched")
            continue
        }
        // `untypedGroup` throws for a group that has gone; the fixture arm degrades quietly. Either
        // way an apply must report rather than 500.
        val fxTarget = try {
            FxSpawner.resolveTargetForCue(state, target, spec)
        } catch (e: Exception) {
            logger.warn(
                "template '{}': target '{}' unresolvable — {}", paletteName, target.key, e.message,
            )
            null
        }
        if (fxTarget == null) {
            skips += PaletteSkipDto(target.key, specAttribute, "unsupported")
            continue
        }
        if (!fixturesSupportAttribute(members, fxTarget.attribute)) {
            skips += PaletteSkipDto(target.key, fxTarget.attribute, "unsupported")
            continue
        }
        val instance = try {
            // No speed-master overrides: an effect template stamps its master at authoring time
            // (D8), and an apply carries no per-application retune the way a layer does.
            FxSpawner.createFxInstance(spec, fxTarget, state)
        } catch (e: Exception) {
            logger.warn(
                "template '{}': effect '{}' could not be created — {}",
                paletteName, effect.fxType, e.message,
            )
            skips += PaletteSkipDto(target.key, fxTarget.attribute, "effect could not be created")
            continue
        }
        if (!fxTarget.accepts(instance.effect.outputType)) {
            // The other half of "the capability check is ours to do": `FxTargetFactory` never
            // fails, so a spec whose `attribute` disagrees with its effect's output type
            // resolves to a target that discards every frame. `FxInstance`'s init warns, but the
            // apply would still report success — the FX add routes reject this up front with
            // `requireOutputTypeMatch`, and this arm reports it per target because that is the
            // shape of its answer.
            logger.warn(
                "template '{}': effect '{}' outputs {} but '{}' applies {} — skipping",
                paletteName, effect.fxType, instance.effect.outputType,
                fxTarget.attribute, fxTarget.acceptedOutputType,
            )
            skips += PaletteSkipDto(target.key, fxTarget.attribute, "unsupported")
            continue
        }
        markProgrammerOwned(instance, true)
        instances += instance
    }

    // One batched add, as the value arm makes one batched write: [FxEngine.addFxBatch] rebuilds the
    // sorted active list and broadcasts once, and returns the ids in list order.
    val fxIds = if (instances.isEmpty()) {
        emptyList()
    } else {
        state.show.fxEngine.addFxBatch(instances)
    }
    return ApplyPaletteResponse(written = 0, skipped = skips, fxIds = fxIds)
}

/**
 * The heads behind one target, for the capability check — one for a fixture, every member for a
 * group (including sub-groups, since `FixtureGroup.fixtures` reads `allMembers`).
 *
 * Null means "nothing patched under this key", which is a different skip reason from a head that is
 * patched but cannot take the effect's property.
 */
private fun membersOfTarget(state: State, target: TargetRef): List<GroupableFixture>? = runCatching {
    when (target) {
        is TargetRef.Group -> state.show.fixtures.untypedGroup(target.key).fixtures.takeIf { it.isNotEmpty() }
        is TargetRef.Fixture -> listOf(state.show.fixtures.untypedGroupableFixture(target.key))
    }
}.getOrNull()
