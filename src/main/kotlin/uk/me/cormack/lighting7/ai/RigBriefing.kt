package uk.me.cormack.lighting7.ai

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.group.detectCapabilities
import uk.me.cormack.lighting7.fx.genericColourRows
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

/**
 * The live rig, described in prose for a model: what the in-app chat puts in its system prompt
 * every turn, and what the MCP server answers `describe_rig` with and sends as its `instructions`.
 *
 * One builder for both surfaces so the two cannot drift into describing the desk differently.
 * Every section reads the *current* project, so the text is only true for the moment it is built.
 */
class RigBriefing(private val state: State) {

    /** Fixtures, groups, the effect library, what is running, speed masters and the show's records. */
    fun describeRig(): String {
        val sb = StringBuilder()
        // Fixtures
        sb.appendLine("## Available Fixtures")
        for (fixture in state.show.fixtures.fixtures) {
            val groups = state.show.fixtures.groupsForFixture(fixture.key)
            // Parenthesised deliberately: without it `+ ")"` binds inside the else branch, so the
            // closing paren went missing for every fixture that *is* in a group.
            sb.appendLine("- **${fixture.fixtureName}** (key=`${fixture.key}`, type=`${fixture.typeKey}`" +
                    (if (groups.isNotEmpty()) ", groups=${groups.joinToString(",")}" else "") +
                    ")")
        }
        sb.appendLine()

        // Groups
        sb.appendLine("## Available Groups")
        for (group in state.show.fixtures.groups) {
            val caps = group.detectCapabilities()
            sb.appendLine("- **${group.name}** (${group.allMembers.size} members, capabilities=${caps.joinToString(",")})")
        }
        sb.appendLine()

        // Effect library summary
        sb.appendLine("## Effect Library (for create_look)")
        for (effect in state.show.fxRegistry.getLibrary()) {
            val params = effect.parameters.joinToString(", ") { "${it.name}:${it.type}=${it.defaultValue}" }
            sb.appendLine("- **${effect.name}** (category=${effect.category}, output=${effect.outputType}) params: $params")
        }
        sb.appendLine()

        // Current state
        sb.appendLine("## Current State")
        sb.appendLine("BPM: ${state.show.fxEngine.masterClock.bpm.value} (master 1)")
        val activeEffects = state.show.fxEngine.getActiveEffects()
        if (activeEffects.isNotEmpty()) {
            sb.appendLine("Active effects: ${activeEffects.size}")
            for (effect in activeEffects.take(20)) {
                sb.appendLine("  - ${effect.effect.name} on ${effect.target.targetKey}.${effect.target.propertyName}" +
                        " (beat=${effect.timing.beatDivision}, blend=${effect.blendMode}" +
                        (effect.cueId?.let { ", cueId=$it" } ?: "") + ")")
            }
        } else {
            sb.appendLine("No active effects.")
        }
        sb.appendLine()

        // Speed masters, with their uuids — like the colour templates below, a uuid the model
        // cannot see is a reference it cannot make, and every effect-authoring tool takes one.
        sb.appendLine("## Speed Masters")
        sb.appendLine("Independent tempo clocks. Effects, cue layers and set_bpm name one by uuid; omitting it means master 1, the global tempo.")
        val masterStates = state.show.fxEngine.speedMasters.masterStates()
        val masterNames = masterStates.mapNotNull { m -> m.uuid?.let { it to m.name } }.toMap()
        for (master in masterStates) {
            val uuid = master.uuid?.let { "`$it`" } ?: "(no uuid yet — omit the reference)"
            // Usage and follow ride the listing so the model can act on the prose above: a
            // follower named here without its ratio would invite a set_bpm the bank refuses.
            val usage = master.usage?.let { ", usage: $it" } ?: ""
            val follow = master.followNum?.let {
                val leader = master.followTargetUuid?.let { t -> masterNames[t] } ?: "Master 1"
                ", follows $leader at $it/${master.followDen} — tempo derived, set_bpm refused"
            } ?: ""

            sb.appendLine(
                "- **${master.name}** — $uuid (index ${master.index}, ${master.bpm} BPM" +
                    usage + follow + (if (master.isRunning) "" else ", stopped") + ")"
            )
        }
        sb.appendLine()

        // Existing looks
        val project = state.projectManager.currentProject
        val looks = transaction(state.database) {
            DaoLook.find { DaoLooks.project eq project.id }
                .map { look ->
                    // No binding note any more: a Look's rows are always bound to their own targets
                    // (session 3 moved the deferred half out to templates), so saying so per Look
                    // would restate the type rather than describe the record.
                    "${look.name} (id=${look.id.value}, ${look.rows.count()} rows, " +
                        "${look.effects.count()} effects)"
                }
        }
        if (looks.isNotEmpty()) {
            sb.appendLine("## Existing Looks")
            looks.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        // Colour templates, with their uuids — the only thing a colour parameter can reference, so
        // the uuid has to be in the prompt or `tmpl:` is unusable.
        //
        // Through [genericColourRows], which is the one place that rule lives. This was an
        // independent `singleOrNull` copy of "exactly one row" — so once a colour template could
        // hold a hex *and* an explicit amber, the desk resolved such a reference perfectly while the
        // AI was never told the template existed and could not offer it.
        //
        // An **effect** template is still excluded (D12), now by name rather than by the accident of
        // holding no rows: it is not a colour, so the reference would resolve to nothing and a
        // running effect's colour would silently fall back to white.
        val colourTemplates = transaction(state.database) {
            DaoTemplate.find { DaoTemplates.project eq project.id }.mapNotNull { template ->
                val colour = genericColourRows(
                    template.rows.toList(), { it.propertyName }, { it.isDeferred },
                ) ?: return@mapNotNull null
                "${template.name} — `tmpl:${template.uuid}` (${colour.joinToString(" ") { it.value }})"
            }
        }
        if (colourTemplates.isNotEmpty()) {
            sb.appendLine("## Colour Templates")
            colourTemplates.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        // Existing cues
        val cues = transaction(state.database) {
            DaoCue.find { DaoCues.project eq project.id }
                .map {
                    val stackInfo = it.cueStack?.let { s -> " [stack: ${s.name}]" } ?: ""
                    "**${it.name}** (id=${it.id.value}, ${it.layers.count()} layers, ${it.adHocEffects.count()} ad-hoc effects)$stackInfo"
                }
        }
        if (cues.isNotEmpty()) {
            sb.appendLine("## Existing Cues")
            cues.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        // Existing cue stacks
        val manager = state.show.cueStackManager
        val stacks = transaction(state.database) {
            DaoCueStack.find { DaoCueStacks.project eq project.id }
                .orderBy(DaoCueStacks.name to SortOrder.ASC)
                .map { stack ->
                    val activeCueId = manager.getActiveCueId(stack.id.value)
                    val stackCues = DaoCue.find { DaoCues.cueStack eq stack.id }
                        .orderBy(DaoCues.sortOrder to SortOrder.ASC)
                        .map { "${it.name} (id=${it.id.value})" }
                    val activeStr = if (activeCueId != null) " [ACTIVE, cueId=$activeCueId]" else ""
                    "**${stack.name}** (id=${stack.id.value}, ${stackCues.size} cues, loop=${stack.loop})$activeStr → ${stackCues.joinToString(" → ").ifEmpty { "(empty)" }}"
                }
        }
        if (stacks.isNotEmpty()) {
            sb.appendLine("## Existing Cue Stacks")
            stacks.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * The per-type property listing a Kotlin script needs. The chat's alone: the MCP surface has no
     * script tool, so this would only teach a model an API it cannot call.
     */
    fun fixtureTypeApi(): String {
        val sb = StringBuilder()
        // Fixture type API (for scripts)
        sb.appendLine("## Fixture Type API (for run_lighting_script)")
        sb.appendLine("When writing scripts, use `fixture<TypeName>(\"key\")` to access fixtures.")
        val fixturesByType = state.show.fixtures.fixtures.groupBy { it::class }
        for ((klass, fixtures) in fixturesByType) {
            val sample = fixtures.first()
            val typeName = klass.simpleName ?: continue
            sb.appendLine("### $typeName")
            sb.appendLine("Keys: ${fixtures.joinToString(", ") { "`${it.key}`" }}")
            sb.appendLine("Properties:")
            for (prop in sample.fixtureProperties) {
                val propValue = prop.classProperty.call(sample)
                val propType = propValue?.javaClass?.simpleName ?: "Unknown"
                sb.appendLine("  - `${prop.name}` ($propType, category=${prop.category})")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * The composition model in a page: layers, templates, speed masters, stacks, the programmer.
     * [scriptTool] says whether the surface offers `run_lighting_script`, which one sentence names.
     */
    fun keyConcepts(scriptTool: Boolean): String {
        val sb = StringBuilder()
        // Key concepts
        sb.appendLine("## Key Concepts")
        sb.appendLine("- Beat divisions: 0.125 (1/32), 0.25 (16th), 0.5 (8th), 1.0 (quarter), 2.0 (half), 4.0 (1 bar), 8.0 (2 bars)")
        sb.appendLine("- Blend modes: OVERRIDE (replace), ADDITIVE (add), MULTIPLY, MAX, MIN")
        sb.appendLine("- Distributions: LINEAR (sequential chase), UNIFIED (all same), CENTER_OUT, EDGES_IN, PING_PONG, REVERSE, SPLIT, RANDOM")
        sb.appendLine("- Colour format: hex '#FF0000', names 'red', extended '#ff0000;w128;a64;uv200', or a template reference 'tmpl:{uuid}'.")
        sb.appendLine("- **Template references**: a colour parameter may name a colour template by uuid — 'tmpl:2f1c…' — instead of stating a colour. Retuning that template moves every running effect that references it, which is the point: it is how a show keeps one set of colours in one place. Only the **generic** colour templates listed above can be referenced; a per-fixture template holds no single colour, and an effect template holds no colour at all. Use them in a colourList too: 'colours' takes a comma-separated mix, e.g. 'tmpl:<warm>,tmpl:<cold>,#ff0000'. Make one with create_template.")
        sb.appendLine("- A template reference is legal **only in an effect parameter**. Cue values, look rows and programmer values are always literals; a cue that should follow a template gets a *layer* applying it (see create_cue / cue layers).")
        sb.appendLine("- For group effects, use distribution=LINEAR for chases, UNIFIED for all-together")
        sb.appendLine("- **Step timing**: Controls whether beat division means per-step time or total cycle time. When stepTiming=true, each step gets one full beat-division (total cycle = beatDivision × steps). When false, the entire cycle completes in one beat-division. Static effects default to stepTiming=true (chase), continuous effects default to false. You can override this per effect with the `stepTiming` parameter.")
        sb.appendLine("- UByte values range 0-255 (use 'u' suffix in scripts: 128u)")
        sb.appendLine("- **Looks and layers**: A *look* is a named, reusable bundle of static values and effects. A *layer* applies one look inside a cue, at a position in the cue's stack. A look's rows always name their own fixtures, so editing a look moves every cue layering it. A look's *effects* may instead be **deferred** — they name no target and fan over whatever the layer points at, so the same effect bundle can be aimed at different fixtures. A value you want to point at a selection is a *template*, not a look — and so is a single named effect you want to point at a selection: a template holds values **or** one effect, never both, so a colour plus a chase is a look and 'a slow amber breathe on the selection' is an effect template. Create either with create_template.")
        sb.appendLine("- **Layer order**: within a cue, later layers override earlier ones for the same fixture and property — for *every* attribute, intensity included. This is not HTP: a later dim layer really does dim. The cue's own local values always win over every layer. Per-layer blendMode (MAX/MIN/MULTIPLY/ADDITIVE) and amount (0..1) modify how a layer mixes over what is beneath it.")
        sb.appendLine("- **One limit worth knowing**: effects sit above static values regardless of layer order, because effects are a higher composition layer than values. So a later layer setting colour statically will not beat an earlier layer running a colour effect.")
        sb.appendLine("- **Cues**: A cue is an ordered stack of look layers plus its own local values and ad-hoc effects. Multiple cues can run concurrently — applying a cue adds it alongside existing cues. Re-applying the same cue refreshes it. Use stop_cue to stop one cue, or apply_cue with replaceAll=true to stop all others first. Looks are read fresh at apply time, so edits to a look are always reflected.")
        sb.appendLine("- **Speed masters**: every beat-synced effect follows exactly one, and a wall-clock effect may additionally scale its rate by one. Both are named by uuid — `speedMasterUuid` and `rateSpeedMasterUuid`, settable on a look effect, a cue's ad-hoc effect, and a cue layer (where they override whatever the layer's own effects asked for). Omitted means master 1 / unscaled. Retune one with set_bpm, add one with create_speed_master. Reach for a second master when part of the rig should run at its own speed — a slow colour wash under a fast strobe chase — rather than fighting it with beat divisions. A master with `followNum`/`followDen` set follows another master (`followTargetUuid`, or Master 1 when absent) at that ratio: it ticks — and beats — in step with that master, its tempo is derived, set_bpm on it is refused, and the way to move it is to retune the master it follows.")
        sb.appendLine("- **Cue Stacks**: An ordered container of cues for sequential playback (theatre-style cue-to-cue). Create a stack with create_cue_stack, add cues with add_cue_to_stack, then run it with go_cue_stack — one GO fires whatever is on deck, and starts the stack if it is stopped. Use advance_cue_stack for BACKWARD and activate_cue_stack to jump to a named cue. Stacks support looping (wraps at end). Individual cues within a stack can have: auto-advance (timed transition to next cue, configured per-cue via autoAdvance + autoAdvanceDelayMs), crossfade (intensity envelope between cue transitions, configured per-cue via fadeDurationMs + fadeCurve). Multiple stacks can be active simultaneously.")
        sb.appendLine("- **The programmer, and how work gets saved**: the programmer is the manual overlay on top of whatever is running — what busking writes, and what apply_look${if (scriptTool) " and run_lighting_script" else ""} write${if (scriptTool) "" else "s"} through. record_cue puts it into a cue (CREATE a new one, or MERGE / UPDATE_EXISTING / REMOVE against an existing one). The round trip in the other direction is include_into_programmer, which loads a cue or look back in as an edit buffer, then update_from_programmer, which writes back **only what changed** — that is what leaves the rest of the cue, template references included, alone. Call update_from_programmer with preview=true first if you are unsure what the programmer is sitting on top of.")
        sb.appendLine("- **Standby and GO**: what the next GO fires is the desk's, not the caller's — an armed standby if one is set, else the cue after the live one. set_standby arms (or, with no cueId, disarms) it without moving a light, so \"stand by cue 5\" and \"go\" stay two gestures; get_current_state's `cue_run` reports what each stack has on deck.")

        return sb.toString()
    }
}
