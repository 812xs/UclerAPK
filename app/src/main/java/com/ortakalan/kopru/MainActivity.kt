package com.ortakalan.kopru

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import jcifs.smb.SmbFile
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    companion object {
        const val DEFAULT_HOST = "192.168.1.3"
        const val POLL_MS = 3000L
    }

    private val REQ_PICK = 101

    private lateinit var prefs: SharedPreferences
    private lateinit var grid: GridView
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var cellInfo: TextView
    private lateinit var search: EditText
    private lateinit var banner: LinearLayout
    private lateinit var refreshBtn: TextView
    private lateinit var filterBtn: TextView
    // editModeBtn kaldırıldı — çift tıkla düzenleme her zaman aktif
    private val ui = Handler(Looper.getMainLooper())

    private var sheets: List<SheetInfo> = emptyList()
    private var sheetIndex = 0
    private var current: SheetData? = null

    // ---- açık dosyanın kaynağı ve izleme durumu
    private var srcKind = "" // "smb" | "uri" | ""
    private var srcSmb = ""
    private var srcUri = ""

    // ---- bekleyen xlsx düzenlemeleri (SMB'ye kaydedilmedi)
    private val pendingEdits = LinkedHashMap<XlsxWriter.CellAddr, XlsxWriter.CellEdit>()
    private var unsaved = false

    @Volatile
    private var baseM = 0L // yüklendiği andaki son değişiklik zamanı

    @Volatile
    private var baseS = -1L // yüklendiği andaki boyut
    private var watching = false
    private var bannerShown = false
    private var refreshing = false
    private val io = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val session = SmbTools.Session() // yalnızca io iş parçacığında kullanılır

    private val savedXlsx: File get() = File(filesDir, "last.xlsx")
    private val smbCache: File get() = File(cacheDir, "smb")

    // ------------------------------------------------------------------ yaşam döngüsü

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("kopru", Context.MODE_PRIVATE)
        buildUi(savedInstanceState == null)
        loadSourceState()

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

    override fun onResume() {
        super.onResume()
        startWatching()
    }

    override fun onPause() {
        stopWatching()
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
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
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Throwable) {
                    // kalıcı izin verilemeyen sağlayıcılarda yenileme için dosyayı yeniden seçmek gerekir
                }
                importFromUri(uri)
            }
        }
    }

    private fun handleIntent(i: Intent?): Boolean {
        if (i != null && i.action == Intent.ACTION_VIEW && i.data != null) {
            importFromUri(i.data!!)
            return true
        }
        return false
    }

    private fun loadSourceState() {
        srcKind = prefs.getString("src_kind", "") ?: ""
        srcSmb = prefs.getString("src_smb", "") ?: ""
        srcUri = prefs.getString("src_uri", "") ?: ""
        baseM = prefs.getLong("src_m", 0L)
        baseS = prefs.getLong("src_s", -1L)
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

    private fun buildUi(withSplash: Boolean) {
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
                applyFilter(false)
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
        // Filtre: dosyada filtre yoksa "Filtre ekle", filtre uygulanmışsa "Filtreyi temizle"
        filterBtn = navButton("Filtre") { onFilterButton() }
        filterBtn.visibility = View.GONE
        val flp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        flp.leftMargin = dp(8)
        nav.addView(filterBtn, flp)

        addNav("Aç") { chooseOpenSource() }
        addNav("Sayfa") { chooseSheet() }
        addNav("Ayarlar") { showSettings() }

        root.addView(
            nav,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // ---- güncelleme uyarısı: dosya başka yerde değişince görünür
        banner = LinearLayout(this)
        banner.orientation = LinearLayout.VERTICAL
        banner.setBackgroundColor(0xFFFFF3CD.toInt())
        banner.setPadding(dp(14), dp(10), dp(14), dp(10))
        banner.visibility = View.GONE

        val bannerMsg = TextView(this)
        bannerMsg.text = "Seçili dosyanız uzak masaüstü tarafından güncellendi, sayfayı yenilemeniz gerekiyor."
        bannerMsg.textSize = 14f
        bannerMsg.setTextColor(0xFF664D03.toInt())
        banner.addView(bannerMsg)

        refreshBtn = TextView(this)
        refreshBtn.text = "Yenile"
        refreshBtn.setTextColor(Color.WHITE)
        refreshBtn.textSize = 14f
        refreshBtn.gravity = Gravity.CENTER
        refreshBtn.setSingleLine(true)
        refreshBtn.setPadding(dp(28), dp(9), dp(28), dp(9))
        refreshBtn.background = rounded(0xFF217346.toInt(), 8)
        refreshBtn.isClickable = true
        refreshBtn.setOnClickListener { refreshSource() }
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = dp(8)
        banner.addView(refreshBtn, rlp)

        root.addView(
            banner,
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
        grid.onFilterTap = { col -> FilterDialog(this, grid, col).show() }
        grid.onFilterChanged = { refreshFilterUi() }
        grid.onLinkTap = { cell ->
            val target = cell.link
            if (target != null) openLink(cell.text.ifBlank { target }, target)
        }
        grid.onCellDoubleTap = { row1, col0, currentText, cell ->
            showCellEditDialog(row1, col0, currentText, cell)
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Açılış animasyonu uygulamanın üstünde oynar; altta Excel zaten yüklenmeye başlar
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (withSplash) {
            val splash = SplashView(this) { v -> frame.removeView(v) }
            frame.addView(splash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(frame)
    }

    // ------------------------------------------------------------------ Excel açma

    private fun chooseOpenSource() {
        AlertDialog.Builder(this)
            .setTitle("Excel dosyası nereden açılsın?")
            .setItems(
                arrayOf(
                    "Sunucudan aç (değişiklikler anında takip edilir)",
                    "Tabletteki dosyalardan aç"
                )
            ) { _, i -> if (i == 0) showSmbBrowser() else pickFile() }
            .show()
    }

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

    /** [son değişiklik zamanı, boyut]; sağlayıcı zamanı vermiyorsa null (dosya izlenemez). */
    private fun statUri(uri: Uri): LongArray? {
        try {
            val proj = arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, OpenableColumns.SIZE)
            contentResolver.query(uri, proj, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val m = if (c.isNull(0)) 0L else c.getLong(0)
                    val s = if (c.isNull(1)) -1L else c.getLong(1)
                    if (m > 0L) return longArrayOf(m, s)
                }
            }
        } catch (e: Throwable) {
            // sağlayıcı bu sütunları desteklemiyor
        }
        return null
    }

    private fun copyUriToSaved(uri: Uri) {
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
                val st = statUri(uri)
                copyUriToSaved(uri)
                val m = st?.get(0) ?: 0L
                val s = st?.get(1) ?: -1L
                prefs.edit()
                    .putString("label", label).putInt("sheet", 0)
                    .putString("src_kind", "uri").putString("src_uri", uri.toString()).putString("src_smb", "")
                    .putLong("src_m", m).putLong("src_s", s)
                    .apply()
                ui.post {
                    srcKind = "uri"
                    srcUri = uri.toString()
                    srcSmb = ""
                    baseM = m
                    baseS = s
                    hideBanner()
                    loadParsed(0)
                    if (m > 0L) {
                        startWatching()
                    } else {
                        stopWatching()
                        toastLong("Bu konumdaki dosyanın değişikliği izlenemiyor. Anlık takip için Aç > Sunucudan aç seçeneğini kullanın.")
                    }
                }
            } catch (e: Throwable) {
                ui.post {
                    subView.text = "Dosya yüklenemedi"
                    showError("Dosya yüklenemedi: ${e.message}")
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ sunucudan açma

    private fun creds(): Array<String> = arrayOf(
        prefs.getString("user", "") ?: "",
        prefs.getString("pass", "") ?: "",
        prefs.getString("domain", "") ?: ""
    )

    private fun showSmbBrowser() {
        val c = creds()
        if (c[0].isBlank()) {
            toast("Önce kullanıcı adı ve şifrenizi girin")
            showSettings()
            return
        }
        val host = (prefs.getString("host", "") ?: "").ifBlank { DEFAULT_HOST }
        var cur = prefs.getString("last_dir", null) ?: "smb://$host/"

        val names = ArrayList<String>()
        val entries = ArrayList<SmbTools.Entry?>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        val lv = ListView(this)
        lv.adapter = adapter
        val dlg = AlertDialog.Builder(this)
            .setTitle("Sunucu")
            .setView(lv)
            .setNegativeButton("Kapat", null)
            .create()

        fun load(url: String) {
            dlg.setTitle("Yükleniyor...")
            Thread {
                try {
                    val ctx = SmbTools.openContext(c[0], c[1], c[2])
                    val list: List<SmbTools.Entry>
                    try {
                        list = SmbTools.list(url, ctx)
                    } finally {
                        try { ctx.close() } catch (e: Throwable) { }
                    }
                    ui.post {
                        cur = url
                        prefs.edit().putString("last_dir", url).apply()
                        names.clear()
                        entries.clear()
                        if (SmbTools.parentUrl(url) != null) {
                            names.add("⬆  Üst klasör")
                            entries.add(null)
                        }
                        for (e in list) {
                            names.add((if (e.isDir) "📁  " else "📄  ") + e.name)
                            entries.add(e)
                        }
                        adapter.notifyDataSetChanged()
                        dlg.setTitle(url.removePrefix("smb://"))
                    }
                } catch (e: Throwable) {
                    prefs.edit().remove("last_dir").apply()
                    ui.post {
                        dlg.setTitle("Sunucu")
                        showError(SmbTools.explain(e))
                    }
                }
            }.start()
        }

        lv.setOnItemClickListener { _, _, pos, _ ->
            val e = entries.getOrNull(pos)
            if (e == null) {
                val parent = SmbTools.parentUrl(cur)
                if (parent != null) load(parent)
            } else if (e.isDir) {
                load(SmbTools.ensureSlash(cur) + e.name + "/")
            } else {
                dlg.dismiss()
                importFromSmb(SmbTools.ensureSlash(cur) + e.name, e.name)
            }
        }
        dlg.show()
        load(cur)
    }

    /** Sunucudaki Excel dosyasını uygulamanın kendi alanına indirir; [zaman, boyut] döndürür. */
    private fun downloadSmbToSaved(url: String): LongArray {
        val c = creds()
        val ctx = SmbTools.openContext(c[0], c[1], c[2])
        try {
            val tmp = File(filesDir, "incoming.xlsx")
            val st = SmbTools.downloadToRetry(url, ctx, tmp, AtomicBoolean(false))
            savedXlsx.delete()
            if (!tmp.renameTo(savedXlsx)) {
                tmp.copyTo(savedXlsx, overwrite = true)
                tmp.delete()
            }
            return st
        } finally {
            try {
                ctx.close()
            } catch (e: Throwable) {
                // kapatma hatası önemsiz
            }
        }
    }

    private fun importFromSmb(url: String, name: String) {
        titleView.text = name
        subView.text = "Sunucudan indiriliyor..."
        Thread {
            try {
                val st = downloadSmbToSaved(url)
                prefs.edit()
                    .putString("label", name).putInt("sheet", 0)
                    .putString("src_kind", "smb").putString("src_smb", url).putString("src_uri", "")
                    .putLong("src_m", st[0]).putLong("src_s", st[1])
                    .apply()
                ui.post {
                    srcKind = "smb"
                    srcSmb = url
                    srcUri = ""
                    baseM = st[0]
                    baseS = st[1]
                    hideBanner()
                    loadParsed(0)
                    startWatching()
                }
            } catch (e: Throwable) {
                ui.post {
                    subView.text = "Dosya indirilemedi"
                    showError(SmbTools.explain(e))
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ değişiklik izleme

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!watching) return
            pollOnce()
            ui.postDelayed(this, POLL_MS)
        }
    }

    private fun canWatch(): Boolean = srcKind == "smb" || (srcKind == "uri" && baseM > 0L)

    private fun startWatching() {
        if (watching || !canWatch()) return
        watching = true
        ui.post(pollRunnable)
    }

    private fun stopWatching() {
        watching = false
        ui.removeCallbacks(pollRunnable)
        io.execute { session.close() }
    }

    private fun pollOnce() {
        if (bannerShown || refreshing) return
        if (!busy.compareAndSet(false, true)) return
        val kind = srcKind
        val smb = srcSmb
        val uriStr = srcUri
        val c = creds()
        io.execute {
            try {
                val cur: LongArray? = when (kind) {
                    "smb" -> session.stat(smb, c[0], c[1], c[2])
                    "uri" -> statUri(Uri.parse(uriStr))
                    else -> null
                }
                if (cur != null && (cur[0] != baseM || cur[1] != baseS)) {
                    ui.post { showBanner() }
                }
            } catch (e: Throwable) {
                // ağ geçici olarak yok ya da dosya kaydedilirken kısa süre erişilemiyor: sessizce tekrar dene
                session.close()
            } finally {
                busy.set(false)
            }
        }
    }

    private fun showBanner() {
        if (bannerShown) return
        bannerShown = true
        banner.visibility = View.VISIBLE
    }

    private fun hideBanner() {
        bannerShown = false
        banner.visibility = View.GONE
    }

    private fun setRefreshing(on: Boolean) {
        refreshing = on
        refreshBtn.text = if (on) "Yenileniyor..." else "Yenile"
        refreshBtn.alpha = if (on) 0.6f else 1f
    }

    /** Aynı dosyayı yeniden açar; sayfa, kaydırma konumu ve arama korunur. */
    private fun refreshSource() {
        if (refreshing) return
        setRefreshing(true)
        val kind = srcKind
        val smb = srcSmb
        val uriStr = srcUri
        Thread {
            try {
                val st: LongArray = when (kind) {
                    "smb" -> downloadSmbToSaved(smb)
                    "uri" -> {
                        val u = Uri.parse(uriStr)
                        val s0 = statUri(u)
                        copyUriToSaved(u)
                        s0 ?: longArrayOf(baseM, baseS)
                    }
                    else -> throw IllegalStateException("Yenilenecek dosya yok.")
                }
                prefs.edit().putLong("src_m", st[0]).putLong("src_s", st[1]).apply()
                ui.post {
                    baseM = st[0]
                    baseS = st[1]
                    setRefreshing(false)
                    hideBanner()
                    loadParsed(sheetIndex, true)
                }
            } catch (e: Throwable) {
                ui.post {
                    setRefreshing(false)
                    val msg = if (kind == "smb") SmbTools.explain(e)
                    else "Dosya yenilenemedi: ${e.message}\n\nDosyaya yeniden erişilemiyorsa Aç ile tekrar seçin."
                    showError(msg)
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ Excel okuma

    private fun loadParsed(wantedSheet: Int, keepPosition: Boolean = false) {
        subView.text = "Excel okunuyor, lütfen bekleyin..."
        Thread {
            try {
                val file = savedXlsx
                val s = XlsxReader.listSheets(file)
                val idx = wantedSheet.coerceIn(0, s.size - 1)
                val sd = XlsxReader.readSheet(file, s[idx])
                ui.post {
                    val samePos = keepPosition && idx == sheetIndex
                    sheets = s
                    sheetIndex = idx
                    current = sd
                    prefs.edit().putInt("sheet", idx).apply()
                    titleView.text = prefs.getString("label", "Excel") ?: "Excel"
                    grid.setData(sd, samePos)
                    if (!samePos) cellInfo.text = ""
                    applyFilter(samePos)
                    refreshFilterUi()
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

    private fun applyFilter(keepScroll: Boolean) {
        val q = search.text.toString().trim().lowercase(TextTools.TR)
        val words = q.split(' ').filter { it.isNotEmpty() }
        grid.setFilter(words, !keepScroll)
        updateStatus()
    }

    /** Alt başlık: sayfa adı, gösterilen / toplam kayıt ve etkin filtre sayısı. */
    private fun updateStatus() {
        val sd = current ?: return
        val sheetName = sheets.getOrNull(sheetIndex)?.name ?: ""
        val fc = grid.filterCounts()
        val active = grid.activeFilterCount()
        val counts = if (fc != null) {
            "${fc[0]} / ${fc[1]} kayıt"
        } else {
            "${grid.shownRowCount()} / ${sd.lastRow - sd.frozenRows} satır"
        }
        subView.text = "$sheetName  ·  $counts" + if (active > 0) "  ·  $active filtre" else ""
    }

    // ------------------------------------------------------------------ filtre

    private fun refreshFilterUi() {
        val active = grid.activeFilterCount()
        if (current == null) {
            filterBtn.visibility = View.GONE
        } else {
            if (active > 0) {
                filterBtn.text = "Filtreyi temizle"
                filterBtn.visibility = View.VISIBLE
            } else if (!grid.hasFilter()) {
                filterBtn.text = "Filtre ekle"
                filterBtn.visibility = View.VISIBLE
            } else {
                filterBtn.visibility = View.GONE
            }
        }
        updateStatus()
    }

    private fun onFilterButton() {
        if (grid.activeFilterCount() > 0) {
            grid.clearAllFilters()
        } else if (!grid.hasFilter()) {
            if (grid.enableInferredFilter()) {
                toastLong("Başlık satırındaki oklara dokunarak filtreleyin")
            } else {
                toast("Bu sayfada filtre uygulanacak başlık satırı bulunamadı")
            }
        }
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

        val candidates = LinkTools.candidateUrls(target, host)
        if (candidates.isEmpty()) {
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
            smbCache.mkdirs()
            SmbTools.cleanOld(smbCache)
            // Her URL adayı ve hatası: hata ayıklama için sakla
            val errors = mutableListOf<Pair<String, String>>()
            for (url in candidates) {
                if (cancel.get()) { ui.post { dlg.dismiss() }; return@Thread }
                try {
                    val f = SmbTools.download(url, user, pass, domain, smbCache, cancel)
                    ui.post { dlg.dismiss(); viewFile(f) }
                    return@Thread
                } catch (c: SmbTools.Cancelled) {
                    ui.post { dlg.dismiss() }
                    return@Thread
                } catch (e: Throwable) {
                    // Ham teknik hata mesajını sakla (Türkçe'ye çevirmeden)
                    val raw = buildString {
                        var t: Throwable? = e
                        var d = 0
                        while (t != null && d < 4) {
                            append(t.javaClass.simpleName)
                            if (!t.message.isNullOrBlank()) append(": ").append(t.message)
                            t = t.cause; d++
                            if (t != null) append(" → ")
                        }
                    }.take(200)
                    errors.add(url.removePrefix("smb://") to raw)
                }
            }
            ui.post {
                dlg.dismiss()
                val detail = errors.joinToString("\n\n") { (u, e) -> "• $u\n  $e" }
                showError("Dosya hiçbir yolda açılamadı.\n\n$detail")
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
            .setNeutralButton("Hakkında") { _, _ -> showAbout() }
            .show()
    }

    private fun showAbout() {
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Throwable) {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle("Köprü Açıcı $ver")
            .setMessage("Üçler Makina\n\nYayıncı · Yapımcı · Geliştirici\nVietra Teknoloji")
            .setPositiveButton("Tamam", null)
            .show()
    }

    // ------------------------------------------------------------------ xlsx düzenleme

    /** Renk seçici grid (4 sütun, renk listesi) inşa eder; seçilen renk [onPick]'e iletilir. */
    private fun showColorPicker(initialColor: Int, onPick: (Int) -> Unit) {
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        var hue = hsv[0]
        var saturation = hsv[1]
        var value = hsv[2]
        var picked = initialColor

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(8))
        }
        val preview = View(this).apply { background = rounded(picked, 8) }
        content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))

        val palette = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                color = Color.WHITE
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (width <= 0 || height <= 0) return
                val pureHue = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
                paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, pureHue, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0x00000000, Color.BLACK, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
                val cx = saturation * width
                val cy = (1f - value) * height
                canvas.drawCircle(cx, cy, dp(7).toFloat(), marker)
            }
            override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                        saturation = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        value = (1f - event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        picked = Color.HSVToColor(floatArrayOf(hue, saturation, value))
                        preview.background = rounded(picked, 8)
                        invalidate()
                        return true
                    }
                }
                return true
            }
        }
        content.addView(palette, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply {
            topMargin = dp(12)
        })

        val hueLabel = TextView(this).apply {
            text = "Renk tonu"
            setTextColor(Color.BLACK)
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        }
        content.addView(hueLabel)
        val hueBar = SeekBar(this).apply {
            max = 360
            progress = hue.toInt()
        }
        hueBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                hue = progress.toFloat()
                picked = Color.HSVToColor(floatArrayOf(hue, saturation, value))
                preview.background = rounded(picked, 8)
                palette.invalidate()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        content.addView(hueBar)
        palette.invalidate()

        AlertDialog.Builder(this)
            .setTitle("Renk paleti")
            .setView(content)
            .setPositiveButton("Seç") { _, _ -> onPick(picked) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    /** Renk kutuları: 12 sütun, seçili renkte tik; özel renk RGB paletiyle seçilir. */
    private fun buildColorGrid(
        colors: List<Int?>,
        initialColor: Int?,
        btnSize: Int = dp(36),
        onPick: (Int?) -> Unit
    ): Pair<LinearLayout, () -> Unit> {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val btnRefs = mutableListOf<Pair<Int?, TextView>>()
        var selectedColor = initialColor
        var row: LinearLayout? = null
        var colInRow = 0
        val cols = 12

        fun ensureRow() {
            if (colInRow == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                wrapper.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        fun markSelected(color: Int?) {
            selectedColor = color
            for ((c, button) in btnRefs) {
                val selected = c == color
                button.text = if (selected) "✓" else if (c == null) "✕" else ""
                button.setTextColor(if (c == null || c == 0xFFFFFFFF.toInt() || c == 0xFFFFFF00.toInt() || c == 0xFFFFD700.toInt()) Color.BLACK else Color.WHITE)
            }
        }

        fun addButton(color: Int?, custom: Boolean = false) {
            ensureRow()
            val btn = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 15f
                background = if (custom) rounded(0xFFEEEEEE.toInt(), 6)
                    else rounded(color ?: 0xFFEEEEEE.toInt(), 6)
                if (color == null && !custom) {
                    text = "✕"
                    setTextColor(Color.DKGRAY)
                }
            }
            val lp = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                rightMargin = dp(3)
                bottomMargin = dp(4)
            }
            row!!.addView(btn, lp)
            if (!custom) btnRefs.add(color to btn)
            colInRow = (colInRow + 1) % cols
            return
        }

        colors.forEach { color ->
            addButton(color)
            val btn = row!!.getChildAt(row!!.childCount - 1) as TextView
            btn.setOnClickListener {
                onPick(color)
                markSelected(color)
            }
        }

        // Son kutucuk: özel renk paleti.
        ensureRow()
        val customBtn = TextView(this).apply {
            text = "+"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            background = rounded(0xFFEEEEEE.toInt(), 6)
        }
        val customLp = LinearLayout.LayoutParams(btnSize, btnSize).apply {
            rightMargin = dp(3)
            bottomMargin = dp(4)
        }
        row!!.addView(customBtn, customLp)
        customBtn.setOnClickListener {
            showColorPicker(selectedColor ?: Color.BLACK) { picked ->
                if (btnRefs.none { it.first == picked }) {
                    val targetRow = if (colInRow == 0) {
                        LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            wrapper.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        }
                    } else row!!
                    val swatch = TextView(this).apply {
                        gravity = Gravity.CENTER
                        textSize = 15f
                        background = rounded(picked, 6)
                    }
                    val lp = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                        rightMargin = dp(3)
                        bottomMargin = dp(4)
                    }
                    targetRow.addView(swatch, lp)
                    swatch.setOnClickListener {
                        onPick(picked)
                        markSelected(picked)
                    }
                    btnRefs.add(picked to swatch)
                    if (colInRow == 0) colInRow = 1 else colInRow = (colInRow + 1) % cols
                }
                onPick(picked)
                markSelected(picked)
            }
        }

        markSelected(initialColor)
        return wrapper to { markSelected(initialColor) }
    }

    private fun showCellEditDialog(row1: Int, col0: Int, currentText: String, cell: Cell?) {
        if (current == null) return

        // Mevcut hücre stilini oku (varsayılan değerler için)
        val d = current!!
        val cellStyle = cell?.style?.let { d.styles.getOrNull(it) }
        val initBg    = cellStyle?.fillColor?.takeIf { it != 0 }
        val initFg    = cellStyle?.fontColor?.takeIf { it != 0 && it != Color.BLACK }
        val initAlign = cellStyle?.hAlign ?: 0

        // Seçili değerler (başlangıçta mevcut değer)
        var selectedBgColor: Int?   = initBg
        var selectedFontColor: Int? = initFg
        var selectedAlign: Int      = initAlign

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(14), dp(20), dp(4))

        // ── Metin ──────────────────────────────────────────────────────────────
        val etLabel = TextView(this)
        etLabel.text = "Hücre metni"
        etLabel.setTextColor(Color.BLACK)
        etLabel.textSize = 13f
        etLabel.setTypeface(etLabel.typeface, android.graphics.Typeface.BOLD)
        col.addView(etLabel)

        val et = EditText(this)
        et.setText(currentText)
        et.setSingleLine(false)
        et.minLines = 2
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val etlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        etlp.topMargin = dp(4)
        col.addView(et, etlp)

        // ── Bölüm başlığı yardımcısı ───────────────────────────────────────────
        fun addSectionLabel(text: String) {
            val tv = TextView(this)
            tv.text = text
            tv.setTextColor(Color.BLACK)
            tv.textSize = 13f
            tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(14)
            col.addView(tv, lp)
        }

        // ── Hizalama ───────────────────────────────────────────────────────────
        addSectionLabel("Hizalama")

        val alignRow = LinearLayout(this)
        alignRow.orientation = LinearLayout.HORIZONTAL
        val alignItems = listOf(0 to "Genel", 1 to "◀ Sol", 2 to "■ Orta", 3 to "Sağ ▶")
        val alignBtns = mutableListOf<TextView>()

        fun refreshAlignBtns() {
            alignBtns.forEachIndexed { i, b ->
                val isSelected = alignItems[i].first == selectedAlign
                b.background = rounded(if (isSelected) 0xFF217346.toInt() else 0xFFDDDDDD.toInt(), 8)
                b.setTextColor(if (isSelected) Color.WHITE else Color.BLACK)
            }
        }

        for ((value, label) in alignItems) {
            val btn = TextView(this)
            btn.text = label
            btn.textSize = 13f
            btn.gravity = Gravity.CENTER
            btn.setPadding(dp(10), dp(7), dp(10), dp(7))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(6)
            val v = value
            btn.setOnClickListener {
                selectedAlign = v
                refreshAlignBtns()
            }
            alignBtns.add(btn)
            alignRow.addView(btn, lp)
        }
        // İlk boyamayı uygula
        refreshAlignBtns()
        col.addView(alignRow)

        // ── Kenarlıklar ─────────────────────────────────────────────────────────
        val selectedBorderSides = linkedSetOf<String>()
        addSectionLabel("Hücre kenarlığı")
        val borderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val borderOptions = listOf(
            "Sol" to "left", "Sağ" to "right", "Üst" to "top",
            "Alt" to "bottom", "Her yeri" to "all"
        )
        val borderButtons = mutableListOf<Pair<String, TextView>>()
        fun refreshBorderButtons() {
            borderButtons.forEach { (side, button) ->
                val active = if (side == "all") selectedBorderSides.size == 4 else side in selectedBorderSides
                button.background = rounded(if (active) 0xFF217346.toInt() else 0xFFDDDDDD.toInt(), 7)
                button.setTextColor(if (active) Color.WHITE else Color.BLACK)
            }
        }
        borderOptions.forEach { (label, side) ->
            val button = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                setPadding(dp(9), dp(8), dp(9), dp(8))
            }
            button.setOnClickListener {
                if (side == "all") {
                    if (selectedBorderSides.size == 4) selectedBorderSides.clear()
                    else selectedBorderSides.addAll(listOf("left", "right", "top", "bottom"))
                } else {
                    if (!selectedBorderSides.add(side)) selectedBorderSides.remove(side)
                }
                refreshBorderButtons()
            }
            borderButtons.add(side to button)
            borderRow.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(5)
            })
        }
        refreshBorderButtons()
        col.addView(borderRow)

        // ── Arka plan rengi ────────────────────────────────────────────────────
        val bgColors: List<Int?> = listOf(
            null,
            0xFFFFFFFF.toInt(), 0xFFF2F2F2.toInt(), 0xFFD9D9D9.toInt(), 0xFF595959.toInt(),
            0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFFFF6347.toInt(), 0xFFFF8C00.toInt(),
            0xFFFFD700.toInt(), 0xFFFFFF00.toInt(), 0xFF90EE90.toInt(), 0xFF008000.toInt(),
            0xFF00CED1.toInt(), 0xFFADD8E6.toInt(), 0xFF0000FF.toInt(), 0xFF000080.toInt(),
            0xFFDA70D6.toInt(), 0xFF800080.toInt(), 0xFFFFB6C1.toInt(), 0xFFA52A2A.toInt()
        )
        addSectionLabel("Arka plan rengi")
        val (bgGrid, _) = buildColorGrid(bgColors, selectedBgColor) { c ->
            selectedBgColor = c
        }
        col.addView(bgGrid)

        // ── Yazı rengi ─────────────────────────────────────────────────────────
        val fontColors: List<Int?> = bgColors
        addSectionLabel("Yazı rengi")
        val (fgGrid, _) = buildColorGrid(fontColors, selectedFontColor) { c ->
            selectedFontColor = c
        }
        col.addView(fgGrid)

        val sv = ScrollView(this)
        sv.addView(col)

        AlertDialog.Builder(this)
            .setTitle("Hücreyi düzenle")
            .setView(sv)
            .setPositiveButton("Kaydet") { _, _ ->
                val newText = et.text.toString()
                val edit = XlsxWriter.CellEdit(
                    text = newText,
                    bgColor = selectedBgColor,
                    fontColor = selectedFontColor,
                    hAlign = if (selectedAlign != 0) selectedAlign else null,
                    borderSides = selectedBorderSides.toSet().takeIf { it.isNotEmpty() }
                )
                val addr = XlsxWriter.CellAddr(row1 - 1, col0)
                pendingEdits[addr] = edit
                unsaved = true
                grid.updateCell(row1, col0, newText, selectedBgColor, selectedFontColor, selectedBorderSides)
                saveEditsToSmb()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun saveEditsToSmb() {
        if (pendingEdits.isEmpty()) return
        if (srcKind != "smb") {
            // SMB değilse sadece yerel olarak sakla (kullanıcıya bildir)
            toastLong("Yerel dosya düzenlendi. SMB kaynağından açılmadığı için sunucuya kaydedilemez.")
            return
        }
        val url = srcSmb
        if (url.isBlank()) return

        val editsCopy = LinkedHashMap(pendingEdits)
        val c = creds()

        Thread {
            try {
                val src = savedXlsx
                if (!src.exists()) throw IllegalStateException("Kaynak dosya bulunamadı: ${src.name}")
                val tmp = File(filesDir, "edit_tmp.xlsx")
                tmp.delete()

                // 1) Değişiklikleri yeni bir geçici dosyaya uygula
                XlsxWriter.apply(src, tmp, sheetIndex, editsCopy)

                // 2) SMB'ye yükle
                val ctx = SmbTools.openContext(c[0], c[1], c[2])
                try {
                    val smbFile = jcifs.smb.SmbFile(url, ctx)
                    smbFile.getOutputStream().use { os ->
                        tmp.inputStream().use { ins -> ins.copyTo(os) }
                    }
                    // 3) SMB yüklemesi başarılı — şimdi yerel kopyayı güncelle
                    //    copyTo her zaman çalışır; rename farklı fs'de başarısız olabilir
                    tmp.copyTo(src, overwrite = true)
                    tmp.delete()

                    // Stat güncelle (dosya değişti, poller kendi dosyamızı tekrar indirmesin)
                    val st = SmbTools.stat(url, ctx)
                    prefs.edit().putLong("src_m", st[0]).putLong("src_s", st[1]).apply()
                    // Long.MAX_VALUE: poller bu değeri asla eşleştirmez → banner çıkmaz
                    baseM = Long.MAX_VALUE; baseS = Long.MAX_VALUE
                    val newM = st[0]; val newS = st[1]
                    val snapIdx = sheetIndex
                    ui.post {
                        // Stat'ı gerçek değere set et; poller artık yeni değeri bilir
                        baseM = newM; baseS = newS
                        pendingEdits.clear()
                        unsaved = false
                        hideBanner()
                        // Arka planda yenile — kullanıcıya bildir, yenile butonuna gerek yok
                        loadParsed(snapIdx, keepPosition = true)
                        toast("Kaydedildi, yenileniyor...")
                    }
                } finally {
                    try { ctx.close() } catch (e: Throwable) {}
                }
            } catch (e: Throwable) {
                ui.post {
                    showError("Sunucuya kaydedilemedi:\n${SmbTools.explain(e)}")
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ küçük yardımcılar

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun toastLong(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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
