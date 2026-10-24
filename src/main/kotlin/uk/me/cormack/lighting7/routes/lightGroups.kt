package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fixture.TriggerIndex
import uk.me.cormack.lighting7.models.Timing
import uk.me.cormack.lighting7.fixture.TriggerNotStorableException
import uk.me.cormack.lighting7.fixture.CommandIndex
import uk.me.cormack.lighting7.fixture.CommandNotStorableException

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.*
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import uk.me.cormack.lighting7.fx.group.clearFx
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.state.State

/**
 * REST API routes for fixture group management and effects.
 */
internal fun Route.routeApiRestGroups(state: State) {
    route("/groups") {
        // List all groups
        get<GroupsResource> {
            val currentProject = state.projectManager.currentProject
            val colours = groupColoursByName(state, currentProject.id.value)

            val groups = state.show.fixtures.groups.map { group -> group.toGroupSummaryDto(colours[group.name]) }
            call.respond(groups)
        }

        // Get a specific group
        get<GroupResource> { resource ->
            try {
                val group = state.show.fixtures.untypedGroup(resource.name)
                call.respond(group.toDetailedDto())
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Group not found"))
            }
        }

        // Get group properties (aggregated property descriptors for all members)
        get<GroupAttributesResource> { resource ->
            try {
                val group = state.show.fixtures.untypedGroup(resource.name)
                val attributes = group.generateGroupAttributeDescriptors()
                call.respond(attributes)
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Group not found"))
            }
        }

        // Apply effect to a group
        post<GroupFxResource> { resource ->
            val request = call.receive<AddGroupFxRequest>()
            try {
                val group = state.show.fixtures.untypedGroup(resource.name)
                val fxId = applyGroupFx(state, group, request)
                call.respond(AddGroupFxResponse(fxId))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Group not found"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to apply effect"))
            }
        }

        // Clear all effects for a group
        delete<GroupFxResource> { resource ->
            try {
                val group = state.show.fixtures.untypedGroup(resource.name)
                val count = group.clearFx(state.show.fxEngine)
                call.respond(ClearGroupFxResponse(count))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Group not found"))
            }
        }

        // Get active effects for a group
        get<GroupActiveFxResource> { resource ->
            try {
                // Validate group exists
                state.show.fixtures.untypedGroup(resource.name)

                val engine = state.show.fxEngine
                val effects = engine.getFxForGroup(resource.name)
                val dtos = effects.map { instance ->
                    val expanded = engine.isMultiElementExpanded(instance)
                    GroupFxDto(
                        id = instance.id,
                        // The display name is the last fallback, for an effect a script constructed
                        // itself; a display name need not resolve, so an id comes first.
                        fxType = instance.registrationId ?: instance.effect.registrationId ?: instance.effect.name,
                        attribute = instance.target.attribute,
                        cycle = instance.timing.cycle,
                        blendMode = instance.blendMode.name,
                        order = instance.order.javaClass.simpleName,
                        elementMode = if (expanded) instance.elementMode.name else null,
                        elementFilter = if (instance.elementFilter != ElementFilter.ALL)
                            instance.elementFilter.name else null,
                        timing = instance.dynamics.timing,
                        isRunning = instance.isRunning,
                        phaseOffset = instance.phaseOffset,
                        currentPhase = instance.lastPhase,
                        parameters = instance.effect.parameters,
                        cueId = instance.cueId,
                        programmerOwned = FxEngine.isProgrammerFxPriority(instance.priority),
                        intensityMultiplier = instance.intensityMultiplier,
                        // Gated exactly as FxDto and IndirectFxDto are — this was the
                        // one effect report sweep item B4 missed, so a BEAT-timed group effect
                        // showed a live rate-master chip for a field it cannot read.
                        speedMasterUuid = instance.reportedSpeedMasterUuid,
                        rateSpeedMasterUuid = instance.reportedRateSpeedMasterUuid,
                        seconds = instance.onSeconds,
                        amount = instance.dynamics.amount,
                        direction = instance.dynamics.direction.name,
                        runs = instance.dynamics.runs,
                        spread = instance.dynamics.spread,
                        wings = instance.dynamics.wings,
                        segments = instance.dynamics.segments,
                        parts = instance.dynamics.parts,
                        spent = instance.spent,
                    )
                }
                call.respond(dtos)
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Group not found"))
            }
        }
    }

    // A vocabulary enumeration, beside the groups rather than under them: a literal under
    // `/groups` would shadow a group of that name.
    get("/spread-orders") {
        call.respond(SpreadOrdersResponse(orders = SpreadOrder.availableOrders))
    }
}

