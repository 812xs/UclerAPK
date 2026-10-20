package com.ortakalan.kopru

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * Excel'deki filtre menüsü: değer listesi (onay kutuları, arama, "Tümünü Seç") ve "Metin/Sayı filtreleri".
 * Liste yalnızca diğer sütunların filtrelerini sağlayan satırlardaki değerleri gösterir.
 */
class FilterDialog(
    private val act: Activity,
    private val grid: GridView,
    private val col: Int
) {

    private val items: List<ValueItem> = grid.collectValues(col)
    private val checked = BooleanArray(items.size)
    private var shown: IntArray = IntArray(items.size) { it }

    /** Arama başlamadan önceki seçim; arama temizlenince geri yüklenir. */
    private var snapshot: BooleanArray? = null

    private val adapter = ItemAdapter()
    private lateinit var allBox: CheckBox
    private lateinit var listView: ListView
    private lateinit var dialog: AlertDialog

    init {
        val cr = grid.criteriaOf(col)
        for (i in items.indices) {
            checked[i] = cr == null || !cr.isActive() || cr.passes(items[i].rep)
        }
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    fun show() {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(6), dp(16), 0)

        val cond = TextView(act)
        cond.text = "Metin / Sayı filtreleri…"
        cond.setTextColor(0xFF1A73E8.toInt())
        cond.textSize = 15f
        cond.setPadding(dp(4), dp(10), dp(4), dp(10))
        cond.isClickable = true
        cond.setOnClickListener { showCondDialog() }
        root.addView(cond)

        val search = EditText(act)
        search.hint = "Ara"
        search.setSingleLine(true)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onSearch(s?.toString() ?: "")
            }
        })
        root.addView(search)

        allBox = CheckBox(act)
        allBox.text = "(Tümünü Seç)"
        allBox.textSize = 15f
        allBox.setOnClickListener { toggleAll() }
        root.addView(allBox)

        listView = ListView(act)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            if (pos in shown.indices) {
                val idx = shown[pos]
                checked[idx] = !checked[idx]
                adapter.notifyDataSetChanged()
                syncAll()
            }
        }
        val h = (act.resources.displayMetrics.heightPixels * 0.42f).toInt()
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))

        val head = grid.headerText(col)
        val title = if (head.isEmpty()) "Filtre: sütun ${grid.columnLetter(col)}" else "Filtre: " + head.take(40)

        dialog = AlertDialog.Builder(act)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("Tamam", null)
            .setNegativeButton("İptal", null)
            .setNeutralButton("Filtreyi temizle") { _, _ -> grid.setCriteria(col, null) }
            .create()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        dialog.show()
        // "Tamam" seçim geçersizse pencereyi kapatmasın
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { applySelection() }
        syncAll()
    }

    // ------------------------------------------------------------------ liste

    private fun syncAll() {
        var all = shown.isNotEmpty()
        for (i in shown) {
            if (!checked[i]) {
                all = false
                break
            }
        }
        allBox.isChecked = all
    }

    private fun toggleAll() {
        var allOn = shown.isNotEmpty()
        for (i in shown) {
            if (!checked[i]) {
                allOn = false
                break
            }
        }
        val target = !allOn
        for (i in shown) checked[i] = target
        adapter.notifyDataSetChanged()
        syncAll()
    }

    private fun onSearch(raw: String) {
        val q = raw.trim().lowercase(TextTools.TR)
        if (q.isEmpty()) {
            val snap = snapshot
            if (snap != null) {
                System.arraycopy(snap, 0, checked, 0, checked.size)
                snapshot = null
            }
            shown = IntArray(items.size) { it }
        } else {
            if (snapshot == null) snapshot = checked.copyOf()
            val list = ArrayList<Int>()
            for (i in items.indices) {
                val hay = if (items[i].isBlank) "(boş)" else items[i].key
                if (hay.contains(q)) list.add(i)
            }
            shown = list.toIntArray()
            // Excel gibi: arama sonuçlarının hepsi seçili gelir, diğerleri seçilmez
            java.util.Arrays.fill(checked, false)
            for (i in shown) checked[i] = true
        }
        adapter.notifyDataSetChanged()
        listView.setSelection(0)
        syncAll()
    }

    private fun applySelection() {
        if (items.isEmpty()) {
            dialog.dismiss()
            return
        }
        var any = false
        var all = true
        for (b in checked) {
            if (b) any = true else all = false
        }
        if (!any) {
            Toast.makeText(act, "En az bir değer seçin", Toast.LENGTH_SHORT).show()
            return
        }
        if (all) {
            grid.setCriteria(col, null)
        } else {
            val keys = HashSet<String>()
            var blank = false
            for (i in items.indices) {
                if (!checked[i]) continue
                if (items[i].isBlank) blank = true else keys.add(items[i].key)
            }
            grid.setCriteria(col, ColCriteria(keys = keys, blank = blank))
        }
        dialog.dismiss()
    }

    private class Holder(val box: CheckBox, val count: TextView)

    private inner class ItemAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = items[shown[position]]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: View
            val holder: Holder
            if (convertView == null) {
                val box = CheckBox(act)
                box.isClickable = false
                box.isFocusable = false
                box.textSize = 15f
                box.setSingleLine(true)
                box.ellipsize = TextUtils.TruncateAt.END
                val cnt = TextView(act)
                cnt.textSize = 12f
                cnt.setTextColor(0xFF80868B.toInt())
                cnt.setPadding(dp(8), 0, dp(4), 0)
                val ll = LinearLayout(act)
                ll.orientation = LinearLayout.HORIZONTAL
                ll.gravity = Gravity.CENTER_VERTICAL
                ll.setPadding(dp(2), dp(2), dp(2), dp(2))
                ll.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                ll.addView(cnt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                holder = Holder(box, cnt)
                ll.tag = holder
                row = ll
            } else {
                row = convertView
                holder = convertView.tag as Holder
            }
            val idx = shown[position]
            holder.box.text = items[idx].text
            holder.box.isChecked = checked[idx]
            holder.count.text = items[idx].count.toString()
            return row
        }
    }

    // ------------------------------------------------------------------ Metin / Sayı filtreleri

    private fun showCondDialog() {
        val cur = grid.criteriaOf(col)

        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(10), dp(20), dp(6))

        fun spinner(sel: Int): Spinner {
            val sp = Spinner(act)
            sp.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, Op.labels)
            sp.setSelection(sel)
            return sp
        }

        fun input(text: String): EditText {
            val e = EditText(act)
            e.hint = "Değer"
            e.setSingleLine(true)
            e.setText(text)
            return e
        }

        val sp1 = spinner(cur?.c1?.op ?: Op.EQ)
        val et1 = input(cur?.c1?.operand ?: "")
        box.addView(sp1)
        box.addView(et1)

        val rg = RadioGroup(act)
        rg.orientation = RadioGroup.HORIZONTAL
        val rbAnd = RadioButton(act)
        rbAnd.text = "VE"
        rbAnd.id = View.generateViewId()
        val rbOr = RadioButton(act)
        rbOr.text = "VEYA"
        rbOr.id = View.generateViewId()
        rg.addView(rbAnd)
        rg.addView(rbOr)
        if (cur?.c1 != null && cur.isAnd.not()) rbOr.isChecked = true else rbAnd.isChecked = true
        box.addView(rg)

        val sp2 = spinner(cur?.c2?.op ?: Op.EQ)
        val et2 = input(cur?.c2?.operand ?: "")
        box.addView(sp2)
        box.addView(et2)

        val note = TextView(act)
        note.text = "\"eşittir\" ve \"eşit değildir\" için ? (tek karakter) ve * (çok karakter) kullanabilirsiniz."
        note.textSize = 12f
        note.setTextColor(Color.GRAY)
        note.setPadding(0, dp(8), 0, 0)
        box.addView(note)

        val cd = AlertDialog.Builder(act)
            .setTitle("Metin / Sayı filtresi")
            .setView(box)
            .setPositiveButton("Tamam", null)
            .setNegativeButton("İptal", null)
            .create()
        cd.show()
        cd.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val t1 = et1.text.toString().trim()
            val t2 = et2.text.toString().trim()
            val c1: Cond? = if (t1.isEmpty()) null else Cond(sp1.selectedItemPosition, t1)
            val c2: Cond? = if (t2.isEmpty()) null else Cond(sp2.selectedItemPosition, t2)
            val first = c1 ?: c2
            if (first == null) {
                Toast.makeText(act, "Bir değer girin", Toast.LENGTH_SHORT).show()
            } else {
                val second = if (c1 != null) c2 else null
                grid.setCriteria(col, ColCriteria(c1 = first, c2 = second, isAnd = rbAnd.isChecked))
                cd.dismiss()
                dialog.dismiss()
            }
        }
    }
}
