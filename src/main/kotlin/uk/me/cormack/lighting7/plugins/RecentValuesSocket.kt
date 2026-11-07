package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fixture.FixtureAttributeCatalogue
import uk.me.cormack.lighting7.fixture.PanTiltAxis
import uk.me.cormack.lighting7.fixture.attribute.Slider
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

/**
 * The `recentValues.*` family (programmer-fixes plan D16): the value pop-ups' recent values for the
 * live project. One inbound frame, sent once when an editor's gesture ends; one outbound, every list
 * of the live project, streamed. No REST and no MCP tool. See `state/RecentValues.kt`.
 */

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class RecentValuesInMessage : InMessage()

/** Put one value at the front of the live project's list [key]. Outside the vocabulary, dropped. */
@Serializable
@SerialName("recentValues.record")
data class RecentValuesRecordInMessage(val key: String, val value: String) : RecentValuesInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class RecentValuesOutMessage : OutMessage()

/** Every list of the live project, by key, newest first. The subscription is the connect snapshot. */
@Serializable
@SerialName("recentValues.state")
data class RecentValuesStateOutMessage(val projectId: Int, val lists: Map<String, List<String>>) : RecentValuesOutMessage()

// ─── Handlers ───────────────────────────────────────────────────────────

internal fun handleRecentValues(scope: SocketScope, message: RecentValuesInMessage) {
    when (message) {
        is RecentValuesRecordInMessage -> {
            val show = scope.state.show
            scope.state.recentValues.record(show.project.id.value, message.key, message.value) { attribute ->
                rigHasLevel(show.fixtures, attribute)
            }
        }
    }
}

/** Whether some patched fixture, or one of its heads, has a level — a slider, not a pan or tilt — named [attribute]. */
private fun rigHasLevel(fixtures: Fixtures, attribute: String): Boolean {
    fun isLevel(klass: KClass<*>): Boolean {
        val declared = FixtureAttributeCatalogue.of(klass).byName[attribute] ?: return false
        val type = declared.classProperty.returnType.classifier as? KClass<*> ?: return false
        return declared.axis == PanTiltAxis.NONE && type.isSubclassOf(Slider::class)
    }
    return fixtures.fixtures.any { fixture ->
        isLevel(fixture::class) || (fixture as? MultiElementFixture<*>)?.elements.orEmpty().any { isLevel(it::class) }
    }
}

/** Follows the live show: the lists are the live project's. */
fun setupRecentValuesSubscriptions(scope: SocketScope) {
    val recentValues = scope.state.recentValues
    scope.subscribeShow({ show ->
        val projectId = show.project.id.value
        recentValues.state.map { RecentValuesStateOutMessage(projectId, it[projectId].orEmpty()) }.distinctUntilChanged()
    }) { scope.send(it) }
}
