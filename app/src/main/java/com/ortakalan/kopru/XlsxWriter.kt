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
 * Kullanım:
 *   val edits = mapOf(CellAddr(3,2) to CellEdit(text="Merhaba", bgColor=0xFFFFFF00.toInt()))
 *   XlsxWriter.apply(srcFile, destFile, sheetIndex=0, edits)
 */
object XlsxWriter {

    data class CellAddr(val row: Int, val col: Int) // 0-tabanlı

    /**
     * @param text  null → metni değiştirme; "" → hücreyi temizle
     * @param bgColor  null → değiştirme; Color.TRANSPARENT → dolguyu kaldır
     * @param fontColor null → değiştirme
     */
    data class CellEdit(
        val text: String? = null,
        val bgColor: Int? = null,
        val fontColor: Int? = null
    )

    // -------------------------------------------------------------------------

    fun apply(src: File, dst: File, sheetIndex: Int, edits: Map<CellAddr, CellEdit>) {
        if (edits.isEmpty()) { src.copyTo(dst, overwrite = true); return }

        ZipFile(src).use { zip ->
            // 1. styles.xml oku → yeni stil indexleri ekle
            val stylesEntry = zip.getEntry("xl/styles.xml")
                ?: error("xl/styles.xml bulunamadı")
            val stylesXml = zip.getInputStream(stylesEntry).bufferedReader().readText()
            val (newStylesXml, styleMap) = patchStyles(stylesXml, edits)

            // 2. sheet XML adını bul
            val sheetPath = resolveSheetPath(zip, sheetIndex)

            // 3. sheet XML'ini yama
            val sheetEntry = zip.getEntry(sheetPath) ?: error("$sheetPath bulunamadı")
            val sheetXml = zip.getInputStream(sheetEntry).bufferedReader().readText()

            // sharedStrings (yeni metin girişleri için)
            val ssEntry = zip.getEntry("xl/sharedStrings.xml")
            val ssXml = if (ssEntry != null) zip.getInputStream(ssEntry).bufferedReader().readText() else null

            val (newSheetXml, newSsXml) = patchSheet(sheetXml, ssXml, edits, styleMap)

            // 4. ZIP'i yeniden oluştur — değişmeyen girdiler aynen kopyalanır
            FileOutputStream(dst).use { fos ->
                ZipOutputStream(fos).use { zout ->
                    for (entry in zip.entries()) {
                        val name = entry.name
                        zout.putNextEntry(ZipEntry(name))
                        when {
                            name == "xl/styles.xml" ->
                                zout.write(newStylesXml.toByteArray(Charsets.UTF_8))
                            name == sheetPath ->
                                zout.write(newSheetXml.toByteArray(Charsets.UTF_8))
                            name == "xl/sharedStrings.xml" && newSsXml != null ->
                                zout.write(newSsXml.toByteArray(Charsets.UTF_8))
                            else -> {
                                val ins: InputStream = zip.getInputStream(entry)
                                ins.copyTo(zout)
                                ins.close()
                            }
                        }
                        zout.closeEntry()
                    }
                    // sharedStrings yoktu ama eklendi
                    if (ssEntry == null && newSsXml != null) {
                        zout.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                        zout.write(newSsXml.toByteArray(Charsets.UTF_8))
                        zout.closeEntry()
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // styles.xml yaması
    // -------------------------------------------------------------------------

    /**
     * Gerekli fill ve font girdilerini styles.xml'e ekler, her (bgColor, fontColor) kombinasyonu
     * için yeni bir xf (cellXf) oluşturur. Döndürülen styleMap: CellAddr → yeni xf indeksi.
     */
    private fun patchStyles(
        xml: String,
        edits: Map<CellAddr, CellEdit>
    ): Pair<String, Map<CellAddr, Int>> {

        // Mevcut sayıları bul
        val fonts  = parseCount(xml, "fonts")
        val fills  = parseCount(xml, "fills")
        val xfs    = parseCount(xml, "cellXfs")

        // Benzersiz (bgColor, fontColor) kombinasyonları
        data class StyleKey(val bg: Int?, val fg: Int?)
        val needed = edits.values
            .filter { it.bgColor != null || it.fontColor != null }
            .map { StyleKey(it.bgColor, it.fontColor) }
            .toSet()

        // CellEdit → styleKey → xf indeksi
        val keyToXf = mutableMapOf<StyleKey, Int>()
        var newFontIdx = fonts
        var newFillIdx = fills
        var newXfIdx   = xfs

        val newFontsXml  = StringBuilder()
        val newFillsXml  = StringBuilder()
        val newXfsXml    = StringBuilder()

        for (key in needed) {
            // font
            val fontRef: Int
            if (key.fg != null && key.fg != Color.TRANSPARENT) {
                val hex = colorHex(key.fg)
                newFontsXml.append("""<font><sz val="11"/><color rgb="FF$hex"/><name val="Calibri"/></font>""")
                fontRef = newFontIdx++
            } else {
                fontRef = 0
            }

            // fill
            val fillRef: Int
            if (key.bg != null && key.bg != Color.TRANSPARENT && key.bg != 0) {
                val hex = colorHex(key.bg)
                newFillsXml.append("""<fill><patternFill patternType="solid"><fgColor rgb="FF$hex"/><bgColor indexed="64"/></patternFill></fill>""")
                fillRef = newFillIdx++
            } else if (key.bg == Color.TRANSPARENT || key.bg == 0) {
                fillRef = 0 // temizle → yok fill
            } else {
                fillRef = 0
            }

            // xf
            newXfsXml.append("""<xf numFmtId="0" fontId="$fontRef" fillId="$fillRef" borderId="0" xfId="0">""")
            if (key.fg != null) newXfsXml.append("""<alignment/>""")
            newXfsXml.append("""</xf>""")
            keyToXf[key] = newXfIdx++
        }

        // edits için styleKey araması
        val styleMap = edits.mapNotNull { (addr, edit) ->
            val key = StyleKey(edit.bgColor, edit.fontColor)
            val idx = keyToXf[key] ?: return@mapNotNull null
            addr to idx
        }.toMap()

        // XML'e ekle
        var result = xml
        if (newFontsXml.isNotEmpty()) {
            result = result.replace(Regex("(</fonts>)"), "$newFontsXml$1")
            result = bumpCount(result, "fonts", fonts + newFontIdx - fonts)
        }
        if (newFillsXml.isNotEmpty()) {
            result = result.replace(Regex("(</fills>)"), "$newFillsXml$1")
            result = bumpCount(result, "fills", fills + newFillIdx - fills)
        }
        if (newXfsXml.isNotEmpty()) {
            result = result.replace(Regex("(</cellXfs>)"), "$newXfsXml$1")
            result = bumpCount(result, "cellXfs", xfs + newXfIdx - xfs)
        }

        return result to styleMap
    }

    // -------------------------------------------------------------------------
    // sheet XML yaması
    // -------------------------------------------------------------------------

    private fun patchSheet(
        sheetXml: String,
        ssXml: String?,
        edits: Map<CellAddr, CellEdit>,
        styleMap: Map<CellAddr, Int>
    ): Pair<String, String?> {

        // sharedStrings listesini yükle / oluştur
        val ssList = parseSharedStrings(ssXml)
        var ssChanged = false

        // Satır bazında edits grupla (0-tabanlı)
        val byRow = edits.entries.groupBy { it.key.row }

        // Sheet XML'ini satır satır işle
        val sb = StringBuilder(sheetXml.length + edits.size * 80)
        var pos = 0
        val rowPattern = Regex("""<row\b[^>]*r="(\d+)"[^>]*>.*?</row>""", RegexOption.DOT_MATCHES_ALL)
        for (match in rowPattern.findAll(sheetXml)) {
            sb.append(sheetXml, pos, match.range.first)
            val rowNum = match.groupValues[1].toIntOrNull() ?: run {
                sb.append(match.value); pos = match.range.last + 1; continue
            }
            val rowEdits = byRow[rowNum - 1] // 0-tabanlı → 1-tabanlı
            if (rowEdits == null) {
                sb.append(match.value)
            } else {
                sb.append(patchRow(match.value, rowNum, rowEdits.associate { it.key to it.value }, styleMap, ssList.also { ssChanged = true }))
                ssChanged = true
            }
            pos = match.range.last + 1
        }
        sb.append(sheetXml, pos, sheetXml.length)

        // Yeni satırlar (sheet'te hiç yoktu)
        val existingRows = rowPattern.findAll(sheetXml).map {
            it.groupValues[1].toIntOrNull() ?: 0
        }.toSet()
        val newRowNums = byRow.keys.filter { (it + 1) !in existingRows }
        if (newRowNums.isNotEmpty()) {
            // </sheetData> öncesine ekle
            val insertPoint = sb.indexOf("</sheetData>")
            if (insertPoint >= 0) {
                val insert = StringBuilder()
                for (r0 in newRowNums.sorted()) {
                    val r1 = r0 + 1
                    val rowEdits = byRow[r0] ?: continue
                    insert.append("""<row r="$r1">""")
                    for ((addr, edit) in rowEdits) {
                        val colLetter = colToLetter(addr.col)
                        val ref = "$colLetter$r1"
                        val sIdx = styleMap[addr]
                        insert.append(buildCell(ref, edit, sIdx, ssList))
                        ssChanged = true
                    }
                    insert.append("</row>")
                }
                sb.insert(insertPoint, insert)
            }
        }

        val newSs = if (ssChanged || ssList.isNotEmpty()) buildSharedStrings(ssList) else ssXml
        return sb.toString() to newSs
    }

    private fun patchRow(
        rowXml: String,
        rowNum: Int,
        rowEdits: Map<CellAddr, CellEdit>,
        styleMap: Map<CellAddr, Int>,
        ssList: MutableList<String>
    ): String {
        val colEdits = rowEdits.entries.associate { it.key.col to it.value }
        val processed = mutableSetOf<Int>()

        // Mevcut hücreleri güncelle
        val cellPat = Regex("""<c\b[^>]*r="([A-Z]+)(\d+)"[^>]*/?>(?:<[^<]*>)*?(?:</c>)?""", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()
        var pos = 0
        // Sadece satır başlangıcı ve sonu arasını işle
        val rowStart = rowXml.indexOf('>') + 1
        val rowEnd   = rowXml.lastIndexOf("</row>")
        sb.append(rowXml, 0, rowStart)

        val inner = if (rowEnd >= 0) rowXml.substring(rowStart, rowEnd) else rowXml.substring(rowStart)
        var ipos = 0
        for (match in cellPat.findAll(inner)) {
            sb.append(inner, ipos, match.range.first)
            val colStr = match.groupValues[1]
            val colIdx = letterToCol(colStr)
            processed.add(colIdx)
            val edit = colEdits[colIdx]
            if (edit == null) {
                sb.append(match.value)
            } else {
                val ref = "$colStr$rowNum"
                val sIdx = styleMap[CellAddr(rowNum - 1, colIdx)]
                sb.append(buildCell(ref, edit, sIdx, ssList))
            }
            ipos = match.range.last + 1
        }
        sb.append(inner, ipos, inner.length)

        // Olmayan hücreler ekle (sütun sırasına göre)
        val missing = colEdits.keys.filter { it !in processed }.sorted()
        for (colIdx in missing) {
            val edit = colEdits[colIdx] ?: continue
            val ref = "${colToLetter(colIdx)}$rowNum"
            val sIdx = styleMap[CellAddr(rowNum - 1, colIdx)]
            sb.append(buildCell(ref, edit, sIdx, ssList))
        }

        if (rowEnd >= 0) sb.append("</row>")
        return sb.toString()
    }

    private fun buildCell(
        ref: String,
        edit: CellEdit,
        styleIdx: Int?,
        ssList: MutableList<String>
    ): String {
        val sAttr = if (styleIdx != null) """ s="$styleIdx"""" else ""
        val text = edit.text
        if (text == null) {
            // Yalnızca stil değişikliği (metin korunacak ama biz bütün hücreyi yazıyoruz — stil için boş)
            return """<c r="$ref"$sAttr/>"""
        }
        if (text.isEmpty()) {
            // Temizle
            return """<c r="$ref"$sAttr/>"""
        }
        // Metin → sharedStrings
        val ssIdx = getOrAddSharedString(text, ssList)
        return """<c r="$ref" t="s"$sAttr><v>$ssIdx</v></c>"""
    }

    // -------------------------------------------------------------------------
    // Yardımcı: sharedStrings
    // -------------------------------------------------------------------------

    private fun parseSharedStrings(xml: String?): MutableList<String> {
        if (xml == null) return mutableListOf()
        val list = mutableListOf<String>()
        val pat = Regex("""<si>.*?</si>""", RegexOption.DOT_MATCHES_ALL)
        for (m in pat.findAll(xml)) {
            // <t> içindeki metni al
            val t = Regex("""<t[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL).find(m.value)
            list.add(t?.groupValues?.get(1)?.let { unescapeXml(it) } ?: "")
        }
        return list
    }

    private fun getOrAddSharedString(text: String, list: MutableList<String>): Int {
        val idx = list.indexOf(text)
        if (idx >= 0) return idx
        list.add(text)
        return list.size - 1
    }

    private fun buildSharedStrings(list: MutableList<String>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${list.size}" uniqueCount="${list.size}">""")
        for (s in list) {
            sb.append("<si><t xml:space=\"preserve\">").append(escapeXml(s)).append("</t></si>")
        }
        sb.append("</sst>")
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // Yardımcı: ZIP sheet yolu
    // -------------------------------------------------------------------------

    private fun resolveSheetPath(zip: ZipFile, sheetIndex: Int): String {
        // workbook.xml → sheet r:id → _rels/workbook.xml.rels → hedef
        val wbXml = zip.getEntry("xl/workbook.xml")?.let {
            zip.getInputStream(it).bufferedReader().readText()
        } ?: return "xl/worksheets/sheet${sheetIndex + 1}.xml"

        val sheetIds = Regex("""<sheet\b[^>]*r:id="([^"]+)"""").findAll(wbXml)
            .map { it.groupValues[1] }.toList()
        val rId = sheetIds.getOrNull(sheetIndex) ?: return "xl/worksheets/sheet${sheetIndex + 1}.xml"

        val relsXml = zip.getEntry("xl/_rels/workbook.xml.rels")?.let {
            zip.getInputStream(it).bufferedReader().readText()
        } ?: return "xl/worksheets/sheet${sheetIndex + 1}.xml"

        val target = Regex("""Id="$rId"[^>]*Target="([^"]+)"""").find(relsXml)?.groupValues?.get(1)
            ?: return "xl/worksheets/sheet${sheetIndex + 1}.xml"

        return if (target.startsWith("/")) target.trimStart('/') else "xl/$target"
    }

    // -------------------------------------------------------------------------
    // Küçük yardımcılar
    // -------------------------------------------------------------------------

    private fun parseCount(xml: String, tag: String): Int =
        Regex("""<$tag\s+count="(\d+)"""").find(xml)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun bumpCount(xml: String, tag: String, newCount: Int): String =
        xml.replace(Regex("""(<$tag\s+count=")(\d+)(")"""), "$1$newCount$3")

    private fun colorHex(c: Int): String =
        String.format("%06X", c and 0xFFFFFF)

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
        for (ch in s) col = col * 26 + (ch - 'A' + 1)
        return col - 1
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
         .replace("\"", "&quot;").replace("'", "&apos;")

    private fun unescapeXml(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
         .replace("&quot;", "\"").replace("&apos;", "'")
}
