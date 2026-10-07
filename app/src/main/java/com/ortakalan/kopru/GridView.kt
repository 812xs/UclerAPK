package com.ortakalan.kopru

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.max
import kotlin.math.min

/**
 * Excel'e benzeyen ızgara: sütun harfleri, satır numaraları, hücre kenarlıkları/dolguları, birleştirilmiş hücreler,
 * sabit paneller, iki yöne kaydırma, iki parmakla yakınlaştırma. Köprülü hücreye dokunulunca onLinkTap çağrılır.
 */
class GridView(context: Context) : View(context) {

    var onLinkTap: ((Cell) -> Unit)? = null
    var onCellSelected: ((String, String) -> Unit)? = null

    private var data: SheetData? = null
    private val dens = resources.displayMetrics.density
    private var zoom = 1f

    private var colCount = 0
    private var colLeft = FloatArray(1)
    private var visRows = IntArray(0)
    private var rowTop = FloatArray(1)
    private var rowIndex = IntArray(1)
    private var frozenRowCount = 0
    private var frozenCols = 0
    private var gutterW = 40f * dens
    private val headerH = 24f * dens

    private var panX = 0f
    private var panY = 0f
    private var selRow = 0
    private var selCol = -1
    private var words: List<String> = emptyList()

    private var mergeAt = HashMap<Long, Merge>()
    private var covered = HashSet<Long>()

    private val scroller = OverScroller(context)

    private val gridPaint = Paint().apply {
        color = 0xFFD4D4D4.toInt()
        strokeWidth = 1f
        isAntiAlias = false
    }
    private val fillPaint = Paint()
    private val borderPaint = Paint().apply { isAntiAlias = false }
    private val selPaint = Paint().apply {
        color = 0xFF1A73E8.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dens
        isAntiAlias = true
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val headBg = Paint().apply { color = 0xFFF3F3F3.toInt() }
    private val headSel = Paint().apply { color = 0xFFD3E3FD.toInt() }
    private val headLine = Paint().apply {
        color = 0xFFBDBDBD.toInt()
        strokeWidth = 1f
    }
    private val headText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt()
        textSize = 12f * dens
    }
    private val freezePaint = Paint().apply {
        color = 0xFF8A8A8A.toInt()
        strokeWidth = 2f
    }
    private val rf = RectF()

    private val typefaces = arrayOf(
        Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
        Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
        Typeface.create(Typeface.DEFAULT, Typeface.ITALIC),
        Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    )

    private class Item(val cell: Cell, val rn: Int, val idx: Int, val merge: Merge?)

    private fun key(r: Int, c: Int): Long = r.toLong() * 20000L + c

    // ------------------------------------------------------------------ veri

    fun setData(sd: SheetData) {
        data = sd
        colCount = sd.colWidthsPx.size
        colLeft = FloatArray(colCount + 1)
        for (c in 0 until colCount) colLeft[c + 1] = colLeft[c] + sd.colWidthsPx[c]
        frozenCols = sd.frozenCols.coerceIn(0, colCount)
        mergeAt = HashMap()
        covered = HashSet()
        for (m in sd.merges) {
            mergeAt[key(m.r1, m.c1)] = m
            val area = (m.r2 - m.r1 + 1).toLong() * (m.c2 - m.c1 + 1).toLong()
            if (area <= 5000L) {
                for (r in m.r1..m.r2) {
                    for (c in m.c1..m.c2) {
                        if (r != m.r1 || c != m.c1) covered.add(key(r, c))
                    }
                }
            }
        }
        val digits = sd.lastRow.toString().length
        gutterW = (digits * 8f + 18f) * dens
        panX = 0f
        panY = 0f
        selRow = 0
        selCol = -1
        rebuildRows()
    }

    fun setFilter(w: List<String>) {
        words = w
        panY = 0f
        rebuildRows()
    }

    fun shownRowCount(): Int = max(0, visRows.size - frozenRowCount)

