package br.com.docscan.domain

import java.util.TimeZone
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdSheetTest {
    @Test
    fun twoSidesAreCenteredOnAnA4SheetWithA10MmGap() {
        val layout = idSheetLayout(hasBack = true)
        assertEquals(2480, layout.sheetW)
        assertEquals(3508, layout.sheetH)

        val front = layout.front
        val back = requireNotNull(layout.back)
        for (card in listOf(front, back)) {
            assertEquals(1011, card.w)
            assertEquals(638, card.h)
            assertTrue(abs(2 * card.x + card.w - layout.sheetW) <= 1)
        }
        assertEquals(118, back.y - (front.y + front.h))
        assertTrue(abs(front.y - (layout.sheetH - back.y - back.h)) <= 1)
    }

    @Test
    fun frontOnlyIsCenteredOnTheSheet() {
        val layout = idSheetLayout(hasBack = false)
        val front = layout.front
        assertNull(layout.back)
        assertTrue(abs(2 * front.x + front.w - layout.sheetW) <= 1)
        assertTrue(abs(2 * front.y + front.h - layout.sheetH) <= 1)
    }

    @Test
    fun defaultNameWithIdCardPrefixUsesTheLocalTime() {
        val zone = TimeZone.getTimeZone("GMT-04:00")
        assertEquals("Cartão de ID 31-12-1969 20.00", defaultDocumentName(0, zone, prefix = "Cartão de ID"))
    }
}
