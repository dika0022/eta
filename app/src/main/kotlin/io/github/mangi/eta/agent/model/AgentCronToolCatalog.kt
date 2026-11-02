package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Model-visible API for managing persistent scheduled agent runs. */
internal object AgentCronToolCatalog {
    val names = setOf(
        "cron_create", "cron_list", "cron_update", "cron_delete",
        "cron_pause", "cron_resume", "cron_run", "cron_history",
    )

    fun appendTo(tools: JSONArray) {
        tools.put(function(
            "cron_create",
            "Buat tugas Agent terjadwal yang tersimpan permanen. schedule_type dapat berupa cron (lima kolom), interval (menit), atau once (waktu Unix dalam milidetik). Instruksi dijalankan oleh model AI yang dipilih saat jadwal dieksekusi. Waktu pelaksanaan dapat bergeser dan tidak dijamin tepat hingga menit/detik tertentu.",
            properties()
                .put("name", text("Nama singkat untuk ditampilkan."))
                .put("prompt", text("Instruksi lengkap yang akan dijalankan Eta pada setiap pelaksanaan."))
                .put("schedule_type", enumeration("Jenis jadwal.", "cron", "interval", "once"))
                .put("cron_expression", text("Ekspresi cron lima kolom, contoh: 0 9 * * 1-5. Wajib untuk jenis cron."))
                .put("interval_minutes", integer("Jarak pengulangan dalam menit (1 hingga 5.256.000). Wajib untuk jenis interval."))
                .put("run_at_ms", integer("Waktu pelaksanaan satu kali dalam milidetik Unix. Wajib untuk jenis once."))
                .put("time_zone", text("Zona waktu IANA, contoh Asia/Jakarta. Secara bawaan mengikuti zona waktu perangkat."))
                .put("max_retries", integer("Jumlah percobaan ulang setelah gagal, 0–5; bawaan 2.")),
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
                .put("max_retries", integer("Jumlah percobaan ulang pengganti, 0–5.")),
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
            "Baca riwayat pelaksanaan terbaru dan keluaran tersimpan dari satu tugas terjadwal.",
            properties()
                .put("id", text("ID jadwal."))
                .put("limit", integer("Jumlah maksimum catatan yang dikembalikan, 1–100; bawaan 20.")),
            listOf("id"),
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
    private fun enumeration(description: String, vararg values: String) =
        text(description).put("enum", JSONArray(values.toList()))
}
