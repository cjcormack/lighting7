package uk.me.cormack.lighting7.routes

import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext

/**
 * Creates a child route that matches without consuming a path segment, so the subtree inside it
 * keeps the paths it would have had — the child exists only to carry interceptors or plugins.
 * The same primitive Ktor's own `authenticate {}` is built on.
 *
 * This is what makes a cross-cutting rule (authentication, an admin requirement, a readiness
 * gate) composable: the rule is declared by *where a route sits in the tree*, and there is no
 * second list of path strings to keep in step with it.
 *
 * [label] is what the node prints in a routing trace; `qualityTransparent` keeps it out of the
 * scoring when the resolver compares sibling candidates.
 */
internal fun Route.transparentChild(label: String, build: Route.() -> Unit): Route {
    val child = createChild(TransparentRouteSelector(label))
    child.build()
    return child
}

private class TransparentRouteSelector(private val label: String) : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = "($label)"
}
