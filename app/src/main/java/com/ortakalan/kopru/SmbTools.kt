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
     * Excel'deki köprü adresini SMB adresine çevirir. Excel dosyasındaki yazı değişmez,
     * sadece uygulama açarken dönüştürür.
     *
     *   \\192.168.1.3\OrtakAlan\2024\a.pdf   ->  smb://192.168.1.3/OrtakAlan/2024/a.pdf
     *   file:///\\192.168.1.3\OrtakAlan\a.pdf ->  smb://192.168.1.3/OrtakAlan/a.pdf
     *   smb://192.168.1.3/OrtakAlan/a.pdf    ->  aynen
     */
    fun toSmbUrl(target: String, hostOverride: String?): String? {
        var t = Uri.decode(target.trim())
        if (t.startsWith("file:", true)) t = t.substring(5)
        else if (t.startsWith("smb:", true)) t = t.substring(4)
        t = t.replace('\\', '/').trimStart('/')

        val parts = t.split('/').filter { it.isNotEmpty() }.dropWhile { it == ".." || it == "." }
        if (parts.size < 3) return null // sunucu / paylaşım / dosya en az 3 parça olmalı
        if (parts[0].length == 2 && parts[0][1] == ':') return null // C:\... gibi yerel yol

        val host = if (!hostOverride.isNullOrBlank()) hostOverride.trim() else parts[0]
        val rest = parts.drop(1).joinToString("/").replace("#", "%23")
        return "smb://$host/$rest"
    }

    /**
     * Köprü yolu eksik veya kırık olduğunda deneyeceğimiz URL listesini üretir.
     * Önce orijinal yol, sonra sunucu kökünden bilinen önek kombinasyonları.
     *
     * Örnek: target = "\dosyalar\2024\a.pdf", host = "192.168.1.3"
     *   1. smb://192.168.1.3/dosyalar/2024/a.pdf  (asıl)
     *   2. smb://192.168.1.3/OrtakAlan/ÜPK/dosyalar/2024/a.pdf
     *   3. smb://192.168.1.3/OrtakAlan/dosyalar/2024/a.pdf
     *   4. smb://192.168.1.3/dosyalar/2024/a.pdf  (zaten 1. ile aynı; tekrar denemez)
     */
    fun candidateUrls(target: String, host: String, user: String, pass: String, domain: String): List<String> {
        val base = toSmbUrl(target, host) ?: return emptyList()
        val result = mutableListOf(base)

        // Köprüde sunucu adı yoksa (sadece göreli yol) önek denemeleri ekle
        var t = Uri.decode(target.trim())
        if (t.startsWith("file:", true)) t = t.substring(5)
        t = t.replace('\\', '/').trimStart('/')
        val parts = t.split('/').filter { it.isNotEmpty() }

        // İlk parça IP/hostname değilse göreli yol demek; önek kombinasyonları dene
        val firstIsHost = parts.isNotEmpty() &&
            (parts[0].contains('.') || parts[0].equals(host, ignoreCase = true))

        val relativePath = if (firstIsHost) parts.drop(1).joinToString("/") else parts.joinToString("/")
        val safeRel = relativePath.replace("#", "%23")

        if (safeRel.isNotEmpty()) {
            val prefixes = listOf(
                "OrtakAlan/%C3%9CPK",   // ÜPK URL-encoded
                "OrtakAlan/ÜPK",
                "OrtakAlan"
            )
            for (pfx in prefixes) {
                val candidate = "smb://$host/$pfx/$safeRel"
                if (candidate !in result) result.add(candidate)
            }
        }
        return result
    }
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
        val f = SmbFile(u, ctx)
        if (f.isDirectory) throw IllegalArgumentException("Bu bağlantı bir klasör, dosya değil.")

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
