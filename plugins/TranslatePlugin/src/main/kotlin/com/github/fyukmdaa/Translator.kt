package com.fyukmdaa.translateplugin

import com.aliucord.Http
import org.json.JSONArray

object Translator {
    fun translate(text: String, targetLang: String = "ja"): String {
        val url = Http.QueryBuilder("https://translate.googleapis.com/translate_a/single")
            .append("client", "gtx")
            .append("sl", "auto")
            .append("tl", targetLang)
            .append("dt", "t")
            .append("q", text)
            .toString()

        val response = Http.Request(url, "GET").apply {
            setHeader("Content-Type", "application/json")
            setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4592.0 Safari/537.36")
        }.execute()

        if (!response.ok()) throw Exception("HTTP ${response.statusCode}")

        val json = JSONArray(response.text())
        val sections = json.getJSONArray(0)
        return buildString {
            for (i in 0 until sections.length()) {
                append(sections.getJSONArray(i).getString(0))
            }
        }
    }
}
