package com.ortakalan.kopru

import android.graphics.Color
import android.util.Xml
import androidx.core.graphics.ColorUtils
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.TreeMap
import java.util.zip.ZipFile

class SheetInfo(val name: String, val path: String)

/**
 * .xlsx dosyasını doğrudan ZIP + XML olarak okur. Hücre değerlerinin yanında Excel'deki görünümü de
 * (yazı tipi, renkler, dolgu, kenarlık, hizalama, sütun genişliği, satır yüksekliği, birleştirilmiş hücreler,
 * sabit paneller, sayı/tarih biçimleri) okur. Köprü adresleri hiçbir şekilde değiştirilmez.
 */
object XlsxReader {

    private const val OPAQUE = -16777216 // 0xFF000000

    private val HYPERLINK_RE = Regex(
        "HYPERLINK\\s*\\(\\s*\"((?:[^\"]|\"\")*)\"",
        RegexOption.IGNORE_CASE
    )

    private val INDEXED = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333
    )

    private val DEFAULT_THEME = intArrayOf(
        0xFFFFFF, 0x000000, 0xE7E6E6, 0x44546A, 0x4472C4, 0xED7D31,
        0xA5A5A5, 0xFFC000, 0x5B9BD5, 0x70AD47, 0x0563C1, 0x954F72
    )

    // ---------------------------------------------------------------- sayfa listesi

    fun listSheets(file: File): List<SheetInfo> {
        return ZipFile(file).use { zip ->
            val p = open(zip, "xl/workbook.xml")
                ?: throw IllegalArgumentException(
                    "Bu dosya .xlsx biçiminde değil. Excel'de \"Farklı Kaydet\" ile .xlsx olarak kaydedip tekrar deneyin."
                )
            val rels = readRels(zip, "xl/_rels/workbook.xml.rels")
            val result = ArrayList<SheetInfo>()
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && ln(p) == "sheet") {
                    val name = attr(p, "name") ?: "Sayfa"
                    val rid = attrSuffix(p, "id")
                    val target = if (rid != null) rels[rid] else null
                    if (target != null) {
                        val path = if (target.startsWith("/")) target.substring(1) else "xl/$target"
                        result.add(SheetInfo(name, path))
                    }
                }
                ev = p.next()
            }
            if (result.isEmpty()) throw IllegalArgumentException("Dosyada okunabilir sayfa bulunamadı.")
            result
        }
    }

    // ---------------------------------------------------------------- sayfa içeriği

    fun readSheet(file: File, info: SheetInfo): SheetData {
        return ZipFile(file).use { zip ->
            val shared = readShared(zip)
            val theme = readTheme(zip)
            val styles = readStyles(zip, theme)

            val slash = info.path.lastIndexOf('/')
            val dir = info.path.substring(0, slash + 1)
            val fname = info.path.substring(slash + 1)
            val rels = readRels(zip, dir + "_rels/" + fname + ".rels")

            val p = open(zip, info.path) ?: throw IllegalArgumentException("Sayfa verisi okunamadı.")

            // Satırlar akış halinde okunur; her satır bitince kalıcı yapıya çevrilir (bellek tasarrufu).
            val rowList = ArrayList<RowData>()
            val rowCells = ArrayList<Cell>()
            var curHt: Float? = null
            var curHidden = false
            val colSpecs = ArrayList<FloatArray>()
            val merges = ArrayList<Merge>()
            val hyperRefs = ArrayList<Array<String?>>() // [ref, rid, location]
            val formulaLinks = ArrayList<Triple<Int, Int, String>>()

            var defRowPt = 15f
            var defColW: Float? = null
            var showGrid = true
            var frozenRows = 0
            var frozenCols = 0
            var sawView = false
            var sawPane = false

            var autoRow = 0
            var curRow = 0
            var lastCol = -1
            var curCol = 0
            var type: String? = null
            var style = -1
            val v = StringBuilder()
            val f = StringBuilder()
            val isT = StringBuilder()
            var inV = false
            var inF = false
            var inIs = false
            var inT = false
            var inRph = false

            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        when (ln(p)) {
                            "sheetFormatPr" -> {
                                defRowPt = attr(p, "defaultRowHeight")?.toFloatOrNull() ?: defRowPt
                                defColW = attr(p, "defaultColWidth")?.toFloatOrNull()
                            }
                            "col" -> {
                                val mn = attr(p, "min")?.toIntOrNull()
                                val mx = attr(p, "max")?.toIntOrNull()
                                val w = attr(p, "width")?.toFloatOrNull()
                                val hid = attr(p, "hidden")
                                if (mn != null && mx != null) {
                                    val px = if (hid == "1" || hid == "true") 0f else if (w != null) w * 7f else -1f
                                    if (px >= 0f) colSpecs.add(floatArrayOf(mn.toFloat(), mx.toFloat(), px))
                                }
                            }
                            "sheetView" -> {
                                if (!sawView) {
                                    sawView = true
                                    val g = attr(p, "showGridLines")
                                    if (g == "0" || g == "false") showGrid = false
                                }
                            }
                            "pane" -> {
                                if (!sawPane) {
                                    sawPane = true
                                    val st = attr(p, "state")
                                    if (st == "frozen" || st == "frozenSplit") {
                                        frozenCols = attr(p, "xSplit")?.toFloatOrNull()?.toInt() ?: 0
                                        frozenRows = attr(p, "ySplit")?.toFloatOrNull()?.toInt() ?: 0
                                    }
                                }
                            }
                            "mergeCell" -> {
                                val ref = attr(p, "ref")
                                if (ref != null) {
                                    val parts = ref.split(":")
                                    if (parts.size == 2) {
                                        val a = parseRef(parts[0])
                                        val b = parseRef(parts[1])
                                        if (a.first > 0 && b.first >= a.first && b.second >= a.second) {
                                            merges.add(Merge(a.first, a.second, b.first, b.second))
                                        }
                                    }
                                }
                            }
                            "row" -> {
                                val r = attr(p, "r")?.toIntOrNull()
                                curRow = r ?: (autoRow + 1)
                                autoRow = curRow
                                lastCol = -1
                                rowCells.clear()
                                val ht = attr(p, "ht")?.toFloatOrNull()
                                curHt = if (ht != null) ht * 96f / 72f else null
                                val hid = attr(p, "hidden")
                                curHidden = hid == "1" || hid == "true"
                            }
                            "c" -> {
                                val ref = attr(p, "r")
                                if (ref != null) {
                                    val rc = parseRef(ref)
                                    if (rc.first > 0) curRow = rc.first
                                    curCol = rc.second
                                } else {
                                    curCol = lastCol + 1
                                }
                                lastCol = curCol
                                type = attr(p, "t")
                                style = attr(p, "s")?.toIntOrNull() ?: -1
                                v.setLength(0)
                                f.setLength(0)
                                isT.setLength(0)
                            }
                            "v" -> inV = true
                            "f" -> inF = true
                            "is" -> inIs = true
                            "rPh" -> inRph = true
                            "t" -> if (inIs) inT = !inRph
                            "hyperlink" -> hyperRefs.add(
                                arrayOf(attr(p, "ref"), attrSuffix(p, "id"), attr(p, "location"))
                            )
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inV) v.append(p.text)
                        else if (inF) f.append(p.text)
                        else if (inT) isT.append(p.text)
                    }
                    XmlPullParser.END_TAG -> {
                        when (ln(p)) {
                            "v" -> inV = false
                            "f" -> inF = false
                            "is" -> inIs = false
                            "t" -> inT = false
                            "rPh" -> inRph = false
                            "c" -> {
                                val raw = v.toString()
                                var kind = 0
                                val text: String = when (type) {
                                    "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                    "inlineStr" -> isT.toString()
                                    "str" -> raw
                                    "b" -> {
                                        kind = 2
                                        if (raw.trim() == "1") "DOĞRU" else "YANLIŞ"
                                    }
                                    "e" -> {
                                        kind = 2
                                        raw
                                    }
                                    else -> if (raw.isNotEmpty()) {
                                        kind = 1
                                        NumFmt.format(raw, styles.getOrNull(style)?.fmtCode)
                                    } else ""
                                }
                                if (f.isNotEmpty()) {
                                    val m = HYPERLINK_RE.find(f.toString())
                                    if (m != null) {
                                        formulaLinks.add(
                                            Triple(curRow, curCol, m.groupValues[1].replace("\"\"", "\""))
                                        )
                                    }
                                }
                                val st = if (style < 0) 0 else style
                                if (curRow in 1..1048576 && curCol in 0..16383) {
                                    // Değeri olmayan ve görünür bir etkisi (dolgu/kenarlık) olmayan hücreler saklanmaz.
                                    val visual = st > 0 && (styles.getOrNull(st)?.let { hasVisual(it) } ?: false)
                                    if (text.isNotEmpty() || visual) {
                                        rowCells.add(Cell(curCol, text, st, kind, null))
                                    }
                                }
                            }
                            "row" -> {
                                if (curRow in 1..1048576 && (rowCells.isNotEmpty() || curHt != null || curHidden)) {
                                    var sorted = true
                                    for (k in 1 until rowCells.size) {
                                        if (rowCells[k].col <= rowCells[k - 1].col) {
                                            sorted = false
                                            break
                                        }
                                    }
                                    if (!sorted) rowCells.sortBy { it.col }
                                    val hgt = if (curHidden) 0f else (curHt ?: (defRowPt * 96f / 72f))
                                    rowList.add(RowData(curRow, hgt, curHidden, rowCells.toTypedArray()))
                                }
                                rowCells.clear()
                            }
                        }
                    }
                }
                ev = p.next()
            }

            // --- köprüleri hücrelere işle
            val defaultRowPx = defRowPt * 96f / 72f
            val rowMap = HashMap<Int, RowData>(rowList.size * 2 + 16)
            for (rd in rowList) rowMap[rd.number] = rd

            fun setLink(r: Int, c: Int, target: String) {
                if (r < 1 || r > 1048576 || c < 0 || c > 16383 || target.isBlank()) return
                var rd = rowMap[r]
                if (rd == null) {
                    rd = RowData(r, defaultRowPx, false, emptyArray<Cell>())
                    rowMap[r] = rd
                    rowList.add(rd)
                }
                val ex = rd.cellAt(c)
                if (ex != null) {
                    ex.link = target
                } else {
                    val old = rd.cells
                    val arr = arrayOfNulls<Cell>(old.size + 1)
                    var pos = 0
                    while (pos < old.size && old[pos].col < c) {
                        arr[pos] = old[pos]
                        pos++
                    }
                    arr[pos] = Cell(c, "", 0, 0, target)
                    for (k in pos until old.size) arr[k + 1] = old[k]
                    @Suppress("UNCHECKED_CAST")
                    rd.cells = arr as Array<Cell>
                }
            }

            var guard = 0
            for (h in hyperRefs) {
                val ref = h[0] ?: continue
                val rid = h[1]
                val target = if (rid != null) rels[rid] else null
                if (target == null) continue // sayfa içi bağlantı -> yok say
                val parts = ref.split(":")
                val a = parseRef(parts[0])
                val b = if (parts.size > 1) parseRef(parts[1]) else a
                for (r in a.first..b.first) {
                    for (c in a.second..b.second) {
                        if (guard++ > 50000) break
                        setLink(r, c, target)
                    }
                }
            }
            for (t in formulaLinks) setLink(t.first, t.second, t.third)

            // --- boyutlar
            rowList.sortBy { it.number }
            var lastRow = frozenRows
            var maxCol = 0
            for (rd in rowList) {
                if (rd.number > lastRow) lastRow = rd.number
                val cs = rd.cells
                if (cs.isNotEmpty() && cs[cs.size - 1].col > maxCol) maxCol = cs[cs.size - 1].col
            }
            val colCount = maxOf(maxCol + 3, 12)

            val rowsByNumber = arrayOfNulls<RowData>(lastRow + 1)
            for (rd in rowList) rowsByNumber[rd.number] = rd

            val defaultW = defColW?.let { it * 7f } ?: 64f
            val widths = FloatArray(colCount) { defaultW }
            for (s in colSpecs) {
                val from = maxOf(s[0].toInt() - 1, 0)
                val to = minOf(s[1].toInt() - 1, colCount - 1)
                for (c in from..to) widths[c] = s[2]
            }

            SheetData(
                rowsByNumber,
                lastRow,
                widths,
                defaultRowPx,
                merges,
                minOf(frozenRows, lastRow),
                minOf(frozenCols, colCount),
                showGrid,
                styles
            )
        }
    }

    /** Dolgusu veya kenarlığı olan hücre, değeri boş olsa bile ekranda görünür. */
    private fun hasVisual(x: XfStyle): Boolean =
        x.fillColor != 0 || x.left != null || x.right != null || x.top != null || x.bottom != null

    // ---------------------------------------------------------------- yardımcılar (XML)

    private fun open(zip: ZipFile, path: String): XmlPullParser? {
        val e = zip.getEntry(path) ?: return null
        val p = Xml.newPullParser()
        p.setInput(zip.getInputStream(e), null)
        return p
    }

    private fun ln(p: XmlPullParser): String {
        val n = p.name ?: return ""
        val i = n.indexOf(':')
        return if (i >= 0) n.substring(i + 1) else n
    }

    private fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i) == name) return p.getAttributeValue(i)
        }
        return null
    }

    private fun attrSuffix(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) {
            val n = p.getAttributeName(i)
            if (n == name || n.endsWith(":$name")) return p.getAttributeValue(i)
        }
        return null
    }

    private fun boolAttr(p: XmlPullParser): Boolean {
        val v = attr(p, "val")
        return v == null || v == "1" || v == "true"
    }

    private fun readRels(zip: ZipFile, path: String): Map<String, String> {
        val p = open(zip, path) ?: return emptyMap()
        val m = HashMap<String, String>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && ln(p) == "Relationship") {
                val id = attr(p, "Id")
                val t = attr(p, "Target")
                if (id != null && t != null) m[id] = t
            }
            ev = p.next()
        }
        return m
    }

    private fun readShared(zip: ZipFile): List<String> {
        val p = open(zip, "xl/sharedStrings.xml") ?: return emptyList()
        val out = ArrayList<String>()
        var sb = StringBuilder()
        var inT = false
        var inRph = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    when (ln(p)) {
                        "si" -> sb = StringBuilder()
                        "rPh" -> inRph = true
                        "t" -> inT = !inRph
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inT) sb.append(p.text)
                }
                XmlPullParser.END_TAG -> {
                    when (ln(p)) {
                        "t" -> inT = false
                        "rPh" -> inRph = false
                        "si" -> out.add(sb.toString())
                    }
                }
            }
            ev = p.next()
        }
        return out
    }

    // ---------------------------------------------------------------- tema ve renkler

    private fun readTheme(zip: ZipFile): IntArray {
        val p = open(zip, "xl/theme/theme1.xml") ?: return DEFAULT_THEME
        val names = listOf(
            "dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3",
            "accent4", "accent5", "accent6", "hlink", "folHlink"
        )
        val found = IntArray(12) { -1 }
        var cur = -1
        var inScheme = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val n = ln(p)
                if (n == "clrScheme") {
                    inScheme = true
                } else if (inScheme) {
                    val idx = names.indexOf(n)
                    if (idx >= 0) {
                        cur = idx
                    } else if (cur >= 0 && (n == "srgbClr" || n == "sysClr")) {
                        val s = if (n == "srgbClr") attr(p, "val") else attr(p, "lastClr")
                        val rgb = s?.toIntOrNull(16)
                        if (rgb != null) found[cur] = rgb
                        cur = -1
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && ln(p) == "clrScheme") {
                inScheme = false
            }
            ev = p.next()
        }
        fun g(i: Int, def: Int): Int = if (found[i] >= 0) found[i] else def
        // Excel tema sırası: lt1, dk1, lt2, dk2, accent1..6, hlink, folHlink
        return intArrayOf(
            g(1, DEFAULT_THEME[0]), g(0, DEFAULT_THEME[1]), g(3, DEFAULT_THEME[2]), g(2, DEFAULT_THEME[3]),
            g(4, DEFAULT_THEME[4]), g(5, DEFAULT_THEME[5]), g(6, DEFAULT_THEME[6]), g(7, DEFAULT_THEME[7]),
            g(8, DEFAULT_THEME[8]), g(9, DEFAULT_THEME[9]), g(10, DEFAULT_THEME[10]), g(11, DEFAULT_THEME[11])
        )
    }

    private fun applyTint(color: Int, tint: Double): Int {
        if (tint == 0.0) return color
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        val l = hsl[2].toDouble()
        val nl = if (tint < 0) l * (1 + tint) else l * (1 - tint) + tint
        hsl[2] = nl.coerceIn(0.0, 1.0).toFloat()
        return ColorUtils.HSLToColor(hsl) or OPAQUE
    }

    private fun colorOf(p: XmlPullParser, theme: IntArray, def: Int): Int {
        val rgb = attr(p, "rgb")
        if (rgb != null) {
            val value = rgb.toLongOrNull(16)
            if (value != null) return OPAQUE or (value.toInt() and 0xFFFFFF)
        }
        val th = attr(p, "theme")?.toIntOrNull()
        if (th != null) {
            val base = OPAQUE or (if (th in theme.indices) theme[th] else 0)
            val tint = attr(p, "tint")?.toDoubleOrNull() ?: 0.0
            return applyTint(base, tint)
        }
        val ix = attr(p, "indexed")?.toIntOrNull()
        if (ix != null) {
            if (ix == 64) return Color.BLACK
            if (ix == 65) return Color.WHITE
            if (ix in INDEXED.indices) return OPAQUE or INDEXED[ix]
        }
        if (attr(p, "auto") == "1") return Color.BLACK
        return def
    }

    // ---------------------------------------------------------------- stiller

    private class FontSpec(
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val size: Float,
        val color: Int
    )

    private fun levelOf(style: String?): Int {
        return when (style) {
            null -> 0
            "thin", "hair", "dotted", "dashed", "dashDot", "dashDotDot" -> 1
            "thick" -> 3
            else -> 2
        }
    }

    private fun readStyles(zip: ZipFile, theme: IntArray): List<XfStyle> {
        val p = open(zip, "xl/styles.xml") ?: return emptyList()
        val custom = HashMap<Int, String>()
        val fonts = ArrayList<FontSpec>()
        val fills = ArrayList<Int>()
        val borders = ArrayList<Array<BorderSide?>>()
        val xfs = ArrayList<XfStyle>()

        var section = ""

        var fBold = false
        var fItalic = false
        var fUnder = false
        var fSize = 11f
        var fColor = Color.BLACK

        var fillColor = 0
        var fillSolid = false

        var bSides = arrayOfNulls<BorderSide>(4)
        var curSide = -1
        var curSideLevel = 0
        var curSideColor = Color.BLACK

        var inXf = false
        var xNum = 0
        var xFont = 0
        var xFill = 0
        var xBorder = 0
        var xH = 0
        var xV = 0
        var xWrap = false
        var xIndent = 0

        val sectionNames = setOf("numFmts", "fonts", "fills", "borders", "cellXfs", "cellStyleXfs", "dxfs", "cellStyles")

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val n = ln(p)
                if (n in sectionNames) {
                    section = n
                } else if (section == "numFmts" && n == "numFmt") {
                    val id = attr(p, "numFmtId")?.toIntOrNull()
                    val code = attr(p, "formatCode")
                    if (id != null && code != null) custom[id] = code
                } else if (section == "fonts") {
                    when (n) {
                        "font" -> {
                            fBold = false
                            fItalic = false
                            fUnder = false
                            fSize = 11f
                            fColor = Color.BLACK
                        }
                        "b" -> fBold = boolAttr(p)
                        "i" -> fItalic = boolAttr(p)
                        "u" -> fUnder = attr(p, "val") != "none"
                        "sz" -> fSize = attr(p, "val")?.toFloatOrNull() ?: 11f
                        "color" -> fColor = colorOf(p, theme, Color.BLACK)
                    }
                } else if (section == "fills") {
                    when (n) {
                        "fill" -> {
                            fillColor = 0
                            fillSolid = false
                        }
                        "patternFill" -> fillSolid = attr(p, "patternType") == "solid"
                        "fgColor" -> if (fillSolid) fillColor = colorOf(p, theme, 0)
                    }
                } else if (section == "borders") {
                    when (n) {
                        "border" -> bSides = arrayOfNulls(4)
                        "left", "start" -> {
                            curSide = 0
                            curSideLevel = levelOf(attr(p, "style"))
                            curSideColor = Color.BLACK
                        }
                        "right", "end" -> {
                            curSide = 1
                            curSideLevel = levelOf(attr(p, "style"))
                            curSideColor = Color.BLACK
                        }
                        "top" -> {
                            curSide = 2
                            curSideLevel = levelOf(attr(p, "style"))
                            curSideColor = Color.BLACK
                        }
                        "bottom" -> {
                            curSide = 3
                            curSideLevel = levelOf(attr(p, "style"))
                            curSideColor = Color.BLACK
                        }
                        "color" -> if (curSide >= 0) curSideColor = colorOf(p, theme, Color.BLACK)
                    }
                } else if (section == "cellXfs") {
                    when (n) {
                        "xf" -> {
                            inXf = true
                            xNum = attr(p, "numFmtId")?.toIntOrNull() ?: 0
                            xFont = attr(p, "fontId")?.toIntOrNull() ?: 0
                            xFill = attr(p, "fillId")?.toIntOrNull() ?: 0
                            xBorder = attr(p, "borderId")?.toIntOrNull() ?: 0
                            xH = 0
                            xV = 0
                            xWrap = false
                            xIndent = 0
                        }
                        "alignment" -> if (inXf) {
                            xH = when (attr(p, "horizontal")) {
                                "left", "justify", "fill" -> 1
                                "center", "centerContinuous", "distributed" -> 2
                                "right" -> 3
                                else -> 0
                            }
                            xV = when (attr(p, "vertical")) {
                                "center" -> 1
                                "top" -> 2
                                else -> 0
                            }
                            val w = attr(p, "wrapText")
                            xWrap = w == "1" || w == "true"
                            xIndent = attr(p, "indent")?.toIntOrNull() ?: 0
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                val n = ln(p)
                if (n in sectionNames) {
                    section = ""
                } else if (section == "fonts" && n == "font") {
                    fonts.add(FontSpec(fBold, fItalic, fUnder, fSize, fColor))
                } else if (section == "fills" && n == "fill") {
                    fills.add(fillColor)
                } else if (section == "borders") {
                    if ((n == "left" || n == "start" || n == "right" || n == "end" || n == "top" || n == "bottom") &&
                        curSide >= 0
                    ) {
                        if (curSideLevel > 0) bSides[curSide] = BorderSide(curSideLevel, curSideColor)
                        curSide = -1
                    } else if (n == "border") {
                        borders.add(bSides)
                    }
                } else if (section == "cellXfs" && n == "xf" && inXf) {
                    inXf = false
                    val fs = fonts.getOrNull(xFont)
                    val bs = borders.getOrNull(xBorder)
                    val code: String? = if (xNum == 0) null else (custom[xNum] ?: NumFmt.builtin[xNum])
                    xfs.add(
                        XfStyle(
                            bold = fs?.bold ?: false,
                            italic = fs?.italic ?: false,
                            underline = fs?.underline ?: false,
                            sizePt = fs?.size ?: 11f,
                            fontColor = fs?.color ?: Color.BLACK,
                            fillColor = fills.getOrNull(xFill) ?: 0,
                            left = bs?.get(0),
                            right = bs?.get(1),
                            top = bs?.get(2),
                            bottom = bs?.get(3),
                            hAlign = xH,
                            vAlign = xV,
                            wrap = xWrap,
                            indent = xIndent,
                            fmtCode = code
                        )
                    )
                }
            }
            ev = p.next()
        }
        return xfs
    }

    /** "B12" -> (satır=12, sütun=1). Sütun 0 tabanlı. Çözülemezse (0,0). */
    private fun parseRef(ref: String): Pair<Int, Int> {
        var col = 0
        var i = 0
        while (i < ref.length && ref[i].isLetter()) {
            col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1)
            i++
        }
        val row = ref.substring(i).toIntOrNull() ?: 0
        return Pair(row, col - 1)
    }
}
