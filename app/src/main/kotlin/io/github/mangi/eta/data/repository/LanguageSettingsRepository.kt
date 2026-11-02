package io.github.mangi.eta.data.repository

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import java.util.Locale

class LanguageSettingsRepository(context: Context) {
    private val localeManager = requireNotNull(
        context.applicationContext.getSystemService(LocaleManager::class.java),
    )

    private val indonesian = Locale.forLanguageTag("id")

    // Bahasa aplikasi dikunci ke Indonesia; bahasa sistem tidak mengubah bahasa Eta.
    val supportedLocales: List<Locale> = listOf(indonesian)

    fun selectedLocale(): Locale = indonesian

    fun selectLocale(locale: Locale?) {
        require(locale == null || locale.language == "id") { "Eta hanya mendukung Bahasa Indonesia." }
        val locales = LocaleList(indonesian)
        if (localeManager.applicationLocales != locales) {
            localeManager.applicationLocales = locales
        }
    }
}
