package com.ortakalan.kopru

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Excel sayı/tarih biçimlerini (Türkçe yerel ayarla: binlik nokta, ondalık virgül) uygular.
 * Desteklenmeyen biçimlerde (kesir, bilimsel) "Genel" gösterime düşer.
 */
object NumFmt {

    private const val DEC = ','
    private const val GRP = '.'

    val builtin: Map<Int, String> = mapOf(
        1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
        9 to "0%", 10 to "0.00%",
        14 to "dd.mm.yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
        18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss",
        22 to "dd.mm.yyyy h:mm",
        37 to "#,##0;(#,##0)", 38 to "#,##0;(#,##0)",
        39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;(#,##0.00)",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0", 49 to "@"
    )

    private val MONTHS = arrayOf(
        "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
        "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"
    )
    private val MONTHS_S = arrayOf(
        "Oca", "Şub", "Mar", "Nis", "May", "Haz",
        "Tem", "Ağu", "Eyl", "Eki", "Kas", "Ara"
    )
    private val DAYS = arrayOf("Pazar", "Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi")
    private val DAYS_S = arrayOf("Paz", "Pzt", "Sal", "Çar", "Per", "Cum", "Cmt")

    // ------------------------------------------------------------------ genel

    private fun generalNum(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return d.toString()
        if (d == Math.rint(d) && Math.abs(d) < 1e11) return d.toLong().toString()
        val a = Math.abs(d)
        if (a >= 1e11 || a < 1e-4) {
            return String.format(Locale.ROOT, "%.5E", d).replace('.', DEC)
        }
        val bd = BigDecimal(d).round(MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros()
        return bd.toPlainString().replace('.', DEC)
    }

    // ------------------------------------------------------------------ giriş noktası

    fun format(raw: String, code: String?): String {
        val d = raw.trim().toDoubleOrNull() ?: return raw
        if (code == null) return generalNum(d)
        val c = code.trim()
        if (c.isEmpty() || c.startsWith("General", true)) return generalNum(d)
        if (c == "@") return raw

        val sections = splitSections(c)
        var sec = sections[0]
        var minus = d < 0
        if (d < 0 && sections.size >= 2) {
            sec = sections[1]
            minus = false
        } else if (d == 0.0 && sections.size >= 3) {
            sec = sections[2]
        }
        if (isDateSection(sec)) return formatDate(d, sec)
        return formatNumber(Math.abs(d), sec, minus)
    }

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        while (i < code.length) {
            val ch = code[i]
            if (ch == '"') {
                inQ = !inQ
                sb.append(ch)
            } else if (ch == '\\' && i + 1 < code.length) {
                sb.append(ch).append(code[i + 1])
                i++
            } else if (ch == ';' && !inQ) {
                out.add(sb.toString())
                sb.setLength(0)
            } else {
                sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun isDateSection(sec: String): Boolean {
        var i = 0
        var inQ = false
        while (i < sec.length) {
            val ch = sec[i]
            if (ch == '"') {
                inQ = !inQ
            } else if (!inQ) {
                if (ch == '\\' || ch == '_' || ch == '*') {
                    i++
                } else if (ch == '[') {
                    val j = sec.indexOf(']', i)
                    if (j > 0) {
                        val inner = sec.substring(i + 1, j).lowercase(Locale.ROOT)
                        if (inner == "h" || inner == "hh" || inner == "m" || inner == "mm" ||
                            inner == "s" || inner == "ss"
                        ) return true
                        i = j
                    }
                } else if ("ymdhsYMDHS".indexOf(ch) >= 0) {
                    return true
                }
            }
            i++
        }
        return false
    }

    // ------------------------------------------------------------------ sayı

    private fun formatNumber(v0: Double, sec: String, minus: Boolean): String {
        val pre = StringBuilder()
        val post = StringBuilder()
        var seenDigit = false
        var afterPoint = false
        var intMin = 0
        var decSlots = 0
        var decMin = 0
        var thousands = false
        var scale = 0
        var percent = false
        val n = sec.length
        var i = 0
        while (i < n) {
            val ch = sec[i]
            val target = if (seenDigit) post else pre
            if (ch == '"') {
                val j = sec.indexOf('"', i + 1)
                val end = if (j < 0) n else j
                target.append(sec, i + 1, end)
                i = end + 1
                continue
            }
            if (ch == '\\') {
                if (i + 1 < n) target.append(sec[i + 1])
                i += 2
                continue
            }
            if (ch == '_') {
                target.append(' ')
                i += 2
                continue
            }
            if (ch == '*') {
                i += 2
                continue
            }
            if (ch == '[') {
                val j = sec.indexOf(']', i + 1)
                val end = if (j < 0) n - 1 else j
                val inner = sec.substring(i + 1, end)
                if (inner.startsWith("$")) {
                    target.append(inner.substring(1).substringBefore('-'))
                }
                i = end + 1
                continue
            }
            if (ch == '0' || ch == '#' || ch == '?') {
                seenDigit = true
                if (afterPoint) {
                    decSlots++
                    if (ch == '0') decMin++
                } else {
                    if (ch == '0') intMin++
                }
            } else if (ch == '.' && !afterPoint) {
                afterPoint = true
                seenDigit = true
            } else if (ch == ',') {
                if (seenDigit && !afterPoint) {
                    val nxt = if (i + 1 < n) sec[i + 1] else ' '
                    if (nxt == '0' || nxt == '#' || nxt == '?') thousands = true else scale++
                } else {
                    target.append(',')
                }
            } else if (ch == '%') {
                percent = true
                target.append('%')
            } else if ((ch == 'E' || ch == 'e') && i + 1 < n && (sec[i + 1] == '+' || sec[i + 1] == '-')) {
                return generalNum(if (minus) -v0 else v0)
            } else if (ch == '/' && seenDigit) {
                return generalNum(if (minus) -v0 else v0)
            } else {
                target.append(ch)
            }
            i++
        }

        if (!seenDigit) return pre.toString()

        var v = v0
        if (percent) v *= 100.0
        for (k in 0 until scale) v /= 1000.0

        val bd = BigDecimal.valueOf(v).setScale(decSlots, RoundingMode.HALF_UP)
        var intPart = bd.toBigInteger().toString()
        var decPart = ""
        if (decSlots > 0) {
            val s = bd.toPlainString()
            val dot = s.indexOf('.')
            decPart = if (dot >= 0) s.substring(dot + 1) else ""
            while (decPart.length > decMin && decPart.endsWith("0")) {
                decPart = decPart.substring(0, decPart.length - 1)
            }
        }
        if (intPart == "0" && intMin == 0) intPart = ""
        while (intPart.length < intMin) intPart = "0$intPart"
        if (thousands && intPart.length > 3) {
            val sb = StringBuilder()
            val len = intPart.length
            for (k in 0 until len) {
                if (k > 0 && (len - k) % 3 == 0) sb.append(GRP)
                sb.append(intPart[k])
            }
            intPart = sb.toString()
        }
        val number = if (decPart.isNotEmpty()) intPart + DEC + decPart else intPart
        return (if (minus) "-" else "") + pre + number + post
    }

    // ------------------------------------------------------------------ tarih / saat

    private class Tok(val kind: Char, val count: Int, val text: String)

    private fun pad2(x: Int): String = if (x < 10) "0$x" else x.toString()

    private fun tokenize(sec: String): ArrayList<Tok> {
        val toks = ArrayList<Tok>()
        val n = sec.length
        var i = 0
        while (i < n) {
            val ch = sec[i]
            if (ch == '"') {
                val j = sec.indexOf('"', i + 1)
                val end = if (j < 0) n else j
                toks.add(Tok('L', 0, sec.substring(i + 1, end)))
                i = end + 1
            } else if (ch == '\\') {
                if (i + 1 < n) toks.add(Tok('L', 0, sec[i + 1].toString()))
                i += 2
            } else if (ch == '_') {
                toks.add(Tok('L', 0, " "))
                i += 2
            } else if (ch == '*') {
                i += 2
            } else if (ch == '[') {
                val j = sec.indexOf(']', i + 1)
                val end = if (j < 0) n - 1 else j
                val inner = sec.substring(i + 1, end)
                val low = inner.lowercase(Locale.ROOT)
                if (low.isNotEmpty() && (low.all { it == 'h' } || low.all { it == 'm' } || low.all { it == 's' })) {
                    toks.add(Tok('E', low.length, low.substring(0, 1)))
                } else if (inner.startsWith("$")) {
                    toks.add(Tok('L', 0, inner.substring(1).substringBefore('-')))
                }
                i = end + 1
            } else if (sec.regionMatches(i, "AM/PM", 0, 5, true)) {
                toks.add(Tok('P', 0, "AM/PM"))
                i += 5
            } else if (sec.regionMatches(i, "A/P", 0, 3, true)) {
                toks.add(Tok('P', 0, "A/P"))
                i += 3
            } else if ("ymdhsYMDHS".indexOf(ch) >= 0) {
                val lc = ch.lowercaseChar()
                var j = i
                while (j < n && sec[j].lowercaseChar() == lc) j++
                toks.add(Tok(lc, j - i, ""))
                i = j
            } else if (ch == '.' && toks.isNotEmpty() && toks[toks.size - 1].kind == 's' &&
                i + 1 < n && sec[i + 1] == '0'
            ) {
                var j = i + 1
                while (j < n && sec[j] == '0') j++
                i = j // kesirli saniye gösterilmez
            } else {
                toks.add(Tok('L', 0, ch.toString()))
                i++
            }
        }
        return toks
    }

    private fun formatDate(d: Double, sec: String): String {
        if (d < 0) return "########"
        var days = Math.floor(d).toLong()
        var secs = Math.round((d - Math.floor(d)) * 86400.0)
        if (secs >= 86400) {
            secs -= 86400
            days += 1
        }
        val hour = (secs / 3600).toInt()
        val minute = ((secs % 3600) / 60).toInt()
        val second = (secs % 60).toInt()

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = (days - 25569L) * 86400000L
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val dow = cal.get(Calendar.DAY_OF_WEEK) - 1

        val ampm = sec.contains("AM/PM", true) || sec.contains("A/P", true)
        val toks = tokenize(sec)
        val sb = StringBuilder()

        for (k in toks.indices) {
            val t = toks[k]
            when (t.kind) {
                'L' -> sb.append(t.text)
                'y' -> {
                    if (t.count <= 2) sb.append(pad2(year % 100)) else sb.append(year)
                }
                'm' -> {
                    var isMinute = false
                    var b = k - 1
                    while (b >= 0 && toks[b].kind == 'L') b--
                    if (b >= 0 && (toks[b].kind == 'h' || (toks[b].kind == 'E' && toks[b].text == "h"))) isMinute = true
                    var f = k + 1
                    while (f < toks.size && toks[f].kind == 'L') f++
                    if (f < toks.size && (toks[f].kind == 's' || (toks[f].kind == 'E' && toks[f].text == "s"))) isMinute = true
                    if (isMinute) {
                        sb.append(if (t.count >= 2) pad2(minute) else minute.toString())
                    } else {
                        when (t.count) {
                            1 -> sb.append(month + 1)
                            2 -> sb.append(pad2(month + 1))
                            3 -> sb.append(MONTHS_S[month])
                            4 -> sb.append(MONTHS[month])
                            else -> sb.append(MONTHS[month].substring(0, 1))
                        }
                    }
                }
                'd' -> {
                    when (t.count) {
                        1 -> sb.append(day)
                        2 -> sb.append(pad2(day))
                        3 -> sb.append(DAYS_S[dow])
                        else -> sb.append(DAYS[dow])
                    }
                }
                'h' -> {
                    var h = hour
                    if (ampm) {
                        h %= 12
                        if (h == 0) h = 12
                    }
                    sb.append(if (t.count >= 2) pad2(h) else h.toString())
                }
                's' -> sb.append(if (t.count >= 2) pad2(second) else second.toString())
                'E' -> {
                    val total: Long = when (t.text) {
                        "h" -> days * 24 + hour
                        "m" -> (days * 24 + hour) * 60 + minute
                        else -> ((days * 24 + hour) * 60 + minute) * 60 + second
                    }
                    var s = total.toString()
                    while (s.length < t.count) s = "0$s"
                    sb.append(s)
                }
                'P' -> {
                    val am = hour < 12
                    if (t.text == "A/P") sb.append(if (am) "Ö" else "S") else sb.append(if (am) "ÖÖ" else "ÖS")
                }
            }
        }
        return sb.toString()
    }
}
