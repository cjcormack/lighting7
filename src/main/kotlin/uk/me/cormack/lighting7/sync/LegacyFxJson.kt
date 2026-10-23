package uk.me.cormack.lighting7.sync

import uk.me.cormack.lighting7.sync.dto.CueAdHocEffectJson
import uk.me.cormack.lighting7.sync.dto.LookEffectJson
import uk.me.cormack.lighting7.sync.dto.StoredFxFields
import uk.me.cormack.lighting7.sync.dto.TemplateEffectJson

// The archive homes of a stored FX, as [LegacyNames.rewriteFx] reads and writes one.

internal fun StoredFxFields.storedFx() =
    LegacyNames.StoredFx(fxType, family, cycle, order, phaseOffset, parameters, direction, runs, spread)

internal fun LookEffectJson.with(fx: LegacyNames.StoredFx) = copy(
    fxType = fx.fxType, family = fx.family, cycle = fx.cycle, order = fx.order,
    phaseOffset = fx.phaseOffset, parameters = fx.parameters, direction = fx.direction, runs = fx.runs, spread = fx.spread,
)

internal fun TemplateEffectJson.with(fx: LegacyNames.StoredFx) = copy(
    fxType = fx.fxType, family = fx.family, cycle = fx.cycle, order = fx.order,
    phaseOffset = fx.phaseOffset, parameters = fx.parameters, direction = fx.direction, runs = fx.runs, spread = fx.spread,
)

internal fun CueAdHocEffectJson.with(fx: LegacyNames.StoredFx) = copy(
    fxType = fx.fxType, family = fx.family, cycle = fx.cycle, order = fx.order,
    phaseOffset = fx.phaseOffset, parameters = fx.parameters, direction = fx.direction, runs = fx.runs, spread = fx.spread,
)