    private fun rebuildRows() {
        val d = data ?: return
        val tmp = IntArray(d.lastRow + 1)
        var n = 0
        var fr = 0
        for (rn in 1..d.lastRow) {
            val rd = d.rowsByNumber[rn]
            if (rd != null && rd.hidden) continue
            val isHead = rn <= d.frozenRows
            val ok = isHead || words.isEmpty() || (rd != null && words.all { rd.searchKey.contains(it) })
            if (!ok) continue
            tmp[n] = rn
            n++
            if (isHead) fr++
        }
        visRows = tmp.copyOf(n)
        frozenRowCount = fr
        rowTop = FloatArray(n + 1)
        rowIndex = IntArray(d.lastRow + 2) { -1 }
        for (i in 0 until n) {
            val rn = visRows[i]
            val h = d.rowsByNumber[rn]?.heightPx ?: d.defaultRowPx
            rowTop[i + 1] = rowTop[i] + h
            rowIndex[rn] = i
        }
        clampPan()
        invalidate()
    }

    // ------------------------------------------------------------------ geometri

    private fun maxPanX(): Float {
        val u = dens * zoom
        return max(0f, colLeft[colCount] * u - (width - gutterW))
    }

    private fun maxPanY(): Float {
        val u = dens * zoom
        return max(0f, rowTop[visRows.size] * u - (height - headerH)) + 24f * dens
    }

    private fun clampPan() {
        panX = panX.coerceIn(0f, maxPanX())
        panY = panY.coerceIn(0f, maxPanY())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
    }

