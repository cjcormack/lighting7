package uk.me.cormack.lighting7.plugins

import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.BuskPadKind
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.state.SelectionSource
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.collectUntilOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `hand.*` family over a real socket (multi-screen plan §3.5, D12): the connect snapshot
 * arrives unasked, a pick-up resolves **in this project only** and is stamped from the socket's
 * announced window, a drop is idempotent, a record deleted while held takes the hand with it, and a
 * project switch empties it.
 *
 * Driven over a socket rather than against [uk.me.cormack.lighting7.state.HandState] for the reason
 * `WindowsSocketTest` and `CueRunStateBroadcastTest` are: a message missing from the `OutMessage`
 * polymorphic scope serializes fine in isolation and closes the socket in production. The unit
 * rules — replace, the timeout, reconcile's identity test — are in `state/HandStateTest`.
 */
class HandSocketTest : RouteIntegrationTest() {

    private fun seedTemplate(name: String, project: Int = projectId): Int = transaction(state.database) {
        DaoTemplate.new {
            this.project = DaoProject.findById(project)!!
            this.name = name
        }.id.value
    }

    private fun seedLook(name: String): Int = transaction(state.database) {
        DaoLook.new {
            this.project = DaoProject.findById(projectId)!!
            this.name = name
        }.id.value
    }

    private fun seedCue(name: String): Int = transaction(state.database) {
        val project = DaoProject.findById(projectId)!!
        val stack = DaoCueStack.new {
            this.project = project
            this.name = "$name-stack"
            loop = false
            type = CueStackType.STACK.name
            sortOrder = 0
        }
        DaoCue.new {
            this.project = project
            this.name = name
            cueStack = stack
            sortOrder = 0
        }.id.value
    }

    private fun announce(name: String = "Screen 1") =
        WindowsAnnounceInMessage("tab-1", name, "/busk", fullscreen = false, follows = true)

