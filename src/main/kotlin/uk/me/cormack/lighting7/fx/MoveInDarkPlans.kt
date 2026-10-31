package uk.me.cormack.lighting7.fx

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.FxTargetsRef
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.moveInDarkSettings
import uk.me.cormack.lighting7.state.State

/**
 * [stackId]'s Move in dark plan from its live entry, for [MoveInDarkService]: the stack's settings, list
 * and next read in one transaction with the apply data of the live cue and of the entries the walk may
 * reach ([MoveInDarkPlanner.candidates]), each cooked as a GO would cook it (Looks, palettes and own
 * rows, groups expanded — `buildCombinedCueLayerCook`) and its FX read for Intensity ones. Null when
 * the stack is not live or no longer exists.
 */
internal fun planMoveInDark(
    state: State,
    manager: CueStackManager,
    stackId: Int,
    heads: Map<String, HeadIntensity>,
): MoveInDarkPlanner.Plan? {
    val liveEntryId = manager.getActiveEntryId(stackId) ?: return null
    val liveCueId = manager.getActiveCueId(stackId) ?: return null
    class Loaded(val input: MoveInDarkPlanner.Input?, val labels: Map<Int, String> = emptyMap())
    val loaded = transaction(state.database) {
        val stack = DaoCueStack.findById(stackId) ?: return@transaction null
        val settings = stack.moveInDarkSettings
        if (!settings.on) return@transaction Loaded(null)
        val ordered = manager.runState.orderedCueEntries(stackId)
        val next = manager.runState.effectiveNext(stackId, ordered, stack.loop)
        val contents = HashMap<Int, MoveInDarkPlanner.CueContent>()
        lateinit var applyData: Map<Int, CueApplyData>
        val input = MoveInDarkPlanner.Input(
            settings = settings,
            ordered = ordered,
            liveEntryId = liveEntryId,
            next = next,
            loop = stack.loop,
            heads = heads,
            content = { cueId ->
                contents.getOrPut(cueId) {
                    val data = applyData[cueId] ?: return@getOrPut MoveInDarkPlanner.CueContent(emptyList())
                    cueContent(state, cueId, data, heads)
                }
            },
        )
        val candidates = MoveInDarkPlanner.candidates(input)
        applyData = buildCueApplyDataForCues(candidates.map { it.cueId }.toSet() + liveCueId)
        Loaded(input, candidates.associate { it.entryId to entryLabel(it.entryId) })
    } ?: return null
    val input = loaded.input ?: return MoveInDarkPlanner.Plan(emptyMap(), emptySet(), off = true)
    return MoveInDarkPlanner.Plan(
        moves = MoveInDarkPlanner.plan(input).mapValues { (_, move) -> move.copy(label = loaded.labels[move.targetEntryId].orEmpty()) },
        litByLive = MoveInDarkPlanner.litHeads(input.content(liveCueId), heads),
        settings = input.settings,
    )
}

/** An entry as a GO labels its cue ([cueGoLabel]). Must run inside a transaction. */
private fun entryLabel(entryId: Int): String {
    val entry = DaoCueStackEntry.findById(entryId) ?: return ""
    return entry.cue?.let { cueGoLabel(entry, it) }.orEmpty()
}

/** One cue's rows and the heads its Intensity FX drive, as the planner reads them. */
private fun cueContent(
    state: State,
    cueId: Int,
    data: CueApplyData,
    heads: Map<String, HeadIntensity>,
): MoveInDarkPlanner.CueContent {
    val cook = buildCombinedCueLayerCook(state, cueId, data, firedTimedLayerIds = emptySet(), priority = 0)
    val fxHeads = HashSet<String>()
    fun addFx(targets: FxTargetsRef?, attribute: String?, family: String) {
        targets ?: return
        for (key in headKeysOf(state, targets)) {
            val head = heads[key] ?: continue
            val intensity = if (attribute == null) family.equals("intensity", ignoreCase = true) else attribute in head.intensityAttributes
            if (intensity) fxHeads += key
        }
    }
    for ((_, fx, targets) in cook.effects) addFx(targets, fx.attribute, fx.family)
    for (fx in data.adHocFx) addFx(FxTargetsRef.ofOrNull(fx.targetType, fx.targetKey), fx.attribute, fx.family)
    return MoveInDarkPlanner.CueContent(cook.values.rows, fxHeads)
}

/** The fixture and element keys [targets] names, groups expanded to their members. */
private fun headKeysOf(state: State, targets: FxTargetsRef): List<String> = targets.items.flatMap { item ->
    when (item) {
        is TargetRef.Fixture -> listOf(item.key)
        is TargetRef.Group -> runCatching { state.show.fixtures.untypedGroup(item.key).fixtures.map { it.targetKey } }.getOrDefault(emptyList())
    }
}
