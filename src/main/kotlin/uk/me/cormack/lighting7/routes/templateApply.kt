package uk.me.cormack.lighting7.routes

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.fixturesSupportProperty
import uk.me.cormack.lighting7.fx.EffectEntry
import uk.me.cormack.lighting7.fx.EffectSpawner
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.ProgrammerWriter
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.TemplateResolver
import uk.me.cormack.lighting7.fx.TemplateSnapshot
import uk.me.cormack.lighting7.fx.parseTemplateIntent
import uk.me.cormack.lighting7.fx.toEffectSpec
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State

private val logger = LoggerFactory.getLogger("templateApply")

/**
 * The plain-click gesture: resolve a template against a selection and put the result into the
 * programmer, **detached** — literals for a value template, a band copy of the effect for an
 * effect template (D1's two arms, dispatched on which one the template holds).
 *
 * Three things about it are the design rather than the implementation:
 *
 *  - **Literals, not a dependency.** Retuning the template afterwards must not move what was
 *    applied. That is the whole difference between clicking a template chip and ⌥clicking it: click
 *    is "this colour, now", ⌥click adds a layer that tracks it. `Templates.dc.html` draws both.
 *  - **Resolution happens here, server-side**, through the same [TemplateResolver] the cook uses.
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
 */
internal fun applyTemplateToProgrammer(
    state: State,
    template: TemplateSnapshot,
    targets: List<CueTargetDto>,
    fadeMs: Long,
): ApplyTemplateResponse {
    // D1: a template holds a value *or* an effect, so the two arms are exclusive and this is the
    // only place that has to know which. Ahead of the row loop rather than beside it, because the
    // effect arm shares none of it — not the fixture expansion (it keeps group shape, see
    // [applyEffectTemplateToProgrammer]), not the group hints, not the fade.
    template.effect?.let { return applyEffectTemplateToProgrammer(state, template.name, it, targets) }

    val fixtureKeys = expandTargetsToFixtureKeys(state, targets)
    if (fixtureKeys.isEmpty()) return ApplyTemplateResponse(0, emptyList())

    // The group each member arrived through, so a slot keeps the operator's group shape and a later
    // Record can collapse back to a group row — the same hint Include stamps.
    val groupHints = groupHintsForTargets(state.show.fixtures, targets.map { it.target })

    val writes = ArrayList<ProgrammerWriter.PropertyWrite>()
    val skips = ArrayList<TemplateSkipDto>()

    for (row in template.rows) {
        val intent = parseTemplateIntent(row.value)
        if (intent == null) {
            logger.warn("template '{}': row '{}' is not an intent — skipping", template.name, row.value)
            continue
        }
        // A per-fixture row applies only to the head it names, *and* only if that head is in the
        // selection: applying a focus position to two of its eight heads must move those two and
        // leave the rest alone.
        val applyTo: List<String> = when (val target = row.target) {
            is TargetRef.Fixture -> listOf(target.key).filter { it in fixtureKeys }
            else -> fixtureKeys.toList()
        }
        for (fixtureKey in applyTo) {
            val fixture: GroupableFixture? = runCatching {
                state.show.fixtures.untypedGroupableFixture(fixtureKey)
            }.getOrNull()
            if (fixture == null) {
                skips += TemplateSkipDto(fixtureKey, row.propertyName, "fixture not patched")
                continue
            }
            val resolution = TemplateResolver.resolve(fixture, row.propertyName, intent)
            val value = resolution.value
            if (value == null) {
                skips += TemplateSkipDto(
                    fixtureKey,
                    row.propertyName,
                    (resolution.note as? TemplateResolver.Note.Unsupported)?.reason ?: "unsupported",
                )
                continue
            }
            writes += ProgrammerWriter.PropertyWrite(
                fixture,
                // The resolved name, not the row's: on a colour wheel the value lands on `colour`
                // rather than `rgbColour`, and writing the row's name would address nothing.
                resolution.propertyName,
                value,
                sourceGroup = groupHints[fixtureKey],
            )
        }
    }

    if (writes.isNotEmpty()) {
        // One batched write, as Include does: a template across a whole rig is hundreds of
        // properties and per-property publishing would visibly stutter it.
        state.show.fxEngine.programmer.writeProperties(ProgrammerOwner.WEB, writes, fadeMs = fadeMs)
    }
    return ApplyTemplateResponse(writes.size, skips)
}

