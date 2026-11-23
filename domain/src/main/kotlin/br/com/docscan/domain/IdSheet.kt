package br.com.docscan.domain

import kotlin.math.roundToInt

data class PixelRect(val x: Int, val y: Int, val w: Int, val h: Int)

data class IdSheetLayout(val sheetW: Int, val sheetH: Int, val front: PixelRect, val back: PixelRect?)

const val ID_CARD_RATIO = 85.60f / 53.98f

fun idSheetLayout(hasBack: Boolean, dpi: Int = 300, gapMm: Float = 10f): IdSheetLayout {
    fun px(mm: Float) = (mm / 25.4f * dpi).roundToInt()

    val sheetW = px(210f)
    val sheetH = px(297f)
    val cardW = px(85.60f)
    val cardH = px(53.98f)
    val gap = px(gapMm)
    val blockH = if (hasBack) 2 * cardH + gap else cardH
    val front = PixelRect((sheetW - cardW) / 2, (sheetH - blockH) / 2, cardW, cardH)
    val back = if (hasBack) front.copy(y = front.y + cardH + gap) else null
    return IdSheetLayout(sheetW, sheetH, front, back)
}
