package uk.me.cormack.lighting7.scripts

/**
 * Determines the compilation template and evaluation context for a script.
 *
 * Each type provides a different base class with a focused API surface:
 * - [GENERAL] — full-power scripts with DMX, fixtures, FX, scenes, coroutines
 * - [FX_DEFINITION] — scripts that define custom effect types via [registerEffect]
 * - [FX_APPLICATION] — scripts that apply effects to fixtures/groups with implicit engine
 * - [FX_CALC] — FX calculate body (phase, context, params → FxOutput)
 * - [FX_CALC_STATEFUL] — FX stateful calculate body (tick, deltaMs, context, params, state → FxOutput)
 * - [FX_CALC_COMPOSITE] — FX composite calculate body (phase, context, params → Map)
 */
enum class ScriptType {
    /** Full-power script using [LightingScript] base class. */
    GENERAL,

    /** Effect definition script using [FxDefinitionScript] base class. */
    FX_DEFINITION,

    /** Effect application script using [FxApplicationScript] base class. */
    FX_APPLICATION,

    /** FX calculate body using [FxCalcScript] base class. */
    FX_CALC,

    /** FX stateful calculate body using [FxStatefulCalcScript] base class. */
    FX_CALC_STATEFUL,

    /** FX composite calculate body using [FxCompositeCalcScript] base class. */
    FX_CALC_COMPOSITE,
}
