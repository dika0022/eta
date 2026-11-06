# Tahap 1: Perluasan Cron Engine

Lanjutan dari `CRON-ENGINE-FIXES-ID.md`. Perubahan di sini menaikkan versi database
dari 24 ke **25** (`MIGRATION_24_25`) — semuanya kolom baru yang nullable/berdefault,
jadi aman untuk data yang sudah ada.

## 1. Batas baterai otomatis

Semua eksekusi OTOMATIS (bukan "Jalankan sekarang" manual) sekarang mensyaratkan
`setRequiresBatteryNotLow(true)` selain `NetworkType.CONNECTED` yang sudah ada.
Manual run tetap tanpa batas ini — kalau Anda memang minta dijalankan sekarang, itu
permintaan eksplisit, bukan proses latar belakang yang menguras baterai diam-diam.

## 2. Mode pratinjau (dry run)

Kolom baru `dry_run` pada tiap jadwal (lewat `cron_create`/`cron_update`). Saat aktif,
prompt yang dikirim ke model dibungkus instruksi eksplisit untuk hanya menjelaskan
rencana, bukan benar-benar menjalankan aksi yang mengubah data — **ini penjagaan di
level prompt, bukan blokir teknis keras**. Model yang benar-benar patuh instruksi akan
menghormatinya, tapi tidak ada mekanisme yang memaksa secara teknis (itu baru akan
datang kalau lapisan konfirmasi aksi berisiko di Tahap 2 dibangun).

## 3. Trigger notifikasi (`schedule_type: "notification"`)

Jadwal jenis baru yang **tidak dijalankan oleh jam**, melainkan oleh notifikasi yang
cocok. Alurnya: `AgentNotificationHistoryService` (listener notifikasi yang sudah ada)
→ `CronNotificationTrigger` mencocokkan `notify_match` (substring, tidak peka
huruf besar/kecil, dicari di judul+isi) dan `notify_package` (opsional, kosongkan
untuk semua aplikasi) → kalau cocok, jadwal itu langsung dieksekusi lewat
`CronWorkScheduler.enqueueEventTrigger`. Jadwal jenis ini tidak punya `next_run_at`
yang berarti (disimpan sebagai `0`) dan tidak pernah mendapat timer WorkManager —
ia hanya menunggu notifikasi yang cocok selama `enabled=true` dan `paused=false`.

**Catatan jujur:** hanya notifikasi yang BENAR-BENAR baru yang bisa memicu (hook
dipasang di `onNotificationPosted`, bukan di pemutaran ulang `onListenerConnected()`
saat service terhubung ulang) — supaya restart app tidak memicu ulang semua
notifikasi yang masih ada di shade secara tiba-tiba.

## 4. Syarat lokasi pada cron/interval/once — **BUKAN geofence OS**

Ini bagian yang paling perlu saya luruskan. Saya awalnya berencana memakai
`GeofencingClient` dari Google Play Services (geofence push real-time), tapi setelah
menelusuri kode yang sudah ada (`DeviceLocationProvider.kt`), app ini sengaja
**tidak** memakai Play Services Location sama sekali — ia hanya membaca lokasi
terakhir yang sudah diketahui sistem secara pasif, tanpa pernah membangunkan GPS
atau mendengarkan terus-menerus (lihat komentar aslinya: "tidak terus mendengarkan,
tidak proaktif membangunkan GPS"). Menambahkan Play Services Location di atas
desain itu akan bertentangan langsung dengan prinsip hemat baterai yang sudah
ditetapkan, jadi saya tidak melakukannya.

Sebagai gantinya: `geo_lat`/`geo_lng`/`geo_radius_m`/`geo_transition` adalah
**syarat opsional** yang bisa ditempel ke jadwal cron/interval/once yang sudah ada.
Jadwalnya tetap berbunyi sesuai waktunya seperti biasa; `CronLocationGate` baru
mengecek lokasi terakhir yang diketahui SAAT jadwal itu berbunyi — kalau syarat
tidak terpenuhi, dicatat sebagai `SKIPPED` di riwayat dan instruksi tidak pernah
dikirim ke model, lalu jadwal dijadwalkan ulang seperti biasa untuk pengecekan
berikutnya.

**Konsekuensi yang perlu dipahami:** ini bukan "begitu Anda masuk radius, langsung
jalan" — responsnya dibatasi seberapa sering jadwal induknya berbunyi (mis. jadwal
interval 15 menit → syarat lokasi baru dicek ulang tiap 15 menit). Kalau Anda
memang butuh geofence instan yang benar-benar real-time, itu perlu Play Services
Location + alur izin khusus, dan sengaja saya tidak bangun sekarang karena
bertentangan dengan arsitektur yang ada — beri tahu saya kalau Anda tetap mau itu,
nanti saya buat sebagai jalur terpisah yang jelas tradeoff-nya.

`ENTER` = jalankan hanya saat berada di dalam radius. `EXIT` = jalankan hanya saat
di luar radius. Keempat kolom `geo_*` wajib diisi bersamaan (atau dikosongkan
semua), dan tidak bisa dipasang bersamaan dengan `schedule_type: "notification"`.
Manual run ("Jalankan sekarang") melewati syarat ini sepenuhnya.

## 5. Yang belum dikerjakan di tahap ini

- **Tidak ada UI Compose baru.** Keempat fitur di atas hanya bisa diakses lewat
  tool (`cron_create`/`cron_update`, dipanggil model saat diminta pengguna), belum
  ada layar khusus untuk membuatnya lewat tap-tap di aplikasi.
- `notify_package`/`notify_match`/`geo_*` **tidak bisa diubah lewat `cron_update`** —
  sengaja disederhanakan; untuk mengganti kriterianya, hapus dan buat ulang
  jadwalnya. Hanya `dry_run` yang bisa diubah lewat update.
- Lapisan konfirmasi aksi berisiko (yang akan membuat `dry_run` benar-benar
  dipaksa secara teknis, bukan cuma instruksi prompt) direncanakan di Tahap 2.

## 6. Build & migrasi

Seperti biasa: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
di mesin Anda sendiri — migrasi 24→25 belum pernah dijalankan lewat Room nyata di
sini (tanpa Android SDK), jadi perlakukan sebagai langkah pertama yang perlu
diperhatikan kalau build gagal di titik migrasi database.
