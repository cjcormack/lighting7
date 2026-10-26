package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fx.FxRegistry
import uk.me.cormack.lighting7.fx.choiceProblem
import uk.me.cormack.lighting7.fx.colourListPaletteProblem
import uk.me.cormack.lighting7.sync.LegacyNames

/**
 * Why an FX of [fxType] with [parameters] (and, where the write stores one, [family]) is refused,
 * or null: an old name the importer reads ([LegacyNames.writeProblem]), or a palette in a
 * `colourList`, which takes literal colours only, or a `choice` none of whose options it is. Every
 * boundary that writes or starts an FX asks this.
 */
internal fun FxRegistry.fxWriteProblem(fxType: String, parameters: Map<String, String>, family: String? = null): String? =
    LegacyNames.writeProblem(fxType, parameters, family)
        ?: colourListPaletteProblem(fxType, parameters)
        ?: choiceProblem(fxType, parameters)