// Resource definitions
@Resource("/")
data object GroupsResource

@Resource("/{name}")
data class GroupResource(val name: String)

@Resource("/{name}/attributes")
data class GroupAttributesResource(val name: String)

@Resource("/{name}/fx")
data class GroupFxResource(val name: String)

@Resource("/{name}/fx/active")
data class GroupActiveFxResource(val name: String)

// DTOs
@Serializable
data class GroupSummaryDto(
    val name: String,
    val memberCount: Int,
    val capabilities: List<String>,
    val symmetricMode: String,
    val defaultOrder: String,
    /** Always empty: a Look lands whole (absolute-looks plan P7). Kept on the wire until the client stops reading it. */
    val compatibleLookIds: List<Int> = emptyList(),
    /**
     * Every member's key in the group's order (`sort_order`, venue-visit plan S7) — the order its
     * effects and a spread over it run in, which the Groups list draws its member rows in.
     */
    val memberKeys: List<String> = emptyList(),
    /**
     * The group's colour (tables-colours plan D7) as the show's rig draws it — a venue group's recolour by
     * the show when it has one; null is none. Read from the stored group, since the loader builds nothing
     * from it.
     */
    val colour: String? = null,
)

@Serializable
data class GroupMemberDto(
    val fixtureKey: String,
    val fixtureName: String,
    val index: Int,
    val normalizedPosition: Double,
    val panOffset: Double,
    val tiltOffset: Double,
    val symmetricInvert: Boolean,
    val tags: List<String>
)

@Serializable
data class GroupDetailDto(
    val name: String,
    val memberCount: Int,
    val capabilities: List<String>,
    val symmetricMode: String,
    val defaultOrder: String,
    val members: List<GroupMemberDto>,
    /** Always empty: a Look lands whole (absolute-looks plan P7). Kept on the wire until the client stops reading it. */
    val compatibleLookIds: List<Int> = emptyList(),
    /**
     * Each strategy's step per member, by `index` (`SpreadOrder.stepsBySize`): the order
     * the group sheet shows effects and a spread running in under that strategy (venue-visit plan S7)
     * — over the members; an effect expanded to multi-head members' cells distributes over the cells.
     */
    val orderSteps: Map<String, List<Int>> = emptyMap(),
)

@Serializable
data class AddGroupFxRequest(
    val fxType: String,
    val attribute: String,  // "dimmer", "colour", "position", "uv"
    /** The FX's own default cycle when omitted — see [AddFxRequest.cycle]. */
    val cycle: Double? = null,
    val blendMode: String = "OVERRIDE",
    val order: String = "NORMAL",
    val phaseOffset: Double = 0.0,
    val parameters: Map<String, String> = emptyMap(),
    val elementMode: String = "PER_FIXTURE",  // PER_FIXTURE or FLAT
    val elementFilter: String = "ALL",
    val timing: Timing? = null,
    /** Speed master to subscribe to, as the master's uuid (null → master 1). */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). Only meaningful for WALL_CLOCK effects. */
    val rateSpeedMasterUuid: String? = null,
    /** On *Seconds* (D7) — see [AddFxRequest.seconds]. */
    val seconds: Boolean = false,
    /** Amount (effects plan D2), 0–1 — see [AddFxRequest.amount]. */
    val amount: Double = 1.0,
    /** Direction (D5) — see [AddFxRequest.direction]. */
    val direction: String? = null,
    /** Runs (D9) — see [AddFxRequest.runs]. */
    val runs: Int? = null,
    /** The spread's shape (D4) — see [AddFxRequest.spread]. */
    val spread: Double = 1.0,
    val wings: Int = 1,
    val segments: Int = 1,
    val parts: Int = 0,
    /**
     * Create this effect in the programmer's reserved priority band
     * ([FxEngine.PROGRAMMER_FX_PRIORITY_BASE]). See [AddFxRequest.programmerOwned].
     */
    val programmerOwned: Boolean = false,
)

