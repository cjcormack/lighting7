package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.full.starProjectedType

/**
 * Root of every WebSocket inbound frame. Each domain defines an intermediate sealed
 * subclass (e.g. [FxInMessage]) under which its concrete leaf messages live, so the
 * top-level dispatcher in `Sockets.kt` only enumerates domains and the per-domain
 * handler exhaustively matches its own messages.
 *
 * **Adding or removing a leaf anywhere under this hierarchy needs this file recompiled.** The
 * polymorphic scope is the sealed serializer kotlinx generates *here*, enumerating every leaf
 * across every file at compile time, and Gradle's incremental Kotlin compile does not re-run it
 * for a change in another file — the new frame then fails with "not found in the polymorphic
 * scope" while every file compiles green. A content change to this file (or `--rerun-tasks`) is
 * the fix; a `touch` is not, because the compile is keyed on content, not on timestamps.
 * (Last edited for exactly that reason when `windows.follow` was added.)
 *
 * [SocketMessageScope] turns that failure into a refusal to start, naming the frame and the
 * command that fixes it, rather than a desk whose sockets close as the frame is first sent —
 * which is how `tunnel.state` reached a desk.
 */
@Serializable
sealed class InMessage

/** Mirror of [InMessage] for outbound frames. See [InMessage] for the layering rationale. */
@Serializable
sealed class OutMessage

/**
 * The startup guard for the trap described on [InMessage]: a leaf the sealed serializer
 * generated in this file does not know about, because the incremental compile did not rerun it.
 *
 * It compares two readings of one hierarchy. The **reflective** one walks
 * [KClass.sealedSubclasses] from the root, and is fresh wherever it matters: a leaf's metadata
 * lives in its intermediate's class file, and the intermediate is recompiled with the file the
 * leaf was added to. The **generated** one is the element list of the root's sealed serializer,
 * which is the stale half. A leaf the first names and the second does not is a frame the server
 * would fail to send (or to parse) with "not found in the polymorphic scope" — for an outbound
 * frame sent on connect, on every socket.
 *
 * The reverse, a leaf the serializer still names but whose class is gone, fails as a
 * [LinkageError] the moment the serializer is built, and is reported the same way.
 *
 * The one case this cannot see is a *direct* subclass of a root declared in another file, since
 * the root's own metadata would be as stale as its serializer. `BootProgressStateOutMessage` is
 * the one such frame today; every other domain adds its leaves under its own intermediate, which
 * is the case that has bitten twice — so a new frame belongs under an intermediate, where this
 * guard can see it.
 */
object SocketMessageScope {
    /** Leaves of [root] that [rootSerializer] cannot encode, each with the reason. */
    @OptIn(ExperimentalSerializationApi::class)
    fun unregisteredLeaves(root: KClass<*>, rootSerializer: KSerializer<*>): List<String> {
        val descriptor = rootSerializer.descriptor
        // `PolymorphicKind` is experimental API. The check only asks whether this is a sealed
        // serializer, and fails loudly rather than silently if that answer ever changes shape.
        check(descriptor.kind == PolymorphicKind.SEALED) {
            "${root.simpleName}'s serializer is ${descriptor.kind}, not a sealed serializer"
        }
        // A sealed descriptor is `{type, value}`; `value`'s elements are the subclass serial names.
        val known = descriptor.getElementDescriptor(1).elementNames.toSet()
        return leavesOf(root).mapNotNull { leaf ->
            val serialName = try {
                serializer(leaf.starProjectedType).descriptor.serialName
            } catch (e: SerializationException) {
                return@mapNotNull "${leaf.qualifiedName} is not @Serializable"
            }
            if (serialName in known) null
            else "${leaf.qualifiedName} ('$serialName') is not in ${root.simpleName}'s polymorphic scope"
        }
    }

    private fun problemsOf(root: KClass<*>, rootSerializer: () -> KSerializer<*>): List<String> =
        try {
            unregisteredLeaves(root, rootSerializer())
        } catch (e: LinkageError) {
            listOf("a class ${root.simpleName}'s serializer names no longer exists (${e.message})")
        }

    private fun leavesOf(klass: KClass<*>): List<KClass<*>> =
        if (klass.isSealed) klass.sealedSubclasses.flatMap(::leavesOf) else listOf(klass)

    private val verified: Unit by lazy {
        // Each root on its own, so a deleted class in one cannot hide what the other found.
        val problems = problemsOf(OutMessage::class) { OutMessage.serializer() } +
            problemsOf(InMessage::class) { InMessage.serializer() }
        check(problems.isEmpty()) {
            "The WebSocket message serializers are out of date with their classes — a stale " +
                "incremental compile, not a code bug (see SocketMessages.kt). Rebuild with " +
                "`./gradlew compileKotlin --rerun-tasks`, then start the desk again. " +
                problems.joinToString("; ")
        }
    }

    /** Throws, naming every stale leaf, unless both roots' serializers know every leaf. Once per JVM. */
    fun verify() = verified
}
