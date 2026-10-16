package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.group.detectCapabilities
import uk.me.cormack.lighting7.fixture.group.fixturesSupportProperty
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.EffectTargetRef
import uk.me.cormack.lighting7.models.LookEffectSpec
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State
import org.slf4j.LoggerFactory
import java.util.UUID
import uk.me.cormack.lighting7.show.referenceFixtureFor

/**
 * Turns a stored effect spec into a live [FxInstance] pointed at a live [FxTarget].
 *
 * Every band that puts an effect on the rig from persisted authoring data comes through here —
 * cue stacks, cue triggers, the programmer layer stack, Include, and the cue REST handlers — so
 * the two steps (what am I pointing at, and what am I running) have one implementation and one
 * set of fallbacks.
 *
 * This lived in `routes/projectCuesHelpers.kt` until sweep item E2. That made `fx/` import
 * `routes/` — the engine depending on the transport that happened to call it first — which is
 * backwards: spawning is a domain operation, and the REST handlers are just one of its callers.
 */
internal object EffectSpawner {
    private val logger = LoggerFactory.getLogger(EffectSpawner::class.java)

    fun resolveTargetForCue(
        state: State,
        target: CueTargetDto,
        effectSpec: LookEffectSpec,
    ): FxTarget? = resolveTargetForCue(state, target.effectTarget, effectSpec)

    fun resolveTargetForCue(
        state: State,
        target: EffectTargetRef,
        effectSpec: LookEffectSpec,
    ): FxTarget? {
        // A recorded row's own `propertyName` wins, and rows recorded while sweep item A11 was live
        // carry `"pan"` for a position effect. The registration's output type is what lets
        // FxTargetFactory read that as the position pair rather than a dead SliderTarget.
        val registration = state.show.fxRegistry.getRegistration(effectSpec.effectType)
        val outputType = registration?.outputType
        // Read only when the spec names no property: Palette Steps' reads its palettes.
        val drivenProperty by lazy { drivenPropertyOf(state, registration, effectSpec) }
        val single = when (target) {
            is EffectTargetRef.Selection -> {
                val propertyName = effectSpec.propertyName
                    ?: resolveEffectPropertyForFixtureInCue(effectSpec, registration, drivenProperty)
                    ?: return null
                return createSelectionTarget(state, target, propertyName, outputType)
            }
            is EffectTargetRef.Single -> target.target
        }
        return when (single) {
            is TargetRef.Group -> {
                val group = state.show.fixtures.untypedGroup(single.key)
                val propertyName = effectSpec.propertyName
                    ?: resolveEffectPropertyForCue(effectSpec, registration, group, drivenProperty)
                    ?: return null
                createGroupTargetForCue(state, single, propertyName, outputType)
            }
            is TargetRef.Fixture -> {
                val propertyName = effectSpec.propertyName
                    ?: resolveEffectPropertyForFixtureInCue(effectSpec, registration, drivenProperty)
                    ?: return null
                createFixtureTargetForCue(single.key, propertyName, state, outputType)
            }
        }
    }

    /**
     * The target for an effect over [selection] (effects plan D3). Never throws for a fixture or
     * group the selection names that has gone: the engine's expansion skips it, as a group's skips a
     * member that has.
     */
    fun createSelectionTarget(
        state: State,
        selection: EffectTargetRef.Selection,
        propertyName: String,
        outputType: FxOutputType?,
    ): FxTarget =
        FxTargetFactory.forSelection(selection, propertyName, outputType, state.show.fixtures.referenceFixtureFor(selection, propertyName))

