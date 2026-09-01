package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.models.SPEED_MASTER_USAGES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The D7 pin from the busking-view plan: a speed master's `usage` vocabulary is the effect
 * library's own `category` strings, so apply-time routing (`usage == effect.category`) compares
 * like with like. `category` is a free string supplied by script frontmatter, which is exactly
 * why the canonical set needs pinning — a category typo'd or minted in a new `.fx.kts` would
 * otherwise silently route nowhere with no one deciding that.
 *
 * Cost: metadata only — parses each `.fx.kts` frontmatter via [FxFileLoader.parseFxFile],
 * never compiling a body (the `FxRegistrationTargetCompatibilityTest` pattern).
 */
class SpeedMasterUsageVocabularyTest {

    private fun builtInCategories(): Set<String> {
        val loader = FxFileLoader::class.java.classLoader
        val index = loader.getResource("fx/index.txt")!!.readText().lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(index.size >= 25, "fx/index.txt should list at least 25 effects, got ${index.size}")

        return index.map { path ->
            val text = loader.getResource("fx/$path")!!.readText()
            FxFileLoader.parseFxFile(text).first.category
        }.toSet()
    }

    @Test
    fun `the shipped effect library's categories are exactly the known set`() {
        assertEquals(
            setOf("dimmer", "colour", "position", "composite"),
            builtInCategories(),
            "the built-in category vocabulary moved. If you added a category, decide whether it " +
                "routes to a usage-tagged speed master (add it to SPEED_MASTER_USAGES and this " +
                "set) or lands on master 1 like composite/controls (add it to this set only) — " +
                "see D7 in docs/plans/busking-view-plan.md",
        )
    }

    @Test
    fun `every routable usage names a real effect category`() {
        val categories = builtInCategories()
        assertTrue(
            SPEED_MASTER_USAGES.all { it in categories },
            "SPEED_MASTER_USAGES ${SPEED_MASTER_USAGES - categories} name no shipped effect " +
                "category — routing on them could never match an apply",
        )
    }

    @Test
    fun `composite and controls are not routable usages`() {
        // `composite` spans families (LightningStrike computes both a dimmer and a colour half),
        // so no single usage master is *the* default for it; `controls` is the synthetic
        // category for from-state settings effects (EffectSpawner, categoryFromPropertyName),
        // and a settings slider has no tempo. Both route to master 1 via a null
        // `speedMasterUuid`, which is what an unmatched category is defined to do.
        assertTrue("composite" !in SPEED_MASTER_USAGES)
        assertTrue("controls" !in SPEED_MASTER_USAGES)
    }
}