/**
 * The click gesture on an **effect** template: one detached programmer-band copy of the effect per
 * target the click names (D9).
 *
 * Four things about it are the design rather than the implementation:
 *
 *  - **No [FxInstance.source].** The copy names no template, and that is the point: it is the
 *    effect equivalent of the value arm's literals, which likewise leave no template attribution in
 *    the programmer. Stamping the template would make both Record paths lie — `captureCurrentState`
 *    forks on `source != null` and would rebuild the copy as a *tracking* template layer — and
 *    would take the copy out of *Save as template…*, which is offered only on an effect no Look or
 *    template already owns. `FX running` shows it as a plain `programmer band` row.
 *  - **No [FxInstance.programmerLayerEffectKey] either**, which is what keeps it still.
 *    `ProgrammerLayerStack.syncEffects` classifies only the instances in
 *    `FxEngine.programmerLayerEffects()`, so no recook can retract or retime a keyless copy — a
 *    later edit of the template leaves it exactly as it was spawned.
 *  - **Per target as named, groups not expanded**, mirroring `CueComposer.effectsForLayer`, which
 *    fans a deferred effect over the layer's targets as authored. Identical to one-per-head for a
 *    head selection; on a group selection it keeps the distribution spread, so clicking and
 *    ⌥clicking the same selection put the same thing on stage — one detached, one tracking.
 *  - **The capability check is ours to do.** `FxTargetFactory` never fails by design, and
 *    `"rgbColour"` resolves to a `ColourTarget` whether or not the head has colour, so an effect
 *    aimed at a head that cannot take it would otherwise produce no light and no report. Two
 *    halves: the head must have the property, and the effect's output type must be the one that
 *    property applies — a spec naming `dimmer` for a colour effect resolves to a `SliderTarget`
 *    that discards every frame. The value arm gets the same honesty for free from
 *    [TemplateResolver].
 *
 * `fadeMs` is not a parameter: an effect has no arrival to fade in, which is why the authoring
 * sheet hides Fade for one. Clicking twice mints two copies — the click is not a toggle, the busk
 * pad is.
 */
private fun applyEffectTemplateToProgrammer(
    state: State,
    /** The template's name, for the log lines — the snapshot itself is not otherwise read here. */
    templateName: String,
    /** The one effect the template holds, taken from the caller's null test rather than re-tested. */
    effect: EffectEntry,
    targets: List<CueTargetDto>,
): ApplyTemplateResponse {
    val spec = effect.toEffectSpec()
    // Only a label for the skip rows, for the case where no target resolved and there is no
    // resolved property name to report.
    val specProperty = spec.propertyName ?: spec.category

    val instances = ArrayList<FxInstance>()
    val skips = ArrayList<TemplateSkipDto>()

    for (target in targets) {
        val members = membersOfTarget(state, target.target)
        if (members == null) {
            skips += TemplateSkipDto(target.key, specProperty, "not patched")
            continue
        }
        // `untypedGroup` throws for a group that has gone; the fixture arm degrades quietly. Either
        // way a click must report rather than 500.
        val fxTarget = try {
            EffectSpawner.resolveTargetForCue(state, target, spec)
        } catch (e: Exception) {
            logger.warn(
                "template '{}': target '{}' unresolvable — {}", templateName, target.key, e.message,
            )
            null
        }
        if (fxTarget == null) {
            skips += TemplateSkipDto(target.key, specProperty, "unsupported")
            continue
        }
        if (!fixturesSupportProperty(members, fxTarget.propertyName)) {
            skips += TemplateSkipDto(target.key, fxTarget.propertyName, "unsupported")
            continue
        }
        val instance = try {
            // No speed-master overrides: an effect template stamps its master at authoring time
            // (D8), and a click carries no per-application retune the way a layer does.
            EffectSpawner.createEffectInstance(spec, fxTarget, state)
        } catch (e: Exception) {
            logger.warn(
                "template '{}': effect '{}' could not be created — {}",
                templateName, effect.effectType, e.message,
            )
            skips += TemplateSkipDto(target.key, fxTarget.propertyName, "effect could not be created")
            continue
        }
        if (instance.effect.outputType != fxTarget.acceptedOutputType) {
            // The other half of "the capability check is ours to do": `FxTargetFactory` never
            // fails, so a spec whose `propertyName` disagrees with its effect's output type
            // resolves to a target that discards every frame. `FxInstance`'s init warns, but the
            // click would still report success — the FX add routes reject this up front with
            // `requireOutputTypeMatch`, and this arm reports it per target because that is the
            // shape of its answer.
            logger.warn(
                "template '{}': effect '{}' outputs {} but '{}' applies {} — skipping",
                templateName, effect.effectType, instance.effect.outputType,
                fxTarget.propertyName, fxTarget.acceptedOutputType,
            )
            skips += TemplateSkipDto(target.key, fxTarget.propertyName, "unsupported")
            continue
        }
        markProgrammerOwned(instance, true)
        instances += instance
    }

    // One batched add, as the value arm makes one batched write: [FxEngine.addEffects] rebuilds the
    // sorted active list and broadcasts once, and returns the ids in list order.
    val effectIds = if (instances.isEmpty()) {
        emptyList()
    } else {
        state.show.fxEngine.addEffects(instances)
    }
    return ApplyTemplateResponse(written = 0, skipped = skips, effectIds = effectIds)
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
