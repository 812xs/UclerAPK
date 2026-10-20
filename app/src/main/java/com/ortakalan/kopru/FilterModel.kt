package com.ortakalan.kopru

import java.text.Collator
import java.time.LocalDate

/** Özel filtre işleçleri (Excel'deki "Metin/Sayı filtreleri"). */
object Op {
    const val EQ = 0
    const val NE = 1
    const val GT = 2
    const val GE = 3
    const val LT = 4
    const val LE = 5
    const val BEGINS = 6
    const val ENDS = 7
    const val CONTAINS = 8
    const val NOT_CONTAINS = 9

    val labels = arrayOf(
        "eşittir", "eşit değildir", "büyüktür", "büyük veya eşittir",
        "küçüktür", "küçük veya eşittir", "ile başlar", "ile biter", "içerir", "içermez"
    )
}

/** Excel'in tarih grubu ölçütü (yıl/ay/gün/saat/dakika/saniye). -1 = o düzeyde koşul yok. */
class DateGroup(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int
) {
    fun matches(serial: Double): Boolean {
        if (serial.isNaN() || serial < 1.0) return false
        val whole = Math.floor(serial)
        val date = try {
            LocalDate.ofEpochDay(whole.toLong() - 25569L)
        } catch (e: Exception) {
            return false
        }
        if (year >= 0 && date.year != year) return false
        if (month >= 0 && date.monthValue != month) return false
        if (day >= 0 && date.dayOfMonth != day) return false
        if (hour >= 0 || minute >= 0 || second >= 0) {
            val secs = Math.round((serial - whole) * 86400.0).toInt()
            if (hour >= 0 && secs / 3600 != hour) return false
            if (minute >= 0 && (secs % 3600) / 60 != minute) return false
            if (second >= 0 && secs % 60 != second) return false
        }
        return true
    }
}

/** "İlk 10" filtresi: Excel kaydederken hesapladığı sınır değeri (filterVal) saklanır. */
class TopSpec(val top: Boolean, val bound: Double)

/** Dinamik filtre (ortalamanın üstü/altı, bugün, bu ay vb.); v/max Excel'in kaydettiği sınırlardır. */
class DynSpec(val type: String, val v: Double, val max: Double)

/** Tek bir koşul: işleç + işlenen. EQ/NE joker karakter (* ?) destekler. */
class Cond(val op: Int, val operand: String) {

    private val low: String = operand.lowercase(TextTools.TR)
    private val numOp: Double = parseOperand(operand)
    private val wild: Regex? =
        if ((op == Op.EQ || op == Op.NE) && (operand.indexOf('*') >= 0 || operand.indexOf('?') >= 0)) {
            wildcard(operand)
        } else {
            null
        }

    fun matches(cell: Cell?): Boolean {
        val text = cell?.text ?: ""
        val num = cell?.num ?: Double.NaN
        return when (op) {
            Op.EQ -> eq(text, num)
            Op.NE -> !eq(text, num)
            Op.GT, Op.GE, Op.LT, Op.LE -> compare(text, num)
            Op.BEGINS -> text.lowercase(TextTools.TR).startsWith(low)
            Op.ENDS -> text.lowercase(TextTools.TR).endsWith(low)
            Op.CONTAINS -> text.lowercase(TextTools.TR).contains(low)
            Op.NOT_CONTAINS -> !text.lowercase(TextTools.TR).contains(low)
            else -> true
        }
    }

    private fun eq(text: String, num: Double): Boolean {
        // Excel "boş olanlar / boş olmayanlar" seçeneğini tek boşluklu işlenenle kaydeder
        if (operand == " ") return text.isEmpty()
        val w = wild
        if (w != null) return w.matches(text)
        if (!numOp.isNaN() && !num.isNaN()) return Math.abs(num - numOp) < 1e-9
        return text.equals(operand, ignoreCase = true)
    }

