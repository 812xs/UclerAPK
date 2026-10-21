package com.ortakalan.kopru

import android.net.Uri
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbFile
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.FileOutputStream
import java.security.Security
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

object LinkTools {

    fun isWeb(t: String): Boolean =
        t.startsWith("http://", true) || t.startsWith("https://", true)

    /**
     * Yoldaki non-ASCII ve özel karakterleri SMB URL için encode eder.
     * Slash ve zaten-encoded karakterlere dokunmaz.
     */
    private fun encodePath(path: String): String {
        // Her segment'i ayrı ayrı encode et, '/' korunur
        return path.split('/').joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
    }

    /**
     * Excel'deki köprü adresini SMB adresine çevirir. Excel dosyasındaki yazı değişmez,
     * sadece uygulama açarken dönüştürür.
     *
     *   \\192.168.1.3\OrtakAlan\2024\a.pdf   ->  smb://192.168.1.3/OrtakAlan/2024/a.pdf
     *   file:///\\192.168.1.3\OrtakAlan\a.pdf ->  smb://192.168.1.3/OrtakAlan/a.pdf
     *   smb://192.168.1.3/OrtakAlan/a.pdf    ->  aynen
     *
     * @param encode true ise yoldaki Türkçe/özel karakterleri URL-encode eder
     */
    fun toSmbUrl(target: String, hostOverride: String?, encode: Boolean = false): String? {
        var t = try { Uri.decode(target.trim()) } catch (e: Throwable) { target.trim() }
        if (t.startsWith("file:", true)) t = t.substring(5)
        else if (t.startsWith("smb:", true)) t = t.substring(4)
        t = t.replace('\\', '/').trimStart('/')

        val parts = t.split('/').filter { it.isNotEmpty() }.dropWhile { it == ".." || it == "." }
        if (parts.size < 2) return null
        if (parts[0].length == 2 && parts[0][1] == ':') return null // C:\... gibi yerel yol

        val host = if (!hostOverride.isNullOrBlank()) hostOverride.trim() else parts[0]
        val restParts = if (!hostOverride.isNullOrBlank()) parts else parts.drop(1)
        val rest = if (encode) {
            restParts.joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        } else {
            restParts.joinToString("/").replace("#", "%23")
        }
        return "smb://$host/$rest"
    }