    /**
     * The one property an effect drives where its stored category cannot say, for a spec stored
     * without a `propertyName` — an effect palette's, a Look's deferred effect: a beam built-in's
     * declared one (effects plan D6), and Palette Steps' from its palettes (§3.6), which may step
     * a strobe in an Intensity palette or a frost in a Beam one. Never a sentinel (`setting`,
     * `slider`), which names no property: Slot Steps is stored with its setting's name.
     */
    private fun drivenPropertyOf(state: State, registration: EffectRegistration?, effectSpec: LookEffectSpec): String? =
        when (registration?.id) {
            null -> null
            PALETTE_STEPS_ID -> paletteStepsShape(
                TemplateSource(state.show.templateRegistry).paletteStepsOf(effectSpec.parameters["palettes"]),
            ).getOrNull()?.property
            else -> registration.compatibleProperties.singleOrNull()?.takeUnless { it in EFFECT_PROPERTY_SENTINELS }
        }

    /**
     * A beam effect (effects plan D6) and Palette Steps (§3.6) drive one property, so a spec stored
     * without a `propertyName` — an effect palette's, a Look's deferred effect — lands on
     * [drivenProperty], on a group only where every member has it.
     */
    private fun resolveEffectPropertyForCue(
        effectSpec: LookEffectSpec,
        registration: EffectRegistration?,
        group: FixtureGroup<*>,
        drivenProperty: String?,
    ): String? {
        if (registration?.id == PALETTE_STEPS_ID) {
            return drivenProperty?.takeIf { fixturesSupportProperty(group.fixtures, it) }
        }
        val capabilities = group.detectCapabilities()
        return when (effectSpec.category) {
            "dimmer" -> if ("dimmer" in capabilities) "dimmer" else null
            "colour" -> if ("colour" in capabilities) "colour" else null
            "position" -> if ("position" in capabilities) "position" else null
            "beam" -> drivenProperty?.takeIf { fixturesSupportProperty(group.fixtures, it) }
            "controls", "setting" -> effectSpec.propertyName
            else -> null
        }
    }

    private fun resolveEffectPropertyForFixtureInCue(
        effectSpec: LookEffectSpec,
        registration: EffectRegistration?,
        drivenProperty: String?,
    ): String? {
        if (registration?.id == PALETTE_STEPS_ID) return drivenProperty
        return when (effectSpec.category) {
            "dimmer" -> "dimmer"
            "colour" -> "colour"
            "position" -> "position"
            "beam" -> drivenProperty
            "controls", "setting" -> effectSpec.propertyName
            else -> null
        }
    }

    private fun createGroupTargetForCue(
        state: State,
        group: TargetRef.Group,
        propertyName: String,
        outputType: FxOutputType?,
    ): FxTarget = FxTargetFactory.forGroup(
        group.key, propertyName, outputType,
        state.show.fixtures.referenceFixtureFor(EffectTargetRef.Single(group), propertyName) as? Fixture,
    )

    private fun createFixtureTargetForCue(
        fixtureKey: String,
        propertyName: String,
        state: State,
        outputType: FxOutputType?,
    ): FxTarget {
        // `untypedGroupableFixture`, not the register-only `untypedFixture`: a cell's key resolves
        // to its element, so the factory's fallback branch reads the cell's own catalogue.
        val fixture = try {
            state.show.fixtures.untypedGroupableFixture(fixtureKey)
        } catch (_: Exception) { null }
        return FxTargetFactory.forFixture(fixtureKey, propertyName, outputType, fixture)
    }