    private fun compare(text: String, num: Double): Boolean {
        val c: Int
        if (!numOp.isNaN()) {
            if (num.isNaN()) return false
            val diff = num - numOp
            c = if (Math.abs(diff) < 1e-9) 0 else if (diff > 0) 1 else -1
        } else {
            if (text.isEmpty()) return false
            c = collator.compare(text, operand)
        }
        return when (op) {
            Op.GT -> c > 0
            Op.GE -> c >= 0
            Op.LT -> c < 0
            else -> c <= 0
        }
    }

    companion object {
        val collator: Collator = Collator.getInstance(TextTools.TR).apply { strength = Collator.SECONDARY }

        private val DATE_RE = Regex("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})$")

        /** "1.234,5" / "12,5" / "12.5" / "17.05.2024" -> sayı (tarih Excel seri numarasına çevrilir). Çözülemezse NaN. */
        fun parseOperand(s: String): Double {
            val t = s.trim()
            if (t.isEmpty()) return Double.NaN
            val dm = DATE_RE.matchEntire(t)
            if (dm != null) {
                val d = dm.groupValues[1].toInt()
                val m = dm.groupValues[2].toInt()
                val y = dm.groupValues[3].toInt()
                return try {
                    (LocalDate.of(y, m, d).toEpochDay() + 25569L).toDouble()
                } catch (e: Exception) {
                    Double.NaN
                }
            }
            var x = t
            if (x.indexOf(',') >= 0) x = x.replace(".", "").replace(',', '.')
            val v = x.toDoubleOrNull() ?: return Double.NaN
            return if (v.isNaN() || v.isInfinite()) Double.NaN else v
        }

        private fun wildcard(p: String): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < p.length) {
                val ch = p[i]
                if (ch == '~' && i + 1 < p.length) {
                    sb.append(Regex.escape(p[i + 1].toString()))
                    i++
                } else if (ch == '*') {
                    sb.append(".*")
                } else if (ch == '?') {
                    sb.append('.')
                } else {
                    sb.append(Regex.escape(ch.toString()))
                }
                i++
            }
            return Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        }
    }
}

/**
 * Bir sütunun filtre ölçütü. Excel gibi tek türde olur: değer listesi, özel koşullar, ilk-10, dinamik
 * ya da (uygulanamayan) renk/simge filtresi. Nesneler değişmezdir; değişiklikte yenisi atanır.
 */
class ColCriteria(
    /** Seçili değerler (küçük harfli görünen metin). null = değer listesi filtresi yok. */
    val keys: Set<String>? = null,
    /** Değer listesinde "(Boş)" seçili mi. */
    val blank: Boolean = false,
    val dateGroups: List<DateGroup> = emptyList(),
    val c1: Cond? = null,
    val c2: Cond? = null,
    val isAnd: Boolean = true,
    val top: TopSpec? = null,
    val dyn: DynSpec? = null,
    /** Renk/simge filtresi gibi uygulanamayan ölçüt: hiçbir satırı elemez. */
    val unsupported: Boolean = false
) {
    val isList: Boolean get() = keys != null || dateGroups.isNotEmpty()

    fun isActive(): Boolean = isList || c1 != null || top != null || dyn != null || unsupported

    fun passes(cell: Cell?): Boolean {
        if (unsupported) return true
        val text = cell?.text ?: ""

        if (isList) {
            if (text.isEmpty()) return blank
            val ks = keys
            if (ks != null && ks.contains(text.lowercase(TextTools.TR))) return true
            if (dateGroups.isNotEmpty()) {
                val n = cell?.num ?: Double.NaN
                for (g in dateGroups) {
                    if (g.matches(n)) return true
                }
            }
            return false
        }

        val a = c1
        if (a != null) {
            val r1 = a.matches(cell)
            val b = c2 ?: return r1
            val r2 = b.matches(cell)
            return if (isAnd) (r1 && r2) else (r1 || r2)
        }

        val t = top
        if (t != null) {
            val n = cell?.num ?: Double.NaN
            if (n.isNaN()) return false
            return if (t.top) n >= t.bound - 1e-9 else n <= t.bound + 1e-9
        }

        val y = dyn
        if (y != null) {
            val n = cell?.num ?: Double.NaN
            if (n.isNaN()) return false
            return when (y.type) {
                "aboveAverage" -> n > y.v
                "belowAverage" -> n < y.v
                else -> if (y.max.isNaN()) n >= y.v else (n >= y.v && n < y.max)
            }
        }
        return true
    }
}

