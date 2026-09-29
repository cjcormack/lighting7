package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.starProjectedType
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SealedSerializerScopeTest {

    // A stale compile, simulated: `Stale` is what the root's serializer looked like before
    // `Added` was declared — same serial names, one leaf short — and `Current` is the hierarchy
    // as the classes now declare it.
    @Serializable
    @SealedIntermediates(Current.Domain::class)
    sealed class Current {
        @Serializable
        sealed class Domain : Current()

        @Serializable
        @SerialName("kept")
        data object Kept : Domain()

        @Serializable
        @SerialName("added")
        data class Added(val value: Int) : Domain()

        // Not a frame at all: a leaf nobody made serializable is reported, not thrown.
        data object Bare : Domain()
    }

    @Serializable
    sealed class Stale {
        @Serializable
        @SerialName("kept")
        data object Kept : Stale()
    }

    // An intermediate two levels down, missing from a list that names a leaf instead.
    @Serializable
    @SealedIntermediates(Mislisted.Outer::class, Mislisted.Outer.Leaf::class)
    sealed class Mislisted {
        @Serializable
        sealed class Outer : Mislisted() {
            @Serializable
            sealed class Inner : Outer()

            @Serializable
            data object Leaf : Outer()
        }
    }

    /**
     * The check the rest of this file exists for, over **every** `@Serializable` sealed hierarchy
     * in the main source set rather than a list someone has to remember to extend.
     *
     * Two halves, doing different jobs. The intermediates half is what CI can catch: a root
     * whose `@SealedIntermediates` misses one fails here on a clean build, before its leaves
     * have had a chance to go stale on anybody's incremental one. The leaves half catches the
     * staleness itself, which only an incremental compile produces — so it fails locally, in
     * the same `./gradlew test` whose classes are stale, rather than at the desk's startup.
     */
    @Test
    fun `every serializable sealed hierarchy in main lists its intermediates and has a current serializer`() {
        val roots = serializableSealedRoots()
        assertTrue(InMessage::class in roots && OutMessage::class in roots, "scan found only $roots")

        assertEquals(
            emptyList(),
            roots.flatMap(SealedSerializerScope::unpinnedIntermediates),
            "list each in its root's @SealedIntermediates (see SealedSerializers.kt)",
        )
        val stale = roots.flatMap { root ->
            try {
                SealedSerializerScope.unregisteredLeaves(root, serializer(root.starProjectedType))
            } catch (e: LinkageError) {
                listOf("a class ${root.qualifiedName}'s serializer names no longer exists (${e.message})")
            }
        }
        assertEquals(emptyList(), stale, "a stale incremental compile: `./gradlew compileKotlin --rerun-tasks`")
        SocketMessageScope.verify()
    }

    @Test
    fun `a leaf the root's serializer does not know is named, however deep it sits`() {
        val missing = SealedSerializerScope.unregisteredLeaves(Current::class, Stale.serializer())

        assertEquals(2, missing.size, "$missing")
        assertTrue(missing.any { "Current.Added" in it && "'added'" in it }, "$missing")
        assertTrue(missing.any { "Current.Bare" in it && "not @Serializable" in it }, "$missing")
    }

    @Test
    fun `the same hierarchy against its own serializer is clean but for the unserializable leaf`() {
        val missing = SealedSerializerScope.unregisteredLeaves(Current::class, Current.serializer())

        assertEquals(1, missing.size, "$missing")
        assertTrue("Current.Bare" in missing.single(), "$missing")
    }

    @Test
    fun `a complete intermediates list is clean`() {
        assertEquals(emptyList(), SealedSerializerScope.unpinnedIntermediates(Current::class))
    }

    @Test
    fun `an unlisted intermediate at any depth and a listed non-intermediate are both named`() {
        val problems = SealedSerializerScope.unpinnedIntermediates(Mislisted::class)

        assertEquals(2, problems.size, "$problems")
        assertTrue(problems.any { "Outer.Inner" in it && "missing" in it }, "$problems")
        assertTrue(problems.any { "Outer.Leaf" in it && "not a sealed intermediate" in it }, "$problems")
    }

    @Test
    fun `a hierarchy with no intermediates needs no list`() {
        assertEquals(emptyList(), SealedSerializerScope.unpinnedIntermediates(Stale::class))
    }

    /**
     * The top of every `@Serializable` sealed hierarchy compiled from `src/main`, found by walking
     * the class-file directory [OutMessage] was loaded from. Classes are loaded without being
     * initialised, so the scan runs no static initialiser and builds no serializer.
     */
    private fun serializableSealedRoots(): Set<KClass<*>> {
        val dir = File(OutMessage::class.java.protectionDomain.codeSource.location.toURI())
        check(dir.isDirectory) { "expected the main classes directory, got $dir" }
        val loader = OutMessage::class.java.classLoader
        return dir.walk()
            .filter { it.isFile && it.extension == "class" }
            .map { Class.forName(it.relativeTo(dir).path.removeSuffix(".class").replace(File.separatorChar, '.'), false, loader) }
            // Every sealed class or interface is abstract; the filter keeps Kotlin reflection off
            // the thousands of concrete and synthetic classes.
            .filter { Modifier.isAbstract(it.modifiers) }
            .map { it.kotlin }
            .filter { it.isSealed && it.hasAnnotation<Serializable>() }
            // A root: no serializable sealed class above it. A sealed parent that is not itself
            // serializable does not make its serializable child an intermediate.
            .filter { k ->
                k.supertypes.none { t ->
                    (t.classifier as? KClass<*>)?.let { it.isSealed && it.hasAnnotation<Serializable>() } == true
                }
            }
            .toSet()
    }
}
