package com.eagalts.still

import android.content.Context
import org.json.JSONObject

/**
 * Переводы. Ключ — сама русская строка (как в gettext): `t("Убрать {0}", n)`.
 * Словари — assets/i18n/<язык>.json ({"русская строка": "перевод"}); язык — системный.
 * Нет перевода на язык телефона — английский, нет и его — русский. Плейсхолдеры {0}, {1}…
 * в переводах сохраняются. Из Plume.
 */
object I18n {
    /** Языки, на которые переведён Still (кроме русского — он в коде). */
    val languages = listOf("en", "es", "de", "fr", "pt", "zh", "ja")

    @Volatile private var dict: Map<String, String> = emptyMap()
    @Volatile private var english: Map<String, String> = emptyMap()
    @Volatile var lang: String = "ru"
        private set

    fun init(ctx: Context, locale: java.util.Locale = System.getProperty("still.lang")?.let { java.util.Locale(it) } ?: java.util.Locale.getDefault()) {
        lang = locale.language.lowercase().let { if (it == "be" || it == "uk" || it == "kk") "ru" else it }
        if (lang == "ru") { dict = emptyMap(); english = emptyMap(); return }
        english = load(ctx, "en")
        dict = if (lang in languages) load(ctx, lang) else english
    }

    internal fun load(ctx: Context, l: String): Map<String, String> = runCatching {
        val o = JSONObject(ctx.assets.open("i18n/$l.json").use { it.readBytes().toString(Charsets.UTF_8) })
        buildMap { o.keys().forEach { k -> put(k, o.getString(k)) } }
    }.getOrDefault(emptyMap())

    fun tr(src: String, args: Array<out Any?>): String {
        var s = dict[src] ?: english[src] ?: src
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
        return s
    }
}

/** Строка интерфейса на языке телефона (ключ — русский текст). */
fun t(src: String, vararg args: Any?): String = I18n.tr(src, args)