@Serializable
data class AddGroupFxResponse(
    val fxId: Long
)

@Serializable
data class GroupFxDto(
    val id: Long,
    val fxType: String,
    val attribute: String,
    val cycle: Double,
    val blendMode: String,
    val order: String,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val timing: Timing = Timing.CYCLE,
    val isRunning: Boolean,
    val phaseOffset: Double,
    val currentPhase: Double,
    val parameters: Map<String, String>,
    val cueId: Int? = null,
    /** True when this effect sits in the programmer's reserved priority band. */
    val programmerOwned: Boolean = false,
    /** The envelope — see [uk.me.cormack.lighting7.fx.FxDto.intensityMultiplier]. */
    val intensityMultiplier: Double = 1.0,
    /** Speed master this effect subscribes to (null → master 1). */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). Only meaningful for WALL_CLOCK effects. */
    val rateSpeedMasterUuid: String? = null,
    /** D7's reading — see [uk.me.cormack.lighting7.fx.FxDto.seconds]. */
    val seconds: Boolean = false,
    /** See [uk.me.cormack.lighting7.fx.FxDto.amount] and its three siblings. */
    val amount: Double = 1.0,
    val direction: String = "FORWARD",
    val runs: Int? = null,
    val spent: Boolean = false,
    /** See [uk.me.cormack.lighting7.fx.FxDto.spread] and its three siblings. */
    val spread: Double = 1.0,
    val wings: Int = 1,
    val segments: Int = 1,
    val parts: Int = 0,
)

@Serializable
data class ClearGroupFxResponse(
    val removedCount: Int
)

@Serializable
data class SpreadOrdersResponse(
    val orders: List<String>,
)

// Helper functions
/** Every group's colour in [projectId]'s rig, by name, as the live group list carries it. */
private fun groupColoursByName(state: State, projectId: Int): Map<String, String> = transaction(state.database) {
    val rig = RigScope.of(projectId)
    rig.groups().mapNotNull { g -> rig.colourOf(g)?.let { g.name to it } }.toMap()
}

/** The group's summary — what the group list answers and what a busk rig tile embeds. */
internal fun FixtureGroup<*>.toGroupSummaryDto(colour: String? = null): GroupSummaryDto {
    return GroupSummaryDto(
        colour = colour,
        name = name,
        memberCount = memberCount,  // Uses allMembers.size (includes subgroups)
        capabilities = detectCapabilities(),
        symmetricMode = metadata.symmetricMode.name,
        defaultOrder = metadata.defaultOrderName,
        memberKeys = allMembers.map { it.key },
    )
}

private fun FixtureGroup<*>.toDetailedDto(): GroupDetailDto {
    return GroupDetailDto(
        name = name,
        memberCount = memberCount,  // Uses allMembers.size (includes subgroups)
        capabilities = detectCapabilities(),
        symmetricMode = metadata.symmetricMode.name,
        defaultOrder = metadata.defaultOrderName,
        members = allMembers.map { member ->  // Use allMembers to include subgroups
            GroupMemberDto(
                fixtureKey = member.key,
                fixtureName = member.name,
                index = member.index,
                normalizedPosition = member.normalizedPosition,
                panOffset = member.metadata.panOffset,
                tiltOffset = member.metadata.tiltOffset,
                symmetricInvert = member.metadata.symmetricInvert,
                tags = member.metadata.tags.toList()
            )
        },
        orderSteps = SpreadOrder.stepsBySize(memberCount),
    )
}

