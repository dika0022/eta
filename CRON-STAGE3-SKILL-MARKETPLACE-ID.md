# Tahap 3: UI Skill Marketplace

Lanjutan dari `CRON-STAGE2-GOVERNANCE-ID.md`. **Tidak ada migrasi database** di
tahap ini — murni UI baru di atas backend skill yang ternyata sudah ada.

## 0. Yang saya temukan sebelum menulis apa pun

Sebelum membangun apa pun, saya telusuri dulu `agent/skill/` — ternyata backend
untuk "pasang skill dari GitHub" **sudah lengkap** (`PublicGitHubSkillSource.kt`,
`SkillPackageInstaller.kt`, dipakai agent lewat tool saat diminta di chat). Yang
betul-betul hilang hanya UI-nya: layar skill yang ada (`AgentSkillsScreen.kt`)
cuma mengelola skill yang SUDAH terpasang (aktifkan/nonaktifkan/hapus/impor zip
lokal), tidak ada cara menjelajah repositori GitHub lewat tap. Jadi Tahap 3 ini
murni front-end baru di atas backend yang sudah teruji, bukan backend baru.

## 1. Apa yang ditambahkan

- Baris baru "Skill komunitas" di atas layar Skill yang sudah ada → layar baru
  yang menampilkan daftar skill dari repositori GitHub (default: daftar
  kurasi `openai/skills`, bisa diganti dengan mengetik `owner/repo` atau
  tautan GitHub lain) dengan tombol "Pasang" per skill.
- Kalau skill itu sudah terpasang dari sumber lain, muncul dialog konfirmasi
  timpa (bukan diam-diam gagal atau diam-diam menimpa).

## 2. Satu penyederhanaan yang disengaja

Jalur yang dipakai agent (lewat chat) punya mekanisme perlindungan "replay"
yang cukup rumit — menyimpan snapshot commit yang pernah diperiksa, menolak
instalasi yang mencoba memakai ID beda dari yang pernah diperiksa, dsb. Itu
dirancang untuk kondisi antar-giliran percakapan yang bisa dimanipulasi (model
dipicu beberapa langkah terpisah). Untuk UI yang saya buat ini saya
sederhanakan: **tanpa** mekanisme snapshot lintas-giliran itu — orang yang
mengetuk "Pasang" di layar yang sedang ia lihat langsung tidak butuh
perlindungan level itu. Konfirmasi timpa tetap ada, dan setiap percobaan ulang
dicocokkan ID yang sama persis (`expectedReplacementIds` diisi dari ID yang
dikembalikan percobaan pertama) supaya tetap tidak bisa menimpa sesuatu yang
tidak pernah ditampilkan ke pengguna.

## 3. Satu hal lagi yang disederhanakan

Hampir semua teks di layar lain di app ini memakai string resource
(`stringResource(R.string.xxx)`) dengan nama yang punya akhiran hash
(tampaknya dibuat oleh alat bantu internal mereka). Saya tidak punya alat itu,
jadi teks di layar baru ini (dan baris "Skill komunitas" yang saya tambahkan
di layar Skill) memakai string Indonesia langsung di kode, bukan resource.
Fungsinya sama persis, cuma tidak melalui jalur string-resource yang sama
dengan bagian lain app. Beri tahu saya kalau ini perlu dirapikan ke pola yang
sama.

## 4. Kesalahan yang saya tangkap sendiri saat menulis ini (dan perbaiki sebelum selesai)

Supaya transparan — tiga hal ini sempat salah saat saya tulis pertama kali,
ketahuan lewat pengecekan sistematis yang sama seperti saat menemukan bug
`CronScheduleDao` dulu, diperbaiki sebelum dikirim:
- `AgentHttpClient` saya kira di package `agent.http`, ternyata `agent.model`.
- Import `androidx.compose.foundation.lazy.items` (untuk menampilkan daftar)
  sempat tidak tertulis sama sekali.
- `expectedId?.let(::setOf)` saya ganti jadi `if (expectedId != null) setOf(expectedId) else emptySet()`
  karena `setOf` punya lebih dari satu overload dan referensi `::setOf` berisiko ambigu.

## 5. Build

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` seperti
biasa. Tidak ada migrasi Room di tahap ini, jadi risiko utamanya murni di sisi
Compose/navigasi (route baru `AppRoute.SkillMarketplace`, entry baru di
`AgentAppRoot.kt`, cabang baru di `titleForRoute` — exhaustive when atas
`AppRoute`, jadi kalau ada when serupa yang saya lewatkan, itu akan gagal
kompilasi dengan jelas, bukan gagal diam-diam).
