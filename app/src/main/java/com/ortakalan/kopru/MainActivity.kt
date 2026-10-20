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
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
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
    private val ui = Handler(Looper.getMainLooper())

    private var sheets: List<SheetInfo> = emptyList()
    private var sheetIndex = 0
    private var current: SheetData? = null

    // ---- açık dosyanın kaynağı ve izleme durumu
    private var srcKind = "" // "smb" | "uri" | ""
    private var srcSmb = ""
    private var srcUri = ""

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
        grid.onLinkTap = { cell ->
            val target = cell.link
            if (target != null) openLink(cell.text.ifBlank { target }, target)
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
                    val list = try {
                        SmbTools.list(url, ctx)
                    } finally {
                        try {
                            ctx.close()
                        } catch (e: Throwable) {
                            // kapatma hatası önemsiz
                        }
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
