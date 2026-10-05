package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.mcp.requireCommandsAccess
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.state.CommandOutput
import uk.me.cormack.lighting7.state.State

/**
 * Fixture commands over REST (fixture optics plan session 7, §3.4, D13):
 *
 * `POST /projects/{id}/patches/{pid}/commands/{command}` — run one of the fixture's commands (a reset,
 * a lamp strike, a lamp off). The desk holds its level for the command's declared time and **answers
 * when the hold ends** — `completed: false` when it was cut short (a project switch, the fixture
 * repatched away). No body.
 *
 * - 400 `COMMAND_UNKNOWN` — the fixture has no such command;
 * - 409 `COMMAND_BUSY` — the fixture is holding another command (one at a time per unit);
 * - 409 `COMMAND_BLIND` — the programmer is blind, and a command would reach the rig;
 * - 409 `COMMAND_PARKED` — a channel the command holds is parked, so it would not reach the fixture;
 * - 403 `REMOTE_COMMANDS_DISABLED` — on the public listener, unless an admin has allowed it
 *   ([requireCommandsAccess]); both roles may run one on the desk's own listener.
 *
 * The current project's only (its fixtures are the ones patched). REST rather than WS so the socket
 * gains no operation; the hold is the request.
 */
internal fun Route.routeApiRestProjectFixtureCommands(state: State) {
    post<PatchCommandResource> { resource ->
        call.requireCommandsAccess(state)
        withCurrentProject(state, resource.parent.parent.parent.projectId, "Only the current project's fixtures can run a command") { project ->
            val patchId = resource.parent.parent.patchId
            val key = transaction(state.database) {
                DaoFixturePatch.findById(patchId)?.takeIf { it.project.id == project.id }?.key
            } ?: return@withCurrentProject call.respond(HttpStatusCode.NotFound, ErrorResponse("Patch not found"))
            when (val outcome = runFixtureCommand(state, key, resource.command)) {
                is FixtureCommandResult.Done -> call.respond(outcome.response)
                is FixtureCommandResult.Refused -> call.respond(outcome.status, ErrorResponse(outcome.message, outcome.code))
            }
        }
    }
}

/** What running one command came to — shared by the route and MCP's `run_fixture_command`. */
internal sealed interface FixtureCommandResult {
    data class Done(val response: FixtureCommandResponse) : FixtureCommandResult
    data class Refused(val status: HttpStatusCode, val code: String, val message: String) : FixtureCommandResult
}

/** Run [command] on [fixtureKey] in the current show and wait for its hold to end. */
internal suspend fun runFixtureCommand(state: State, fixtureKey: String, command: String): FixtureCommandResult {
    val output = state.show.commandOutput
    return when (val outcome = output.run(fixtureKey, command)) {
        is CommandOutput.Outcome.Started -> {
            val running = outcome.running
            val ended = running.done.await()
            FixtureCommandResult.Done(
                FixtureCommandResponse(
                    fixture = running.fixtureKey,
                    fixtureName = running.fixtureName,
                    command = running.command.name,
                    label = running.command.spec.label,
                    holdMs = running.command.spec.holdMs,
                    startedAt = running.startedAt.toIsoUtc(),
                    endedAt = nowUtc().toIsoUtc(),
                    completed = ended == CommandOutput.Ended.COMPLETED,
                ),
            )
        }
        is CommandOutput.Outcome.Busy -> {
            val r = outcome.running
            val remaining = (r.endsAt.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0)
            FixtureCommandResult.Refused(
                HttpStatusCode.Conflict, "COMMAND_BUSY",
                "'${r.fixtureName}' is running '${r.command.spec.label}' for another ${remaining / 1000.0} s — one command at a time",
            )
        }
        is CommandOutput.Outcome.Unknown -> FixtureCommandResult.Refused(HttpStatusCode.BadRequest, "COMMAND_UNKNOWN", outcome.message)
        CommandOutput.Outcome.Blind -> FixtureCommandResult.Refused(
            HttpStatusCode.Conflict, "COMMAND_BLIND",
            "The programmer is blind: a command would reach the rig, so it is refused until Blind is off",
        )
        is CommandOutput.Outcome.Parked -> FixtureCommandResult.Refused(HttpStatusCode.Conflict, "COMMAND_PARKED", outcome.message)
    }
}

@Resource("/commands")
internal data class PatchCommandsResource(val parent: ProjectPatchResource)

@Resource("/{command}")
internal data class PatchCommandResource(val parent: PatchCommandsResource, val command: String)

@Serializable
data class FixtureCommandResponse(
    val fixture: String,
    val fixtureName: String,
    val command: String,
    val label: String,
    val holdMs: Long,
    val startedAt: String,
    val endedAt: String,
    /** False when the hold was cut short — a project switch, or the fixture repatched away. */
    val completed: Boolean,
)