    /**
     * Köprü için denenecek tüm URL adaylarını üretir (encode edilmiş/edilmemiş varyantlar dahil).
     * Sıra: tam yol ham → tam yol encoded → göreli yolla önek kombinasyonları (ham + encoded)
     */
    fun candidateUrls(target: String, host: String): List<String> {
        val result = LinkedHashSet<String>()

        // 1. Tam yol, ham (orijinal)
        toSmbUrl(target, host, encode = false)?.let { result.add(it) }
        // 2. Tam yol, URL-encoded (Türkçe karakter sorunu için)
        toSmbUrl(target, host, encode = true)?.let { result.add(it) }

        // Göreli yol kısmını çıkar (host'u soy)
        var t = try { Uri.decode(target.trim()) } catch (e: Throwable) { target.trim() }
        if (t.startsWith("file:", true)) t = t.substring(5)
        t = t.replace('\\', '/').trimStart('/')
        val parts = t.split('/').filter { it.isNotEmpty() }
        // İlk parça: IP/hostname ise host say, at. Sadece harf/rakam/tire/nokta olan
        // ve rakam veya nokta içeren (IP benzeri) ya da ayarlardaki host ile eşleşen her şey host.
        // MAILLEFER gibi NetBIOS adları da host olabilir — bunları da at.
        val firstIsHost = parts.isNotEmpty() && run {
            val p0 = parts[0]
            p0.equals(host, ignoreCase = true) ||          // ayarlardaki host
            p0.contains('.') ||                             // IP veya FQDN
            p0.all { c -> c.isLetterOrDigit() || c == '-' } // NetBIOS adı (MAILLEFER gibi)
        }

        // Göreli kısım: host'tan sonraki parçalar
        val relParts = if (firstIsHost) parts.drop(1) else parts

        // Tam yol zaten OrtakAlan/ÜPK ile başlıyorsa önek ekleme
        val alreadyHasPrefix = relParts.size >= 2 &&
            relParts[0].equals("OrtakAlan", ignoreCase = true) &&
            relParts[1].equals("ÜPK", ignoreCase = true)

        if (!alreadyHasPrefix && relParts.isNotEmpty()) {
            // 3-6: OrtakAlan/ÜPK öneği + göreli yol (ham ve encoded)
            val relRaw = relParts.joinToString("/").replace("#", "%23")
            val relEnc = relParts.joinToString("/") {
                java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
            val upkRaw  = "ÜPK"
            val upkEnc  = "%C3%9CPK"
            for (upk in listOf(upkRaw, upkEnc)) {
                for (rel in listOf(relRaw, relEnc)) {
                    result.add("smb://$host/OrtakAlan/$upk/$rel")
                }
            }
            // 7-8: Sadece OrtakAlan öneği
            for (rel in listOf(relRaw, relEnc)) {
                result.add("smb://$host/OrtakAlan/$rel")
            }
        }

        return result.toList()
    }

    /** Hata ayıklama için URL listesini okunabilir biçimde döndürür. */
    fun candidatesSummary(urls: List<String>): String =
        urls.mapIndexed { i, u -> "${i + 1}. ${u.removePrefix("smb://")}" }.joinToString("\n")
}

object SmbTools {

    class Cancelled : RuntimeException()

    init {
        // Android'in kendi "BC" sağlayıcısı jcifs-ng için eksik (MD4 vb.). Tam sürümü öne alıyoruz.
        try {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (e: Throwable) {
            // sağlayıcı değiştirilemezse yine de denemeye devam et
        }
    }

    private fun newContext(user: String, pass: String, domain: String): CIFSContext {
        val p = Properties()
        p.setProperty("jcifs.smb.client.dfs.disabled", "true")
        p.setProperty("jcifs.smb.client.connTimeout", "10000")
        p.setProperty("jcifs.smb.client.responseTimeout", "20000")
        p.setProperty("jcifs.smb.client.soTimeout", "30000")
        val base = BaseContext(PropertyConfiguration(p))
        val dom: String? = if (domain.isBlank()) null else domain.trim()
        return base.withCredentials(NtlmPasswordAuthenticator(dom, user.trim(), pass))
    }

    /** Dosyayı SMB sunucusundan outDir altına indirir ve yerel File döndürür. */
    fun download(
        url: String,
        user: String,
        pass: String,
        domain: String,
        outDir: File,
        cancel: AtomicBoolean
    ): File {
        val ctx = newContext(user, pass, domain)
        try {
            val attempts = LinkedHashSet<String>()
            attempts.add(url)
            attempts.add(url.replace(" ", "%20"))
            var last: Exception? = null
            for (u in attempts) {
                try {
                    return fetch(u, ctx, outDir, cancel)
                } catch (e: Cancelled) {
                    throw e
                } catch (e: SmbAuthException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                }
            }
            throw last ?: IllegalStateException("Bilinmeyen hata")
        } finally {
            try {
                ctx.close()
            } catch (e: Throwable) {
                // kapatma hatası önemsiz
            }
        }
    }

    private fun fetch(u: String, ctx: CIFSContext, outDir: File, cancel: AtomicBoolean): File {
        // URL'nin sonundaki slash'ı kaldır — jcifs-ng slash olan adresi klasör sayar
        val cleanUrl = u.trimEnd('/')
        val f = SmbFile(cleanUrl, ctx)
        // isDirectory kontrolü yapmıyoruz: bazı sunucular nokta içeren klasör adlarında
        // (ör. SR308743.5) yanlış true döndürüyor. getInputStream() zaten hata verir.

        val rawName = f.getName().trimEnd('/')
        val name = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "dosya" }
        val dirKey = java.lang.Long.toHexString(u.hashCode().toLong() and 0xffffffffL)
        val dir = File(outDir, dirKey)
        dir.mkdirs()
        val out = File(dir, name)

        f.getInputStream().use { ins ->
            FileOutputStream(out).use { os ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancel.get()) throw Cancelled()
                    val n = ins.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ sunucu gezinme / izleme

    class Entry(val name: String, val isDir: Boolean)

    fun openContext(user: String, pass: String, domain: String): CIFSContext = newContext(user, pass, domain)

    fun ensureSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

    /** "smb://sunucu/a/b/" -> "smb://sunucu/a/"; kök adreste null. */
    fun parentUrl(url: String): String? {
        val body = url.trimEnd('/').removePrefix("smb://")
        val i = body.lastIndexOf('/')
        if (i < 0) return null
        return "smb://" + body.substring(0, i + 1)
    }

    private fun <T> withUrls(url: String, block: (String) -> T): T {
        var last: Exception? = null
        for (u in linkedSetOf(url, url.replace(" ", "%20"))) {
            try {
                return block(u)
            } catch (e: Cancelled) {
                throw e
            } catch (e: SmbAuthException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Bilinmeyen hata")
    }

    /** Klasörün içeriği: alt klasörler ve Excel dosyaları (klasörler önce). */
    fun list(url: String, ctx: CIFSContext): List<Entry> = withUrls(ensureSlash(url)) { u ->
        val dir = SmbFile(u, ctx)
        val out = ArrayList<Entry>()
        val arr = dir.listFiles() ?: emptyArray()
        for (c in arr) {
            val raw = c.name
            val isDir = raw.endsWith("/")
            val n = raw.trimEnd('/')
            if (n.isEmpty() || n.endsWith("$")) continue
            if (!isDir) {
                val l = n.lowercase()
                if (!(l.endsWith(".xlsx") || l.endsWith(".xlsm") || l.endsWith(".xltx"))) continue
            }
            out.add(Entry(n, isDir))
        }
        out.sortWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
        out
    }

    /** [son değişiklik zamanı, boyut]. Dosya yoksa/erişilemezse hata fırlatır. */
    fun stat(url: String, ctx: CIFSContext): LongArray = withUrls(url) { u ->
        val f = SmbFile(u, ctx)
        val lm = f.lastModified()
        val len = f.length()
        if (lm == 0L && len == 0L && !f.exists()) throw IllegalStateException("object_name_not_found")
        longArrayOf(lm, len)
    }

    /**
     * Dosyayı tam olarak dest'e indirir ve indirmeden ÖNCE okunan [zaman, boyut] değerini döndürür.
     * Excel kaydederken dosya kısa süre kilitli/eksik olabilir; bu yüzden birkaç kez denenir.
     */
    fun downloadToRetry(url: String, ctx: CIFSContext, dest: File, cancel: AtomicBoolean): LongArray {
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                return withUrls(url) { u ->
                    val f = SmbFile(u, ctx)
                    val st = longArrayOf(f.lastModified(), f.length())
                    f.getInputStream().use { ins ->
                        FileOutputStream(dest).use { os ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancel.get()) throw Cancelled()
                                val n = ins.read(buf)
                                if (n < 0) break
                                os.write(buf, 0, n)
                            }
                        }
                    }
                    st
                }
            } catch (e: Cancelled) {
                throw e
            } catch (e: SmbAuthException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (attempt < 3) Thread.sleep(1500)
            }
        }
        throw last ?: IllegalStateException("Bilinmeyen hata")
    }

    /** Arka plan izleyicinin tek bağlantıyı yeniden kullanması için küçük oturum. Tek iş parçacığından kullanılır. */
    class Session {
        private var ctx: CIFSContext? = null
        private var key = ""

        fun stat(url: String, user: String, pass: String, domain: String): LongArray {
            val k = "$user|$pass|$domain"
            var c = ctx
            if (c == null || key != k) {
                close()
                c = openContext(user, pass, domain)
                ctx = c
                key = k
            }
            return SmbTools.stat(url, c)
        }

        fun close() {
            try {
                ctx?.close()
            } catch (e: Throwable) {
                // kapatma hatası önemsiz
            }
            ctx = null
        }
    }

    /** 1 saatten eski indirilmiş dosyaları siler. */
    fun cleanOld(dir: File) {
        val limit = System.currentTimeMillis() - 60L * 60L * 1000L
        dir.listFiles()?.forEach { child ->
            if (child.lastModified() < limit) child.deleteRecursively()
        }
    }

    /** Kullanıcıya anlaşılır Türkçe hata mesajı üretir. */
    fun explain(e: Throwable): String {
        val sb = StringBuilder()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 6) {
            sb.append(t.javaClass.simpleName).append(' ').append(t.message ?: "").append(' ')
            t = t.cause
            depth++
        }
        val all = sb.toString().lowercase()
        val friendly = when {
            e is SmbAuthException || "logon_failure" in all || "access_denied" in all ||
                "authenticat" in all ->
                "Kullanıcı adı/şifre hatalı ya da bu dosyaya erişim izniniz yok. Ayarlar'dan bilgileri kontrol edin."
            "object_name_not_found" in all || "object_path_not_found" in all ||
                "bad_network_name" in all || "no such file" in all ->
                "Dosya ya da paylaşım bulunamadı. Dosya taşınmış/silinmiş ya da yol yanlış olabilir."
            "unknownhost" in all || "connect" in all || "timed out" in all ||
                "timeout" in all || "unreachable" in all || "refused" in all ->
                "Sunucuya ulaşılamadı. Tabletin ortak ağa bağlı olduğunu ve sunucunun açık olduğunu kontrol edin."
            e is IllegalArgumentException -> e.message ?: "Geçersiz bağlantı."
            else -> "Dosya açılamadı."
        }
        val tech = (e.message ?: e.javaClass.simpleName).take(160)
        return "$friendly\n\n(Teknik ayrıntı: $tech)"
    }
}