/** Sayfadaki filtre bölgesi (başlık satırı + veri satırları) ve dosyada kayıtlı ölçütler. */
class FilterInfo(
    /** Başlık satırı, 1 tabanlı. */
    val headerRow: Int,
    val firstCol: Int,
    val lastCol: Int,
    /** Son veri satırı (dahil), 1 tabanlı. */
    val lastRow: Int,
    /** Dosyada kayıtlı ölçütler: mutlak sütun -> ölçüt. */
    val saved: Map<Int, ColCriteria>,
    /** Filtre düğmesi gizlenmiş sütunlar. */
    val noButton: Set<Int>,
    /** Dosyada filtre tanımı yok; uygulama başlık satırını tahmin ederek oluşturdu. */
    val inferred: Boolean
) {
    fun isDataRow(rn: Int): Boolean = rn > headerRow && rn <= lastRow
    fun hasButton(col: Int): Boolean = col in firstCol..lastCol && !noButton.contains(col)
}

/** Filtre listesindeki bir değer (Excel'deki onay kutulu satır). */
class ValueItem(
    val key: String,
    val text: String,
    val num: Double,
    var count: Int,
    val isBlank: Boolean,
    val rep: Cell?
)

object FilterEngine {

    /** Satır, verilen tüm ölçütleri sağlıyor mu. */
    fun passesAll(rd: RowData?, active: List<Pair<Int, ColCriteria>>): Boolean {
        for (p in active) {
            if (!p.second.passes(rd?.cellAt(p.first))) return false
        }
        return true
    }

    private val cmp = Comparator<ValueItem> { a, b ->
        val an = !a.num.isNaN()
        val bn = !b.num.isNaN()
        if (an != bn) {
            if (an) -1 else 1
        } else if (an) {
            java.lang.Double.compare(a.num, b.num)
        } else {
            Cond.collator.compare(a.text, b.text)
        }
    }

    /**
     * [col] sütununun filtre listesi. Excel gibi, yalnızca diğer sütunların ölçütlerini sağlayan
     * satırlardaki değerler listelenir. Sayılar/tarihler küçükten büyüğe, metinler alfabetik, "(Boş)" en sonda.
     */
    fun collect(
        rows: Array<RowData?>,
        f: FilterInfo,
        col: Int,
        crit: Map<Int, ColCriteria>
    ): List<ValueItem> {
        val others = ArrayList<Pair<Int, ColCriteria>>()
        for ((c, cr) in crit) {
            if (c != col && cr.isActive()) others.add(Pair(c, cr))
        }
        val map = HashMap<String, ValueItem>()
        var blanks: ValueItem? = null
        val last = minOf(f.lastRow, rows.size - 1)
        var rn = f.headerRow + 1
        while (rn <= last) {
            val rd = rows[rn]
            if (passesAll(rd, others)) {
                val cell = rd?.cellAt(col)
                val text = cell?.text ?: ""
                if (text.isEmpty()) {
                    val b = blanks
                    if (b == null) {
                        blanks = ValueItem("", "(Boş)", Double.NaN, 1, true, null)
                    } else {
                        b.count++
                    }
                } else {
                    val key = text.lowercase(TextTools.TR)
                    val ex = map[key]
                    if (ex == null) {
                        map[key] = ValueItem(key, text, cell?.num ?: Double.NaN, 1, false, cell)
                    } else {
                        ex.count++
                    }
                }
            }
            rn++
        }
        val out = ArrayList<ValueItem>(map.values)
        out.sortWith(cmp)
        val b = blanks
        if (b != null) out.add(b)
        return out
    }
}
