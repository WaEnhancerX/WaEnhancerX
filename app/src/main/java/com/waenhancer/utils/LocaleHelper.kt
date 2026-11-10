package com.waenhancer.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale
import android.content.res.Resources
import com.waenhancer.config.PreferenceStores
import com.waenhancer.app.R

object LocaleHelper {

    const val PREF_APP_LANGUAGE = "app_language"

    val SUPPORTED_LANGUAGES = listOf(
        LanguageOption("system", "Follow System", "System Default"),
        LanguageOption("en", "English", "English"),
        LanguageOption("zh-CN", "Chinese (Simplified)", "简体中文"),
        LanguageOption("es-419", "Spanish (Latin America)", "Español (Latinoamérica)"),
        LanguageOption("ar", "Arabic", "العربية"),
        LanguageOption("pt", "Portuguese", "Português"),
        LanguageOption("in", "Indonesian", "Bahasa Indonesia"),
        LanguageOption("ru", "Russian", "Русский"),
        LanguageOption("tr", "Turkish", "Türkçe"),
        LanguageOption("hi", "Hindi", "हिन्दी"),
        LanguageOption("ur", "Urdu", "اردو"),
        LanguageOption("de", "German", "Deutsch"),
        LanguageOption("fr", "French", "Français"),
        LanguageOption("sl", "Slovenian", "Slovenščina")
    )

    fun getSelectedLanguageCode(context: Context): String {
        val prefs = PreferenceStores.publicStore(context)
        return DisplayLocalePolicy.canonicalCode(prefs.getString(PREF_APP_LANGUAGE, "system"))
    }

    fun getLanguageDisplayName(code: String): String {
        val canonical = DisplayLocalePolicy.canonicalCode(code)
        if (canonical == "system") return UiText.get(R.string.language_default, "System Default")
        if (canonical == "es-419") return "Español (Latinoamérica)"
        return SUPPORTED_LANGUAGES.find { it.code == canonical }?.nativeName ?: canonical
    }

    /** Localized language names, without changing the persisted BCP-47 values. */
    fun getLanguageExplanation(code: String): String {
        if (code == "system") return getLanguageDisplayName(code)
        return DisplayLocalePolicy.resolve(code, Locale.ENGLISH)
            .getDisplayName(UiText.displayLocale())
    }

    fun applyLanguage(context: Context, langCode: String) {
        val prefs = PreferenceStores.publicStore(context)
        prefs.edit().putString(PREF_APP_LANGUAGE, langCode).apply()

        val locale = DisplayLocalePolicy.resolve(langCode, Resources.getSystem().configuration.locales[0])
        Locale.setDefault(locale)
    }

    fun wrapContext(context: Context): Context {
        val langCode = getSelectedLanguageCode(context)
        if (langCode == "system") return context

        val locale = DisplayLocalePolicy.resolve(langCode, Resources.getSystem().configuration.locales[0])
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
            return context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            return context
        }
    }

    data class LanguageOption(
        val code: String,
        val englishName: String,
        val nativeName: String
    )
}
