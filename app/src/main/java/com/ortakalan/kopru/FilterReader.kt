package com.ortakalan.kopru

import org.xmlpull.v1.XmlPullParser

/**
 * Sayfa XML'indeki (veya tablo XML'indeki) <autoFilter> bölümünü toplar: aralık, sütun başına kayıtlı ölçütler
 * ve gizli filtre düğmeleri. Sütun numaraları aralığın ilk sütununa görecelidir (colId).
 */
class FilterCollector {

    var ref: String? = null
    val criteria = HashMap<Int, ColCriteria>()
    val noButton = HashSet<Int>()

    private var inAf = false
    private var inCol = false
    private var colId = 0
    private var vals = ArrayList<String>()
    private var blank = false
    private var groups = ArrayList<DateGroup>()
    private var conds = ArrayList<Cond>()
    private var andOp = false
    private var top: TopSpec? = null
    private var dyn: DynSpec? = null
    private var unsupported = false

    private fun local(p: XmlPullParser): String {
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

    private fun flag(v: String?, def: Boolean): Boolean = when (v) {
        null -> def
        "1", "true" -> true
        "0", "false" -> false
        else -> def
    }

    private fun intAttr(p: XmlPullParser, name: String): Int = attr(p, name)?.toIntOrNull() ?: -1

    private fun dblAttr(p: XmlPullParser, name: String): Double = attr(p, name)?.toDoubleOrNull() ?: Double.NaN

    private fun opOf(s: String?): Int = when (s) {
        "notEqual" -> Op.NE
        "greaterThan" -> Op.GT
        "greaterThanOrEqual" -> Op.GE
        "lessThan" -> Op.LT
        "lessThanOrEqual" -> Op.LE
        else -> Op.EQ
    }

    fun start(p: XmlPullParser) {
        when (local(p)) {
            "autoFilter" -> {
                if (ref == null) {
                    ref = attr(p, "ref")
                    inAf = ref != null
                }
            }
            "filterColumn" -> if (inAf) {
                inCol = true
                colId = attr(p, "colId")?.toIntOrNull() ?: 0
                vals = ArrayList()
                blank = false
                groups = ArrayList()
                conds = ArrayList()
                andOp = false
                top = null
                dyn = null
                unsupported = false
                if (flag(attr(p, "hiddenButton"), false) || !flag(attr(p, "showButton"), true)) {
                    noButton.add(colId)
                }
            }
            "filters" -> if (inCol) blank = flag(attr(p, "blank"), false)
            "filter" -> if (inCol) {
                val v = attr(p, "val")
                if (v != null) vals.add(v)
            }
            "dateGroupItem" -> if (inCol) {
                groups.add(
                    DateGroup(
                        intAttr(p, "year"), intAttr(p, "month"), intAttr(p, "day"),
                        intAttr(p, "hour"), intAttr(p, "minute"), intAttr(p, "second")
                    )
                )
            }
            "customFilters" -> if (inCol) andOp = flag(attr(p, "and"), false)
            "customFilter" -> if (inCol) {
                conds.add(Cond(opOf(attr(p, "operator")), attr(p, "val") ?: ""))
            }
            "top10" -> if (inCol) {
                val b = dblAttr(p, "filterVal")
                if (b.isNaN()) unsupported = true else top = TopSpec(flag(attr(p, "top"), true), b)
            }
            "dynamicFilter" -> if (inCol) {
                val v = dblAttr(p, "val")
                if (v.isNaN()) {
                    unsupported = true
                } else {
                    dyn = DynSpec(attr(p, "type") ?: "", v, dblAttr(p, "maxVal"))
                }
            }
            "colorFilter", "iconFilter" -> if (inCol) unsupported = true
        }
    }

    fun end(p: XmlPullParser) {
        when (local(p)) {
            "filterColumn" -> finishColumn()
            "autoFilter" -> inAf = false
        }
    }

    private fun finishColumn() {
        if (!inCol) return
        inCol = false
        val t = top
        val y = dyn
        val c: ColCriteria? = when {
            unsupported -> ColCriteria(unsupported = true)
            t != null -> ColCriteria(top = t)
            y != null -> ColCriteria(dyn = y)
            conds.isNotEmpty() -> ColCriteria(c1 = conds[0], c2 = conds.getOrNull(1), isAnd = andOp)
            vals.isNotEmpty() || groups.isNotEmpty() || blank -> ColCriteria(
                keys = vals.map { it.lowercase(TextTools.TR) }.toHashSet(),
                blank = blank,
                dateGroups = groups
            )
            else -> null
        }
        if (c != null) criteria[colId] = c
    }
}
