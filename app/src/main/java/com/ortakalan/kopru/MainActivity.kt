package com.ortakalan.kopru

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private val REQ_PICK = 101

    private lateinit var prefs: SharedPreferences
    private lateinit var grid: GridView
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var cellInfo: TextView
    private lateinit var search: EditText
    private val ui = Handler(Looper.getMainLooper())

    private var sheets: List<SheetInfo> = emptyList()
    private var sheetIndex = 0
    private var current: SheetData? = null

    private val savedXlsx: File get() = File(filesDir, "last.xlsx")
    private val smbCache: File get() = File(cacheDir, "smb")

    // ------------------------------------------------------------------ yaşam döngüsü

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("kopru", Context.MODE_PRIVATE)
        buildUi()

        if (!handleIntent(intent)) {
            if (savedXlsx.exists()) {
                titleView.text = prefs.getString("label", "Excel") ?: "Excel"
                loadParsed(prefs.getInt("sheet", 0))
            } else {
                titleView.text = "Köprü Açıcı"
                subView.text = "Başlamak için \"Aç\" düğmesiyle Excel dosyanızı seçin"
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) importFromUri(uri)
        }
    }

    private fun handleIntent(i: Intent?): Boolean {
        if (i != null && i.action == Intent.ACTION_VIEW && i.data != null) {
            importFromUri(i.data!!)
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ arayüz

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun navButton(text: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = text
        b.setTextColor(Color.WHITE)
        b.textSize = 14f
        b.gravity = Gravity.CENTER
        b.setSingleLine(true)
        b.setPadding(dp(14), dp(9), dp(14), dp(9))
        b.background = rounded(0x33FFFFFF, 8)
        b.isClickable = true
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        // ---- üst çubuk (navbar): dosya adı | arama | Aç / Sayfa / Ayarlar
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL
        nav.setBackgroundColor(0xFF217346.toInt())
        nav.setPadding(dp(14), dp(8), dp(10), dp(8))
        nav.elevation = dp(4).toFloat()

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titleView = TextView(this)
        titleView.setTextColor(Color.WHITE)
        titleView.textSize = 16f
        titleView.setTypeface(titleView.typeface, android.graphics.Typeface.BOLD)
        titleView.setSingleLine(true)
        titleView.ellipsize = TextUtils.TruncateAt.END
        subView = TextView(this)
        subView.setTextColor(0xFFCFE8D9.toInt())
        subView.textSize = 11f
        subView.setSingleLine(true)
        subView.ellipsize = TextUtils.TruncateAt.END
        titles.addView(titleView)
        titles.addView(subView)
        nav.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        search = EditText(this)
        search.hint = "Ara..."
        search.setHintTextColor(0xFF8A8A8A.toInt())
        search.setTextColor(Color.BLACK)
        search.textSize = 14f
        search.setSingleLine(true)
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.background = rounded(Color.WHITE, 20)
        search.setPadding(dp(16), 0, dp(16), 0)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })
        val sp = LinearLayout.LayoutParams(0, dp(40), 1.3f)
        sp.leftMargin = dp(12)
        nav.addView(search, sp)

        fun addNav(text: String, onClick: () -> Unit) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = dp(8)
            nav.addView(navButton(text, onClick), lp)
        }
        addNav("Aç") { pickFile() }
        addNav("Sayfa") { chooseSheet() }
        addNav("Ayarlar") { showSettings() }

        root.addView(
            nav,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // ---- formül çubuğu: seçili hücrenin adresi ve tam içeriği
        cellInfo = TextView(this)
        cellInfo.textSize = 13f
        cellInfo.setTextColor(Color.BLACK)
        cellInfo.setBackgroundColor(0xFFF3F3F3.toInt())
        cellInfo.setPadding(dp(12), dp(5), dp(12), dp(5))
        cellInfo.maxLines = 2
        cellInfo.ellipsize = TextUtils.TruncateAt.END
        root.addView(
            cellInfo,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // ---- tablo
        grid = GridView(this)
        grid.onCellSelected = { ref, text ->
            cellInfo.text = if (text.isEmpty()) ref else "$ref:  $text"
        }
        grid.onLinkTap = { cell ->
            val target = cell.link
            if (target != null) openLink(cell.text.ifBlank { target }, target)
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    // ------------------------------------------------------------------ Excel yükleme

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        // Seçicide yalnızca Excel dosyaları listelenir
        i.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
                "application/vnd.ms-excel.sheet.macroEnabled.12",
                "application/vnd.ms-excel"
            )
        )
        startActivityForResult(i, REQ_PICK)
    }

    private fun displayName(uri: Uri): String? {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        } catch (e: Throwable) {
            // ad okunamazsa yine de devam et
        }
        return name
    }

    private fun importFromUri(uri: Uri) {
        val label = displayName(uri) ?: "Excel"
        val lower = label.lowercase()
        if (lower.endsWith(".xls") || lower.endsWith(".csv")) {
            showError("Bu dosya türü desteklenmiyor: $label\n\nExcel'de \"Farklı Kaydet\" ile .xlsx olarak kaydedip onu seçin.")
            return
        }
        titleView.text = label
        subView.text = "Dosya yükleniyor..."
        Thread {
            try {
                val tmp = File(filesDir, "incoming.xlsx")
                contentResolver.openInputStream(uri).use { ins ->
                    if (ins == null) throw IllegalArgumentException("Dosya okunamadı.")
                    FileOutputStream(tmp).use { os -> ins.copyTo(os) }
                }
                savedXlsx.delete()
                if (!tmp.renameTo(savedXlsx)) {
                    tmp.copyTo(savedXlsx, overwrite = true)
                    tmp.delete()
                }
                prefs.edit().putString("label", label).putInt("sheet", 0).apply()
                ui.post { loadParsed(0) }
            } catch (e: Throwable) {
                ui.post {
                    subView.text = "Dosya yüklenemedi"
                    showError("Dosya yüklenemedi: ${e.message}")
                }
            }
        }.start()
    }

    private fun loadParsed(wantedSheet: Int) {
        subView.text = "Excel okunuyor, lütfen bekleyin..."
        Thread {
            try {
                val file = savedXlsx
                val s = XlsxReader.listSheets(file)
                val idx = wantedSheet.coerceIn(0, s.size - 1)
                val sd = XlsxReader.readSheet(file, s[idx])
                ui.post {
                    sheets = s
                    sheetIndex = idx
                    current = sd
                    prefs.edit().putInt("sheet", idx).apply()
                    titleView.text = prefs.getString("label", "Excel") ?: "Excel"
                    grid.setData(sd)
                    cellInfo.text = ""
                    applyFilter()
                }
            } catch (e: Throwable) {
                ui.post {
                    subView.text = "Excel okunamadı"
                    val msg = if (e is IllegalArgumentException) e.message else "${e.javaClass.simpleName}: ${e.message}"
                    showError("Excel dosyası okunamadı.\n\n$msg")
                }
            }
        }.start()
    }

    private fun chooseSheet() {
        if (sheets.size <= 1) {
            toast(if (sheets.isEmpty()) "Önce bir Excel dosyası açın" else "Dosyada tek sayfa var")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Sayfa seçin")
            .setItems(sheets.map { it.name }.toTypedArray()) { _, i -> loadParsed(i) }
            .show()
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase(TextTools.TR)
        val words = q.split(' ').filter { it.isNotEmpty() }
        grid.setFilter(words)

        val sd = current
        val sheetName = sheets.getOrNull(sheetIndex)?.name ?: ""
        val total = if (sd == null) 0 else (sd.lastRow - sd.frozenRows)
        subView.text = "$sheetName  ·  ${grid.shownRowCount()} / $total satır"
    }

    // ------------------------------------------------------------------ köprü açma

    private fun openLink(label: String, target: String) {
        if (LinkTools.isWeb(target)) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
            } catch (e: ActivityNotFoundException) {
                showError("Web bağlantısı açılamadı.")
            }
            return
        }

        val user = prefs.getString("user", "") ?: ""
        val pass = prefs.getString("pass", "") ?: ""
        val domain = prefs.getString("domain", "") ?: ""
        val host = prefs.getString("host", "") ?: ""

        if (user.isBlank()) {
            toast("Önce kullanıcı adı ve şifrenizi girin")
            showSettings()
            return
        }

        val url = LinkTools.toSmbUrl(target, host)
        if (url == null) {
            showError("Bu bağlantı anlaşılamadı:\n\n$target")
            return
        }

        val cancel = AtomicBoolean(false)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Dosya açılıyor")
            .setMessage(label)
            .setNegativeButton("İptal") { _, _ -> cancel.set(true) }
            .setCancelable(false)
            .create()
        dlg.show()

        Thread {
            try {
                smbCache.mkdirs()
                SmbTools.cleanOld(smbCache)
                val f = SmbTools.download(url, user, pass, domain, smbCache, cancel)
                ui.post {
                    dlg.dismiss()
                    viewFile(f)
                }
            } catch (c: SmbTools.Cancelled) {
                ui.post { dlg.dismiss() }
            } catch (e: Throwable) {
                ui.post {
                    dlg.dismiss()
                    showError(SmbTools.explain(e))
                }
            }
        }.start()
    }

    private fun viewFile(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val ext = f.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, mime)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            showError("Dosya indirildi ama bu türü açacak bir uygulama yok: ${f.name}\n\nTablete bir PDF/dosya görüntüleyici kurun.")
        } catch (e: Throwable) {
            showError("Dosya açılamadı: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ ayarlar

    private fun showSettings() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(10), dp(20), dp(10))

        fun field(label: String, key: String, password: Boolean): EditText {
            val t = TextView(this)
            t.text = label
            t.setPadding(0, dp(10), 0, 0)
            col.addView(t)
            val e = EditText(this)
            e.setSingleLine(true)
            e.setText(prefs.getString(key, "") ?: "")
            e.inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            col.addView(e)
            return e
        }

        val user = field("Kullanıcı adı", "user", false)
        val pass = field("Şifre", "pass", true)
        val domain = field("Etki alanı / bilgisayar adı (isteğe bağlı)", "domain", false)
        val host = field("Sunucu IP (boş bırakırsanız köprüdeki adres kullanılır)", "host", false)

        val sv = ScrollView(this)
        sv.addView(col)

        AlertDialog.Builder(this)
            .setTitle("Ağ giriş bilgileri")
            .setView(sv)
            .setPositiveButton("Kaydet") { _, _ ->
                prefs.edit()
                    .putString("user", user.text.toString().trim())
                    .putString("pass", pass.text.toString())
                    .putString("domain", domain.text.toString().trim())
                    .putString("host", host.text.toString().trim())
                    .apply()
                toast("Kaydedildi")
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ------------------------------------------------------------------ küçük yardımcılar

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showError(msg: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Uyarı")
            .setMessage(msg)
            .setPositiveButton("Tamam", null)
            .show()
    }
}