/**
 * Check whether a group supports a given property, either directly on the
 * fixtures or via multi-element fixture elements.
 *
 * The dispatch itself is [fixturesSupportAttribute], shared with the effect arm of a template apply
 * — a head is a group of one, and two copies of the trait dispatch could disagree about the same
 * fixture.
 */
private fun groupSupportsAttribute(group: FixtureGroup<*>, attribute: String): Boolean =
    fixturesSupportAttribute(group.fixtures, attribute)

private fun applyGroupFx(
    state: State,
    group: FixtureGroup<*>,
    request: AddGroupFxRequest
): Long {
    val engine = state.show.fxEngine
    state.show.fxRegistry.fxWriteProblem(request.fxType, request.parameters)
        ?.let { throw IllegalArgumentException(it) }
    val effect = state.show.fxRegistry.createFxWithPalettes(
        state.show.paletteRegistry,
        request.fxType,
        request.parameters,
    )
    val registration = state.show.fxRegistry.getRegistration(request.fxType)
    val timing = FxTiming(request.cycle ?: registration?.defaultCycle ?: CycleLength.ONE_BAR)
    val blendMode = FxSpecCoercion.Strict.blendMode(request.blendMode)
    val spreadOrder = FxSpecCoercion.Strict.order(request.order)
    val elementMode = FxSpecCoercion.Strict.elementMode(request.elementMode)

    // No effect may drive a member's one-shot trigger (stage-view plan session 9, D15).
    TriggerIndex.refusalLive(state.show.fixtures, "group", group.name, request.attribute, "effect")
        ?.let { throw TriggerNotStorableException(it) }
    // Nor a member's fixture command (fixture optics plan session 7).
    CommandIndex.refusalLive(state.show.fixtures, "group", group.name, request.attribute, "effect")
        ?.let { throw CommandNotStorableException(it) }

    // Validate property support (direct or via elements)
    if (!groupSupportsAttribute(group, request.attribute)) {
        throw IllegalStateException(
            "Not all fixtures in group support ${request.attribute} " +
                "(directly or via elements)"
        )
    }

    val target = FxTargetFactory.forGroup(
        group.name,
        request.attribute,
        effect.outputType,
        group.fixtures.firstOrNull() as? Fixture,
    )
    requireOutputTypeMatch(effect, target)

    val elFilter = FxSpecCoercion.Strict.elementFilter(request.elementFilter)

    // Create SINGLE FxInstance for the entire group
    secondsTimingConflict(request.seconds, request.speedMasterUuid, request.rateSpeedMasterUuid)
        ?.let { throw IllegalArgumentException(it) }
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        registrationId = registration?.id
        phaseOffset = request.phaseOffset
        order = spreadOrder
        this.elementMode = elementMode
        this.elementFilter = elFilter
        applyRequestedDynamics(
            request.amount, request.direction, request.runs,
            SpreadShape(request.spread, request.wings, request.segments, request.parts),
        )
        request.timing?.let { t -> updateDynamics { it.copy(timing = t) } }
        speedMasterUuid = requireSpeedMasterUuid(request.speedMasterUuid)
        rateSpeedMasterUuid = requireSpeedMasterUuid(request.rateSpeedMasterUuid)
        // The type's loop, and D7's choice — this route never set the loop before S15, so a
        // wall-clock effect on a group ran on the beat loop.
        applyTimingChoice(registration?.timingSource ?: TimingSource.BEAT, request.seconds)
    }

    markProgrammerOwned(instance, request.programmerOwned)

    return engine.addFx(instance)
}

