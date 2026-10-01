package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/**
 * Root of every WebSocket inbound frame. Each domain defines an intermediate sealed
 * subclass (e.g. [FxInMessage]) under which its concrete leaf messages live, so the
 * top-level dispatcher in `Sockets.kt` only enumerates domains and the per-domain
 * handler exhaustively matches its own messages.
 *
 * **A new domain's intermediate must be added to [SealedIntermediates] here.** The polymorphic
 * scope is the sealed serializer kotlinx generates *in this file*, enumerating every leaf across
 * every file, and Kotlin's incremental compile only reruns it when this file references the
 * intermediate the leaf changed under — see [SealedIntermediates] for the mechanism.
 * `SealedSerializerScopeTest` fails until the list is complete, so a missing entry is a red
 * build rather than a desk whose sockets close on a frame the serializer never heard of, which
 * is how `tunnel.state` reached a desk and `projectDetailsChanged` reached a startup.
 *
 * [SocketMessageScope] stays as the backstop: if the serializer is stale anyway, the desk
 * refuses to start, naming the frame and the command that fixes it.
 */
@Serializable
@SealedIntermediates(
    BuskInMessage::class,
    ChannelInMessage::class,
    FxInMessage::class,
    HandInMessage::class,
    ParkInMessage::class,
    ProgrammerInMessage::class,
    ProjectInMessage::class,
    SelectionInMessage::class,
    SpeedMasterInMessage::class,
    SurfaceInMessage::class,
    WindowsInMessage::class,
)
sealed class InMessage

/** Mirror of [InMessage] for outbound frames. See [InMessage] for the layering rationale. */
@Serializable
@SealedIntermediates(
    BroadcastOutMessage::class,
    BuskOutMessage::class,
    ChannelOutMessage::class,
    CloudSyncOutMessage::class,
    EffectsOutMessage::class,
    FxOutMessage::class,
    HandOutMessage::class,
    MachineOutMessage::class,
    ParkOutMessage::class,
    ProgrammerOutMessage::class,
    ProjectOutMessage::class,
    SceneryOutMessage::class,
    SelectionOutMessage::class,
    SpeedMasterOutMessage::class,
    StageRenderOutMessage::class,
    SurfaceOutMessage::class,
    WindowsOutMessage::class,
)
sealed class OutMessage

/**
 * The startup guard for the trap described on [SealedIntermediates]: a leaf the sealed
 * serializer generated in this file does not know about, because the incremental compile did not
 * rerun it. Compares [SealedSerializerScope]'s two readings of each root; a leaf the reflective
 * walk names and the serializer does not is a frame the server would fail to send (or to parse)
 * with "not found in the polymorphic scope" — for an outbound frame sent on connect, on every
 * socket.
 *
 * The reverse, a leaf the serializer still names but whose class is gone, fails as a
 * [LinkageError] the moment the serializer is built, and is reported the same way.
 */
object SocketMessageScope {
    private fun problemsOf(root: KClass<*>, rootSerializer: () -> KSerializer<*>): List<String> =
        try {
            SealedSerializerScope.unregisteredLeaves(root, rootSerializer())
        } catch (e: LinkageError) {
            listOf("a class ${root.simpleName}'s serializer names no longer exists (${e.message})")
        }

    private val verified: Unit by lazy {
        // Each root on its own, so a deleted class in one cannot hide what the other found.
        val problems = problemsOf(OutMessage::class) { OutMessage.serializer() } +
            problemsOf(InMessage::class) { InMessage.serializer() }
        check(problems.isEmpty()) {
            // An unlisted intermediate is the usual cause, so name it when there is one.
            val unpinned = SealedSerializerScope.unpinnedIntermediates(OutMessage::class) +
                SealedSerializerScope.unpinnedIntermediates(InMessage::class)
            "The WebSocket message serializers are out of date with their classes — a stale " +
                "incremental compile, not a code bug (see SocketMessages.kt). Rebuild with " +
                "`./gradlew compileKotlin --rerun-tasks`, then start the desk again. " +
                problems.joinToString("; ") +
                (if (unpinned.isEmpty()) "" else ". Cause: " + unpinned.joinToString("; "))
        }
    }

    /** Throws, naming every stale leaf, unless both roots' serializers know every leaf. Once per JVM. */
    fun verify() = verified
}
