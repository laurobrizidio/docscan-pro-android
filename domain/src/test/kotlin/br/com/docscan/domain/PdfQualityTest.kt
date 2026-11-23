package br.com.docscan.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfQualityTest {
    private val estimates = mapOf(
        PdfQuality.HIGH to 4_000_000L,
        PdfQuality.BALANCED to 3_000_000L,
        PdfQuality.COMPACT to 2_000_000L,
        PdfQuality.MINIMAL to 1_000_000L,
    )

    @Test
    fun choosesHighWhenEveryQualityFits() {
        assertEquals(PdfQuality.HIGH, bestQualityFor(5_000_000L, estimates))
    }

    @Test
    fun choosesCompactWhenOnlyCompactAndMinimalFit() {
        assertEquals(PdfQuality.COMPACT, bestQualityFor(2_000_000L, estimates))
    }

    @Test
    fun returnsNullWhenNoQualityFits() {
        assertNull(bestQualityFor(999_999L, estimates))
    }

    @Test
    fun skipsQualitiesWithoutAnEstimate() {
        assertEquals(PdfQuality.BALANCED, bestQualityFor(5_000_000L, estimates - PdfQuality.HIGH))
        assertNull(bestQualityFor(Long.MAX_VALUE, emptyMap()))
    }

    @Test
    fun ordersQualityByNonIncreasingJpegQualityAndMaximumSide() {
        assertEquals(PdfQuality.entries.toSet(), qualityLadder.toSet())
        assertTrue(qualityLadder.zipWithNext().all { (higher, lower) ->
            higher.jpeg >= lower.jpeg && higher.maxSide >= lower.maxSide
        })
    }

    @Test
    fun preservesQualityNamesUsedByExistingDrafts() {
        assertEquals(PdfQuality.COMPACT, PdfQuality.valueOf("COMPACT"))
        assertEquals(PdfQuality.BALANCED, PdfQuality.valueOf("BALANCED"))
        assertEquals(PdfQuality.HIGH, PdfQuality.valueOf("HIGH"))
    }
}
