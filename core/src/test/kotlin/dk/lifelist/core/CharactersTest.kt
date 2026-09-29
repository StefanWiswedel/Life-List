package dk.lifelist.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The wasp that started it (§95). The camera names seven of Denmark's eleven Vespidae and
 * stops at *Vespula*, because what separates *vulgaris* from *germanica* is on the clypeus and
 * is not in a photograph taken from above.
 */
class CharactersTest {

    private val vulgaris = 1311477
    private val germanica = 1311527
    private val rufa = 1311560

    private val clypeus = Characters.Character(
        key = "clypeus",
        prompt = "What does the face look like?",
        options = listOf(
            Characters.Option("One black mark, anchor- or dagger-shaped", listOf(vulgaris)),
            Characters.Option("Three small black dots", listOf(germanica)),
        ),
        source = "https://en.wikipedia.org/wiki/Vespula_germanica",
        note = "The mark can appear broken, especially in males, which makes the two look alike.",
    )

    private fun close(a: Float, b: Float) = assertTrue(abs(a - b) < 1e-6f, "$a != $b")

    @Test
    fun `the chosen option is favoured and the other is not deleted`() {
        val after = Characters.applyAnswer(
            mapOf(vulgaris to 0.5f, germanica to 0.5f), clypeus, "Three small black dots",
        )
        assertTrue(after.getValue(germanica) > after.getValue(vulgaris))
        assertTrue(after.getValue(vulgaris) > 0f, "an honest answer must not make a species unloggable")
    }

    @Test
    fun `a species the character says nothing about is left alone`() {
        // Silence is not contradiction. Treating it as such would quietly delete every
        // candidate that no key happens to cover.
        val after = Characters.applyAnswer(
            mapOf(vulgaris to 0.4f, germanica to 0.4f, rufa to 0.2f),
            clypeus, "Three small black dots",
        )
        close(0.2f, after.getValue(rufa))
    }

    @Test
    fun `two answers settle it without ever reaching certainty`() {
        var p = mapOf(vulgaris to 0.5f, germanica to 0.5f)
        repeat(2) { p = Characters.renormalise(Characters.applyAnswer(p, clypeus, "Three small black dots")) }
        assertTrue(p.getValue(germanica) > 0.99f)
        assertTrue(p.getValue(vulgaris) > 0f)
    }

    @Test
    fun `an unknown answer is refused rather than ignored`() {
        assertFailsWith<IllegalArgumentException> {
            Characters.applyAnswer(mapOf(vulgaris to 1f), clypeus, "yellow antennae")
        }
    }

    @Test
    fun `an answer that rules out everything does not divide by zero`() {
        close(Characters.CONTRARY, Characters.applyAnswer(mapOf(vulgaris to 1f), clypeus, "Three small black dots").getValue(vulgaris))
        close(0f, Characters.renormalise(mapOf(vulgaris to 0f)).getValue(vulgaris))
    }

    @Test
    fun `a character that cannot split the candidates is not offered`() {
        val guide = Characters.Guide(1311473, view = "the face", characters = listOf(clypeus))
        assertEquals(listOf(clypeus), guide.applicable(listOf(vulgaris, germanica)))
        assertEquals(emptyList(), guide.applicable(listOf(vulgaris, rufa)))
        assertEquals(emptyList(), guide.applicable(listOf(rufa)))
    }

    @Test
    fun `the dense update agrees with the sparse one`() {
        val index = mapOf(vulgaris to 0, germanica to 1, rufa to 2)
        val got = Characters.narrow(floatArrayOf(0.4f, 0.4f, 0.2f), index, clypeus, "Three small black dots")
        val want = Characters.renormalise(
            Characters.applyAnswer(
                mapOf(vulgaris to 0.4f, germanica to 0.4f, rufa to 0.2f),
                clypeus, "Three small black dots",
            )
        )
        close(want.getValue(vulgaris), got[0])
        close(want.getValue(germanica), got[1])
        close(want.getValue(rufa), got[2])
        close(1f, got.sum())
    }

    @Test
    fun `a taxon the model does not carry is skipped rather than crashing`() {
        // A guide outlives the model it was written against, exactly as a saved record does.
        val got = Characters.narrow(floatArrayOf(1f), mapOf(vulgaris to 0), clypeus, "Three small black dots")
        close(1f, got[0])
    }
}