    @Test
    fun `the connect snapshot says the hand is empty, unasked`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        client.webSocket("/api") {
            assertNull(awaitOfType<HandStateOutMessage>().item, "a fresh desk holds nothing")
        }
    }

    @Test
    fun `a pick-up carries the record's own summary DTO, for each of the three kinds`() = testApplication {
        mountTestApp(state)
        val templateId = seedTemplate("Warm wash")
        val lookId = seedLook("Act 1 opener")
        val cueId = seedCue("Cue 3")
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<HandStateOutMessage>()

            sendSerialized<InMessage>(HandPickUpInMessage("TEMPLATE", templateId))
            val template = awaitOfType<HandStateOutMessage> { it.item != null }.item!!
            assertEquals(BuskPadKind.TEMPLATE, template.kind)
            assertEquals(templateId, template.id)
            assertEquals("Warm wash", template.template?.name, "the ghost's face comes from the frame, not a fetch")
            assertNull(template.look)
            assertNull(template.cue)
            assertTrue(template.expiresAtMs > template.pickedUpAtMs, "the desk stamped its own expiry")

            sendSerialized<InMessage>(HandPickUpInMessage("LOOK", lookId))
            val look = awaitOfType<HandStateOutMessage> { it.item?.kind == BuskPadKind.LOOK }.item!!
            assertEquals("Act 1 opener", look.look?.name)
            assertNull(look.template, "a second pick-up replaces — there is no both")

            sendSerialized<InMessage>(HandPickUpInMessage("CUE", cueId))
            val cue = awaitOfType<HandStateOutMessage> { it.item?.kind == BuskPadKind.CUE }.item!!
            assertEquals("Cue 3", cue.cue?.name)
            assertEquals("Cue 3-stack", cue.cue?.cueStackName, "a cue pad needs its stack's name")
        }
    }

    @Test
    fun `a pick-up resolves in this project only`() = testApplication {
        mountTestApp(state)
        val otherProjectId = seedMinimalProject(state, projectName = "Second project", universe = 1)
        val foreign = seedTemplate("Another show's template", project = otherProjectId)
        val mine = seedTemplate("This show's template")
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<HandStateOutMessage>()

            // A template that exists — in the wrong project. Dropped, with no reply and no hand.
            // The pick-up of *this* project's template behind it is the stopping point: every
            // `hand.state` up to and including it must be the empty one, or the foreign row landed.
            sendSerialized<InMessage>(HandPickUpInMessage("TEMPLATE", foreign))
            sendSerialized<InMessage>(HandPickUpInMessage("TEMPLATE", mine))
            val held = awaitOfType<HandStateOutMessage> { it.item != null }.item!!
            assertEquals(mine, held.id, "only the current project's template reached the hand")

            // And nothing at all, likewise: the hand keeps what it holds.
            sendSerialized<InMessage>(HandPickUpInMessage("TEMPLATE", 999_999))
            sendSerialized<InMessage>(HandDropInMessage())
            assertNull(awaitOfType<HandStateOutMessage> { it.item == null }.item)
        }
    }

    @Test
    fun `a malformed kind is dropped rather than closing the socket`() = testApplication {
        mountTestApp(state)
        val templateId = seedTemplate("Warm wash")
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<HandStateOutMessage>()
            sendSerialized<InMessage>(HandPickUpInMessage("CUE_STACK", templateId))
            sendSerialized<InMessage>(HandPickUpInMessage("TEMPLATE", templateId))
            assertEquals(templateId, awaitOfType<HandStateOutMessage> { it.item != null }.item?.id)
        }
    }

    @Test
    fun `a pick-up is stamped with the announcing window, never with a payload`() = testApplication {
        mountTestApp(state)
        val templateId = seedTemplate("Warm wash")
        val client = createWsClient()

        client.webSocket("/api") {
            // No priming read of either snapshot: the connect burst has no fixed order, so a
            // `hand.state` read first would swallow the `windows.state` behind it and the await
            // after it would never return. Every read here is predicated instead.
            sendSerialized<InMessage>(announce(name = "Screen 2"))
            val row = awaitOfType<WindowsStateOutMessage> { it.windows.isNotEmpty() }.windows.single()

            sendSerialized<InMessage>(HandPickUpInMessage("TEMPLATE", templateId))
            val held = awaitOfType<HandStateOutMessage> { it.item != null }.item!!
            assertEquals(SelectionSource.KIND_WINDOW, held.pickedUpOn?.kind)
            assertEquals("Screen 2", held.pickedUpOn?.name)
            assertEquals(row.id, held.pickedUpOn?.id, "the registry row id, so a chip can match its own")
        }
    }

    @Test
    fun `a drop empties the hand, and dropping again sends nothing`() = testApplication {
        mountTestApp(state)
        val templateId = seedTemplate("Warm wash")
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<HandStateOutMessage>()
            sendSerialized<InMessage>(HandPickUpInMessage("TEMPLATE", templateId))
            awaitOfType<HandStateOutMessage> { it.item != null }

            sendSerialized<InMessage>(HandDropInMessage())
            assertNull(awaitOfType<HandStateOutMessage> { it.item == null }.item)

            // The second drop is a no-op, so it emits no frame: the next `hand.state` to arrive is
            // the one the pick-up after it causes, not an echo of the drop.
            sendSerialized<InMessage>(HandDropInMessage())
            sendSerialized<InMessage>(HandPickUpInMessage("TEMPLATE", templateId))
            val frames = collectUntilOfType<HandStateOutMessage>()
            assertTrue(
                frames.filterIsInstance<HandStateOutMessage>().none { it.item == null },
                "a drop that changed nothing must not broadcast: $frames",
            )
        }
    }

    /**
     * The two-screen case the hand exists for, and the reason `hand.drop` carries an optional
     * record: a place is two independent round-trips — the window's own mutation, then the drop —
     * and another window may have picked something up in the gap. A bare drop would clear it.
     */
    @Test
    fun `a drop naming a record lets go only of that record`() = testApplication {
        mountTestApp(state)
        val templateId = seedTemplate("Warm wash")
        val lookId = seedLook("Act 1 opener")
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<HandStateOutMessage>()
            sendSerialized<InMessage>(HandPickUpInMessage("TEMPLATE", templateId))
            val template = awaitOfType<HandStateOutMessage> { it.item != null }.item!!

            // Window A places its template; in the gap window B picks up a Look. A's drop names the
            // template, so it must not clear B's Look.
            sendSerialized<InMessage>(HandPickUpInMessage("LOOK", lookId))
            val look = awaitOfType<HandStateOutMessage> { it.item?.kind == BuskPadKind.LOOK }.item!!
            sendSerialized<InMessage>(HandDropInMessage(uuid = template.uuid))

            // Nothing happened, so no frame: the next one is the drop that does match.
            sendSerialized<InMessage>(HandDropInMessage(uuid = look.uuid))
            val frames = collectUntilOfType<HandStateOutMessage>()
            assertTrue(
                frames.filterIsInstance<HandStateOutMessage>().none { it.item?.kind == BuskPadKind.TEMPLATE },
                "the stale drop cleared the other window's item: $frames",
            )
            assertNull(frames.filterIsInstance<HandStateOutMessage>().last().item)
        }
    }

    @Test
    fun `a record deleted while held drops the hand on every window`() = testApplication {
        mountTestApp(state)
        val lookId = seedLook("Act 1 opener")
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<HandStateOutMessage>()
            sendSerialized<InMessage>(HandPickUpInMessage("LOOK", lookId))
            awaitOfType<HandStateOutMessage> { it.item != null }

            transaction(state.database) { DaoLook.findById(lookId)!!.delete() }
            // What every Look delete fires, and what carries the reconcile: without it the chip
            // would still name a row that has gone.
            state.show.fixtures.lookListChanged()

            assertNull(awaitOfType<HandStateOutMessage> { it.item == null }.item)
        }
    }

    /**
     * The project-scoped half, and the contrast `WindowsSocketTest` draws the other way round: the
     * windows registry survives a switch because a window is a fact about the machine, and the hand
     * does not because a held record id belongs to one project's rows.
     */
    @Test
    fun `a project switch empties the hand, where a window survives it`() = testApplication {
        mountTestApp(state)
        val otherProjectId = seedMinimalProject(state, projectName = "Second project", universe = 1)
        val templateId = seedTemplate("Warm wash")
        val client = createWsClient()

        client.webSocket("/api") {
            sendSerialized<InMessage>(announce())
            awaitOfType<WindowsStateOutMessage> { it.windows.isNotEmpty() }

            sendSerialized<InMessage>(HandPickUpInMessage("TEMPLATE", templateId))
            awaitOfType<HandStateOutMessage> { it.item != null }

            state.projectManager.switchProject(otherProjectId)

            assertNull(awaitOfType<HandStateOutMessage> { it.item == null }.item)
            assertNotNull(
                state.windowRegistry.windows.value.singleOrNull(),
                "the window outlives the switch; only the hand is the project's",
            )
        }
    }
}
