package uk.me.cormack.lighting7.ai

import uk.me.cormack.lighting7.fx.EffectDirection
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxTargetRef
import uk.me.cormack.lighting7.fixture.media.FittedMedia
import uk.me.cormack.lighting7.fixture.media.GelLibrary
import uk.me.cormack.lighting7.fixture.FixtureCommands
import uk.me.cormack.lighting7.fixture.FixtureTriggers

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fixture.group.detectCapabilities
import uk.me.cormack.lighting7.fixture.lantern.LanternLibrary
import uk.me.cormack.lighting7.fixture.lantern.effectiveKind
import uk.me.cormack.lighting7.fx.genericColourRows
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.SliderPropertyDescriptor
import uk.me.cormack.lighting7.show.beamDirection
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.venue.ShowOverrides

/**
 * The live rig, described in prose for a model: what the MCP server answers `describe_rig` with,
 * and the composition model it sends as its `instructions`.
 *
 * Every section reads the *current* project, so the text is only true for the moment it is built.
 */
class RigBriefing(private val state: State) {

    /** `, one-shot triggers=output1 (A),output2 (B)` for a fixture with one-shot triggers — what a cue's events name. */
    private fun triggerLabel(fixture: uk.me.cormack.lighting7.fixture.Fixture): String {
        val triggers = FixtureTriggers.of(fixture)
        return if (triggers.isEmpty()) "" else ", one-shot triggers=" + triggers.joinToString(",") { "${it.name} (${it.spec.label})" }
    }

    /**
     * `, cells=3 (`shehds-1.zone-1` … `shehds-1.zone-3`)` for a multi-head fixture: a cell is a
     * target of its own, by its own key, and a property only its cells have (the 24-channel Shehds'
     * colour) is set on every cell by naming the fixture. The first and last key, not every one — a
     * pixel bar has dozens, and their numbering is the rule the middle follows.
     */
    private fun cellLabel(fixture: uk.me.cormack.lighting7.fixture.Fixture): String {
        val cells = (fixture as? uk.me.cormack.lighting7.fixture.group.MultiElementFixture<*>)?.elements
        if (cells.isNullOrEmpty()) return ""
        val keys = if (cells.size <= 2) {
            cells.joinToString(", ") { "`${it.elementKey}`" }
        } else {
            "`${cells.first().elementKey}` … `${cells.last().elementKey}`"
        }
        return ", cells=${cells.size} ($keys)"
    }

