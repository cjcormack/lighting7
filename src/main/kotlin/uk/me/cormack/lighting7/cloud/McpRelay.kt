package uk.me.cormack.lighting7.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.auth.AuthService
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.cloud.protocol.DeskTokens
import uk.me.cormack.lighting7.cloud.protocol.Envelope
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.MAX_FRAME_BYTES
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.cloud.protocol.TokenCheck
import uk.me.cormack.lighting7.cloud.protocol.TokenScope
import uk.me.cormack.lighting7.mcp.McpProtocol
import uk.me.cormack.lighting7.telemetry.Tracing
import java.security.PublicKey
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP through the cloud (cloud-p1 plan D15): each `mcp.call` verified as a token of scope `mcp`, its
 * account mapped through the link to a desk user, and its JSON-RPC message run by [handle] (the
 * desk's `McpProtocol`) as that user, the answer sent back as `mcp.result`.
 *
 * The channel's thread only takes a call in: the switch and the queue are judged there, and the rest
 * runs on [dispatcher], [RUNNING] at once and [QUEUED] more waiting, `DESK_BUSY` beyond, so a flood of
 * calls holds none of the channel's threads and none of the show's beyond what the tools themselves do.
 */
internal class McpRelay(
    private val handle: suspend (JsonObject, AuthenticatedUser) -> JsonObject?,
    private val authService: AuthService?,
    private val sessions: RemoteSessions,
    private val trust: () -> CloudTrust?,
    private val keys: () -> Map<String, PublicKey>,
    private val mcpAccess: () -> Boolean,
    /** Send a reply to the cloud's message: false when it could not be queued. */
    private val reply: (Message, String) -> Boolean,
    private val clock: () -> Instant,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(McpRelay::class.java)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("mcp-relay"))
    private val running = Semaphore(RUNNING)
    private val inFlight = AtomicInteger()

    /** An `mcp.call`, [id] its envelope's. Called on the channel's thread: never blocks. */
    fun call(id: String, call: Message.McpCall) {
        if (!mcpAccess()) return send(id, refusal(ErrorCode.MCP_ACCESS_OFF, MCP_OFF_MESSAGE))
        if (inFlight.incrementAndGet() > RUNNING + QUEUED) {
            inFlight.decrementAndGet()
            return send(id, refusal(ErrorCode.DESK_BUSY, "This desk is already running ${RUNNING + QUEUED} calls."))
        }
        scope.launch {
            // The slot is free before the answer is queued, so a cloud that sends its next call on hearing it finds room.
            val answer = try {
                running.withPermit { answer(id, call) }
            } finally {
                inFlight.decrementAndGet()
            }
            send(id, answer)
        }
    }

    private suspend fun answer(id: String, call: Message.McpCall): Message {
        if (!mcpAccess()) return refusal(ErrorCode.MCP_ACCESS_OFF, MCP_OFF_MESSAGE)
        val trust = trust() ?: return refusal(ErrorCode.MCP_ACCESS_OFF, "This desk is not adopted.")
        val claims = when (val check = DeskTokens.verify(call.token, keys(), trust.installId, TokenScope.MCP, clock())) {
            is TokenCheck.Refused -> return refusal(check.refusal.code, "This desk refused the call's token (${check.refusal.code}).")
            is TokenCheck.Valid -> check.claims
        }
        if (claims.org != trust.orgId) return refusal(ErrorCode.TOKEN_WRONG_AUDIENCE, "This token is for another organisation.")
        val gid = claims.gid ?: return refusal(ErrorCode.TOKEN_MALFORMED, "An MCP token names a grant.")
        if (call.grant.id != gid) return refusal(ErrorCode.MCP_GRANT_MISMATCH, "The call's grant is not its token's.")
        if (sessions.tokenRevoked(claims)) return refusal(ErrorCode.TOKEN_REVOKED, "This account's access was revoked.")
        val link = sessions.linkedDeskUser(claims.sub)
        val record = link?.let { authService?.findUser(it.userId) }
            ?: return refusal(ErrorCode.CLOUD_ACCOUNT_NOT_LINKED, NOT_LINKED_MESSAGE)
        val requestId = call.request["id"]
        if (record.disabled) {
            return Message.McpResult(requestId?.let { McpProtocol.error(it, ACCOUNT_DISABLED, "The desk account this cloud account links to is disabled.") })
        }
        val user = AuthenticatedUser(
            userId = record.userId,
            uuid = record.uuid,
            username = record.username,
            displayName = record.displayName,
            role = record.role,
            sessionTokenHash = CLOUD_GRANT_PREFIX + gid,
            clientName = ChannelConnection.clip(call.grant.clientName),
        )
        val response = try {
            Tracing.transactionSuspending(OP_RELAY, "mcp.call") { handle(call.request, user) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("A relayed MCP call failed", e)
            requestId?.let { McpProtocol.error(it, McpProtocol.INTERNAL_ERROR, "Internal error: ${e.message ?: e::class.simpleName}") }
        }
        return fitted(id, response, requestId)
    }

    /** [response] as `mcp.result`, or a JSON-RPC error in its place when the frame would be over the limit. */
    private fun fitted(id: String, response: JsonObject?, requestId: JsonElement?): Message.McpResult {
        val result = Message.McpResult(response)
        // Measured as the channel encodes it: its own envelope id is a random UUID, the same length as this one.
        val bytes = ChannelOutbox.utf8Size(Envelope(id = SIZING_ID, body = result, replyTo = id).encode())
        if (bytes <= MAX_FRAME_BYTES) return result
        logger.warn("A relayed MCP result of {} bytes is over the frame limit", bytes)
        val size = "%.1f".format(Locale.ROOT, kotlin.math.ceil(bytes / MIB * 10) / 10)
        return Message.McpResult(
            McpProtocol.error(
                requestId ?: JsonNull,
                McpProtocol.INTERNAL_ERROR,
                "The desk's answer is too large to send through Lighting7 Cloud ($size MiB, over ${MAX_FRAME_BYTES / MIB.toInt()} MiB). Ask for less at once.",
            ),
        )
    }

    private fun refusal(code: String, message: String) = Message.Error(code, message)

    private fun send(id: String, message: Message) {
        if (!reply(message, id)) logger.warn("Couldn't answer an MCP call from the cloud with '{}'", message.type)
    }

    fun close() {
        scope.cancel()
    }

    companion object {
        /** How many relayed calls run at once, and how many more wait; the cloud keeps eight in flight. */
        const val RUNNING = 4
        const val QUEUED = 4

        /** What a cloud grant's [AuthenticatedUser.sessionTokenHash] starts with, before the grant's id. */
        const val CLOUD_GRANT_PREFIX: String = "cloud-grant:"

        /** The JSON-RPC error a call from a disabled desk account answers: a server error, in JSON-RPC's own range. */
        const val ACCOUNT_DISABLED = -32001

        /** The transaction a relayed call runs under, which `McpProtocol`'s `mcp.server` span joins. */
        const val OP_RELAY = "mcp.relay"

        private const val MIB = 1024.0 * 1024.0
        private val SIZING_ID = UUID(0, 0).toString()
        private const val MCP_OFF_MESSAGE = "Claude (MCP) is turned off on this desk."

        private const val NOT_LINKED_MESSAGE =
            "This cloud account is not linked to a user on this desk. An admin can invite it from the desk's Users page, " +
                "or you can open the desk from the portal and sign in once with your desk username and password."
    }
}
