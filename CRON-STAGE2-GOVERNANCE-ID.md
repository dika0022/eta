# Tahap 2: Konfirmasi Aksi Berisiko + Audit Log

Lanjutan dari `CRON-STAGE1-EXPANSION-ID.md`. Menaikkan versi database dari 25 ke
**26** (`MIGRATION_25_26`): kolom `require_confirmation` pada jadwal, kolom
`tool_calls_json` pada riwayat pelaksanaan.

## 0. Keputusan cakupan (sesuai arahan "bebas, yang tidak ribet")

Saya batasi lapisan konfirmasi **hanya untuk pelaksanaan otomatis lewat cron**
(cron/interval/once/notification yang berbunyi sendiri), **bukan** untuk semua
aksi agent di mana pun dijalankan. Alasannya: menggerbang SEMUA aksi mutating di
seluruh app berarti saya harus mengklasifikasi setiap tool yang ada di codebase
ini (ada puluhan, lintas accessibility/gesture/terminal/browser/dll.) tanpa
pernah membaca semuanya satu per satu — risiko saya salah menilai mana yang
"aman" terlalu besar untuk ditebak. Cron adalah tempat paling masuk akal untuk
mulai: itu satu-satunya jalur yang sudah pasti berjalan tanpa pengawasan.

## 1. Bagaimana cara kerja "konfirmasi" untuk proses yang tidak ada orangnya

Tidak ada manusia yang bisa menjawab prompt konfirmasi saat itu juga di proses
latar belakang. Jadi alurnya dua tahap:

1. Jadwal dibuat/diubah dengan `require_confirmation: true`.
2. Saat jadwal itu **berbunyi sendiri** (bukan lewat `cron_run` manual): instruksi
   **tidak** dikirim ke model. Baris riwayat berstatus `PENDING_CONFIRMATION`
   dibuat, notifikasi sistem muncul ("Menunggu persetujuan: <nama>"), dan jadwal
   tetap dijadwalkan ulang seperti biasa untuk kesempatan berikutnya.
3. Pengguna menanyakan ke Eta (chat biasa, bukan layar baru) — model memanggil
   `cron_pending` untuk melihat daftar yang menunggu.
4. Pengguna bilang setuju/tolak — model memanggil `cron_confirm(run_key, approve)`.
   - Tolak → baris ditandai `DENIED`, selesai, tidak ada yang dijalankan.
   - Setuju → dijalankan **sekarang**, lewat jalur yang sama persis dengan
     "Jalankan sekarang" manual (`cron_run`) — jadi baris pelaksanaan yang
     sebenarnya adalah baris riwayat BARU yang terpisah, bukan baris
     `PENDING_CONFIRMATION` yang sama diubah statusnya.
5. **Kedaluwarsa otomatis 24 jam** — kalau tidak diputuskan dalam 24 jam sejak
   dipicu, `cron_confirm` akan menjawab `EXPIRED` alih-alih menjalankannya;
   mencegah backlog permintaan basi menumpuk selamanya.

Urutan pengecekan saat jadwal berbunyi: syarat lokasi (Tahap 1) dicek lebih
dulu — kalau tidak memenuhi syarat, langsung dilewati seperti biasa tanpa
pernah sampai ke tahap "minta konfirmasi". "Jalankan sekarang" manual melewati
kedua gerbang ini sepenuhnya, sama seperti sebelumnya.

**Catatan jujur:** belum ada tombol setuju/tolak langsung dari notifikasinya —
notifikasi hanya pengingat, keputusan tetap lewat chat dengan Eta seperti tool
`cron_*` lainnya. Mewujudkan tombol aksi di notifikasi adalah pekerjaan UI
terpisah yang belum saya kerjakan.

## 2. Audit log pemanggilan tool per pelaksanaan

Setiap pelaksanaan (cron, manual, atau yang disetujui lewat konfirmasi) kini
merekam daftar tool yang benar-benar dipanggil selama itu — nama tool, cuplikan
argumen, cuplikan hasil, dan berhasil/tidaknya — tersimpan di `tool_calls_json`
pada baris riwayat yang sama, dibaca lewat `cron_history`/`cron_pending` sebagai
field `tool_calls`. Dibatasi maksimal 30 pemanggilan per pelaksanaan dan sekitar
240 karakter per cuplikan, supaya ukurannya tetap kecil (±16 KB terburuk).

**Satu hal yang perlu Anda sadari sebelum saya lanjutkan ke tahap berikutnya:**
log umum milik app ini (`AgentEvent.toLogLine()`) sengaja TIDAK pernah mencatat
isi argumen/hasil tool — hanya jumlah karakternya — ini jelas keputusan privasi
yang disengaja oleh pengembang aslinya. Audit log cron yang saya buat ini
**berbeda**: ia memang menyimpan cuplikan argumen dan hasil asli, karena itulah
inti dari "lihat apa yang sebenarnya dilakukan agent saat saya tidak mengawasi".
Datanya tetap di database lokal di perangkat (sama seperti semua data cron
lainnya, tidak dikirim ke mana pun oleh perubahan ini), tapi artinya lebih
banyak konten yang berpotensi sensitif tersimpan di `agent_cron_runs`
dibanding log umum app. Beri tahu saya kalau Anda ingin ini dibuat lebih
konservatif (mis. disabled secara default, atau dipotong lebih pendek).

## 3. Tool baru

- **`cron_pending`** — daftar pelaksanaan yang menunggu persetujuan (belum
  kedaluwarsa).
- **`cron_confirm(run_key, approve)`** — setujui/tolak satu di antaranya.
- `cron_create`/`cron_update` bertambah field `require_confirmation`.
- `cron_history`/`cron_pending` bertambah field `tool_calls` per baris.

## 4. Yang belum dikerjakan

- Tidak ada tombol aksi di notifikasi — hanya pengingat.
- Lapisan ini tidak menyentuh aksi di luar cron sama sekali (lihat §0).
- `require_confirmation` tidak bisa membedakan "jenis aksi apa yang mau
  dikonfirmasi" — sifatnya ya/tidak untuk seluruh pelaksanaan jadwal itu, bukan
  per tool call di dalamnya.

## 5. Build & migrasi

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` di mesin
Anda — migrasi 25→26 juga belum pernah dijalankan lewat Room nyata di sandbox
ini.