    /**
     * Build an [FxInstance] from a look effect definition.
     *
     * There were two of these until the positional colour list went. A cue-scoped one resolved `P1`
     * against `getCuePalette(cueId) ?: getPalette()`, and Include needed a second that took an explicit
     * snapshot instead — because the cue-scoped supplier silently fell back to the *global* list when
     * the cue wasn't live, resolving an included cue's colours against the wrong ones. A `tmpl:`
     * reference has one answer wherever it is read, so the fork had nothing left to be about.
     */
    fun createEffectInstance(
        effectSpec: LookEffectSpec,
        fxTarget: FxTarget,
        state: State,
        /** Per-cue-application override; null falls through to the effect spec's own master. */
        overrideSpeedMasterUuid: UUID? = null,
        overrideRateSpeedMasterUuid: UUID? = null,
    ): FxInstance {
        val effect = state.show.fxRegistry.createEffectWithTemplates(
            state.show.templateRegistry,
            effectSpec.effectType,
            effectSpec.parameters,
        )
        val timing = FxTiming(effectSpec.beatDivision)

        // Lenient, not strict: these fields come off a stored row, and a spec written by an older
        // build (or hand-edited) must still fire rather than take the cue down. The warn names the
        // effect type, which is as much identity as a `LookEffectSpec` carries here.
        val context = { "look effect '${effectSpec.effectType}'" }
        val blendMode = EffectSpecCoercion.Lenient.blendMode(effectSpec.blendMode, context)
        val distribution = EffectSpecCoercion.Lenient.distribution(effectSpec.distribution, context)
        val elementMode = EffectSpecCoercion.Lenient.elementMode(effectSpec.elementMode, context)
        val elementFilter = EffectSpecCoercion.Lenient.elementFilter(effectSpec.elementFilter, context)
        val direction = EffectSpecCoercion.Lenient.direction(effectSpec.direction, context)
        val size = EffectSpecCoercion.Lenient.size(effectSpec.size, context)
        val nShot = EffectSpecCoercion.Lenient.nShot(effectSpec.nShot, context)
        val shape = EffectSpecCoercion.Lenient.spreadShape(
            effectSpec.spread, effectSpec.wings, effectSpec.blocks, effectSpec.groups, context,
        )

        // Propagate timing source from the effect's registration
        val registration = state.show.fxRegistry.getRegistration(effectSpec.effectType)
        val timingSource = registration?.timingSource ?: TimingSource.BEAT

        return FxInstance(effect, fxTarget, timing, blendMode).apply {
            // The *canonical* id, not `effectSpec.effectType`: the registry resolves aliases, so two
            // stored specs can name one registration and must compare equal. See
            // [FxInstance.registrationId].
            this.registrationId = registration?.id
            phaseOffset = effectSpec.phaseOffset
            distributionStrategy = distribution
            this.elementMode = elementMode
            this.elementFilter = elementFilter
            this.timingSource = timingSource
            effectSpec.stepTiming?.let { this.stepTiming = it }
            // Size, direction and runs (effects plan D2, D5, D9) — the row's own; a fade's envelope
            // is runtime and starts at 1.
            // The spread's shape (D4) is the row's too.
            updateDynamics {
                it.copy(
                    size = size, direction = direction, nShot = nShot,
                    spread = shape.spread, wings = shape.wings, blocks = shape.blocks, groups = shape.groups,
                )
            }
            speedMasterUuid = overrideSpeedMasterUuid
                ?: speedMasterUuidOrNull(effectSpec.speedMasterUuid)
            rateSpeedMasterUuid = overrideRateSpeedMasterUuid
                ?: speedMasterUuidOrNull(effectSpec.rateSpeedMasterUuid)
            // D7, after the masters: an effect on Seconds runs its own cycle — a beat effect on the
            // wall-clock loop — so a layer's master override does not reach it. A wall-clock row that
            // merely has no rate master (every row before S15) keeps the rule it always had: a
            // layer's rate override still scales it.
            applyTimingChoice(timingSource, effectSpec.seconds)
        }
    }

    /**
     * [createEffectInstance], or null with a warn when the effect cannot be built — a stored row
     * naming an effect no longer registered — so one dead effect is skipped rather than aborting
     * the cue or include it is part of. [context] names the caller in the warn.
     */
    fun createEffectInstanceOrNull(
        effectSpec: LookEffectSpec,
        fxTarget: FxTarget,
        state: State,
        context: String,
        overrideSpeedMasterUuid: UUID? = null,
        overrideRateSpeedMasterUuid: UUID? = null,
    ): FxInstance? = try {
        createEffectInstance(effectSpec, fxTarget, state, overrideSpeedMasterUuid, overrideRateSpeedMasterUuid)
    } catch (e: Exception) {
        logger.warn("{}: effect '{}' cannot be created — skipping: {}", context, effectSpec.effectType, e.message)
        null
    }
}
