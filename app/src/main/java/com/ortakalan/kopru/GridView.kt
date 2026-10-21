package com.ortakalan.kopru

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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

    /** Başlık hücresindeki filtre düğmesine dokunuldu (mutlak sütun numarası). */
    var onFilterTap: ((Int) -> Unit)? = null

    /** Filtre ölçütleri değişti (ölçüt eklendi/kaldırıldı/temizlendi). */
    var onFilterChanged: (() -> Unit)? = null

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
    private var baseFrozenRows = 0
    private var baseFrozenCols = 0
    private var sortedMerges: List<Merge> = emptyList()
    private var maxMergeSpan = 0
    private var gutterW = 40f * dens
    private val headerH = 24f * dens

    private var panX = 0f
    private var panY = 0f
    private var selRow = 0
    private var selCol = -1
    private var words: List<String> = emptyList()

    // ---- sütun filtreleri (Excel'deki otomatik filtre)
    private var filterInfo: FilterInfo? = null
    private val crit = HashMap<Int, ColCriteria>()

    /** false: dosyadaki gizli satırlar olduğu gibi gösterilir; kullanıcı filtreye dokununca true olur. */
    private var engineMode = false
    private var dataShown = 0
    private var dataTotal = 0

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

    private val btnFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dens
        color = 0xFF9AA0A6.toInt()
    }
    private val btnIcon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnPath = Path()

    private val typefaces = arrayOf(
        Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
        Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
        Typeface.create(Typeface.DEFAULT, Typeface.ITALIC),
        Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    )

    private class Item(val cell: Cell, val rn: Int, val idx: Int, val merge: Merge?)

    private fun key(r: Int, c: Int): Long = r.toLong() * 20000L + c

    // ------------------------------------------------------------------ veri

    fun setData(sd: SheetData, keepPosition: Boolean = false) {
        data = sd
        colCount = sd.colWidthsPx.size
        colLeft = FloatArray(colCount + 1)
        for (c in 0 until colCount) colLeft[c + 1] = colLeft[c] + sd.colWidthsPx[c]
        baseFrozenCols = sd.frozenCols.coerceIn(0, colCount)
        frozenCols = baseFrozenCols
        sortedMerges = sd.merges.sortedBy { it.r1 }
        var span = 0
        for (m in sd.merges) if (m.r2 - m.r1 > span) span = m.r2 - m.r1
        maxMergeSpan = span
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
        val oldF = filterInfo
        val newF = sd.filter
        filterInfo = newF
        val sameFilter = keepPosition && oldF != null && newF != null && !oldF.inferred &&
            oldF.headerRow == newF.headerRow && oldF.firstCol == newF.firstCol && oldF.lastCol == newF.lastCol
        if (!sameFilter) {
            crit.clear()
            engineMode = false
            if (newF != null) crit.putAll(newF.saved)
            if (keepPosition && oldF != null && oldF.inferred && newF == null) {
                // yenilemede, kullanıcının eklediği tahmini filtre korunur
                val inf = buildInferred(sd)
                filterInfo = inf
                if (inf != null) engineMode = true
            }
        }
        val digits = sd.lastRow.toString().length
        gutterW = (digits * 8f + 18f) * dens
        if (!keepPosition) {
            panX = 0f
            panY = 0f
            selRow = 0
            selCol = -1
        }
        rebuildRows()
    }

    fun setFilter(w: List<String>, resetScroll: Boolean = true) {
        words = w
        if (resetScroll) panY = 0f
        rebuildRows()
    }

    fun shownRowCount(): Int = max(0, visRows.size - frozenRowCount)

    // ------------------------------------------------------------------ filtre API

    fun hasFilter(): Boolean = filterInfo != null

    fun activeFilterCount(): Int {
        var n = 0
        for (c in crit.values) if (c.isActive()) n++
        return n
    }

    fun criteriaOf(col: Int): ColCriteria? = crit[col]

    fun headerText(col: Int): String {
        val f = filterInfo ?: return ""
        val t = data?.rowsByNumber?.getOrNull(f.headerRow)?.cellAt(col)?.text ?: ""
        return t.replace('\n', ' ').trim()
    }

    fun columnLetter(col: Int): String = colName(col)

    /** [gösterilen veri satırı, toplam veri satırı]; filtre yoksa null. */
    fun filterCounts(): IntArray? = if (filterInfo == null) null else intArrayOf(dataShown, dataTotal)

    /** Bir sütunun filtre listesi (diğer sütunların ölçütlerine göre daralmış, Excel gibi). */
    fun collectValues(col: Int): List<ValueItem> {
        val d = data ?: return emptyList()
        val f = filterInfo ?: return emptyList()
        return FilterEngine.collect(d.rowsByNumber, f, col, crit)
    }

    fun setCriteria(col: Int, c: ColCriteria?) {
        if (c == null || !c.isActive()) crit.remove(col) else crit[col] = c
        engineMode = true
        rebuildRows()
        onFilterChanged?.invoke()
    }

    fun clearAllFilters() {
        crit.clear()
        engineMode = true
        rebuildRows()
        onFilterChanged?.invoke()
    }

    /** Dosyada filtre tanımı yoksa başlık satırını tahmin ederek filtre düğmeleri ekler. */
    fun enableInferredFilter(): Boolean {
        val d = data ?: return false
        val inf = buildInferred(d) ?: return false
        filterInfo = inf
        crit.clear()
        engineMode = true
        rebuildRows()
        onFilterChanged?.invoke()
        return true
    }

    private fun buildInferred(d: SheetData): FilterInfo? {
        if (d.lastRow < 2) return null
        // aday başlık satırları: sabit panel varsa onun satırları (alttan yukarı), yoksa baştan
        val candidates = ArrayList<Int>()
        if (d.frozenRows > 0) {
            for (r in d.frozenRows downTo 1) candidates.add(r)
        } else {
            for (r in 1..min(d.lastRow, 50)) candidates.add(r)
        }
        var header = -1
        for (r in candidates) {
            val rd = d.rowsByNumber.getOrNull(r) ?: continue
            var cnt = 0
            for (c in rd.cells) if (c.text.isNotEmpty()) cnt++
            if (cnt >= 2) {
                header = r
                break
            }
        }
        if (header < 0) {
            for (r in candidates) {
                val rd = d.rowsByNumber.getOrNull(r) ?: continue
                if (rd.cells.any { it.text.isNotEmpty() }) {
                    header = r
                    break
                }
            }
        }
        if (header < 1 || header >= d.lastRow) return null
        val hr = d.rowsByNumber[header] ?: return null
        var minC = Int.MAX_VALUE
        var maxC = -1
        for (c in hr.cells) {
            if (c.text.isEmpty()) continue
            if (c.col < minC) minC = c.col
            if (c.col > maxC) maxC = c.col
        }
        if (maxC < 0) return null
        return FilterInfo(header, minC, maxC, d.lastRow, emptyMap(), emptySet(), true)
    }

    private fun rebuildRows() {
        val d = data ?: return
        val f = filterInfo
        val active = ArrayList<Pair<Int, ColCriteria>>()
        if (engineMode) {
            for ((c, cr) in crit) if (cr.isActive()) active.add(Pair(c, cr))
        }
        val tmp = IntArray(d.lastRow + 1)
        var n = 0
        var fr = 0
        var shown = 0
        for (rn in 1..d.lastRow) {
            val rd = d.rowsByNumber[rn]
            val inRange = f != null && f.isDataRow(rn)
            // dosyada gizli satırlar olduğu gibi gizli kalır; filtre bölgesinde ise filtre karar verir
            if (rd != null && rd.hidden && !(engineMode && inRange)) continue
            val frozenHead = rn <= d.frozenRows
            var ok = frozenHead || (f != null && rn <= f.headerRow)
            if (!ok) {
                ok = words.isEmpty() || (rd != null && words.all { rd.searchKey.contains(it) })
                if (ok && inRange && active.isNotEmpty()) ok = FilterEngine.passesAll(rd, active)
            }
            if (!ok) continue
            tmp[n] = rn
            n++
            if (frozenHead) fr++
            if (inRange) shown++
        }
        dataShown = shown
        dataTotal = if (f == null) 0 else max(0, min(f.lastRow, d.lastRow) - f.headerRow)
        visRows = tmp.copyOf(n)
        baseFrozenRows = fr
        frozenRowCount = fr
        rowTop = FloatArray(n + 1)
        rowIndex = IntArray(d.lastRow + 2) { -1 }
        for (i in 0 until n) {
            val rn = visRows[i]
            val h = d.rowsByNumber[rn]?.heightPx ?: d.defaultRowPx
            rowTop[i + 1] = rowTop[i] + h
            rowIndex[rn] = i
        }
        applyFrozenCaps()
        clampPan()
        invalidate()
    }

    /**
     * Dondurulmuş bölge ekranın çok büyük kısmını kaplarsa kalan sütunlara/satırlara ulaşılamaz
     * (tablette ekran küçük). Bu yüzden dondurulmuş alan yatayda %50, dikeyde %40 ile sınırlanır.
     */
    private fun applyFrozenCaps() {
        val u = dens * zoom
        val availW = width - gutterW
        val availH = height - headerH
        var fc = baseFrozenCols
        if (availW > 0f) {
            while (fc > 0 && colLeft[fc] * u > availW * 0.5f) fc--
        }
        frozenCols = fc
        var fr = baseFrozenRows
        if (availH > 0f) {
            while (fr > 0 && fr < rowTop.size && rowTop[fr] * u > availH * 0.4f) fr--
        }
        frozenRowCount = fr
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
        applyFrozenCaps()
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
            val rd = d.rowsByNumber[rn]
            if (rd != null) {
                for (cell in rd.cells) {
                    if (cell.col > c1) break
                    val k = key(rn, cell.col)
                    if (covered.contains(k) || mergeAt.containsKey(k)) continue
                    if (cell.col < c0) {
                        // Soldaki hücrenin taşan metni görünür alana girebilir
                        if (cell.col < c0 - 40 || cell.text.isEmpty() || cell.kind != 0) continue
                        val sx0 = styleOf(d, cell.style)
                        if (sx0.wrap || sx0.hAlign == 2 || sx0.hAlign == 3) continue
                    }
                    items.add(Item(cell, rn, i, null))
                }
            }
            // Satır/sütunun tamamına uygulanmış dolgu ve kenarlıklar (hücre kaydı olmayan boş hücreler)
            if (d.hasDefaultStyles) {
                val rs = rd?.style ?: 0
                val lastC = min(c1, colCount - 1)
                for (c in c0..lastC) {
                    val k = key(rn, c)
                    if (covered.contains(k) || mergeAt.containsKey(k)) continue
                    if (rd != null && rd.cellAt(c) != null) continue
                    val sid = if (rs > 0) rs else d.colStyles.getOrElse(c) { 0 }
                    if (sid <= 0) continue
                    val xs = d.styles.getOrNull(sid) ?: continue
                    if (!hasVisualStyle(xs)) continue
                    items.add(Item(Cell(c, "", sid, 0, null), rn, i, null))
                }
            }
        }

        // Birleştirilmiş hücreler: görünür alanla kesişenlerin hepsi (sol üst hücre kayıtlı olmasa da)
        val rnLo = visRows[r0]
        val rnHi = visRows[r1]
        var lo = 0
        var hi = sortedMerges.size
        val target = rnLo - maxMergeSpan
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sortedMerges[mid].r1 < target) lo = mid + 1 else hi = mid
        }
        for (k in lo until sortedMerges.size) {
            val m = sortedMerges[k]
            if (m.r1 > rnHi) break
            if (m.r2 < rnLo) continue
            if (m.c2 < c0 || m.c1 > c1) continue
            val idx = rowIndex.getOrElse(m.r1) { -1 }
            if (idx < 0) continue
            val cell = d.rowsByNumber.getOrNull(m.r1)?.cellAt(m.c1) ?: Cell(m.c1, "", 0, 0, null)
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
            var fill = st.fillColor
            val mg = it.merge
            if (fill == 0 && mg != null) fill = mergedFill(d, mg)
            if (fill != 0) {
                cellRect(it, ax, ay, u, rf)
                fillPaint.color = fill
                canvas.drawRect(rf, fillPaint)
            }
        }

        // --- 3) kenarlıklar
        for (it in items) {
            val st = styleOf(d, it.cell.style)
            val mg = it.merge
            var l: BorderSide? = st.left
            var r: BorderSide? = st.right
            var t: BorderSide? = st.top
            var b: BorderSide? = st.bottom
            if (mg != null) {
                // Birleştirilmiş hücrede kenarlıklar dış kenardaki hücrelerin stilinde saklanır
                val a0 = styleAt(d, mg.r1, mg.c1)
                val tr = styleAt(d, mg.r1, mg.c2)
                val bl = styleAt(d, mg.r2, mg.c1)
                val br = styleAt(d, mg.r2, mg.c2)
                l = a0.left ?: bl.left
                r = tr.right ?: br.right ?: a0.right
                t = a0.top ?: tr.top
                b = bl.bottom ?: br.bottom ?: a0.bottom
            }
            if (l == null && r == null && t == null && b == null) continue
            cellRect(it, ax, ay, u, rf)
            if (l != null) {
                setBorder(l)
                canvas.drawLine(rf.left, rf.top, rf.left, rf.bottom, borderPaint)
            }
            if (r != null) {
                setBorder(r)
                canvas.drawLine(rf.right, rf.top, rf.right, rf.bottom, borderPaint)
            }
            if (t != null) {
                setBorder(t)
                canvas.drawLine(rf.left, rf.top, rf.right, rf.top, borderPaint)
            }
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

        // --- filtre düğmeleri (başlık satırı)
        val fi = filterInfo
        if (fi != null) drawFilterButtons(canvas, fi, u, ax, ay, c0, c1, r0, r1)

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

    private fun hasVisualStyle(x: XfStyle): Boolean =
        x.fillColor != 0 || x.left != null || x.right != null || x.top != null || x.bottom != null

    /** Hücrenin gerçek stili: kendi stili, yoksa satır, yoksa sütun varsayılanı. */
    private fun styleAt(d: SheetData, r: Int, c: Int): XfStyle {
        val rd = d.rowsByNumber.getOrNull(r)
        val cell = rd?.cellAt(c)
        if (cell != null) return styleOf(d, cell.style)
        val rs = rd?.style ?: 0
        val sid = if (rs > 0) rs else d.colStyles.getOrElse(c) { 0 }
        return if (sid > 0) styleOf(d, sid) else XfStyle.DEFAULT
    }

    private fun mergedFill(d: SheetData, m: Merge): Int {
        var f = styleAt(d, m.r1, m.c2).fillColor
        if (f == 0) f = styleAt(d, m.r2, m.c1).fillColor
        if (f == 0) f = styleAt(d, m.r2, m.c2).fillColor
        return f
    }

    private fun drawFilterButtons(
        canvas: Canvas, f: FilterInfo, u: Float, ax: Float, ay: Float,
        c0: Int, c1: Int, r0: Int, r1: Int
    ) {
        val hi = rowIndex.getOrElse(f.headerRow) { -1 }
        if (hi < r0 || hi > r1) return
        val from = max(c0, f.firstCol)
        val to = min(c1, min(f.lastCol, colCount - 1))
        for (c in from..to) {
            if (!f.hasButton(c)) continue
            val active = crit[c]?.isActive() == true
            val cl = ax + colLeft[c] * u
            val cr = ax + colLeft[c + 1] * u
            val ct = ay + rowTop[hi] * u
            val cb = ay + rowTop[hi + 1] * u
            val sz = min((17f * dens * zoom).coerceIn(13f * dens, 26f * dens), min(cb - ct, cr - cl) - 2f * dens)
            if (sz < 6f * dens) continue
            val bx = cr - sz - 1.5f * dens
            val by = cb - sz - 1.5f * dens
            rf.set(bx, by, bx + sz, by + sz)
            btnFill.color = if (active) 0xFF1A73E8.toInt() else Color.WHITE
            canvas.drawRoundRect(rf, 3f * dens, 3f * dens, btnFill)
            if (!active) canvas.drawRoundRect(rf, 3f * dens, 3f * dens, btnStroke)
            btnPath.reset()
            if (active) {
                // dolu huni: filtre uygulanmış
                btnIcon.color = Color.WHITE
                btnPath.moveTo(bx + sz * 0.22f, by + sz * 0.25f)
                btnPath.lineTo(bx + sz * 0.78f, by + sz * 0.25f)
                btnPath.lineTo(bx + sz * 0.56f, by + sz * 0.52f)
                btnPath.lineTo(bx + sz * 0.56f, by + sz * 0.78f)
                btnPath.lineTo(bx + sz * 0.44f, by + sz * 0.70f)
                btnPath.lineTo(bx + sz * 0.44f, by + sz * 0.52f)
            } else {
                // aşağı ok
                btnIcon.color = 0xFF5F6368.toInt()
                btnPath.moveTo(bx + sz * 0.27f, by + sz * 0.38f)
                btnPath.lineTo(bx + sz * 0.73f, by + sz * 0.38f)
                btnPath.lineTo(bx + sz * 0.50f, by + sz * 0.68f)
            }
            btnPath.close()
            canvas.drawPath(btnPath, btnIcon)
        }
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
            applyFrozenCaps()
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

    /** Fare tekerleği / dokunmatik yüzey kaydırma (PC-mod tabletler). */
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.source and android.view.InputDevice.SOURCE_CLASS_POINTER == 0) return super.onGenericMotionEvent(e)
        if (e.action == MotionEvent.ACTION_SCROLL) {
            val vScroll = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hScroll = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            // Tekerlek başına kaç piksel kaydırılacak: 3 satır yüksekliği kadar hızlı
            val lineH = (20f * dens * zoom).coerceAtLeast(24f)
            panY = (panY - vScroll * lineH * 3f).coerceIn(0f, maxPanY())
            panX = (panX - hScroll * lineH * 3f).coerceIn(0f, maxPanX())
            invalidate()
            return true
        }
        return super.onGenericMotionEvent(e)
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

        // başlık hücresinin sağ tarafı (parmak için geniş bölge) filtre menüsünü açar
        val fi = filterInfo
        if (fi != null && rn == fi.headerRow && fi.hasButton(col)) {
            val right = colLeft[col + 1] * u
            val zone = min(34f * dens, (colLeft[col + 1] - colLeft[col]) * u * 0.6f).coerceAtLeast(22f * dens)
            if (right - worldX <= zone) {
                onFilterTap?.invoke(col)
                return
            }
        }

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
