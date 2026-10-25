package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.group.detectCapabilities
import uk.me.cormack.lighting7.fixture.group.fixturesSupportAttribute
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.FxTargetsRef
import uk.me.cormack.lighting7.models.LookFxSpec
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
internal object FxSpawner {
    private val logger = LoggerFactory.getLogger(FxSpawner::class.java)

    fun resolveTargetForCue(
        state: State,
        target: CueTargetDto,
        fxSpec: LookFxSpec,
    ): FxTarget? = resolveTargetForCue(state, target.fxTarget, fxSpec)

    fun resolveTargetForCue(
        state: State,
        target: FxTargetsRef,
        fxSpec: LookFxSpec,
    ): FxTarget? {
        // A recorded row's own `attribute` wins, and rows recorded while sweep item A11 was live
        // carry `"pan"` for a position effect. The registration's output type is what lets
        // FxTargetFactory read that as the position pair rather than a dead SliderTarget.
        val registration = state.show.fxRegistry.getRegistration(fxSpec.fxType)
        val outputType = registration?.outputType
        // Read only when the spec names no property: Palette Steps' reads its palettes.
        val drivenAttribute by lazy { drivenAttributeOf(state, registration, fxSpec) }
        val single = when (target) {
            is FxTargetsRef.Selection -> {
                val attribute = fxSpec.attribute
                    ?: resolveFxAttributeForFixtureInCue(fxSpec, registration, drivenAttribute)
                    ?: return null
                return createSelectionTarget(state, target, attribute, outputType)
            }
            is FxTargetsRef.Single -> target.target
        }
        return when (single) {
            is TargetRef.Group -> {
                val group = state.show.fixtures.untypedGroup(single.key)
                val attribute = fxSpec.attribute
                    ?: resolveFxAttributeForCue(fxSpec, registration, group, drivenAttribute)
                    ?: return null
                createGroupTargetForCue(state, single, attribute, outputType)
            }
            is TargetRef.Fixture -> {
                val attribute = fxSpec.attribute
                    ?: resolveFxAttributeForFixtureInCue(fxSpec, registration, drivenAttribute)
                    ?: return null
                createFixtureTargetForCue(single.key, attribute, state, outputType)
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
        selection: FxTargetsRef.Selection,
        attribute: String,
        outputType: FxOutputType?,
    ): FxTarget =
        FxTargetFactory.forSelection(selection, attribute, outputType, state.show.fixtures.referenceFixtureFor(selection, attribute))

    /**
     * The one property an effect drives where its stored category cannot say, for a spec stored
     * without an `attribute` — an effect palette's, or any other that names none: a beam built-in's
     * declared one (effects plan D6), and Palette Steps' from its palettes (§3.6), which may step
     * a strobe in an Intensity palette or a frost in a Beam one. Never a sentinel (`setting`,
     * `slider`), which names no property: Slot Steps is stored with its setting's name.
     */
    private fun drivenAttributeOf(state: State, registration: FxRegistration?, fxSpec: LookFxSpec): String? =
        when (registration?.id) {
            null -> null
            PALETTE_STEPS_ID -> paletteStepsShape(
                PaletteSource(state.show.paletteRegistry).paletteStepsOf(fxSpec.parameters["palettes"]),
            ).getOrNull()?.attribute
            else -> registration.compatibleAttributes.singleOrNull()?.takeUnless { it in FX_ATTRIBUTE_SENTINELS }
        }

    /**
     * A beam effect (effects plan D6) and Palette Steps (§3.6) drive one property, so a spec stored
     * without an `attribute` — an effect palette's, or any other that names none — lands on
     * [drivenAttribute], on a group only where every member has it.
     */
    private fun resolveFxAttributeForCue(
        fxSpec: LookFxSpec,
        registration: FxRegistration?,
        group: FixtureGroup<*>,
        drivenAttribute: String?,
    ): String? {
        if (registration?.id == PALETTE_STEPS_ID) {
            return drivenAttribute?.takeIf { fixturesSupportAttribute(group.fixtures, it) }
        }
        val capabilities = group.detectCapabilities()
        return when (fxSpec.family) {
            "intensity" -> if ("dimmer" in capabilities) "dimmer" else null
            "colour" -> if ("colour" in capabilities) "colour" else null
            "position" -> if ("position" in capabilities) "position" else null
            "beam" -> drivenAttribute?.takeIf { fixturesSupportAttribute(group.fixtures, it) }
            "controls", "setting" -> fxSpec.attribute
            else -> null
        }
    }

    private fun resolveFxAttributeForFixtureInCue(
        fxSpec: LookFxSpec,
        registration: FxRegistration?,
        drivenAttribute: String?,
    ): String? {
        if (registration?.id == PALETTE_STEPS_ID) return drivenAttribute
        return when (fxSpec.family) {
            "intensity" -> "dimmer"
            "colour" -> "colour"
            "position" -> "position"
            "beam" -> drivenAttribute
            "controls", "setting" -> fxSpec.attribute
            else -> null
        }
    }

    private fun createGroupTargetForCue(
        state: State,
        group: TargetRef.Group,
        attribute: String,
        outputType: FxOutputType?,
    ): FxTarget = FxTargetFactory.forGroup(
        group.key, attribute, outputType,
        state.show.fixtures.referenceFixtureFor(FxTargetsRef.Single(group), attribute) as? Fixture,
    )

    private fun createFixtureTargetForCue(
        fixtureKey: String,
        attribute: String,
        state: State,
        outputType: FxOutputType?,
    ): FxTarget {
        // `untypedGroupableFixture`, not the register-only `untypedFixture`: a cell's key resolves
        // to its element, so the factory's fallback branch reads the cell's own catalogue.
        val fixture = try {
            state.show.fixtures.untypedGroupableFixture(fixtureKey)
        } catch (_: Exception) { null }
        return FxTargetFactory.forFixture(fixtureKey, attribute, outputType, fixture)
    }

    /**
     * Build an [FxInstance] from a look effect definition.
     *
     * There were two of these until the positional colour list went. A cue-scoped one resolved `P1`
     * against `getCuePalette(cueId) ?: getPalette()`, and Include needed a second that took an explicit
     * snapshot instead — because the cue-scoped supplier silently fell back to the *global* list when
     * the cue wasn't live, resolving an included cue's colours against the wrong ones. A `pal:`
     * reference has one answer wherever it is read, so the fork had nothing left to be about.
     */
    fun createFxInstance(
        fxSpec: LookFxSpec,
        fxTarget: FxTarget,
        state: State,
        /** Per-cue-application override; null falls through to the effect spec's own master. */
        overrideSpeedMasterUuid: UUID? = null,
        overrideRateSpeedMasterUuid: UUID? = null,
    ): FxInstance {
        val effect = state.show.fxRegistry.createFxWithPalettes(
            state.show.paletteRegistry,
            fxSpec.fxType,
            fxSpec.parameters,
        )
        val timing = FxTiming(fxSpec.cycle)

        // Lenient, not strict: these fields come off a stored row, and a spec written by an older
        // build (or hand-edited) must still fire rather than take the cue down. The warn names the
        // effect type, which is as much identity as a `LookFxSpec` carries here.
        val context = { "look effect '${fxSpec.fxType}'" }
        val blendMode = FxSpecCoercion.Lenient.blendMode(fxSpec.blendMode, context)
        val spreadOrder = FxSpecCoercion.Lenient.order(fxSpec.order, context)
        val elementMode = FxSpecCoercion.Lenient.elementMode(fxSpec.elementMode, context)
        val elementFilter = FxSpecCoercion.Lenient.elementFilter(fxSpec.elementFilter, context)
        val direction = FxSpecCoercion.Lenient.direction(fxSpec.direction, context)
        val amount = FxSpecCoercion.Lenient.amount(fxSpec.amount, context)
        val runs = FxSpecCoercion.Lenient.runs(fxSpec.runs, context)
        val shape = FxSpecCoercion.Lenient.spreadShape(
            fxSpec.spread, fxSpec.wings, fxSpec.segments, fxSpec.parts, context,
        )

        // Propagate timing source from the effect's registration
        val registration = state.show.fxRegistry.getRegistration(fxSpec.fxType)
        val timingSource = registration?.timingSource ?: TimingSource.BEAT

        return FxInstance(effect, fxTarget, timing, blendMode).apply {
            // The *canonical* id, not `fxSpec.fxType`: the registry resolves aliases, so two
            // stored specs can name one registration and must compare equal. See
            // [FxInstance.registrationId].
            this.registrationId = registration?.id
            phaseOffset = fxSpec.phaseOffset
            order = spreadOrder
            this.elementMode = elementMode
            this.elementFilter = elementFilter
            this.timingSource = timingSource
            fxSpec.timing?.let { t -> updateDynamics { it.copy(timing = t) } }
            // Size, direction and runs (effects plan D2, D5, D9) — the row's own; a fade's envelope
            // is runtime and starts at 1.
            // The spread's shape (D4) is the row's too.
            updateDynamics {
                it.copy(
                    amount = amount, direction = direction, runs = runs,
                    spread = shape.spread, wings = shape.wings, segments = shape.segments, parts = shape.parts,
                )
            }
            speedMasterUuid = overrideSpeedMasterUuid
                ?: speedMasterUuidOrNull(fxSpec.speedMasterUuid)
            rateSpeedMasterUuid = overrideRateSpeedMasterUuid
                ?: speedMasterUuidOrNull(fxSpec.rateSpeedMasterUuid)
            // D7, after the masters: an effect on Seconds runs its own cycle — a beat effect on the
            // wall-clock loop — so a layer's master override does not reach it. A wall-clock row that
            // merely has no rate master (every row before S15) keeps the rule it always had: a
            // layer's rate override still scales it.
            applyTimingChoice(timingSource, fxSpec.seconds)
        }
    }

    /**
     * [createFxInstance], or null with a warn when the effect cannot be built — a stored row
     * naming an effect no longer registered — so one dead effect is skipped rather than aborting
     * the cue or include it is part of. [context] names the caller in the warn.
     */
    fun createFxInstanceOrNull(
        fxSpec: LookFxSpec,
        fxTarget: FxTarget,
        state: State,
        context: String,
        overrideSpeedMasterUuid: UUID? = null,
        overrideRateSpeedMasterUuid: UUID? = null,
    ): FxInstance? = try {
        createFxInstance(fxSpec, fxTarget, state, overrideSpeedMasterUuid, overrideRateSpeedMasterUuid)
    } catch (e: Exception) {
        logger.warn("{}: effect '{}' cannot be created — skipping: {}", context, fxSpec.fxType, e.message)
        null
    }
}
