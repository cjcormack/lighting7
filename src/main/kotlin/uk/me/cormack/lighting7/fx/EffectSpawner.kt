package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.group.detectCapabilities
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.LookEffectSpec
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State
import java.util.UUID

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
    fun resolveTargetForCue(
        state: State,
        target: CueTargetDto,
        effectSpec: LookEffectSpec,
    ): FxTarget? {
        // A recorded row's own `propertyName` wins, and rows recorded while sweep item A11 was live
        // carry `"pan"` for a position effect. The registration's output type is what lets
        // FxTargetFactory read that as the position pair rather than a dead SliderTarget.
        val outputType = state.show.fxRegistry.getRegistration(effectSpec.effectType)?.outputType
        return when (target.target) {
            is TargetRef.Group -> {
                val group = state.show.fixtures.untypedGroup(target.key)
                val propertyName = effectSpec.propertyName
                    ?: resolveEffectPropertyForCue(effectSpec, group.detectCapabilities())
                    ?: return null
                createGroupTargetForCue(group.name, propertyName, group, outputType)
            }
            is TargetRef.Fixture -> {
                val propertyName = effectSpec.propertyName
                    ?: resolveEffectPropertyForFixtureInCue(effectSpec)
                    ?: return null
                createFixtureTargetForCue(target.key, propertyName, state, outputType)
            }
        }
    }

    private fun resolveEffectPropertyForCue(
        effectSpec: LookEffectSpec,
        capabilities: List<String>,
    ): String? {
        return when (effectSpec.category) {
            "dimmer" -> if ("dimmer" in capabilities) "dimmer" else null
            "colour" -> if ("colour" in capabilities) "colour" else null
            "position" -> if ("position" in capabilities) "position" else null
            "controls", "setting" -> effectSpec.propertyName
            else -> null
        }
    }

    private fun resolveEffectPropertyForFixtureInCue(
        effectSpec: LookEffectSpec,
    ): String? {
        return when (effectSpec.category) {
            "dimmer" -> "dimmer"
            "colour" -> "colour"
            "position" -> "position"
            "controls", "setting" -> effectSpec.propertyName
            else -> null
        }
    }

    private fun createGroupTargetForCue(
        groupName: String,
        propertyName: String,
        group: FixtureGroup<*>,
        outputType: FxOutputType?,
    ): FxTarget = FxTargetFactory.forGroup(
        groupName, propertyName, outputType, group.fixtures.firstOrNull() as? Fixture,
    )

    private fun createFixtureTargetForCue(
        fixtureKey: String,
        propertyName: String,
        state: State,
        outputType: FxOutputType?,
    ): FxTarget {
        val fixture = try {
            state.show.fixtures.untypedFixture(fixtureKey) as? Fixture
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
            speedMasterUuid = overrideSpeedMasterUuid
                ?: speedMasterUuidOrNull(effectSpec.speedMasterUuid)
            rateSpeedMasterUuid = overrideRateSpeedMasterUuid
                ?: speedMasterUuidOrNull(effectSpec.rateSpeedMasterUuid)
        }
    }
}
