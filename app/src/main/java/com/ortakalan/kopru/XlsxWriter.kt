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
            val stylesEntry = zip.getEntry("xl/styles.xml")
                ?: error("xl/styles.xml bulunamadı")
            val stylesXml = zip.getInputStream(stylesEntry).bufferedReader().readText()
            val (newStylesXml, styleMap) = patchStyles(stylesXml, edits)

            val sheetPath = resolveSheetPath(zip, sheetIndex)

            val sheetEntry = zip.getEntry(sheetPath) ?: error("$sheetPath bulunamadı")
            val sheetXml = zip.getInputStream(sheetEntry).bufferedReader().readText()

            val ssEntry = zip.getEntry("xl/sharedStrings.xml")
            val ssXml = ssEntry?.let { zip.getInputStream(it).bufferedReader().readText() }

            val (newSheetXml, newSsXml) = patchSheet(sheetXml, ssXml, edits, styleMap)

            FileOutputStream(dst).use { fos ->
                ZipOutputStream(fos).use { zout ->
                    for (entry in zip.entries()) {
                        val name = entry.name
                        val newEntry = ZipEntry(name)
                        zout.putNextEntry(newEntry)
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

    private fun patchStyles(
        xml: String,
        edits: Map<CellAddr, CellEdit>
    ): Pair<String, Map<CellAddr, Int>> {

        val fonts = parseCount(xml, "fonts")
        val fills = parseCount(xml, "fills")
        val xfs   = parseCount(xml, "cellXfs")

        data class StyleKey(val bg: Int?, val fg: Int?)
        val needed = edits.values
            .filter { it.bgColor != null || it.fontColor != null }
            .map { StyleKey(it.bgColor, it.fontColor) }
            .toSet()

        val keyToXf = mutableMapOf<StyleKey, Int>()
        var newFontIdx = fonts
        var newFillIdx = fills
        var newXfIdx   = xfs

        val newFontsXml = StringBuilder()
        val newFillsXml = StringBuilder()
        val newXfsXml   = StringBuilder()

        for (key in needed) {
            // font
            val fontRef: Int
            val applyFont: Boolean
            if (key.fg != null && key.fg != Color.TRANSPARENT) {
                val hex = colorHex(key.fg)
                newFontsXml.append(
                    """<font><sz val="11"/><color rgb="FF$hex"/><name val="Calibri"/></font>"""
                )
                fontRef   = newFontIdx++
                applyFont = true
            } else {
                fontRef   = 0
                applyFont = false
            }

            // fill
            val fillRef: Int
            val applyFill: Boolean
            if (key.bg != null && key.bg != Color.TRANSPARENT && key.bg != 0) {
                val hex = colorHex(key.bg)
                newFillsXml.append(
                    """<fill><patternFill patternType="solid"><fgColor rgb="FF$hex"/><bgColor indexed="64"/></patternFill></fill>"""
                )
                fillRef   = newFillIdx++
                applyFill = true
            } else {
                fillRef   = 0
                applyFill = false
            }

            // xf — applyFont ve applyFill olmadan Excel rengi uygulamaz
            val xfAttrs = buildString {
                append("""numFmtId="0" fontId="$fontRef" fillId="$fillRef" borderId="0" xfId="0"""")
                if (applyFont) append(""" applyFont="1"""")
                if (applyFill) append(""" applyFill="1"""")
            }
            newXfsXml.append("<xf $xfAttrs/>")
            keyToXf[key] = newXfIdx++
        }

        val styleMap = edits.mapNotNull { (addr, edit) ->
            val key = StyleKey(edit.bgColor, edit.fontColor)
            val idx = keyToXf[key] ?: return@mapNotNull null
            addr to idx
        }.toMap()

        var result = xml
        if (newFontsXml.isNotEmpty()) {
            result = result.replace("</fonts>", "$newFontsXml</fonts>")
            result = bumpCount(result, "fonts", newFontIdx)
        }
        if (newFillsXml.isNotEmpty()) {
            result = result.replace("</fills>", "$newFillsXml</fills>")
            result = bumpCount(result, "fills", newFillIdx)
        }
        if (newXfsXml.isNotEmpty()) {
            result = result.replace("</cellXfs>", "$newXfsXml</cellXfs>")
            result = bumpCount(result, "cellXfs", newXfIdx)
        }

        return result to styleMap
    }

    // -------------------------------------------------------------------------
    // sheet XML yaması — karakter-düzeyi parser (regex DEĞİL)
    // -------------------------------------------------------------------------

    private fun patchSheet(
        sheetXml: String,
        ssXml: String?,
        edits: Map<CellAddr, CellEdit>,
        styleMap: Map<CellAddr, Int>
    ): Pair<String, String?> {

        val ssList = parseSharedStrings(ssXml)
        var ssChanged = false

        val byRow = edits.entries.groupBy { it.key.row }
        val existingRowNums = mutableSetOf<Int>()

        val sb = StringBuilder(sheetXml.length + edits.size * 120)
        var i = 0
        val len = sheetXml.length

        while (i < len) {
            val rowOpen = sheetXml.indexOf("<row", i)
            if (rowOpen < 0) {
                sb.append(sheetXml, i, len)
                break
            }
            sb.append(sheetXml, i, rowOpen)

            val tagEnd = sheetXml.indexOf('>', rowOpen)
            if (tagEnd < 0) { sb.append(sheetXml, rowOpen, len); break }

            val rowTag = sheetXml.substring(rowOpen, tagEnd + 1)
            val isSelfClose = rowTag.endsWith("/>")

            val rowNum = Regex("""\br="(\d+)"""").find(rowTag)?.groupValues?.get(1)?.toIntOrNull()
            existingRowNums.add(rowNum ?: 0)

            if (isSelfClose) {
                val rowEdits = if (rowNum != null) byRow[rowNum - 1] else null
                if (rowEdits == null || rowNum == null) {
                    sb.append(rowTag)
                } else {
                    sb.append(rowTag.dropLast(2)).append(">")
                    for ((addr, edit) in rowEdits) {
                        val ref = "${colToLetter(addr.col)}$rowNum"
                        val sIdx = styleMap[addr]
                        sb.append(buildCell(ref, edit, sIdx, ssList))
                        ssChanged = true
                    }
                    sb.append("</row>")
                }
                i = tagEnd + 1
                continue
            }

            val rowClose = sheetXml.indexOf("</row>", tagEnd + 1)
            if (rowClose < 0) { sb.append(sheetXml, rowOpen, len); break }
            val innerXml = sheetXml.substring(tagEnd + 1, rowClose)
            val afterClose = rowClose + 6

            val rowEdits = if (rowNum != null) byRow[rowNum - 1] else null
            if (rowEdits == null || rowNum == null) {
                sb.append(rowTag).append(innerXml).append("</row>")
            } else {
                val newInner = patchRowInner(
                    innerXml, rowNum,
                    rowEdits.associate { it.key to it.value },
                    styleMap, ssList
                )
                ssChanged = true
                sb.append(rowTag).append(newInner).append("</row>")
            }
            i = afterClose
        }

        // Yeni satırlar (sheet'te yoktu)
        val newRowNums = byRow.keys.filter { (it + 1) !in existingRowNums }
        if (newRowNums.isNotEmpty()) {
            val insertPoint = sb.indexOf("</sheetData>")
            if (insertPoint >= 0) {
                val ins = StringBuilder()
                for (r0 in newRowNums.sorted()) {
                    val r1 = r0 + 1
                    val rowEdits = byRow[r0] ?: continue
                    ins.append("""<row r="$r1">""")
                    for ((addr, edit) in rowEdits) {
                        val ref = "${colToLetter(addr.col)}$r1"
                        val sIdx = styleMap[addr]
                        ins.append(buildCell(ref, edit, sIdx, ssList))
                        ssChanged = true
                    }
                    ins.append("</row>")
                }
                sb.insert(insertPoint, ins)
            }
        }

        val newSs = if (ssChanged) buildSharedStrings(ssList) else ssXml
        return sb.toString() to newSs
    }

    /**
     * Bir row'un iç kısmını günceller — karakter-düzeyi parser.
     */
    private fun patchRowInner(
        inner: String,
        rowNum: Int,
        rowEdits: Map<CellAddr, CellEdit>,
        styleMap: Map<CellAddr, Int>,
        ssList: MutableList<String>
    ): String {
        val colEdits = rowEdits.entries.associate { it.key.col to it.value }
        val processed = mutableSetOf<Int>()
        val sb = StringBuilder(inner.length + colEdits.size * 80)
        var i = 0
        val len = inner.length

        while (i < len) {
            val cOpen = inner.indexOf("<c", i)
            if (cOpen < 0) { sb.append(inner, i, len); break }

            // <c ile başlayan ama <calcChain gibi başka tag değil mi?
            val nextCh = if (cOpen + 2 < len) inner[cOpen + 2] else ' '
            if (nextCh != ' ' && nextCh != '\t' && nextCh != '\n' && nextCh != '\r' && nextCh != '>') {
                sb.append(inner, i, cOpen + 1)
                i = cOpen + 1
                continue
            }

            sb.append(inner, i, cOpen)

            val tagEnd = inner.indexOf('>', cOpen)
            if (tagEnd < 0) { sb.append(inner, cOpen, len); break }

            val cellTag = inner.substring(cOpen, tagEnd + 1)
            val isSelfClose = cellTag.endsWith("/>")

            val refMatch = Regex("""\br="([A-Z]+)(\d+)"""").find(cellTag)
            val colStr   = refMatch?.groupValues?.get(1) ?: ""
            val colIdx   = if (colStr.isNotEmpty()) letterToCol(colStr) else -1
            val ref      = "$colStr$rowNum"

            if (isSelfClose) {
                processed.add(colIdx)
                val edit = if (colIdx >= 0) colEdits[colIdx] else null
                if (edit == null) {
                    sb.append(cellTag)
                } else {
                    val sIdx = styleMap[CellAddr(rowNum - 1, colIdx)]
                    sb.append(buildCell(ref, edit, sIdx, ssList))
                }
                i = tagEnd + 1
                continue
            }

            val cClose = inner.indexOf("</c>", tagEnd + 1)
            if (cClose < 0) {
                sb.append(inner, cOpen, len); break
            }
            val cellContent = inner.substring(tagEnd + 1, cClose)
            val afterClose  = cClose + 4

            processed.add(colIdx)
            val edit = if (colIdx >= 0) colEdits[colIdx] else null
            if (edit == null) {
                sb.append(cellTag).append(cellContent).append("</c>")
            } else {
                val sIdx = styleMap[CellAddr(rowNum - 1, colIdx)]
                sb.append(buildCell(ref, edit, sIdx, ssList))
            }
            i = afterClose
        }

        // Eksik hücreler (sütun sırasıyla)
        for (colIdx in colEdits.keys.sorted()) {
            if (colIdx in processed) continue
            val edit = colEdits[colIdx] ?: continue
            val r = "${colToLetter(colIdx)}$rowNum"
            val sIdx = styleMap[CellAddr(rowNum - 1, colIdx)]
            sb.append(buildCell(r, edit, sIdx, ssList))
        }

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
        return when {
            text == null || text.isEmpty() -> """<c r="$ref"$sAttr/>"""
            else -> {
                val ssIdx = getOrAddSharedString(text, ssList)
                """<c r="$ref" t="s"$sAttr><v>$ssIdx</v></c>"""
            }
        }
    }

    // -------------------------------------------------------------------------
    // sharedStrings
    // -------------------------------------------------------------------------

    private fun parseSharedStrings(xml: String?): MutableList<String> {
        if (xml == null) return mutableListOf()
        val list = mutableListOf<String>()
        var i = 0
        while (true) {
            val start = xml.indexOf("<si>", i).takeIf { it >= 0 } ?: break
            val end   = xml.indexOf("</si>", start).takeIf { it >= 0 } ?: break
            val siBlock = xml.substring(start + 4, end)
            val tStart  = siBlock.indexOf("<t")
            val tEnd    = if (tStart >= 0) siBlock.indexOf("</t>", tStart) else -1
            if (tStart >= 0 && tEnd >= 0) {
                val tTagEnd = siBlock.indexOf('>', tStart)
                val raw = if (tTagEnd >= 0 && tTagEnd < tEnd) siBlock.substring(tTagEnd + 1, tEnd) else ""
                list.add(unescapeXml(raw))
            } else {
                list.add("")
            }
            i = end + 5
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
        sb.append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"")
        sb.append(" count=\"${list.size}\" uniqueCount=\"${list.size}\">")
        for (s in list) {
            sb.append("<si><t xml:space=\"preserve\">").append(escapeXml(s)).append("</t></si>")
        }
        sb.append("</sst>")
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // ZIP sheet yolu
    // -------------------------------------------------------------------------

    private fun resolveSheetPath(zip: ZipFile, sheetIndex: Int): String {
        val wbXml = zip.getEntry("xl/workbook.xml")?.let {
            zip.getInputStream(it).bufferedReader().readText()
        } ?: return "xl/worksheets/sheet${sheetIndex + 1}.xml"

        val sheetIds = Regex("""<sheet\b[^>]*r:id="([^"]+)"""").findAll(wbXml)
            .map { it.groupValues[1] }.toList()
        val rId = sheetIds.getOrNull(sheetIndex)
            ?: return "xl/worksheets/sheet${sheetIndex + 1}.xml"

        val relsXml = zip.getEntry("xl/_rels/workbook.xml.rels")?.let {
            zip.getInputStream(it).bufferedReader().readText()
        } ?: return "xl/worksheets/sheet${sheetIndex + 1}.xml"

        val target = Regex("""Id="$rId"[^>]*Target="([^"]+)"""").find(relsXml)
            ?.groupValues?.get(1)
            ?: return "xl/worksheets/sheet${sheetIndex + 1}.xml"

        return if (target.startsWith("/")) target.trimStart('/') else "xl/$target"
    }

    // -------------------------------------------------------------------------
    // Küçük yardımcılar
    // -------------------------------------------------------------------------

    private fun parseCount(xml: String, tag: String): Int =
        Regex("""<$tag\b[^>]*\bcount="(\d+)"""").find(xml)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun bumpCount(xml: String, tag: String, newCount: Int): String =
        xml.replace(Regex("""(<$tag\b[^>]*\bcount=")(\d+)(")""")) { mr ->
            "${mr.groupValues[1]}$newCount${mr.groupValues[3]}"
        }

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
        for (ch in s.uppercase()) col = col * 26 + (ch - 'A' + 1)
        return col - 1
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
         .replace("<", "&lt;")
         .replace(">", "&gt;")
         .replace("\"", "&quot;")

    private fun unescapeXml(s: String): String =
        s.replace("&amp;", "&")
         .replace("&lt;", "<")
         .replace("&gt;", ">")
         .replace("&quot;", "\"")
         .replace("&apos;", "'")
}
