package dk.lifelist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandingTest {

    @Test
    fun `the boundary belongs to the side that clears`() {
        assertEquals(Standing.CLEARS, standing(0.82f, 0.82f))
        assertEquals(Standing.NEAR, standing(0.819f, 0.82f))
    }

    @Test
    fun `the magpie that started this`() {
        // BirdNET 0.77 against the 0.82 the app was using: short, but near enough that the
        // number is worth showing rather than hiding behind "not sure enough" (§65).
        assertEquals(Standing.NEAR, standing(0.77f, 0.82f))
        // A raven at 0.12 is not the same situation, and must not be coloured as if it were.
        assertEquals(Standing.SHORT, standing(0.12f, 0.82f))
    }

    @Test
    fun `near is measured from the threshold, not from a fixed number`() {
        // The same score is near a high bar and clear of a low one. The dial moves the bar
        // (§59), so a band pinned to an absolute score would mean something different at each
        // setting of it.
        assertEquals(Standing.NEAR, standing(0.60f, 0.70f))
        assertEquals(Standing.CLEARS, standing(0.60f, 0.50f))
        assertEquals(Standing.SHORT, standing(0.60f, 0.90f))
    }

    @Test
    fun `every score has a standing`() {
        for (threshold in listOf(0.3f, 0.5f, 0.7f, 0.9f, 1f)) {
            for (step in 0..100) {
                val probability = step / 100f
                val where = standing(probability, threshold)
                assertTrue(
                    when (where) {
                        Standing.CLEARS -> probability >= threshold
                        Standing.NEAR -> probability < threshold
                        Standing.SHORT -> probability < threshold - NEAR_MARGIN
                    },
                    "$probability against $threshold was called $where",
                )
            }
        }
    }
}
