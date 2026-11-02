# Build APK Eta di GitHub Actions

Source ini sudah dilengkapi workflow `.github/workflows/eta-debug-apk.yml` untuk membuat APK Debug tanpa release keystore.

## Cara menjalankan

1. Buat repository GitHub (private disarankan), lalu push seluruh isi folder `Eta-3.4.0` ke branch `main` menggunakan Git. Pastikan folder `.github` ikut terunggah.
2. Buka tab **Actions** di repository.
3. Pilih workflow **Eta Debug APK**, lalu tekan **Run workflow**.
4. Setelah job selesai, buka hasil run dan unduh artifact **Eta-debug-apk**. Ekstrak ZIP artifact untuk memperoleh file APK.

Workflow ini memakai JDK 25 dan Android SDK 37 sesuai konfigurasi proyek, lalu menjalankan `:app:assembleDebug`. Tidak memerlukan Release keystore atau GitHub Secrets.

## Catatan

- Ini adalah APK Debug, bukan APK Release yang ditandatangani dengan kunci rilis pribadi.
- Proyek menargetkan perangkat ARM64 dan minimum Android 13 (API 33).
- Jangan memasukkan API key, password, token, atau file keystore ke repository.
