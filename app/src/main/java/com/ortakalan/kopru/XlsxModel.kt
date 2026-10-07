package com.ortakalan.kopru

import android.graphics.Color
import java.util.Locale

object TextTools {
    val TR: Locale = Locale("tr", "TR")
}

/** kind: 0 = metin, 1 = sayı/tarih, 2 = mantıksal/hata (ortalanır). text zaten Excel biçimiyle biçimlendirilmiştir. */
class Cell(val col: Int, val text: String, val style: Int, val kind: Int, val link: String?)

class RowData(
    val number: Int,
    val heightPx: Float,
    val hidden: Boolean,
    val cells: Array<Cell>
) {
    val searchKey: String by lazy {
        val sb = StringBuilder()
        for (c in cells) {
            if (c.text.isNotEmpty()) sb.append(c.text).append(' ')
        }
        sb.toString().lowercase(TextTools.TR)
    }

    fun cellAt(col: Int): Cell? {
        var lo = 0
        var hi = cells.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cells[mid].col
            if (c == col) return cells[mid]
            if (c < col) lo = mid + 1 else hi = mid - 1
        }
        return null
    }
}

class Merge(val r1: Int, val c1: Int, val r2: Int, val c2: Int)

/** level: 1 ince, 2 orta, 3 kalın */
class BorderSide(val level: Int, val color: Int)

class XfStyle(
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val sizePt: Float,
    val fontColor: Int,
    val fillColor: Int, // 0 = dolgu yok
    val left: BorderSide?,
    val right: BorderSide?,
    val top: BorderSide?,
    val bottom: BorderSide?,
    val hAlign: Int, // 0 genel, 1 sol, 2 orta, 3 sağ
    val vAlign: Int, // 0 alt, 1 orta, 2 üst
    val wrap: Boolean,
    val indent: Int,
    val fmtCode: String?
) {
    companion object {
        val DEFAULT = XfStyle(
            false, false, false, 11f, Color.BLACK, 0,
            null, null, null, null, 0, 0, false, 0, null
        )
    }
}

class SheetData(
    val rowsByNumber: Array<RowData?>,
    val lastRow: Int,
    val colWidthsPx: FloatArray,
    val defaultRowPx: Float,
    val merges: List<Merge>,
    val frozenRows: Int,
    val frozenCols: Int,
    val showGrid: Boolean,
    val styles: List<XfStyle>
)