    /**
     * `, commands=reset (Reset, 5.0 s, RESET),lampOn (Lamp on, 5.0 s, LAMP_ON)` for a fixture with
     * fixture commands (fixture optics plan session 7) — what `run_fixture_command` names. The role is
     * left out for a command that has none.
     */
    private fun commandLabel(fixture: uk.me.cormack.lighting7.fixture.Fixture): String {
        val commands = FixtureCommands.of(fixture)
        return if (commands.isEmpty()) "" else ", commands=" + commands.joinToString(",") { c ->
            val role = c.spec.role.takeIf { it != uk.me.cormack.lighting7.fixture.CommandRole.OTHER }?.let { ", ${it.name}" }.orEmpty()
            val pre = c.alongside.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "; sets ") { it.why }.orEmpty()
            "${c.name} (${c.spec.label}, ${c.spec.holdMs / 1000.0} s$role$pre)"
        }
    }

    /** Fixtures, groups, the effect library, what is running and parked, speed masters and the show's records. */
    fun describeRig(): String {
        val sb = StringBuilder()
        // Whose each record is (venue plan session 3): a show on a venue holds the venue's rig at
        // its pinned version and its own additions, and the setup tools refuse writes to the former.
        val owners = ownership()
        owners?.let { o ->
            sb.appendLine("## Venue")
            sb.appendLine(
                "This show sits on the venue **${o.venueName}** at v${o.version}. Every record below says whose it is: " +
                    "`owner=venue` is the venue's — its identity (name, key, type, head) is the venue's, and the setup " +
                    "tools refuse a write to it with VENUE_OWNED; a venue fixture can be hidden in this show, or one of its " +
                    "four facets — address, position, focus, media — made this show's own, with override_venue_fixtures " +
                    "(place_fixtures then edits the ones this show owns). `overrides=` names the facets this show overrides, and every " +
                    "field shown is already the overridden one — but for a `(dormant)` one, where the venue's stands; " +
                    "`(drifted)` marks one the venue has changed since. " +
                    "`owner=show` is this show's own. Keys and names are one namespace across both.",
            )
            // A show held behind its venue (session 6) composes and draws but sends zero: without this a
            // model reads every apply_cue as working while the rig stays dark.
            val hold = state.venueHold.status.value
            if (hold.held && hold.projectId == state.projectManager.currentProject.id.value) {
                sb.appendLine()
                sb.appendLine(
                    "**Output is held.** This show is on v${hold.pinned} and ${o.venueName} v${hold.installed} changes its " +
                        "patch, so every controller sends zero on every unparked channel: cues, looks and the programmer still compose and draw, but " +
                        "nothing reaches the rig until the operator updates the show to v${hold.installed} or runs it " +
                        "against v${hold.pinned} (the venue banner's *Review update* and *Run against*).",
                )
            }
            sb.appendLine()
        }
        fun ownerLabel(set: (Ownership) -> Set<String>, name: String): String =
            owners?.let { o -> if (name in set(o)) ", owner=venue" else ", owner=show" } ?: ""
        // A venue patch's facet overrides (venue plan session 5), after its owner.
        fun overridesLabel(key: String): String =
            owners?.overrides?.get(key)?.let { ", overrides=" + it.joinToString(",") } ?: ""
        // Fixtures
        sb.appendLine("## Available Fixtures")
        val infrastructureKeys = state.show.fixtures.infrastructureKeys()
        val (infrastructure, lighting) = state.show.fixtures.fixtures.partition { it.key in infrastructureKeys }
        // Head numbers are read from the patch rather than the live registry, which carries none
        // (the loader never reads them). An operator migrated from another console calls fixtures
        // by these — "head 12 at full" — so the model has to see them beside the keys it acts on.
        val headNumbers = patchHeadNumbers()
        // Each conventional's lantern (stage-view plan session 7), read from the patch as the head
        // numbers are: which unit a dimmer drives is what "the fresnel on 12" means to an operator.
        val lanterns = patchLanterns()
        // Each unit's fitted media (fixture optics plan session 3): which gel a scroller frame holds is
        // the unit's, so "frame 9" says nothing until the model can see what is loaded there.
        val media = patchMedia()
        // Each fixture's and group's colour (tables-colours plan D7), so "the red fixtures" means something.
        val (patchColours, groupColours) = rigColours()
        for (fixture in lighting) {
            val groups = state.show.fixtures.groupsForFixture(fixture.key)
            // Parenthesised deliberately: without it `+ ")"` binds inside the else branch, so the
            // closing paren went missing for every fixture that *is* in a group.
            sb.appendLine("- **${fixture.fixtureName}** (" + headLabel(headNumbers, fixture.key) +
                    "key=`${fixture.key}`, type=`${fixture.typeKey}`" +
                    (lanterns[fixture.key]?.let { ", $it" } ?: "") +
                    (media[fixture.key]?.let { ", $it" } ?: "") +
                    (patchColours[fixture.key]?.let { ", colour=$it" } ?: "") +
                    (if (groups.isNotEmpty()) ", groups=${groups.joinToString(",")}" else "") +
                    cellLabel(fixture) +
                    triggerLabel(fixture) +
                    commandLabel(fixture) +
                    ownerLabel({ it.patches }, fixture.key) +
                    overridesLabel(fixture.key) +
                    ")")
        }
        sb.appendLine()

        // Infrastructure — named, so a model reading a cue or group that includes one knows what it
        // is, but set apart so it is never the obvious target of "all the lights".
        if (infrastructure.isNotEmpty()) {
            sb.appendLine("## Infrastructure (not lighting — do not target unless the operator names it)")
            for (fixture in infrastructure) {
                sb.appendLine("- **${fixture.fixtureName}** (" + headLabel(headNumbers, fixture.key) +
                    "key=`${fixture.key}`, type=`${fixture.typeKey}`" + ownerLabel({ it.patches }, fixture.key) +
                    overridesLabel(fixture.key) + ")")
            }
            sb.appendLine()
        }

        // Groups
        sb.appendLine("## Available Groups")
        for (group in state.show.fixtures.groups) {
            val caps = group.detectCapabilities()
            sb.appendLine(
                "- **${group.name}** (${group.allMembers.size} members, capabilities=${caps.joinToString(",")}" +
                    (groupColours[group.name]?.let { ", colour=$it" } ?: "") +
                    ownerLabel({ it.groups }, group.name) + ")",
            )
        }
        sb.appendLine()

        // The stage, in a paragraph (stage-view plan session 2): enough for a model to know where
        // the riggings are and that a scene exists, without the whole document —
        // get_scene is the detail.
        stageSummary()?.let {
            sb.appendLine("## Stage")
            sb.append(it)
            sb.appendLine()
        }

        // Effect library summary
        sb.appendLine("## Effect Library (for create_look)")
        for (effect in state.show.fxRegistry.getLibrary()) {
            val params = effect.parameters.joinToString(", ") { "${it.name}:${it.type}=${it.defaultValue}" }
            sb.appendLine("- **${effect.name}** (category=${effect.category}, output=${effect.outputType}) params: $params")
        }
        // The two step effects take references rather than values (effects plan §3.6), which
        // neither the parameter type nor the category says on its own.
        sb.appendLine(
            "PaletteSteps steps through generic value palettes of one family (`palettes`: two or more " +
                "`tmpl:{uuid}`, comma-separated) — store it with that family's category (dimmer, colour, " +
                "position or beam). SlotSteps steps one setting's options by name (`slots`, e.g. " +
                "`Gobo 3,Gobo 5`) — name the setting as its propertyName.",
        )
        sb.appendLine()

        // Current state
        sb.appendLine("## Current State")
        sb.appendLine("BPM: ${state.show.fxEngine.masterClock.bpm.value} (master 1)")
        val activeEffects = state.show.fxEngine.getActiveEffects()
        if (activeEffects.isNotEmpty()) {
            sb.appendLine("Active effects: ${activeEffects.size}")
            for (effect in activeEffects.take(20)) {
                sb.appendLine("  - ${effect.effect.name} on ${briefingTargetOf(effect)}.${effect.target.propertyName}" +
                        // On Seconds (venue-visit plan D7) the division is a cycle in seconds, not beats.
                        (if (effect.onSeconds) " (seconds=" else " (beat=") +
                        "${effect.timing.beatDivision}, blend=${effect.blendMode}" +
                        effect.dynamics.let { dyn ->
                            // Effects plan D2, D5, D9 — said only where not the default.
                            (if (dyn.size != 1.0) ", size=${dyn.size}" else "") +
                                (if (dyn.direction != EffectDirection.FORWARD) ", direction=${dyn.direction}" else "") +
                                (dyn.nShot?.let { ", nShot=$it" } ?: "") +
                                // D4's shape, likewise.
                                (if (dyn.spread != 1.0) ", spread=${dyn.spread}" else "") +
                                (if (dyn.wings != 1) ", wings=${dyn.wings}" else "") +
                                (if (dyn.blocks != 1) ", blocks=${dyn.blocks}" else "") +
                                (if (dyn.groups != 0) ", groups=${dyn.groups}" else "")
                        } +
                        (effect.cueId?.let { ", cueId=$it" } ?: "") +
                        (if (effect.leaving) ", leaving" else "") + ")")
            }
        } else {
            sb.appendLine("No active effects.")
        }
        sb.appendLine()

        // Parked channels. Park overrides every layer the tools write, so a model that is not told
        // would apply a look to a parked head, see nothing change, and have no way to say why.
        // Only when something is parked: an empty heading every turn would be noise.
        val parked = parkedChannelReports(state)
        val released = releasedParkReports(state)
        if (parked.isNotEmpty() || released.isNotEmpty()) {
            sb.appendLine("## Parked Channels")
            sb.appendLine("Held at a fixed value above everything else — cues, looks, effects and the programmer do not reach them. unpark_channel releases one.")
            if (state.show.parkManager.isOnVenue) {
                sb.appendLine(
                    "This show is on a venue: a park marked (venue) is the venue's, inherited from its version. " +
                        "park_channel re-parks it at another value for this show only; unpark_channel releases it for this show " +
                        "(the venue keeps it), and unparking this show's park over a venue's brings the venue's back.",
                )
            }
            for (p in parked) {
                val drives = p.mapping?.let { " — ${it.fixtureName} (key=`${it.fixtureKey}`): ${it.description}" } ?: " — not patched"
                val whose = when {
                    p.owner == RigOwner.VENUE -> " (venue)"
                    p.venueValue != null -> " (this show's, over the venue's at ${p.venueValue})"
                    else -> ""
                }
                sb.appendLine("- universe ${p.universe}, channel ${p.channel} at ${p.value}$whose$drives")
            }
            for (r in released) {
                val drives = r.mapping?.let { " — ${it.fixtureName} (key=`${it.fixtureKey}`): ${it.description}" } ?: " — not patched"
                val venue = r.venueValue?.let { "the venue parks it at $it" } ?: "the venue no longer parks it"
                sb.appendLine("- universe ${r.universe}, channel ${r.channel}: released in this show ($venue)$drives")
            }
            sb.appendLine()
        }

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
            sb.appendLine("## Colour Palettes")
            colourTemplates.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        // Existing cues
        val cues = transaction(state.database) {
            val all = DaoCue.find { DaoCues.project eq project.id }.toList()
            val places = entrySlotsOf(all.map { it.id.value })
            all.map {
                "**${it.name}** (id=${it.id.value}, ${it.layers.count()} layers, ${it.adHocEffects.count()} ad-hoc effects) — " +
                    cuePlacesLabel(places[it.id.value].orEmpty())
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
                    val stackCues = entriesOf(stack).mapNotNull { e ->
                        e.cue?.let { "${e.cueNumber?.let { n -> "Q$n " }.orEmpty()}${it.name} (cue ${it.id.value}, entry ${e.id.value})" }
                    }
                    val activeStr = activeCueId?.let {
                        " [ACTIVE, cueId=$it, entryId=${manager.getActiveEntryId(stack.id.value)}]"
                    }.orEmpty()
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

    /** The composition model in a page: layers, templates, speed masters, stacks, the programmer. */
    fun keyConcepts(): String {
        val sb = StringBuilder()
        // Key concepts
        sb.appendLine("## Key Concepts")
        sb.appendLine("- Beat divisions are beats a cycle: 0.125, 0.25, 0.5, 1.0 (one beat), 2.0, 4.0 (one bar), 8.0 (two bars). A new effect is one bar unless asked otherwise.")
        sb.appendLine("- **Size, direction and runs**: every effect takes `size` (0–1, how much of it reaches the stage over what is underneath — 0.5 sits halfway; a movement effect's radius halves about the position underneath), `direction` (`FORWARD`, `BACKWARD` or `BOUNCE`) and `nShot` (stop after that many cycles — a flash on cue is 1; omit for forever).")
        sb.appendLine("- **The spread's shape**: an effect over several heads also takes `spread` (0–2, how much of the cycle the heads cover over the distribution order; 0.5 is a tighter ripple), `wings` (mirrored wings, not with CENTER_OUT, EDGES_IN or SPLIT, which are two wings already), `blocks` (neighbouring heads moving as one) and `groups` (the spread repeating every N heads; 0 is off). These shape a spreading `distribution` only — send one, since an omitted distribution is UNIFIED and every head runs in step.")
        sb.appendLine("- **Seconds**: an effect may run on its own cycle instead of following a master — `seconds: true`, with `beatDivision` the cycle in seconds and no `speedMasterUuid`. Use it for a slow breathe or drift that must not change speed when the tempo does.")
        sb.appendLine("- Blend modes: OVERRIDE (replace), ADDITIVE (add), MULTIPLY, MAX, MIN")
        sb.appendLine("- Distributions: LINEAR (sequential chase), UNIFIED (all same), CENTER_OUT, EDGES_IN, PING_PONG, REVERSE, SPLIT, RANDOM")
        sb.appendLine("- Colour format: hex '#FF0000', names 'red', extended '#ff0000;w128;a64;uv200', or a palette reference 'tmpl:{uuid}'.")
        sb.appendLine("- **Palette references**: a colour parameter may name a colour palette by uuid — 'tmpl:2f1c…' — instead of stating a colour. Retuning that palette moves every running effect that references it, which is the point: it is how a show keeps one set of colours in one place. Only the **generic** colour palettes listed above can be referenced; a per-fixture palette holds no single colour, and an effect palette holds no colour at all. Use them in a colourList too: 'colours' takes a comma-separated mix, e.g. 'tmpl:<warm>,tmpl:<cold>,#ff0000'. Make one with create_palette.")
        sb.appendLine("- A palette reference is legal **only in an effect parameter**. Cue values, look rows and programmer values are always literals; a cue that should follow a palette gets a *layer* applying it (see create_cue / cue layers).")
        sb.appendLine("- For group effects, use distribution=LINEAR for chases, UNIFIED for all-together")
        sb.appendLine("- **Step timing**: Controls whether beat division means per-step time or total cycle time. When stepTiming=true, each step gets one full beat-division (total cycle = beatDivision × steps). When false, the entire cycle completes in one beat-division. Each effect type sets its own default: every built-in is continuous and defaults to false; a user effect that steps (a chase) may default to true. You can override this per effect with the `stepTiming` parameter.")
        sb.appendLine("- DMX values range 0-255")
        sb.appendLine("- **Looks and layers**: A *look* is a named, reusable bundle of static values and effects. A *layer* applies one look inside a cue, at a position in the cue's stack. A look's values always name their own fixtures or groups (create_look's `values`, e.g. the front-wash group at 'pct:70'), so editing a look (update_look) moves every cue layering it, and delete_look removes one, refused while a cue layers it unless forced; list_fixture_types with properties: true names what each type can be set to. A look's *effects* name their own targets as well — a fixture, a group, or several as one effect, taken as create_cue's ad-hoc effects take them. A value you want to point at a selection is a *palette*, not a look — and so is a single named effect you want to point at a selection: a palette holds values **or** one effect, never both, so a colour plus a chase is a look and 'a slow amber breathe on the selection' is an effect palette. Create either with create_palette.")
        sb.appendLine("- **Layer order**: within a cue, later layers override earlier ones for the same fixture and property — for *every* attribute, intensity included. This is not HTP: a later dim layer really does dim. The cue's own local values always win over every layer. Per-layer blendMode (MAX/MIN/MULTIPLY/ADDITIVE) and amount (0..1) modify how a layer mixes over what is beneath it.")
        sb.appendLine("- **One limit worth knowing**: effects sit above static values regardless of layer order, because effects are a higher composition layer than values. So a later layer setting colour statically will not beat an earlier layer running a colour effect.")
        sb.appendLine("- **Cues**: A cue is an ordered stack of layers — each applying a look, or a palette by paletteId (a generic one needs layer targets) — plus its own local values (create_cue / build_cue_stack's `values`, in create_look's grammar) and ad-hoc effects. Multiple cues can run concurrently. apply_cue holds a cue loose — live above every running stack, the newest apply on top — without moving any stack; applying a cue already held loose only puts it back on top. stop_cue lets that hold go, and the cue comes off unless a running stack also holds it (stop a stack with deactivate_cue_stack). apply_cue with replaceAll=true lets every other loose cue go first; stacks are never touched. Looks are read fresh at apply time, so edits to a look are always reflected.")
        sb.appendLine("- **Speed masters**: every beat-synced effect follows exactly one, and a wall-clock effect may additionally scale its rate by one. Both are named by uuid — `speedMasterUuid` and `rateSpeedMasterUuid`, settable on a look effect, a cue's ad-hoc effect, and a cue layer (where they override whatever the layer's own effects asked for). Omitted on a beat effect means the master whose `usage` matches the effect's family when one is tagged, else master 1; omitted on a wall-clock effect means unscaled. Retune one with set_bpm, add one with create_speed_master. Reach for a second master when part of the rig should run at its own speed — a slow colour wash under a fast strobe chase — rather than fighting it with beat divisions. A master with `followNum`/`followDen` set follows another master (`followTargetUuid`, or Master 1 when absent) at that ratio: it ticks — and beats — in step with that master, its tempo is derived, set_bpm on it is refused, and the way to move it is to retune the master it follows.")
        sb.appendLine("- **Cue Stacks**: An ordered container of cues for sequential playback (theatre-style cue-to-cue). Create a stack with create_cue_stack, add cues with add_cue_to_stack (or create_cue / build_cue_stack straight into it), then run it with go_cue_stack — one GO fires whatever is on deck, and starts the stack if it is stopped. Use advance_cue_stack for BACKWARD and activate_cue_stack to jump to an entry. Stacks support looping (wraps at end). Each place in a stack is an **entry**, with its own number and its own follow (auto-advance after a delay); the cue's fade and curve are the cue's. Multiple stacks can be active simultaneously.")
        sb.appendLine("- **A cue may be shared, and delete is explicit**: one cue can sit in several stacks, or twice in one, or in none (loose — Existing Cues says `loose`, or `in:` each place). Editing the cue changes it everywhere it plays. So a tool names a place by **entry** — `entryId`, or the stack's `number` (unique in its stack; the cue id alone is ambiguous) — in activate_cue_stack, set_standby, remove_cue_from_stack, move_cue_to_stack, include_into_programmer and the prompt book's anchors; a cue's own edits name the cue. add_cue_to_stack (or a build_cue_stack row naming cueId) shares a cue into a stack; move_cue_to_stack moves one entry whole, its number, follow and prompt-book anchor with it; remove_cue_from_stack takes a place away and never deletes the cue, nor does deleting a stack. Deleting cues is delete_cues alone, refused while anything names them (entries, busk pads, cue slots, anchors, bindings) unless forced — ask the operator before forcing. copy_cues copies cues from another show, resolving their looks, palettes, masters and fixtures there.")
        sb.appendLine("- **The programmer, and how work gets saved**: the programmer is the manual overlay on top of whatever is running — what busking writes, and what set_programmer (plain values — a level, a colour, a position, a gobo; a fixed state is always a value, never an effect), apply_palette, apply_look, aim_fixtures and move_scenery write through. clear_programmer clears it, all of it or just some fixtures and groups; set_blind takes it off stage while it keeps everything, so a state can be built and recorded unseen. record_cue puts it into a cue (CREATE a new one, or MERGE / UPDATE_EXISTING / REMOVE against an existing one), record_look into a Look the same way, and either Record makes what it wrote the target of the next update_from_programmer. The round trip in the other direction is include_into_programmer, which loads a cue or look back in as an edit buffer, then update_from_programmer, which writes back **only what changed** — that is what leaves the rest of the cue, palette references included, alone — and **deletes the row of any included value you cleared since** (a clear_programmer on some fixtures after an include removes those values from the cue or look on update; preview=true does not show that). Call update_from_programmer with preview=true first if you are unsure what the programmer is sitting on top of.")
        sb.appendLine("- **One-shot triggers and cue events**: a fixture listed with one-shot triggers (a confetti cannon) spends something physical when it fires, so its triggers are never a value — no look, palette, cue row, effect or programmer value can name one, and the desk refuses it by name. A cue fires one through its **events** (create_cue / build_cue_stack's `events`, or set_cue_events): a tube and an offset after GO. Events fire on GO into their cue only — never tracked, never on GO TO a later cue, never previewed — and only while the operator has armed the desk; unarmed, they are skipped and announced. Arming and firing a tube outright are the operator's, at the desk, and no tool offers them — but the arm is the operator's consent to the show's events, so while the desk is armed a GO you make (go_cue_stack, apply_cue) fires its cue's events as a GO at the desk would.")
        sb.appendLine("- **Standby and GO**: what the next GO fires is the desk's, not the caller's — an armed standby if one is set, else the cue after the live one. set_standby arms an entry (or, naming none, disarms) without moving a light, so \"stand by cue 5\" and \"go\" stay two gestures; get_current_state's `cue_run` reports what each stack has on deck.")

        return sb.toString()
    }

    /** Whose the live rig's records are, when the current project is a show on a venue; else null. */
    private class Ownership(
        val venueName: String,
        val version: Int,
        /** The venue's records, by patch key and by name. */
        val patches: Set<String>,
        val groups: Set<String>,
        val riggings: Set<String>,
        val regions: Set<String>,
        /** Each overridden venue patch's facets by key, lower case, a drifted one marked. */
        val overrides: Map<String, List<String>> = emptyMap(),
    )

    private fun ownership(): Ownership? {
        val project = state.projectManager.currentProject
        return transaction(state.database) {
            val rig = RigScope.of(project)
            val venue = rig.venue ?: return@transaction null
            fun <T> venueNames(rows: Iterable<T>, project: (T) -> org.jetbrains.exposed.v1.core.dao.id.EntityID<Int>, name: (T) -> String) =
                rows.filter { rig.ownerOf(project(it)) == RigOwner.VENUE }.map(name).toSet()
            Ownership(
                venue.venueName,
                venue.version,
                patches = venueNames(rig.patches(), { it.project.id }, { it.key }),
                groups = venueNames(rig.groups(), { it.project.id }, { it.name }),
                riggings = venueNames(rig.riggings(), { it.project.id }, { it.name }),
                regions = venueNames(rig.stageRegions(), { it.project.id }, { it.name }),
                overrides = ShowOverrides.drift(project).filter { !it.missing && it.patchKey != null }
                    .groupBy({ it.patchKey!! }, { d ->
                        d.facet.name.lowercase() + when {
                            d.dormant -> " (dormant: names a universe or rigging the rig lacks, so the venue's stands)"
                            d.drifted -> " (drifted)"
                            else -> ""
                        }
                    }),
            )
        }
    }

    /**
     * The stage box, the regions, the riggings upstage first with their kind and trim, and a count
     * of the scene's elements and viewpoints. Null when the project has none of it.
     */
    private fun stageSummary(): String? {
        val project = state.projectManager.currentProject
        return transaction(state.database) {
            val p = DaoProject.findById(project.id) ?: return@transaction null
            val rig = RigScope.of(project)
            // A show on a venue marks the venue's regions and riggings (venue plan session 3).
            fun whose(rowProject: org.jetbrains.exposed.v1.core.dao.id.EntityID<Int>) =
                if (rig.venue != null && rig.ownerOf(rowProject) == RigOwner.VENUE) " [venue's]" else ""
            val regions = rig.stageRegions()
                .orderBy(DaoStageRegions.sortOrder to SortOrder.ASC).map { it.name + whose(it.project.id) }
            val riggings = rig.riggings().toList()
                .sortedByDescending { it.positionY ?: Double.NEGATIVE_INFINITY }
            val elements = rig.stageElements().toList()
            val viewpoints = rig.stageViewpoints()
                .orderBy(DaoStageViewpoints.sortOrder to SortOrder.ASC).map { it.name }
            // The stage's size is the rig's: a show on a venue reads the venue's (venue plan §3.2).
            val bounds = rig.stageBounds()
            val box = listOf(bounds.widthM, bounds.depthM, bounds.heightM)
            if (box.all { it == null } && regions.isEmpty() && riggings.isEmpty() && elements.isEmpty() && viewpoints.isEmpty()) {
                return@transaction null
            }
            // Locale-free, and no "−0.0" for a value that rounds to nothing.
            fun m(v: Double?): String {
                val r = Math.round((v ?: return "?") * 10) / 10.0
                return String.format(java.util.Locale.ROOT, "%.1f", if (r == 0.0) 0.0 else r).replace("-", "−")
            }
            buildString {
                appendLine("Metres, FOH-relative and Z-up: origin at the centre of the stage's downstage edge at deck level, +y upstage, +x audience right.")
                if (box.any { it != null }) appendLine("Stage box ${m(bounds.widthM)} × ${m(bounds.depthM)} × ${m(bounds.heightM)} m (width × depth × trim).")
                if (regions.isNotEmpty()) appendLine("Regions: ${regions.joinToString()}.")
                if (riggings.isNotEmpty()) {
                    appendLine(
                        "Riggings, upstage first: " + riggings.joinToString { r ->
                            val stood = if (standsOn(r.kind)) ", units stand on it" else ""
                            "${r.name}${whose(r.project.id)} (${r.kind?.lowercase()?.replace('_', ' ') ?: "rigging"}, y ${m(r.positionY)}, z ${m(r.positionZ)}$stood)"
                        } + ".",
                    )
                }
                mountMismatches(project.id)?.let { appendLine(it) }
                if (elements.isNotEmpty()) {
                    val venue = elements.count { it.layer == StageElementLayer.VENUE.name }
                    appendLine("Scene: $venue venue and ${elements.size - venue} set element(s) — get_scene lists them.")
                }
                if (viewpoints.isNotEmpty()) appendLine("Saved viewpoints: ${viewpoints.joinToString()}.")
            }
        }
    }

    /**
     * The moving heads whose base orientation disagrees with how their rigging carries them, in a
     * sentence, or null when none does. A head's mount is its base orientation — at 0 its body stands
     * up, at `basePitchDeg` 180 it hangs — and that is what the view draws and `aim_fixtures` solves
     * from, so a head hung at 180 on a ledge is drawn and aimed upside down under it (the Commemoration
     * Hall's balcony Revolutions, until stage-view plan P5 sets them to 0). Which way a body faces is
     * read exactly as the view reads it: its own up axis through yaw, pitch *and* roll
     * ([beamDirection] at pan and tilt 0, the body axis), up or down past 60°; a head on its side is
     * neither and is not flagged. Read inside the caller's transaction, after it has loaded the
     * riggings, so each patch's rigging is the entity cache's.
     */
    private fun mountMismatches(projectId: org.jetbrains.exposed.v1.core.dao.id.EntityID<Int>): String? {
        data class MountedUnit(val name: String, val rigging: DaoRigging?, val yaw: Double?, val pitch: Double?, val roll: Double?)
        val scope = RigScope.of(projectId)
        // As the rig sees them: a venue patch's Position or Focus override is where it hangs and what it is.
        val rows = scope.patches().toList()
        val heads = scope.effective(rows).filter { isMovingHead(it.fixtureTypeKey, it.kindOverride, it.lanternType) }
        if (heads.isEmpty()) return null
        val placements = scope.effectivePlacementsByPatch(heads.map { it.row })
        val units = heads.flatMap { p ->
            val name = p.displayName.ifBlank { p.key }
            listOf(MountedUnit(name, p.rigging, p.baseYawDeg, p.basePitchDeg, p.baseRollDeg)) +
                placements[p.id.value].orEmpty().map { pl ->
                    MountedUnit(
                        pl.label?.takeIf { it.isNotBlank() }?.let { "$name · $it" } ?: name,
                        pl.rigging, pl.baseYawDeg, pl.basePitchDeg, pl.baseRollDeg,
                    )
                }
        }
        val notes = units.mapNotNull { u ->
            val rig = u.rigging ?: return@mapNotNull null
            val up = beamDirection(u.yaw, u.pitch, 0.0, 0.0, u.roll).z
            val kind = rig.kind?.lowercase()?.replace('_', ' ') ?: "rigging"
            when {
                standsOn(rig.kind) && up < -UPRIGHT ->
                    "${u.name} is on ${rig.name}, which it stands on, but its basePitchDeg ${m1(u.pitch)} hangs it: it is drawn and aimed upside down under the $kind (0 stands a moving head)"
                !standsOn(rig.kind) && up > UPRIGHT ->
                    "${u.name} hangs from ${rig.name} but its basePitchDeg ${m1(u.pitch)} stands it on top (180 hangs a moving head)"
                else -> null
            }
        }
        return if (notes.isEmpty()) null else "Mounts: " + notes.joinToString("; ") + "."
    }

    /**
     * Whether the view draws this patch as a mover: its kind — its lantern's, then the patch's
     * `kindOverride`, then the type's, as the view's `bodyInputFor` reads it ([effectiveKind]) — is
     * a moving head or a scanner, or the type has a tilt axis (`archetypeFor` in the frontend's
     * `bodies/archetype.ts`). A lantern is never a mover's, so naming one takes a patch out.
     */
    private fun isMovingHead(typeKey: String, kindOverride: String?, lanternType: String?): Boolean {
        val info = FixtureTypeRegistry.typeInfoForKey(typeKey) ?: return false
        val kind = effectiveKind(typeKey, kindOverride, lanternType) ?: info.kind
        return kind == FixtureKind.MOVING_HEAD || kind == FixtureKind.SCANNER ||
            info.properties.any { it is SliderPropertyDescriptor && it.axis == "TILT" }
    }

    /**
     * Each lantern-hung patch's lantern, in a phrase, by fixture key: `lantern=…` for one unit and
     * `lanterns=[…]` for a pair, each placement by its label. A unit that names none is drawn as its
     * kind's default, and says so. Patches of a type that takes no lantern are absent.
     */
    private fun patchLanterns(): Map<String, String> {
        val project = state.projectManager.currentProject
        return transaction(state.database) {
            val rig = RigScope.of(project)
            val patches = rig.effective(rig.patches().toList())
                .filter { FixtureTypeRegistry.typeInfoForKey(it.fixtureTypeKey)?.acceptsLantern == true }
            if (patches.isEmpty()) return@transaction emptyMap()
            val placements = rig.effectivePlacementsByPatch(patches.map { it.row })
            patches.associate { p ->
                val kind = effectiveKind(p.fixtureTypeKey, p.kindOverride, p.lanternType) ?: FixtureKind.GENERIC
                fun name(own: String?, inherited: String?): String {
                    val named = LanternLibrary.byId(own ?: inherited)
                    return named?.name ?: LanternLibrary.defaultFor(kind)?.let { "${it.name} (default)" } ?: "none"
                }
                val others = placements[p.id.value].orEmpty()
                p.key to if (others.isEmpty()) {
                    "lantern=${name(p.lanternType, null)}"
                } else {
                    "lanterns=[" + (listOf(name(p.lanternType, null)) + others.map { pl ->
                        (pl.label?.takeIf { it.isNotBlank() }?.let { "$it: " } ?: "") + name(pl.lanternType, p.lanternType)
                    }).joinToString("; ") + "]"
                }
            }
        }
    }

    /**
     * Each loadable patch's fitted media, in a phrase, by fixture key: `media=…` for one unit and
     * `media=[…]` for a pair, each placement by its label with its own layered over the patch's.
     * Only the slots a unit has fitted are named — `gelScroller L201_FULL_CT_BLUE: R26 Light Red` —
     * and a unit with none says `stock`. Patches of a type with no loadable settings are absent.
     */
    private fun patchMedia(): Map<String, String> {
        val project = state.projectManager.currentProject
        return transaction(state.database) {
            val rig = RigScope.of(project)
            val patches = rig.effective(rig.patches().toList())
                .filter { FittedMedia.loadableSettings(it.fixtureTypeKey).isNotEmpty() }
            if (patches.isEmpty()) return@transaction emptyMap()
            val placements = rig.effectivePlacementsByPatch(patches.map { it.row })
            patches.associate { p ->
                val own = p.fittedMedia
                val others = placements[p.id.value].orEmpty()
                p.key to if (others.isEmpty()) {
                    "media=${describeMedia(own)}"
                } else {
                    "media=[" + (listOf(describeMedia(own)) + others.map { pl ->
                        (pl.label?.takeIf { it.isNotBlank() }?.let { "$it: " } ?: "") +
                            describeMedia(pl.fittedMedia?.over(own) ?: own)
                    }).joinToString("; ") + "]"
                }
            }
        }
    }

    private fun describeMedia(media: FittedMedia?): String {
        if (media == null || media.isEmpty) return "stock"
        return media.slots.toSortedMap().entries.joinToString(", ") { (property, options) ->
            property + " " + options.toSortedMap().entries.joinToString(", ") { (option, slot) ->
                val content = slot.gel?.let { code -> GelLibrary.byCode(code)?.let { "$code ${it.name}" } ?: code }
                    ?: slot.gobo?.let { "gobo $it" }
                    ?: "empty"
                "$option: $content"
            }
        } + "; the rest stock"
    }

    private fun m1(v: Double?): String =
        String.format(java.util.Locale.ROOT, "%.0f", v ?: 0.0).replace("-", "−")

    /** Every patched head's number in the current project, by fixture key; unnumbered heads absent. */
    private fun patchHeadNumbers(): Map<String, Int> {
        val project = state.projectManager.currentProject
        return transaction(state.database) {
            RigScope.of(project).patches()
                .mapNotNull { p -> p.headNumber?.let { p.key to it } }
                .toMap()
        }
    }

    /** Every patch's colour by key, and every group's by name, as the current project's rig draws them. */
    private fun rigColours(): Pair<Map<String, String>, Map<String, String>> {
        val project = state.projectManager.currentProject
        return transaction(state.database) {
            val rig = RigScope.of(project)
            rig.patches().mapNotNull { p -> rig.colourOf(p)?.let { p.key to it } }.toMap() to
                rig.groups().mapNotNull { g -> rig.colourOf(g)?.let { g.name to it } }.toMap()
        }
    }

    private fun headLabel(headNumbers: Map<String, Int>, key: String): String =
        headNumbers[key]?.let { "head $it, " } ?: ""
}

/** The body's up axis past 60° from level: it stands up, or hangs down. Between, it is on its side. */
private const val UPRIGHT = 0.5

/** An effect's target as the other tools name it: its key, or a selection's items in order. */
private fun briefingTargetOf(effect: FxInstance): String =
    (effect.target.targetRef as? FxTargetRef.SelectionRef)?.items
        ?.joinToString(", ", "selection(", ")") { if (it is TargetRef.Group) "group:${it.key}" else it.key }
        ?: effect.target.targetKey
