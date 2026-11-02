# Cron Engine — Catatan Penerapan & Perbaikan

Dokumen ini mencatat apa yang dilakukan terhadap source `Eta-3.4.0`
(commit dasar `e90922a4490389f524a10c6acebbcca336883935`) saat menerapkan
`eta-cron-engine-patch` dan perbaikan tambahan yang dilakukan sesudahnya.
Lihat juga `README-ID.md`, `PATCH-REPORT.md`, dan `LANGUAGE-REPORT.md` pada
bundle patch asli untuk detail fitur dan keterbatasan desain yang memang
disengaja (mis. WorkManager bersifat *deferrable*, tidak ada halaman Compose
khusus untuk mengelola jadwal).

## 1. Status penerapan

`tools/apply_cron_patch.py .` dijalankan sekali dari root proyek dan
berhasil tanpa error: seluruh file baru disalin, dan ketujuh titik
integrasi (`EtaDatabase.kt`, `AgentToolCatalog.kt`, `AgentLocalTools.kt`,
`AgentToolRequirements.kt`, `EtaApp.kt`, `app/build.gradle.kts`,
`AndroidManifest.xml`) serta patch bahasa Indonesia-saja berhasil ditempel.

Validasi yang sempat dilakukan di lingkungan ini (tanpa Android SDK/Gradle/
akses jaringan, sama seperti keterbatasan yang sudah disebut di
`PATCH-REPORT.md`):

- Logika `CronExpression.kt` direplikasi ulang di Python dan dicocokkan
  terhadap kelima skenario pada `CronExpressionTest.kt` (termasuk lompatan
  DST dan aturan "Minggu = 0 atau 7") — semua cocok.
- `values-id/strings.xml` (1.187 string + 20 grup plural) diverifikasi:
  XML valid, tidak ada nama resource duplikat, jumlah placeholder format
  (`%s`/`%1$d`, dst.) dan kelompok plural cocok 1:1 dengan `values/strings.xml`.
- Semua titik integrasi (Room entities ↔ migrasi SQL, tool catalog ↔
  dispatcher ↔ tool requirements, konstruktor `AgentRuntimeClient`/
  `AgentRuntimeWire.RunRequest`/`RunResult`) ditelusuri manual dan field-nya
  cocok.
- Build APK penuh (`./gradlew :app:testDebugUnitTest :app:assembleDebug
  :app:lintDebug`) **belum dijalankan** di sini karena tidak tersedia
  Android SDK/Gradle/jaringan. **Wajib dijalankan di mesin Anda** sebelum
  memasang APK.

## 2. Bug yang ditemukan dan diperbaiki

1. **`CronScheduleWorker.kt` — riwayat manual run terpecah saat retry.**
   `runKey` untuk eksekusi manual (`cron_run`) sebelumnya dibuat dengan
   `UUID.randomUUID()` baru di setiap pemanggilan `doWork()`, termasuk saat
   WorkManager melakukan retry otomatis. Akibatnya satu trigger manual yang
   gagal lalu di-retry akan muncul sebagai beberapa baris riwayat terpisah,
   bukan satu baris yang berkembang dari `RUNNING` → `RETRYING` →
   `SUCCESS`/`FAILED` (seperti pada jadwal terjadwal biasa). **Perbaikan:**
   gunakan `this.id` — ID request milik WorkManager sendiri, yang terbukti
   stabil selama percobaan ulang request yang sama — sebagai bagian kunci,
   bukan UUID baru di setiap percobaan.

2. **`CronRescheduleReceiver.kt` — risiko crash saat boot.**
   Pemanggilan `CronScheduleManager.rescheduleAll()` di dalam
   `BroadcastReceiver` tidak dibungkus penanganan error, berbeda dari
   pemanggilan yang sama di `EtaApp.onCreate()` yang sudah memakai
   `runCatching`. Jika terjadi exception saat menangani `BOOT_COMPLETED`,
   proses aplikasi bisa crash. **Perbaikan:** dibungkus `runCatching` dan
   kegagalan dicatat lewat `AndroidAgentLogger.warn(...)`, selaras dengan
   pola yang sudah ada di `EtaApp.kt`.

3. **`CronToolExecutor.kt` → `mutate()` — field `"accepted": true` tidak
   pernah muncul.** Fungsi ini mengecek `result == null` untuk menandai
   operasi berhasil tanpa objek jadwal yang dikembalikan, padahal
   `CronScheduleManager.delete()` dan `.runNow()` mengembalikan `Unit`
   (bukan `null`) di Kotlin, sehingga cabang itu tidak pernah tereksekusi
   untuk tool `cron_delete`/`cron_run`. **Perbaikan:** cek diubah menjadi
   `result !is CronScheduleEntity`, yang benar-benar membedakan operasi
   tanpa hasil (`cron_delete`, `cron_run`) dari operasi yang mengembalikan
   jadwal (`cron_pause`, `cron_resume`).

4. **`CronToolExecutor.kt` → `takeInt()`/`takeLong()` — nilai rusak bisa
   lolos tanpa error.** `optInt()`/`optLong()` bawaan `org.json` diam-diam
   mengembalikan `0` untuk nilai yang ada tapi bukan angka. Untuk
   `max_retries`, `0` adalah nilai sah (0–5), sehingga input rusak dari
   model bisa secara tidak sengaja ditafsirkan sebagai "atur ulang percobaan
   ke 0" tanpa pesan error apa pun. **Perbaikan:** kedua fungsi ditulis
   ulang agar hanya menerima nilai JSON bertipe `Number` atau `String`
   numerik; selain itu mengembalikan `null` (dianggap "tidak diisi") supaya
   validasi `require`/`requireNotNull` di `CronScheduleManager` yang
   menangani kasusnya, bukan fallback diam-diam.

5. **`LanguagePreference.kt` — import tak terpakai.** `import
   android.os.LocaleList` tersisa setelah logika yang memakainya
   (`LocaleList.matchesLanguageAndScript`) dihapus oleh patch bahasa
   Indonesia-saja. **Perbaikan:** import dihapus.

Semua perbaikan di atas bersifat lokal pada file yang sama dan tidak
mengubah skema database, kontrak tool yang terlihat model (`cron_*`), atau
perilaku yang didokumentasikan di `README-ID.md`.

## 3. Langkah selanjutnya (wajib di mesin Anda)

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Tinjau hasil `git diff`, lalu commit ke branch baru (mis.
`feature/eta-cron-engine`) — jangan langsung ke `main` sebelum semua tes
dan lint lolos. APK debug hasil build akan ada di
`app/build/outputs/apk/debug/`.
