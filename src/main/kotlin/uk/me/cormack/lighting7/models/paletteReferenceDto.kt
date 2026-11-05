package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable

/**
 * A value's palette reference as the wire reads it (palette-references plan D6): which palette, by
 * id, uuid and name, and why it no longer resolves where it does not. The resolved value travels
 * beside it, so no client resolves one.
 */
@Serializable
data class PaletteReferenceDto(
    val paletteId: Int,
    val paletteUuid: String,
    val name: String,
    /** Why the palette does not resolve here now (D3), the value kept being the last it did. */
    val unresolved: String? = null,
)
