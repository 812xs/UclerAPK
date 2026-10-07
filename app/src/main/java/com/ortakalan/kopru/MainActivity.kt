package com.ortakalan.kopru

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
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
    private lateinit var status: TextView
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
                loadParsed(prefs.getInt("sheet", 0))
            } else {
                status.text = "Başlamak için \"Excel Aç\" düğmesiyle .xlsx dosyanızı seçin."
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

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)
        root.setPadding(dp(6), dp(6), dp(6), 0)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL

        fun barButton(text: String, onClick: () -> Unit) {
            val b = Button(this)
            b.text = text
            b.setOnClickListener { onClick() }
            bar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        barButton("Excel Aç") { pickFile() }
        barButton("Sayfa") { chooseSheet() }
        barButton("Ayarlar") { showSettings() }
        root.addView(bar)

        status = TextView(this)
        status.textSize = 12f
        status.setTextColor(Color.DKGRAY)
        status.setPadding(dp(4), dp(4), dp(4), dp(2))
        root.addView(status)

        search = EditText(this)
        search.hint = "Ara (birden fazla kelime yazabilirsiniz)"
        search.setSingleLine(true)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })
        root.addView(search)

        // Excel'deki formül çubuğu gibi: seçili hücrenin adresi ve tam içeriği
        cellInfo = TextView(this)
        cellInfo.textSize = 13f
        cellInfo.setTextColor(Color.BLACK)
        cellInfo.setBackgroundColor(0xFFF3F3F3.toInt())
        cellInfo.setPadding(dp(8), dp(4), dp(8), dp(4))
        cellInfo.maxLines = 2
        cellInfo.ellipsize = TextUtils.TruncateAt.END
        root.addView(
            cellInfo,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

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
        startActivityForResult(i, REQ_PICK)
    }

    private fun importFromUri(uri: Uri) {
        status.text = "Dosya yükleniyor..."
        Thread {
            try {
                var label = "Excel"
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) label = c.getString(idx) ?: label
                }
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
                    status.text = "Dosya yüklenemedi."
                    showError("Dosya yüklenemedi: ${e.message}")
                }
            }
        }.start()
    }

    private fun loadParsed(wantedSheet: Int) {
        status.text = "Excel okunuyor, lütfen bekleyin..."
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
                    grid.setData(sd)
                    cellInfo.text = ""
                    applyFilter()
                }
            } catch (e: Throwable) {
                ui.post {
                    status.text = "Excel okunamadı."
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
        val label = prefs.getString("label", "") ?: ""
        val sheetName = sheets.getOrNull(sheetIndex)?.name ?: ""
        val total = if (sd == null) 0 else (sd.lastRow - sd.frozenRows)
        status.text = "$label  ·  $sheetName  ·  ${grid.shownRowCount()} / $total satır"
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
