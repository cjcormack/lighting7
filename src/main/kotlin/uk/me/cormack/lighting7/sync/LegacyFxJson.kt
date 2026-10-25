package uk.me.cormack.lighting7.sync

import uk.me.cormack.lighting7.sync.dto.CueAdHocFxJson
import uk.me.cormack.lighting7.sync.dto.LookFxJson
import uk.me.cormack.lighting7.sync.dto.StoredFxFields
import uk.me.cormack.lighting7.sync.dto.PaletteFxJson

// The archive homes of a stored FX, as [LegacyNames.rewriteFx] reads and writes one.

internal fun StoredFxFields.storedFx() =
    LegacyNames.StoredFx(fxType, family, cycle, order, phaseOffset, parameters, direction, runs, spread)

internal fun LookFxJson.with(fx: LegacyNames.StoredFx) = copy(
    fxType = fx.fxType, family = fx.family, cycle = fx.cycle, order = fx.order,
    phaseOffset = fx.phaseOffset, parameters = fx.parameters, direction = fx.direction, runs = fx.runs, spread = fx.spread,
)

internal fun PaletteFxJson.with(fx: LegacyNames.StoredFx) = copy(
    fxType = fx.fxType, family = fx.family, cycle = fx.cycle, order = fx.order,
    phaseOffset = fx.phaseOffset, parameters = fx.parameters, direction = fx.direction, runs = fx.runs, spread = fx.spread,
)

internal fun CueAdHocFxJson.with(fx: LegacyNames.StoredFx) = copy(
    fxType = fx.fxType, family = fx.family, cycle = fx.cycle, order = fx.order,
    phaseOffset = fx.phaseOffset, parameters = fx.parameters, direction = fx.direction, runs = fx.runs, spread = fx.spread,
)
