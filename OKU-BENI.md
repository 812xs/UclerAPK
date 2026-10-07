# Köprü Açıcı – Kurulum (bilgisayara hiçbir program kurmadan)

Bu uygulama Excel (.xlsx) dosyanızı açar, `\\192.168.1.3\OrtakAlan\...\dosya.pdf`
biçimindeki köprülere dokunduğunuzda dosyayı ağdan indirip tabletteki PDF/dosya
görüntüleyicide açar. Excel'deki köprü yazıları DEĞİŞTİRİLMEZ.

## A) APK'yı GitHub ile derletin (ücretsiz, ~5 dakika)

1. https://github.com adresinde ücretsiz hesap açın (varsa giriş yapın).
2. Sağ üstte **+** > **New repository**. Ad: `KopruAcici`. **Private** seçebilirsiniz.
   **Create repository**'ye basın.
3. Açılan sayfada **uploading an existing file** bağlantısına tıklayın.
4. Bu zip'i bilgisayarda açın. İçindeki **her şeyi** (app klasörü, .github klasörü,
   build.gradle.kts, settings.gradle.kts, gradle.properties dahil) sayfaya sürükleyin.
   Alttan **Commit changes** deyin.
   - `.github` klasörü görünmüyorsa/yüklenmediyse: **Add file > Create new file**,
     dosya adına `.github/workflows/build.yml` yazın, `build.yml.kopya.txt` dosyasının
     içeriğini yapıştırıp kaydedin.
5. Üstteki **Actions** sekmesine girin. **APK Derle** işi otomatik başlar (yoksa
   soldan **APK Derle** > **Run workflow**). Yeşil tik çıkana kadar bekleyin (3-6 dk).
6. Biten işe tıklayın, sayfanın altındaki **Artifacts** bölümünden
   **KopruAcici-APK**'yı indirin. Zip'in içinde `app-debug.apk` vardır.
7. APK'yı tablete aktarın (USB, e-posta, Drive vb.), dokunup kurun.
   Tablet "bilinmeyen kaynaklardan yükleme" izni isterse verin.

Derleme kırmızı hata verirse: işe tıklayın, hata satırlarını kopyalayıp bana gönderin.

## B) Kullanım

1. Uygulamayı açın > **Ayarlar** > kullanıcı adı ve şifrenizi yazın > **Kaydet**.
   (Sunucu IP alanını boş bırakın; adres köprüden alınır.)
2. **Excel Aç** > .xlsx dosyanızı seçin. Dosya uygulamaya kopyalanır; sonraki açılışlarda
   otomatik yüklenir. Excel'i güncellerseniz yeniden **Excel Aç** ile seçin.
3. Köprüsü olan satırlar mavi ve 🔗 işaretlidir. Satıra dokunun: dosya indirilir ve
   PDF görüntüleyicide açılır. Üstteki kutudan arama yapabilirsiniz.
4. Birden fazla sayfa varsa **Sayfa** düğmesiyle değiştirin.

## Notlar

- Yalnızca `.xlsx` / `.xlsm` desteklenir. Eski `.xls` ise Excel'de "Farklı Kaydet" ile .xlsx yapın.
- Tablet ortak ağa (192.168.1.x) bağlı olmalı.
- Şifre yalnızca bu uygulamanın kendi özel alanında saklanır.
- İndirilen dosyalar geçici olarak tutulur, 1 saatten eskileri otomatik silinir.
