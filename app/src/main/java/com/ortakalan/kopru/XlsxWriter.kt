package com.ortakalan.kopru

import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Mevcut bir xlsx dosyasında hücre değeri ve/veya stil (arka plan, yazı rengi) değiştirir.
 * Dosya yapısına mümkün olduğunca az dokunur: yalnızca etkilenen ZIP girişlerini yeniden yazar.
 *
 * Mimari kararlar:
 * - XML parse'ı için regex KULLANILMAZ; tüm işlemler indexOf tabanlıdır.
 * - sharedStrings yokken ekleme yapılırsa Content_Types ve workbook.xml.rels güncellenir.
 * - Her zaman inline string (t="inlineStr") kullanılır; sharedStrings'e bağımlılık ortadan kalkar.
 */
object XlsxWriter {

    data class CellAddr(val row: Int, val col: Int) // 0-tabanlı

    data class CellEdit(
        val text: String? = null,
        val bgColor: Int? = null,
        val fontColor: Int? = null
    )

    // -------------------------------------------------------------------------

    fun apply(src: File, dst: File, sheetIndex: Int, edits: Map<CellAddr, CellEdit>) {
        if (edits.isEmpty()) { src.copyTo(dst, overwrite = true); return }

        ZipFile(src).use { zip ->
            // --- styles.xml ---
            val stylesEntry = zip.getEntry("xl/styles.xml")
                ?: error("xl/styles.xml bulunamadı")
            val stylesXml = zip.getInputStream(stylesEntry).bufferedReader().readText()
            val (newStylesXml, styleMap) = patchStyles(stylesXml, edits)

            // --- sheet ---
            val sheetPath = resolveSheetPath(zip, sheetIndex)
            val sheetEntry = zip.getEntry(sheetPath) ?: error("$sheetPath bulunamadı")
            val sheetXml = zip.getInputStream(sheetEntry).bufferedReader().readText()
            val newSheetXml = patchSheet(sheetXml, edits, styleMap)

            // --- ZIP yeniden yaz ---
            FileOutputStream(dst).use { fos ->
                ZipOutputStream(fos).use { zout ->
                    for (entry in zip.entries()) {
                        val name = entry.name
                        val newEntry = ZipEntry(name)
                        zout.putNextEntry(newEntry)
                        when (name) {
                            "xl/styles.xml" ->
                                zout.write(newStylesXml.toByteArray(Charsets.UTF_8))
                            sheetPath ->
                                zout.write(newSheetXml.toByteArray(Charsets.UTF_8))
                            else -> {
                                val ins: InputStream = zip.getInputStream(entry)
                                ins.copyTo(zout)
                                ins.close()
                            }
                        }
                        zout.closeEntry()
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // styles.xml yaması
    // -------------------------------------------------------------------------

    private fun patchStyles(
        xml: String,
        edits: Map<CellAddr, CellEdit>
    ): Pair<String, Map<CellAddr, Int>> {

        // Renk gerektiren edit'leri topla
        data class ColorKey(val bg: Int?, val fg: Int?)
        val colorEdits = edits.filter { (_, e) -> e.bgColor != null || e.fontColor != null }
        if (colorEdits.isEmpty()) return xml to emptyMap()

        val needed = colorEdits.values
            .map { ColorKey(it.bgColor, it.fontColor) }
            .toSet()

        // Mevcut sayıları bul
        var fontCount = attrInt(xml, "fonts",    "count") ?: 1
        var fillCount = attrInt(xml, "fills",    "count") ?: 2
        var xfCount   = attrInt(xml, "cellXfs",  "count") ?: 1

        val keyToXf = mutableMapOf<ColorKey, Int>()
        val newFonts = StringBuilder()
        val newFills = StringBuilder()
        val newXfs   = StringBuilder()

        for (key in needed) {
            // --- font ---
            val fontId: Int
            val applyFont: Boolean
            val fg = key.fg
            if (fg != null && fg != 0 && fg != Color.TRANSPARENT) {
                newFonts.append("""<font><sz val="11"/><color rgb="FF${colorHex(fg)}"/><name val="Calibri"/></font>""")
                fontId    = fontCount++
                applyFont = true
            } else {
                fontId    = 0
                applyFont = false
            }

            // --- fill ---
            val fillId: Int
            val applyFill: Boolean
            val bg = key.bg
            if (bg != null && bg != 0 && bg != Color.TRANSPARENT) {
                newFills.append("""<fill><patternFill patternType="solid"><fgColor rgb="FF${colorHex(bg)}"/><bgColor indexed="64"/></patternFill></fill>""")
                fillId    = fillCount++
                applyFill = true
            } else {
                fillId    = 0
                applyFill = false
            }

            // --- xf ---
            val xfAttrs = buildString {
                append("""numFmtId="0" fontId="$fontId" fillId="$fillId" borderId="0" xfId="0"""")
                if (applyFont) append(""" applyFont="1"""")
                if (applyFill) append(""" applyFill="1"""")
            }
            newXfs.append("<xf $xfAttrs/>")
            keyToXf[key] = xfCount++
        }

        val styleMap: Map<CellAddr, Int> = colorEdits.mapNotNull { (addr, edit) ->
            val key = ColorKey(edit.bgColor, edit.fontColor)
            val idx = keyToXf[key] ?: return@mapNotNull null
            addr to idx
        }.toMap()

        var result = xml
        if (newFonts.isNotEmpty()) {
            result = insertBefore(result, "</fonts>",  newFonts.toString())
            result = setAttrInt(result, "fonts",   "count", fontCount)
        }
        if (newFills.isNotEmpty()) {
            result = insertBefore(result, "</fills>",  newFills.toString())
            result = setAttrInt(result, "fills",   "count", fillCount)
        }
        if (newXfs.isNotEmpty()) {
            result = insertBefore(result, "</cellXfs>", newXfs.toString())
            result = setAttrInt(result, "cellXfs", "count", xfCount)
        }

        return result to styleMap
    }

    // -------------------------------------------------------------------------
    // sheet XML yaması — karakter-düzeyi parser, regex YOK
    // -------------------------------------------------------------------------

    private fun patchSheet(
        sheetXml: String,
        edits: Map<CellAddr, CellEdit>,
        styleMap: Map<CellAddr, Int>
    ): String {
        // Satır bazında grupla (0-tabanlı row)
        val byRow: Map<Int, List<Map.Entry<CellAddr, CellEdit>>> =
            edits.entries.groupBy { it.key.row }

        val existingRows = mutableSetOf<Int>()   // 1-tabanlı
        val sb = StringBuilder(sheetXml.length + edits.size * 150)
        var i = 0
        val len = sheetXml.length

        while (i < len) {
            val rowOpen = findExactTag(sheetXml, "<row", i)
            if (rowOpen < 0) { sb.append(sheetXml, i, len); break }

            sb.append(sheetXml, i, rowOpen)

            val tagEnd = findTagClose(sheetXml, rowOpen)
            if (tagEnd < 0) { sb.append(sheetXml, rowOpen, len); break }

            val rowTag     = sheetXml.substring(rowOpen, tagEnd + 1)
            val selfClose  = rowTag.endsWith("/>")
            val rowNum     = attrIntInTag(rowTag, "r") // 1-tabanlı
            if (rowNum != null) existingRows.add(rowNum)

            if (selfClose) {
                // <row .../> — içi boş
                val rowEdits = if (rowNum != null) byRow[rowNum - 1] else null
                if (rowEdits == null || rowNum == null) {
                    sb.append(rowTag)
                } else {
                    sb.append(rowTag.dropLast(2)).append(">")
                    appendEdits(sb, rowEdits, rowNum, styleMap)
                    sb.append("</row>")
                }
                i = tagEnd + 1
                continue
            }

            // Normal <row ...>...</row>
            val rowClose = sheetXml.indexOf("</row>", tagEnd + 1)
            if (rowClose < 0) { sb.append(sheetXml, rowOpen, len); break }

            val innerXml  = sheetXml.substring(tagEnd + 1, rowClose)
            val afterRow  = rowClose + 6

            val rowEdits = if (rowNum != null) byRow[rowNum - 1] else null
            if (rowEdits == null || rowNum == null) {
                sb.append(rowTag).append(innerXml).append("</row>")
            } else {
                val newInner = patchRowInner(innerXml, rowNum, rowEdits.associate { it.key to it.value }, styleMap)
                sb.append(rowTag).append(newInner).append("</row>")
            }
            i = afterRow
        }

        // Tamamen yeni satırlar (sheet'te hiç yoktu)
        val insertPos = sb.indexOf("</sheetData>")
        if (insertPos >= 0) {
            val ins = StringBuilder()
            for (r0 in byRow.keys.sorted()) {
                if ((r0 + 1) in existingRows) continue
                val r1 = r0 + 1
                val rowEdits = byRow[r0] ?: continue
                ins.append("""<row r="$r1">""")
                appendEdits(ins, rowEdits, r1, styleMap)
                ins.append("</row>")
            }
            if (ins.isNotEmpty()) sb.insert(insertPos, ins)
        }

        return sb.toString()
    }

    private fun appendEdits(
        sb: StringBuilder,
        rowEdits: List<Map.Entry<CellAddr, CellEdit>>,
        rowNum: Int,
        styleMap: Map<CellAddr, Int>
    ) {
        for ((addr, edit) in rowEdits.sortedBy { it.key.col }) {
            val ref  = "${colToLetter(addr.col)}$rowNum"
            val sIdx = styleMap[addr]
            sb.append(buildCell(ref, edit, sIdx))
        }
    }

    private fun patchRowInner(
        inner: String,
        rowNum: Int,
        rowEdits: Map<CellAddr, CellEdit>,   // addr.col -> edit
        styleMap: Map<CellAddr, Int>
    ): String {
        val colEdits = rowEdits.mapKeys { it.key.col }
        val processed = mutableSetOf<Int>()
        val sb = StringBuilder(inner.length + colEdits.size * 100)
        var i = 0
        val len = inner.length

        while (i < len) {
            val cOpen = findExactTag(inner, "<c", i)
            if (cOpen < 0) { sb.append(inner, i, len); break }

            sb.append(inner, i, cOpen)

            val tagEnd = findTagClose(inner, cOpen)
            if (tagEnd < 0) { sb.append(inner, cOpen, len); break }

            val cellTag   = inner.substring(cOpen, tagEnd + 1)
            val selfClose = cellTag.endsWith("/>")

            // r="A1" gibi bir attribute'dan sütun harfini al
            val refAttr = extractAttr(cellTag, "r")
            val colStr  = refAttr?.takeWhile { it.isLetter() } ?: ""
            val colIdx  = if (colStr.isNotEmpty()) letterToCol(colStr) else -1
            val ref     = "$colStr$rowNum"

            if (selfClose) {
                processed.add(colIdx)
                val edit = if (colIdx >= 0) colEdits[colIdx] else null
                if (edit == null) sb.append(cellTag)
                else sb.append(buildCell(ref, edit, styleMap[CellAddr(rowNum - 1, colIdx)]))
                i = tagEnd + 1
                continue
            }

            // İçi dolu hücre: <c ...>...</c>
            // </c> bul; hücre içeriği hiçbir zaman gerçek bir </c> içermez
            val cClose = inner.indexOf("</c>", tagEnd + 1)
            if (cClose < 0) { sb.append(inner, cOpen, len); break }

            processed.add(colIdx)
            val edit = if (colIdx >= 0) colEdits[colIdx] else null
            if (edit == null) {
                sb.append(inner, cOpen, cClose + 4)
            } else {
                sb.append(buildCell(ref, edit, styleMap[CellAddr(rowNum - 1, colIdx)]))
            }
            i = cClose + 4
        }

        // Satırda hiç bulunmayan sütunları ekle
        for (colIdx in colEdits.keys.sorted()) {
            if (colIdx in processed) continue
            val edit = colEdits[colIdx] ?: continue
            val ref  = "${colToLetter(colIdx)}$rowNum"
            sb.append(buildCell(ref, edit, styleMap[CellAddr(rowNum - 1, colIdx)]))
        }

        return sb.toString()
    }

    /**
     * Hücre XML'i üretir. Metin inline string olarak yazılır (t="inlineStr"),
     * sharedStrings bağımlılığı yoktur ve xlsx okuyucuların çoğu bunu destekler.
     */
    private fun buildCell(ref: String, edit: CellEdit, styleIdx: Int?): String {
        val sAttr = if (styleIdx != null) """ s="$styleIdx"""" else ""
        val text  = edit.text
        return if (text.isNullOrEmpty()) {
            """<c r="$ref"$sAttr/>"""
        } else {
            // t="inlineStr" ile <is><t>...</t></is>
            """<c r="$ref" t="inlineStr"$sAttr><is><t xml:space="preserve">${escapeXml(text)}</t></is></c>"""
        }
    }

    // -------------------------------------------------------------------------
    // ZIP sheet yolu
    // -------------------------------------------------------------------------

    private fun resolveSheetPath(zip: ZipFile, sheetIndex: Int): String {
        val wbXml = zip.getEntry("xl/workbook.xml")?.let {
            zip.getInputStream(it).bufferedReader().readText()
        } ?: return "xl/worksheets/sheet${sheetIndex + 1}.xml"

        // <sheet ... r:id="rId1" .../>  şeklinde bul
        var pos = 0
        var count = 0
        while (true) {
            val tagStart = findExactTag(wbXml, "<sheet", pos)
            if (tagStart < 0) break
            val tagEnd = findTagClose(wbXml, tagStart)
            if (tagEnd < 0) break
            val tag = wbXml.substring(tagStart, tagEnd + 1)
            if (count == sheetIndex) {
                val rId = extractAttr(tag, "r:id") ?: extractAttr(tag, "id")
                    ?: return "xl/worksheets/sheet${sheetIndex + 1}.xml"
                val relsXml = zip.getEntry("xl/_rels/workbook.xml.rels")?.let {
                    zip.getInputStream(it).bufferedReader().readText()
                } ?: return "xl/worksheets/sheet${sheetIndex + 1}.xml"
                return resolveRId(relsXml, rId, sheetIndex)
            }
            count++
            pos = tagEnd + 1
        }
        return "xl/worksheets/sheet${sheetIndex + 1}.xml"
    }

    private fun resolveRId(relsXml: String, rId: String, fallbackIdx: Int): String {
        var pos = 0
        while (true) {
            val tagStart = findExactTag(relsXml, "<Relationship", pos)
            if (tagStart < 0) break
            val tagEnd = findTagClose(relsXml, tagStart)
            if (tagEnd < 0) break
            val tag = relsXml.substring(tagStart, tagEnd + 1)
            if (extractAttr(tag, "Id") == rId) {
                val target = extractAttr(tag, "Target")
                    ?: return "xl/worksheets/sheet${fallbackIdx + 1}.xml"
                return if (target.startsWith("/")) target.trimStart('/') else "xl/$target"
            }
            pos = tagEnd + 1
        }
        return "xl/worksheets/sheet${fallbackIdx + 1}.xml"
    }

    // -------------------------------------------------------------------------
    // XML karakter-düzeyi yardımcılar
    // -------------------------------------------------------------------------

    /**
     * xml içinde [from]'dan itibaren tam isimle tag açılışı bulur.
     * "<row" arar ama "<rowBreaks" eşleşmez: tag adından sonra ' ', '>', '\t', '\n', '\r', '/' olmalı.
     */
    private fun findExactTag(xml: String, prefix: String, from: Int): Int {
        var pos = from
        val plen = prefix.length
        while (true) {
            val idx = xml.indexOf(prefix, pos)
            if (idx < 0) return -1
            val next = xml.getOrElse(idx + plen) { ' ' }
            if (next == ' ' || next == '>' || next == '\t' || next == '\n' || next == '\r' || next == '/') return idx
            pos = idx + 1
        }
    }

    /**
     * [tagStart]'tan başlayan XML tag'ının kapanış '>' konumunu döndürür.
     * Attribute değerleri içindeki tırnaklı '>' karakterlerini atlar.
     */
    private fun findTagClose(xml: String, tagStart: Int): Int {
        var i = tagStart
        val len = xml.length
        var inSingle = false
        var inDouble = false
        while (i < len) {
            when (xml[i]) {
                '"'  -> if (!inSingle) inDouble = !inDouble
                '\'' -> if (!inDouble) inSingle = !inSingle
                '>'  -> if (!inSingle && !inDouble) return i
            }
            i++
        }
        return -1
    }

    /**
     * Bir XML parçasından (tek tag) belirtilen attribute değerini çıkarır.
     * Örnek: extractAttr("""<c r="A1" s="2"/>""", "r") == "A1"
     */
    private fun extractAttr(tag: String, name: String): String? {
        // name=" veya name=' ile başlayan attribute
        for (quote in listOf('"', '\'')) {
            val needle = "$name=$quote"
            val start = tag.indexOf(needle)
            if (start >= 0) {
                val valStart = start + needle.length
                val valEnd   = tag.indexOf(quote, valStart)
                if (valEnd >= 0) return tag.substring(valStart, valEnd)
            }
        }
        return null
    }

    /**
     * XML bloğunda belirtilen element'in count attribute'unu sayısal olarak döndürür.
     * Örnek: attrInt(xml, "fonts", "count") == 4
     */
    private fun attrInt(xml: String, element: String, attr: String): Int? {
        val tagStart = findExactTag(xml, "<$element", 0)
        if (tagStart < 0) return null
        val tagEnd = findTagClose(xml, tagStart)
        if (tagEnd < 0) return null
        val tag = xml.substring(tagStart, tagEnd + 1)
        return extractAttr(tag, attr)?.toIntOrNull()
    }

    /**
     * Bir tag string'inde attribute'dan int oku (tag zaten çıkarılmış).
     */
    private fun attrIntInTag(tag: String, attr: String): Int? =
        extractAttr(tag, attr)?.toIntOrNull()

    /**
     * XML bloğunda belirtilen element'in [attr] attribute'unu [value] ile değiştirir.
     * Hem " hem ' tırnak desteklenir.
     */
    private fun setAttrInt(xml: String, element: String, attr: String, value: Int): String {
        val tagStart = findExactTag(xml, "<$element", 0)
        if (tagStart < 0) return xml
        val tagEnd = findTagClose(xml, tagStart)
        if (tagEnd < 0) return xml
        // attribute xml içinde tagStart..tagEnd arasında — offset'i xml üzerinden hesapla
        for (quote in listOf('"', '\'')) {
            val needle = "$attr=$quote"
            val ai = xml.indexOf(needle, tagStart)
            if (ai < 0 || ai > tagEnd) continue
            val vs = ai + needle.length          // değerin başlangıcı (xml indeksi)
            val ve = xml.indexOf(quote, vs)      // değerin bitiş tırnağı
            if (ve < 0 || ve > tagEnd) continue
            return xml.substring(0, vs) + value.toString() + xml.substring(ve)
        }
        return xml // attribute yok, olduğu gibi bırak
    }

    /** Bir XML string'inin içine [endTag]'dan önce [content] ekler. */
    private fun insertBefore(xml: String, endTag: String, content: String): String {
        val idx = xml.lastIndexOf(endTag)
        return if (idx >= 0) xml.substring(0, idx) + content + xml.substring(idx) else xml
    }

    private fun colorHex(c: Int): String = String.format("%06X", c and 0xFFFFFF)

    fun colToLetter(col: Int): String {
        var n = col + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    fun letterToCol(s: String): Int {
        var col = 0
        for (ch in s.uppercase()) col = col * 26 + (ch - 'A' + 1)
        return col - 1
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
         .replace("<", "&lt;")
         .replace(">", "&gt;")
         .replace("\"", "&quot;")
}
