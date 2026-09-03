package com.roadguard.app.data.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract for ego-lane pair selection. The old LaneDetector took the single
 * strongest histogram peak per half-frame — on multi-lane roads that is the
 * OUTER marking, so the overlay spanned two lanes and drift warnings fired
 * while driving straight (verified on 3 dashcam clips).
 * RED first: the inner (ego) pair must beat the wider outer pair.
 */
class EgoLaneSelectorTest {

    private lateinit var selector: EgoLaneSelector
    private val w = 800

    @Before
    fun setUp() {
        selector = EgoLaneSelector()
    }

    private fun cand(xTop: Float, xBottom: Float) = EgoLaneSelector.Candidate(xTop, xBottom)

    @Test
    fun innerPairBeatsOuterPair() {
        // solidWhiteRight frame 121 (working coords): L176/R584 hug the ego
        // lane, L4/R788 are the outer markings one lane over.
        val left = listOf(cand(373f, 92f), cand(371f, 4f))
        val right = listOf(cand(407f, 669f), cand(467f, 788f))
        val pair = selector.selectPair(left, right, w)
        assertNotNull("a pair must be selected", pair)
        assertEquals(92f, pair!!.first.xBottom, 1f)
        assertEquals(669f, pair.second.xBottom, 1f)
    }

    @Test
    fun gluedCandidatesLoseToValidOnes() {
        // Outer marking trace ran to the border (xBottom=0) while the true ego
        // marking traced cleanly; with a plausible right partner the valid
        // left candidate must win over the glued one.
        val glued = cand(350f, 0f)
        val valid = cand(346f, 98f)
        val right = listOf(cand(380f, 378f))
        val pair = selector.selectPair(listOf(glued, valid), right, w)
        assertNotNull("a pair must be selected", pair)
        assertEquals(98f, pair!!.first.xBottom, 1f)
    }

    @Test
    fun implausibleWidthsAreRejected() {
        // Lane nearly as wide as the frame: adjacent-lane + shoulder edge.
        val pair = selector.selectPair(
            listOf(cand(300f, 50f)), listOf(cand(400f, 750f)), w
        )
        assertNull("700px pair on 800px frame must be rejected", pair)
    }

    @Test
    fun singleOutlierDoesNotShiftWidthPrior() {
        repeat(6) { selector.observeWidth(280f, w) }
        val prior = selector.expectedWidth
        assertTrue("prior must settle near 280, was $prior", prior in 200f..360f)
        selector.observeWidth(600f, w)
        assertTrue(
            "one 600px outlier must not move the prior (was $prior, now ${selector.expectedWidth})",
            kotlin.math.abs(selector.expectedWidth - prior) < 30f
        )
    }

    @Test
    fun consistentNarrowerLaneMovesPrior() {
        repeat(6) { selector.observeWidth(280f, w) }
        val before = selector.expectedWidth
        // Genuine change (road works, exit lane): many consistent observations.
        repeat(15) { selector.observeWidth(200f, w) }
        assertTrue(
            "prior must move toward 200 after consistent observations (was $before, now ${selector.expectedWidth})",
            selector.expectedWidth < before
        )
    }
}
