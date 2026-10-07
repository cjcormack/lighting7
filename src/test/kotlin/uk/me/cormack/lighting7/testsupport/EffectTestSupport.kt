package uk.me.cormack.lighting7.testsupport

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fx.BlendMode
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxTargetFactory
import uk.me.cormack.lighting7.fx.FxTiming
import uk.me.cormack.lighting7.fx.createEffectWithTemplates
import uk.me.cormack.lighting7.state.State

/**
 * Start an effect on the live engine the way the add routes build one — the registry's effect, the
 * target factory's target — with the provenance fields set directly, so a test can stand an
 * effect in any band (a cue's, a manual one, the programmer's own) without the route that would
 * normally own it. Group targets go through [FxTargetFactory.forGroup], as the group add route does.
 */
object EffectTestSupport {
    fun start(
        state: State,
        effectType: String,
        targetKey: String,
        propertyName: String,
        group: Boolean = false,
        programmerBand: Boolean = false,
        priority: Int = 0,
        cueId: Int? = null,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        parameters: Map<String, String> = emptyMap(),
    ): FxInstance {
        val effect = state.show.fxRegistry.createEffectWithTemplates(
            state.show.templateRegistry, effectType, parameters,
        )
        val target = if (group) {
            val first = state.show.fixtures.untypedGroup(targetKey).fixtures.firstOrNull() as? Fixture
            FxTargetFactory.forGroup(targetKey, propertyName, effect.outputType, first)
        } else {
            FxTargetFactory.forFixture(
                targetKey, propertyName, effect.outputType,
                state.show.fixtures.untypedGroupableFixture(targetKey),
            )
        }
        val instance = FxInstance(effect, target, FxTiming(0.5), blendMode).apply {
            registrationId = state.show.fxRegistry.getRegistration(effectType)?.id
            this.priority = if (programmerBand) FxEngine.PROGRAMMER_FX_PRIORITY_BASE else priority
            this.cueId = cueId
        }
        state.show.fxEngine.addEffect(instance)
        return instance
    }
}