    private fun colAt(x: Float): Int {
        var lo = 0
        var hi = colCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (colLeft[mid] <= x) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun rowIdxAt(y: Float): Int {
        val n = visRows.size
        if (n == 0) return -1
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (rowTop[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun lastVisIdxAtOrBefore(rn: Int): Int {
        var lo = 0
        var hi = visRows.size - 1
        var res = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (visRows[mid] <= rn) {
                res = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return res
    }

    private fun colName(c: Int): String {
        var n = c + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, 'A' + r)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun styleOf(d: SheetData, s: Int): XfStyle = d.styles.getOrNull(s) ?: XfStyle.DEFAULT

    private fun cellRect(it: Item, ax: Float, ay: Float, u: Float, out: RectF) {
        val m = it.merge
        out.left = ax + colLeft[it.cell.col] * u
        out.top = ay + rowTop[it.idx] * u
        if (m == null) {
            out.right = ax + colLeft[min(it.cell.col + 1, colCount)] * u
            out.bottom = ay + rowTop[it.idx + 1] * u
        } else {
            val c2 = min(m.c2 + 1, colCount)
            out.right = ax + colLeft[c2] * u
            val li = max(lastVisIdxAtOrBefore(m.r2), it.idx)
            out.bottom = ay + rowTop[li + 1] * u
        }
    }

    // ------------------------------------------------------------------ çizim

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val d = data ?: return
        if (colCount == 0) return
        val u = dens * zoom
        val w = width.toFloat()
        val h = height.toFloat()
        val ox = gutterW
        val oy = headerH
        val fx = colLeft[frozenCols] * u
        val fy = rowTop[frozenRowCount] * u

        drawQuad(canvas, d, u, ox, oy, ox + fx, oy + fy, false, false)
        drawQuad(canvas, d, u, ox + fx, oy, w, oy + fy, true, false)
        drawQuad(canvas, d, u, ox, oy + fy, ox + fx, h, false, true)
        drawQuad(canvas, d, u, ox + fx, oy + fy, w, h, true, true)

        if (frozenCols > 0) canvas.drawLine(ox + fx, oy, ox + fx, h, freezePaint)
        if (frozenRowCount > 0) canvas.drawLine(ox, oy + fy, w, oy + fy, freezePaint)

        drawHeaders(canvas, u, ox + fx, oy + fy)
    }

    private fun drawQuad(
        canvas: Canvas, d: SheetData, u: Float,
        left: Float, top: Float, right: Float, bottom: Float,
        scrollCols: Boolean, scrollRows: Boolean
    ) {
        if (right <= left || bottom <= top || visRows.isEmpty()) return
        val ox = gutterW
        val oy = headerH
        val sx = if (scrollCols) panX else 0f
        val sy = if (scrollRows) panY else 0f

        val c0: Int
        val c1: Int
        if (scrollCols) {
            c0 = colAt((left - ox + sx) / u)
            c1 = colAt((right - ox + sx) / u)
        } else {
            c0 = 0
            c1 = frozenCols - 1
        }
        val r0: Int
        val r1: Int
        if (scrollRows) {
            r0 = rowIdxAt((top - oy + sy) / u)
            r1 = rowIdxAt((bottom - oy + sy) / u)
        } else {
            r0 = 0
            r1 = frozenRowCount - 1
        }
        if (c1 < c0 || r1 < r0 || r0 < 0) return

        val ax = ox - sx
        val ay = oy - sy

        canvas.save()
        canvas.clipRect(left, top, right, bottom)

        // --- çizilecek hücreleri topla
        val items = ArrayList<Item>()
        for (i in r0..r1) {
            val rn = visRows[i]
            val rd = d.rowsByNumber[rn] ?: continue
            for (cell in rd.cells) {
                if (cell.col < c0) continue
                if (cell.col > c1) break
                val k = key(rn, cell.col)
                if (covered.contains(k)) continue
                items.add(Item(cell, rn, i, mergeAt[k]))
            }
        }
        for (m in d.merges) {
            val idx = rowIndex.getOrElse(m.r1) { -1 }
            if (idx < 0) continue
            if (idx in r0..r1 && m.c1 in c0..c1) continue
            val lastIdx = lastVisIdxAtOrBefore(m.r2)
            if (lastIdx < r0 || idx > r1) continue
            if (m.c2 < c0 || m.c1 > c1) continue
            val cell = d.rowsByNumber[m.r1]?.cellAt(m.c1) ?: continue
            items.add(Item(cell, m.r1, idx, m))
        }

        // --- 1) ızgara çizgileri
        if (d.showGrid) {
            for (c in c0..c1 + 1) {
                val x = ax + colLeft[min(c, colCount)] * u
                canvas.drawLine(x, top, x, bottom, gridPaint)
            }
            for (i in r0..r1 + 1) {
                val y = ay + rowTop[min(i, visRows.size)] * u
                canvas.drawLine(left, y, right, y, gridPaint)
            }
        }

        // --- 2) dolgular
        for (it in items) {
            val st = styleOf(d, it.cell.style)
            if (st.fillColor != 0) {
                cellRect(it, ax, ay, u, rf)
                fillPaint.color = st.fillColor
                canvas.drawRect(rf, fillPaint)
            }
        }

        // --- 3) kenarlıklar
        for (it in items) {
            val st = styleOf(d, it.cell.style)
            if (st.left == null && st.right == null && st.top == null && st.bottom == null) continue
            cellRect(it, ax, ay, u, rf)
            val l = st.left
            if (l != null) {
                setBorder(l)
                canvas.drawLine(rf.left, rf.top, rf.left, rf.bottom, borderPaint)
            }
            val r = st.right
            if (r != null) {
                setBorder(r)
                canvas.drawLine(rf.right, rf.top, rf.right, rf.bottom, borderPaint)
            }
            val t = st.top
            if (t != null) {
                setBorder(t)
                canvas.drawLine(rf.left, rf.top, rf.right, rf.top, borderPaint)
            }
            val b = st.bottom
            if (b != null) {
                setBorder(b)
                canvas.drawLine(rf.left, rf.bottom, rf.right, rf.bottom, borderPaint)
            }
        }

        // --- 4) metinler
        for (it in items) {
            if (it.cell.text.isEmpty()) continue
            val st = styleOf(d, it.cell.style)
            cellRect(it, ax, ay, u, rf)
            drawText(canvas, d, u, it, st, ax)
        }

        // --- 5) seçili hücre
        if (selCol >= 0 && selRow > 0) {
            val si = rowIndex.getOrElse(selRow) { -1 }
            if (si in r0..r1 && selCol in c0..c1) {
                val cell = d.rowsByNumber[selRow]?.cellAt(selCol)
                val m = mergeAt[key(selRow, selCol)]
                val it = Item(cell ?: Cell(selCol, "", 0, 0, null), selRow, si, m)
                cellRect(it, ax, ay, u, rf)
                canvas.drawRect(rf, selPaint)
            }
        }

        canvas.restore()
    }

    private fun setBorder(b: BorderSide) {
        borderPaint.color = b.color
        borderPaint.strokeWidth = max(1f, b.level * dens)
    }

    private fun drawText(canvas: Canvas, d: SheetData, u: Float, it: Item, st: XfStyle, ax: Float) {
        val cell = it.cell
        val pad = 3f * u
        textPaint.textSize = st.sizePt * 96f / 72f * u
        val tfIndex = (if (st.bold) 1 else 0) + (if (st.italic) 2 else 0)
        textPaint.typeface = typefaces[tfIndex]
        var color = st.fontColor
        var under = st.underline
        if (cell.link != null && color == Color.BLACK) {
            color = 0xFF0563C1.toInt()
            under = true
        }
        textPaint.color = color
        textPaint.isUnderlineText = under

        val kind = cell.kind
        val h = when (st.hAlign) {
            1 -> 1
            2 -> 2
            3 -> 3
            else -> when (kind) {
                1 -> 3
                2 -> 2
                else -> 1
            }
        }

        val left = rf.left
        val top = rf.top
        val bottom = rf.bottom
        var right = rf.right
        val indentPx = if (h == 1) st.indent * 9f * u else 0f

        if (st.wrap) {
            val text = cell.text
            val w = max(1, (right - left - 2 * pad - indentPx).toInt())
            val align = when (h) {
                2 -> Layout.Alignment.ALIGN_CENTER
                3 -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, w)
                .setAlignment(align)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()
            val lh = layout.height.toFloat()
            val ty = when (st.vAlign) {
                1 -> top + (bottom - top - lh) / 2f
                2 -> top + pad * 0.5f
                else -> bottom - lh - pad * 0.5f
            }
            canvas.save()
            canvas.clipRect(left, top, right, bottom)
            canvas.translate(left + pad + indentPx, ty)
            layout.draw(canvas)
            canvas.restore()
            return
        }

        var s = cell.text
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) s = s.replace('\n', ' ').replace('\r', ' ')
        var tw = textPaint.measureText(s)
        val availW = right - left - 2 * pad - indentPx

        // soldan hizalı metin boş komşu hücrelere taşar
        if (h == 1 && kind == 0 && it.merge == null && tw > availW) {
            val rd = d.rowsByNumber[it.rn]
            var limitCol = colCount
            if (rd != null) {
                for (c in rd.cells) {
                    if (c.col > cell.col && c.text.isNotEmpty()) {
                        limitCol = c.col
                        break
                    }
                }
            }
            right = ax + colLeft[limitCol] * u
        }

        if (kind == 1 && tw > availW) {
            val hash = textPaint.measureText("#")
            val cnt = max(1, if (hash > 0f) (availW / hash).toInt() else 1)
            s = "#".repeat(cnt)
            tw = textPaint.measureText(s)
        }

        val fm = textPaint.fontMetrics
        val y = when (st.vAlign) {
            1 -> (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f
            2 -> top + pad * 0.5f - fm.ascent
            else -> bottom - pad * 0.6f - fm.descent
        }
        val x = when (h) {
            3 -> rf.right - pad - tw
            2 -> (rf.left + rf.right) / 2f - tw / 2f
            else -> left + pad + indentPx
        }
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        canvas.drawText(s, x, y, textPaint)
        canvas.restore()
    }

    // ------------------------------------------------------------------ başlıklar

    private fun drawHeaders(canvas: Canvas, u: Float, scrollLeft: Float, scrollTop: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, gutterW, headerH, headBg)
        canvas.drawLine(0f, headerH - 1f, gutterW, headerH - 1f, headLine)
        canvas.drawLine(gutterW - 1f, 0f, gutterW - 1f, headerH, headLine)

        drawColHeaders(canvas, u, gutterW, gutterW + colLeft[frozenCols] * u, false)
        drawColHeaders(canvas, u, scrollLeft, w, true)
        drawRowHeaders(canvas, u, headerH, headerH + rowTop[frozenRowCount] * u, false)
        drawRowHeaders(canvas, u, scrollTop, h, true)
    }

    private fun drawColHeaders(canvas: Canvas, u: Float, left: Float, right: Float, scroll: Boolean) {
        if (right <= left) return
        val sx = if (scroll) panX else 0f
        val c0: Int
        val c1: Int
        if (scroll) {
            c0 = colAt((left - gutterW + sx) / u)
            c1 = colAt((right - gutterW + sx) / u)
        } else {
            c0 = 0
            c1 = frozenCols - 1
        }
        if (c1 < c0) return
        canvas.save()
        canvas.clipRect(left, 0f, right, headerH)
        val fm = headText.fontMetrics
        val ty = headerH / 2f - (fm.ascent + fm.descent) / 2f
        for (c in c0..c1) {
            val x1 = gutterW - sx + colLeft[c] * u
            val x2 = gutterW - sx + colLeft[c + 1] * u
            if (x2 - x1 < 1f) continue
            canvas.drawRect(x1, 0f, x2, headerH, if (c == selCol) headSel else headBg)
            canvas.drawLine(x2, 0f, x2, headerH, headLine)
            val name = colName(c)
            canvas.drawText(name, (x1 + x2) / 2f - headText.measureText(name) / 2f, ty, headText)
        }
        canvas.drawLine(left, headerH - 1f, right, headerH - 1f, headLine)
        canvas.restore()
    }

    private fun drawRowHeaders(canvas: Canvas, u: Float, top: Float, bottom: Float, scroll: Boolean) {
        if (bottom <= top || visRows.isEmpty()) return
        val sy = if (scroll) panY else 0f
        val r0: Int
        val r1: Int
        if (scroll) {
            r0 = rowIdxAt((top - headerH + sy) / u)
            r1 = rowIdxAt((bottom - headerH + sy) / u)
        } else {
            r0 = 0
            r1 = frozenRowCount - 1
        }
        if (r1 < r0 || r0 < 0) return
        canvas.save()
        canvas.clipRect(0f, top, gutterW, bottom)
        val fm = headText.fontMetrics
        for (i in r0..r1) {
            val y1 = headerH - sy + rowTop[i] * u
            val y2 = headerH - sy + rowTop[i + 1] * u
            if (y2 - y1 < 1f) continue
            val rn = visRows[i]
            canvas.drawRect(0f, y1, gutterW, y2, if (rn == selRow) headSel else headBg)
            canvas.drawLine(0f, y2, gutterW, y2, headLine)
            val name = rn.toString()
            val ty = (y1 + y2) / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(name, gutterW / 2f - headText.measureText(name) / 2f, ty, headText)
        }
        canvas.drawLine(gutterW - 1f, top, gutterW - 1f, bottom, headLine)
        canvas.restore()
    }

    // ------------------------------------------------------------------ dokunma

    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            panX += dx
            panY += dy
            clampPan()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(
                panX.toInt(), panY.toInt(), -vx.toInt(), -vy.toInt(),
                0, maxPanX().toInt(), 0, maxPanY().toInt()
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }
    })

    private val sgd = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val nz = (zoom * detector.scaleFactor).coerceIn(0.4f, 3f)
            val ratio = nz / zoom
            val fxs = detector.focusX - gutterW
            val fys = detector.focusY - headerH
            panX = (panX + fxs) * ratio - fxs
            panY = (panY + fys) * ratio - fys
            zoom = nz
            clampPan()
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sgd.onTouchEvent(e)
        if (!sgd.isInProgress) gd.onTouchEvent(e)
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            panX = scroller.currX.toFloat()
            panY = scroller.currY.toFloat()
            clampPan()
            postInvalidateOnAnimation()
        }
    }

    private fun handleTap(x: Float, y: Float) {
        val d = data ?: return
        if (colCount == 0 || visRows.isEmpty()) return
        if (x < gutterW || y < headerH) return
        val u = dens * zoom
        val fx = colLeft[frozenCols] * u
        val fy = rowTop[frozenRowCount] * u

        val worldX = if (x < gutterW + fx) x - gutterW else x - gutterW + panX
        val worldY = if (y < headerH + fy) y - headerH else y - headerH + panY
        val col = colAt(worldX / u)
        val idx = rowIdxAt(worldY / u)
        if (idx < 0) return
        val rn = visRows[idx]

        var tr = rn
        var tc = col
        for (m in d.merges) {
            if (rn in m.r1..m.r2 && col in m.c1..m.c2) {
                tr = m.r1
                tc = m.c1
                break
            }
        }
        selRow = tr
        selCol = tc
        invalidate()

        val cell = d.rowsByNumber.getOrNull(tr)?.cellAt(tc)
        onCellSelected?.invoke(colName(tc) + tr, cell?.text ?: "")
        if (cell != null && cell.link != null) onLinkTap?.invoke(cell)
    }
}
