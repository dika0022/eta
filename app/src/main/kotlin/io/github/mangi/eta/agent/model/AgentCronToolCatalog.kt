package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Model-visible API for managing persistent scheduled agent runs. */
internal object AgentCronToolCatalog {
    val names = setOf(
        "cron_create", "cron_list", "cron_update", "cron_delete",
        "cron_pause", "cron_resume", "cron_run", "cron_history",
        "cron_pending", "cron_confirm",
    )

    fun appendTo(tools: JSONArray) {
        tools.put(function(
            "cron_create",
            "Buat tugas Agent terjadwal yang tersimpan permanen. schedule_type dapat berupa cron (lima kolom), interval (menit), once (waktu Unix dalam milidetik), atau notification (dipicu saat notifikasi yang cocok muncul, bukan oleh jam). Instruksi dijalankan oleh model AI yang dipilih saat jadwal dieksekusi. Waktu pelaksanaan cron/interval/once dapat bergeser dan tidak dijamin tepat hingga menit/detik tertentu. cron/interval/once juga bisa diberi syarat lokasi opsional (geo_*): jadwal tetap berjalan sesuai waktunya, tapi instruksi hanya benar-benar dijalankan kalau posisi perangkat saat itu memenuhi syarat — ini BUKAN geofence real-time, hanya dicek ulang setiap kali jadwal berbunyi.",
            properties()
                .put("name", text("Nama singkat untuk ditampilkan."))
                .put("prompt", text("Instruksi lengkap yang akan dijalankan Eta pada setiap pelaksanaan."))
                .put("schedule_type", enumeration("Jenis jadwal.", "cron", "interval", "once", "notification"))
                .put("cron_expression", text("Ekspresi cron lima kolom, contoh: 0 9 * * 1-5. Wajib untuk jenis cron."))
                .put("interval_minutes", integer("Jarak pengulangan dalam menit (1 hingga 5.256.000). Wajib untuk jenis interval."))
                .put("run_at_ms", integer("Waktu pelaksanaan satu kali dalam milidetik Unix. Wajib untuk jenis once."))
                .put("time_zone", text("Zona waktu IANA, contoh Asia/Jakarta. Secara bawaan mengikuti zona waktu perangkat."))
                .put("max_retries", integer("Jumlah percobaan ulang setelah gagal, 0–5; bawaan 2."))
                .put("dry_run", boolean("Jika true, setiap pelaksanaan hanya diminta menjelaskan rencananya, bukan benar-benar melakukan aksi yang mengubah data. Bawaan false."))
                .put("notify_package", text("Wajib hanya untuk jenis notification (opsional di dalamnya): batasi ke package aplikasi tertentu, mis. com.whatsapp. Kosongkan untuk mencocokkan semua aplikasi."))
                .put("notify_match", text("Wajib untuk jenis notification: teks (tidak peka huruf besar/kecil) yang dicari di judul+isi notifikasi untuk memicu jadwal ini."))
                .put("geo_lat", number("Lintang titik pusat syarat lokasi (-90..90). Opsional, hanya untuk cron/interval/once; empat kolom geo_* harus diisi bersamaan."))
                .put("geo_lng", number("Bujur titik pusat syarat lokasi (-180..180)."))
                .put("geo_radius_m", number("Radius syarat lokasi dalam meter (50..50000)."))
                .put("geo_transition", enumeration("ENTER = jalankan hanya saat perangkat di dalam radius. EXIT = jalankan hanya saat di luar radius.", "ENTER", "EXIT"))
                .put("require_confirmation", boolean("Jika true, setiap pelaksanaan otomatis (bukan cron_run manual) berhenti menunggu persetujuan pengguna (lihat cron_pending/cron_confirm) alih-alih langsung berjalan. Bawaan false.")),
            listOf("name", "prompt", "schedule_type"),
        ))
        tools.put(function(
            "cron_list",
            "Tampilkan semua tugas Agent terjadwal yang tersimpan, termasuk status dan waktu pelaksanaan berikutnya.",
            properties(), emptyList(),
        ))
        tools.put(function(
            "cron_update",
            "Ubah tugas yang tersimpan. Hanya kolom yang dikirim yang akan diubah; schedule_type tidak berubah.",
            properties()
                .put("id", text("ID jadwal dari hasil cron_create atau cron_list."))
                .put("name", text("Nama tampilan yang baru."))
                .put("prompt", text("Instruksi Agent yang baru."))
                .put("cron_expression", text("Ekspresi cron lima kolom pengganti untuk jadwal cron."))
                .put("interval_minutes", integer("Jarak pengulangan pengganti untuk jadwal interval."))
                .put("run_at_ms", integer("Waktu Unix dalam milidetik pengganti untuk jadwal sekali jalan."))
                .put("time_zone", text("Zona waktu IANA pengganti."))
                .put("max_retries", integer("Jumlah percobaan ulang pengganti, 0–5."))
                .put("dry_run", boolean("Ganti mode pratinjau: true/false. notify_*/geo_* tidak bisa diubah di sini — hapus dan buat ulang jadwalnya."))
                .put("require_confirmation", boolean("Ganti syarat persetujuan sebelum eksekusi otomatis: true/false.")),
            listOf("id"),
        ))
        listOf(
            Triple("cron_delete", "Hapus tugas terjadwal beserta seluruh riwayat pelaksanaannya.", "id"),
            Triple("cron_pause", "Jeda jadwal tanpa menghapusnya.", "id"),
            Triple("cron_resume", "Lanjutkan jadwal yang sedang dijeda.", "id"),
            Triple("cron_run", "Jalankan tugas tersimpan satu kali sekarang tanpa mengubah jadwal normalnya.", "id"),
        ).forEach { (name, description, required) ->
            tools.put(function(name, description, properties().put(required, text("ID jadwal.")), listOf(required)))
        }
        tools.put(function(
            "cron_history",
            "Baca riwayat pelaksanaan terbaru dan keluaran tersimpan dari satu tugas terjadwal, termasuk daftar tool yang dipanggil selama tiap pelaksanaan (tool_calls_json, jika ada).",
            properties()
                .put("id", text("ID jadwal."))
                .put("limit", integer("Jumlah maksimum catatan yang dikembalikan, 1–100; bawaan 20.")),
            listOf("id"),
        ))
        tools.put(function(
            "cron_pending",
            "Daftar pelaksanaan jadwal (require_confirmation=true) yang sedang menunggu persetujuan pengguna, belum kedaluwarsa (24 jam sejak dipicu).",
            properties().put("limit", integer("Jumlah maksimum yang dikembalikan, 1–100; bawaan 20.")),
            emptyList(),
        ))
        tools.put(function(
            "cron_confirm",
            "Setujui atau tolak satu pelaksanaan yang menunggu persetujuan dari cron_pending. Menyetujui langsung menjalankannya sekarang (seperti cron_run); menolak membatalkannya tanpa menjalankan apa pun.",
            properties()
                .put("run_key", text("Pengenal pelaksanaan dari hasil cron_pending/cron_history, bukan ID jadwal."))
                .put("approve", boolean("true untuk menyetujui dan menjalankan sekarang, false untuk menolak.")),
            listOf("run_key", "approve"),
        ))
    }

    private fun function(name: String, description: String, fields: JSONObject, required: List<String>): JSONObject =
        AgentToolSchema.function(
            name = name,
            description = description,
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", fields)
                .put("required", JSONArray(required))
                .put("additionalProperties", false),
        )

    private fun properties() = JSONObject()
    private fun text(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun integer(description: String) = JSONObject().put("type", "integer").put("description", description)
    private fun number(description: String) = JSONObject().put("type", "number").put("description", description)
    private fun boolean(description: String) = JSONObject().put("type", "boolean").put("description", description)
    private fun enumeration(description: String, vararg values: String) =
        text(description).put("enum", JSONArray(values.toList()))
}
