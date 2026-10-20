package com.ortakalan.kopru

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterModelTest {

    private fun txt(col: Int, text: String) = Cell(col, text, 0, 0, null)
    private fun num(col: Int, text: String, n: Double) = Cell(col, text, 0, 1, null, n)
    private fun serial(y: Int, m: Int, d: Int): Double = (LocalDate.of(y, m, d).toEpochDay() + 25569L).toDouble()

    @Test
    fun containsIsCaseInsensitiveAndTurkishAware() {
        val c = Cond(Op.CONTAINS, "ışık")
        assertTrue(c.matches(txt(0, "BAŞKA IŞIK VAR")))
        assertFalse(c.matches(txt(0, "karanlık")))
        assertTrue(Cond(Op.NOT_CONTAINS, "ışık").matches(txt(0, "karanlık")))
        assertTrue(Cond(Op.BEGINS, "üç").matches(txt(0, "ÜÇLER MAKİNA")))
        assertTrue(Cond(Op.ENDS, "makina").matches(txt(0, "Üçler Makina")))
    }

    @Test
    fun numericComparisonUsesTurkishNumberFormat() {
        val gt = Cond(Op.GT, "1.000,5")
        assertTrue(gt.matches(num(0, "1.200,00", 1200.0)))
        assertFalse(gt.matches(num(0, "900", 900.0)))
        assertFalse(gt.matches(txt(0, "metin")))
        assertTrue(Cond(Op.LE, "10").matches(num(0, "10", 10.0)))
        assertTrue(Cond(Op.EQ, "2,5").matches(num(0, "2,5", 2.5)))
    }

    @Test
    fun dateOperandIsComparedBySerialNumber() {
        val after = Cond(Op.GE, "01.05.2024")
        assertTrue(after.matches(num(0, "17.05.2024", serial(2024, 5, 17))))
        assertFalse(after.matches(num(0, "30.04.2024", serial(2024, 4, 30))))
    }

    @Test
    fun wildcardsWorkForEqualAndNotEqual() {
        assertTrue(Cond(Op.EQ, "A*a").matches(txt(0, "Alfa")))
        assertFalse(Cond(Op.EQ, "A*a").matches(txt(0, "Beta")))
        assertTrue(Cond(Op.EQ, "?lfa").matches(txt(0, "Alfa")))
        assertFalse(Cond(Op.EQ, "?lfa").matches(txt(0, "Alfaa")))
        assertTrue(Cond(Op.NE, "A*").matches(txt(0, "Beta")))
    }

    @Test
    fun excelNonBlankEncodingIsHonoured() {
        val nonBlank = Cond(Op.NE, " ")
        assertFalse(nonBlank.matches(null))
        assertTrue(nonBlank.matches(txt(0, "x")))
        assertTrue(Cond(Op.EQ, " ").matches(null))
    }

    @Test
    fun valueListMatchesCaseInsensitivelyAndHandlesBlanks() {
        val onlyAlfa = ColCriteria(keys = setOf("alfa"))
        assertTrue(onlyAlfa.passes(txt(0, "ALFA")))
        assertFalse(onlyAlfa.passes(txt(0, "Beta")))
        assertFalse(onlyAlfa.passes(null))
        assertTrue(ColCriteria(keys = setOf("alfa"), blank = true).passes(null))
    }

    @Test
    fun dateGroupItemsMatchYearMonthDay() {
        val may2024 = ColCriteria(dateGroups = listOf(DateGroup(2024, 5, -1, -1, -1, -1)))
        assertTrue(may2024.passes(num(0, "17.05.2024", serial(2024, 5, 17))))
        assertFalse(may2024.passes(num(0, "17.06.2024", serial(2024, 6, 17))))
        assertFalse(may2024.passes(null))
        val exact = ColCriteria(dateGroups = listOf(DateGroup(2024, 5, 17, -1, -1, -1)))
        assertTrue(exact.passes(num(0, "17.05.2024", serial(2024, 5, 17))))
        assertFalse(exact.passes(num(0, "18.05.2024", serial(2024, 5, 18))))
    }

    @Test
    fun customConditionsCombineWithAndOr() {
        val between = ColCriteria(c1 = Cond(Op.GE, "5"), c2 = Cond(Op.LE, "10"), isAnd = true)
        assertTrue(between.passes(num(0, "7", 7.0)))
        assertFalse(between.passes(num(0, "11", 11.0)))
        val either = ColCriteria(c1 = Cond(Op.LT, "2"), c2 = Cond(Op.GT, "9"), isAnd = false)
        assertTrue(either.passes(num(0, "1", 1.0)))
        assertTrue(either.passes(num(0, "10", 10.0)))
        assertFalse(either.passes(num(0, "5", 5.0)))
    }

    @Test
    fun topTenAndDynamicUseSavedBounds() {
        val top = ColCriteria(top = TopSpec(true, 25.0))
        assertTrue(top.passes(num(0, "30", 30.0)))
        assertTrue(top.passes(num(0, "25", 25.0)))
        assertFalse(top.passes(num(0, "20", 20.0)))
        assertFalse(top.passes(txt(0, "x")))
        val above = ColCriteria(dyn = DynSpec("aboveAverage", 10.0, Double.NaN))
        assertTrue(above.passes(num(0, "11", 11.0)))
        assertFalse(above.passes(num(0, "10", 10.0)))
    }

    @Test
    fun unsupportedCriteriaDoNotHideRows() {
        val colorFilter = ColCriteria(unsupported = true)
        assertTrue(colorFilter.isActive())
        assertTrue(colorFilter.passes(txt(0, "anything")))
    }

    // ---- değer listesi: Excel gibi diğer sütunların filtresine göre daralır, sıralı gelir

    private fun rows(): Array<RowData?> {
        val data = arrayOfNulls<RowData>(7)
        data[1] = RowData(1, 20f, false, arrayOf(txt(0, "Müşteri"), txt(1, "Durum"), txt(2, "Tutar")))
        data[2] = RowData(2, 20f, false, arrayOf(txt(0, "Alfa"), txt(1, "Açık"), num(2, "10", 10.0)))
        data[3] = RowData(3, 20f, false, arrayOf(txt(0, "Beta"), txt(1, "Kapalı"), num(2, "2", 2.0)))
        data[4] = RowData(4, 20f, false, arrayOf(txt(0, "alfa"), txt(1, "Kapalı"), num(2, "1", 1.0)))
        data[5] = RowData(5, 20f, false, arrayOf(txt(1, "Açık"), num(2, "5", 5.0)))
        data[6] = RowData(6, 20f, false, arrayOf(txt(0, "Gama"), txt(1, "Açık"), num(2, "7", 7.0)))
        return data
    }

    private val info = FilterInfo(1, 0, 2, 6, emptyMap(), emptySet(), false)

    @Test
    fun collectGroupsCaseInsensitivelyAndPutsBlankLast() {
        val items = FilterEngine.collect(rows(), info, 0, emptyMap())
        assertEquals(listOf("Alfa", "Beta", "Gama", "(Boş)"), items.map { it.text })
        assertEquals(listOf(2, 1, 1, 1), items.map { it.count })
        assertTrue(items.last().isBlank)
    }

    @Test
    fun collectSortsNumbersNumerically() {
        val items = FilterEngine.collect(rows(), info, 2, emptyMap())
        assertEquals(listOf("1", "2", "5", "7", "10"), items.map { it.text })
    }

    @Test
    fun collectIsNarrowedByOtherColumnsFilters() {
        val crit = mapOf(1 to ColCriteria(keys = setOf("açık")))
        val items = FilterEngine.collect(rows(), info, 0, crit)
        assertEquals(listOf("Alfa", "Gama", "(Boş)"), items.map { it.text })
        // kendi sütununun filtresi listeyi daraltmaz
        val own = mapOf(0 to ColCriteria(keys = setOf("alfa")))
        assertEquals(4, FilterEngine.collect(rows(), info, 0, own).size)
    }

    @Test
    fun passesAllAppliesEveryActiveColumn() {
        val r = rows()
        val active = listOf(
            Pair(1, ColCriteria(keys = setOf("açık"))),
            Pair(2, ColCriteria(c1 = Cond(Op.GT, "6")))
        )
        val visible = (2..6).filter { FilterEngine.passesAll(r[it], active) }
        assertEquals(listOf(2, 6), visible)
    }
}
