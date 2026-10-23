package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fx.FxRegistry
import uk.me.cormack.lighting7.fx.colourListPaletteProblem
import uk.me.cormack.lighting7.sync.LegacyNames

/**
 * Why an FX of [effectType] with [parameters] (and, where the write stores one, [family]) is refused,
 * or null: an old name the importer reads ([LegacyNames.writeProblem]), or a palette in a
 * `colourList`, which takes literal colours only. Every boundary that writes or starts an FX asks this.
 */
internal fun FxRegistry.fxWriteProblem(effectType: String, parameters: Map<String, String>, family: String? = null): String? =
    LegacyNames.writeProblem(effectType, parameters, family) ?: colourListPaletteProblem(effectType, parameters)
