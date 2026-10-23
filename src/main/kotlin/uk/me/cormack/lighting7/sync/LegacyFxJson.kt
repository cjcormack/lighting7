package uk.me.cormack.lighting7.sync

import uk.me.cormack.lighting7.sync.dto.CueAdHocEffectJson
import uk.me.cormack.lighting7.sync.dto.LookEffectJson
import uk.me.cormack.lighting7.sync.dto.StoredFxFields
import uk.me.cormack.lighting7.sync.dto.TemplateEffectJson

// The archive homes of a stored FX, as [LegacyNames.rewriteFx] reads and writes one.

internal fun StoredFxFields.storedFx() =
    LegacyNames.StoredFx(effectType, category, beatDivision, distribution, phaseOffset, parameters, direction, nShot, spread)

internal fun LookEffectJson.with(fx: LegacyNames.StoredFx) = copy(
    effectType = fx.effectType, category = fx.category, beatDivision = fx.beatDivision, distribution = fx.distribution,
    phaseOffset = fx.phaseOffset, parameters = fx.parameters, direction = fx.direction, nShot = fx.nShot, spread = fx.spread,
)

internal fun TemplateEffectJson.with(fx: LegacyNames.StoredFx) = copy(
    effectType = fx.effectType, category = fx.category, beatDivision = fx.beatDivision, distribution = fx.distribution,
    phaseOffset = fx.phaseOffset, parameters = fx.parameters, direction = fx.direction, nShot = fx.nShot, spread = fx.spread,
)

internal fun CueAdHocEffectJson.with(fx: LegacyNames.StoredFx) = copy(
    effectType = fx.effectType, category = fx.category, beatDivision = fx.beatDivision, distribution = fx.distribution,
    phaseOffset = fx.phaseOffset, parameters = fx.parameters, direction = fx.direction, nShot = fx.nShot, spread = fx.spread,
)
