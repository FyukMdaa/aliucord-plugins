package com.fyukmdaa.translateplugin

import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray

object Translator {
    fun translate(text: String, targetLang: String = "ja"): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded"

        val response = URL(url).readText()
        val json = JSONArray(response)
        val translations = json.getJSONArray(0)

        val sb = StringBuilder()
        for (i in 0 until translations.length()) {
            val part = translations.getJSONArray(i)
            if (!part.isNull(0)) sb.append(part.getString(0))
        }
        return sb.toString()
    }
}
