package dk.lifelist.app

import dk.lifelist.core.Certainty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence under the setting, and the shipped table it is read from.
 *
 * Both halves are here because both are places the app could quietly overclaim: a parser that
 * dropped `reached` and a sentence that ignored it would each produce a screen promising 95%
 * over a model managing 89.7%.
 */
class CertaintyTextTest {

    private fun certainty(
        group: String,
        target: Float,
        threshold: Float,
        delivered: Float?,
        reached: Boolean,
        n: Int,
    ) = Certainty(group, target, threshold, delivered, reached, n)

    @Test
    fun `a reached target says what it costs and what it buys`() {
        val text = certainty("Birds", 0.95f, 0.82f, 0.954f, reached = true, n = 3387)
            .let(::forThisAnswer)

        assertTrue(text, "82%" in text)
        assertTrue(text, "95%" in text)
        assertTrue(text, "3,387" in text)
    }

    @Test
    fun `an unreached target says so instead of repeating the promise`() {
        val text = certainty("Mammals", 0.95f, 0.92f, 0.897f, reached = false, n = 497)
            .let(::forThisAnswer)

        assertTrue(text, "out of reach" in text)
        assertTrue(text, "90%" in text)
        assertFalse(text, "right about 95%" in text)
    }

    @Test
    fun `an unmeasured group admits there is no number`() {
        val text = certainty("Reptiles", 0.95f, 0.70f, null, reached = false, n = 0)
            .let(::forThisAnswer)

        assertTrue(text, "too few" in text)
        assertTrue(text, "70%" in text)
    }

    // -- reading the shipped table ----------------------------------------------

    private val meta = """
        {
          "spec_version": 1,
          "temperature": 1.31,
          "n_taxa": 3482,
          "model_version": "2026-08-30",
          "group_thresholds": {
            "0.95": {
              "Birds": {"threshold": 0.82, "accuracy": 0.9536, "reached": true, "n": 3387},
              "Mammals": {"threshold": 0.92, "accuracy": 0.8974, "reached": false, "n": 497},
              "Broken": {"accuracy": 0.9}
            }
          }
        }
    """.trimIndent()

    @Test
    fun `the table is read back with what each group delivers`() {
        val table = TaxonomyAssets.parseMeta(meta).certainty

        assertEquals(listOf(0.95f), table.targets)
        assertEquals(0.82f, table.certaintyFor(0.95f, "Birds").threshold, 1e-6f)
        assertFalse(table.certaintyFor(0.95f, "Mammals").reached)
        assertEquals(497, table.certaintyFor(0.95f, "Mammals").n)
    }

    @Test
    fun `one malformed group costs that group and not the app`() {
        val table = TaxonomyAssets.parseMeta(meta).certainty

        assertFalse(table.certaintyFor(0.95f, "Broken").measured)
        assertTrue(table.certaintyFor(0.95f, "Birds").measured)
    }

    @Test
    fun `a model exported before the fit still loads`() {
        val older = meta.substringBefore(",\n  \"group_thresholds\"") + "\n}"

        val table = TaxonomyAssets.parseMeta(older).certainty

        assertTrue(table.isEmpty)
    }
}
