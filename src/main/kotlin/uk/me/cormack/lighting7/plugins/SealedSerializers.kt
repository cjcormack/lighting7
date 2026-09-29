package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.starProjectedType

/**
 * Names every sealed class *between* the annotated `@Serializable` sealed root and its leaves, so
 * that the root's file references them — which is what keeps the root's generated serializer
 * current under Kotlin's incremental compile.
 *
 * The trap it closes: kotlinx generates a sealed root's serializer in the root's own file,
 * enumerating every leaf of the whole hierarchy. Adding, removing or renaming a leaf under an
 * intermediate declared in *another* file changes that intermediate's metadata, and the
 * incremental compiler recompiles exactly the files that reference the intermediate by name. The
 * serializer's reference is plugin-generated and not tracked, so without this annotation nothing
 * in the root's file names the intermediate, the root is not recompiled, and the build goes green
 * around a serializer that cannot encode the new leaf ("not found in the polymorphic scope") or
 * still names a deleted one (`NoClassDefFoundError`). A class literal in this annotation *is* a
 * tracked reference, so a leaf change under a listed intermediate recompiles the root with it.
 *
 * A **direct** subclass of the root needs no entry: the root's own metadata lists it, so the
 * compiler already recompiles the root's file when one is added or removed anywhere. That is
 * also why the list can be checked — a new intermediate recompiles the root, so
 * [SealedSerializerScope.unpinnedIntermediates] sees it at once and `SealedSerializerScopeTest`
 * fails, in CI as well as locally, until it is listed here.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SealedIntermediates(vararg val value: KClass<*>)

/**
 * Two readings of one `@Serializable` sealed hierarchy, for the checks that keep its generated
 * serializer honest. The **reflective** one walks [KClass.sealedSubclasses] from the root, and is
 * fresh wherever it matters: a leaf's metadata lives in its intermediate's class file, which is
 * recompiled with the file the leaf was added to. The **generated** one is the element list of
 * the root's sealed serializer, which is the half an incremental compile can leave stale.
 */
object SealedSerializerScope {
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

    /**
     * Where [root]'s [SealedIntermediates] disagrees with its hierarchy: an intermediate it does
     * not list (whose leaves can go stale), or an entry that is not an intermediate of [root].
     */
    fun unpinnedIntermediates(root: KClass<*>): List<String> {
        val actual = intermediatesOf(root).toSet()
        val listed = root.findAnnotation<SealedIntermediates>()?.value?.toSet().orEmpty()
        return (actual - listed).map {
            "${it.qualifiedName} is a sealed intermediate of ${root.simpleName} missing from its @SealedIntermediates"
        } + (listed - actual).map {
            "${it.qualifiedName} is in ${root.simpleName}'s @SealedIntermediates but is not a sealed intermediate of it"
        }
    }

    /** Every sealed class strictly below [root], at any depth. */
    fun intermediatesOf(root: KClass<*>): List<KClass<*>> =
        root.sealedSubclasses.filter { it.isSealed }.flatMap { listOf(it) + intermediatesOf(it) }

    private fun leavesOf(klass: KClass<*>): List<KClass<*>> =
        if (klass.isSealed) klass.sealedSubclasses.flatMap(::leavesOf) else listOf(klass)
}
